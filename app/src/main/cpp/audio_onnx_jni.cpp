#include <dlfcn.h>
#include <jni.h>
#include <onnxruntime_c_api.h>

#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Runtime {
    const OrtApi* api;
    OrtEnv* env = nullptr;

    Runtime() {
        // Keep the same runtime as Sherpa, including its versioned C symbol.
        void* library = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error("ONNX Runtime unavailable");
        using GetApiBase = const OrtApiBase* (*)();
        const auto get_api_base = reinterpret_cast<GetApiBase>(dlsym(library, "OrtGetApiBase"));
        api = get_api_base ? get_api_base()->GetApi(ORT_API_VERSION) : nullptr;
        if (!api) throw std::runtime_error("Unsupported ONNX Runtime API");
        check(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "VantaAudio", &env));
    }

    ~Runtime() { api->ReleaseEnv(env); }

    void check(OrtStatus* status) const {
        if (!status) return;
        std::string message(api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        throw std::runtime_error(message);
    }
};

const Runtime& runtime() {
    static const Runtime value;
    return value;
}

template <typename T>
auto owned(T* pointer, void (ORT_API_CALL* deleter)(T*)) {
    return std::unique_ptr<T, decltype(deleter)>(pointer, deleter);
}

std::string string_value(JNIEnv* jni, jstring value) {
    if (!value) throw std::runtime_error("Missing ONNX string");
    const char* raw = jni->GetStringUTFChars(value, nullptr);
    if (!raw) throw std::runtime_error("Unable to read ONNX string");
    const auto release_chars = [&](const char* chars) { jni->ReleaseStringUTFChars(value, chars); };
    const std::unique_ptr<const char, decltype(release_chars)> chars(raw, release_chars);
    return std::string(chars.get());
}

std::vector<std::string> strings(JNIEnv* jni, jobjectArray values) {
    const jsize count = jni->GetArrayLength(values);
    std::vector<std::string> out;
    out.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(jni->GetObjectArrayElement(values, i));
        out.push_back(string_value(jni, value));
        jni->DeleteLocalRef(value);
    }
    return out;
}

std::vector<const char*> pointers(const std::vector<std::string>& values) {
    std::vector<const char*> out;
    out.reserve(values.size());
    for (const auto& value : values) out.push_back(value.c_str());
    return out;
}

void throw_java(JNIEnv* jni, const char* message) {
    if (jni->ExceptionCheck()) return;
    jclass type = jni->FindClass("java/lang/IllegalStateException");
    if (type) jni->ThrowNew(type, message);
}

struct OutputValues {
    const OrtApi* api;
    std::vector<OrtValue*> values;

    OutputValues(const OrtApi* api, size_t size) : api(api), values(size, nullptr) {}
    ~OutputValues() {
        for (auto* value : values) if (value) api->ReleaseValue(value);
    }
};
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_stenograf_app_audio_AudioOnnxNative_create(
    JNIEnv* jni, jobject, jstring path, jbyteArray model, jint threads
) {
    try {
        const auto& ort = runtime();
        OrtSessionOptions* raw_options = nullptr;
        ort.check(ort.api->CreateSessionOptions(&raw_options));
        const auto options = owned(raw_options, ort.api->ReleaseSessionOptions);
        ort.check(ort.api->SetIntraOpNumThreads(options.get(), threads));
        ort.check(ort.api->SetInterOpNumThreads(options.get(), 1));
        ort.check(ort.api->SetSessionGraphOptimizationLevel(options.get(), ORT_ENABLE_ALL));
        OrtSession* session = nullptr;
        if (path) {
            const std::string name = string_value(jni, path);
            ort.check(ort.api->CreateSession(ort.env, name.c_str(), options.get(), &session));
        } else {
            if (!model) throw std::runtime_error("Missing ONNX model");
            std::vector<jbyte> bytes(jni->GetArrayLength(model));
            jni->GetByteArrayRegion(model, 0, static_cast<jsize>(bytes.size()), bytes.data());
            if (jni->ExceptionCheck()) return 0;
            ort.check(ort.api->CreateSessionFromArray(ort.env, bytes.data(), bytes.size(), options.get(), &session));
        }
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception& error) {
        throw_java(jni, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_stenograf_app_audio_AudioOnnxNative_run(
    JNIEnv* jni, jobject, jlong handle, jobjectArray names, jobjectArray buffers,
    jobjectArray dimensions, jobjectArray output_names
) {
    try {
        const auto& ort = runtime();
        const auto inputs = strings(jni, names);
        const auto outputs = strings(jni, output_names);
        const auto input_names = pointers(inputs);
        const auto result_names = pointers(outputs);
        OrtMemoryInfo* raw_memory = nullptr;
        ort.check(ort.api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &raw_memory));
        const auto memory = owned(raw_memory, ort.api->ReleaseMemoryInfo);

        std::vector<std::vector<float>> input_data(inputs.size());
        OutputValues tensors(ort.api, inputs.size());
        std::vector<const OrtValue*> input_tensors(inputs.size());
        for (size_t i = 0; i < inputs.size(); ++i) {
            const auto array = static_cast<jfloatArray>(jni->GetObjectArrayElement(buffers, i));
            const auto shape_array = static_cast<jlongArray>(jni->GetObjectArrayElement(dimensions, i));
            const jsize size = jni->GetArrayLength(array);
            const jsize rank = jni->GetArrayLength(shape_array);
            input_data[i].resize(size);
            std::vector<jlong> jni_shape(rank);
            jni->GetFloatArrayRegion(array, 0, size, input_data[i].data());
            jni->GetLongArrayRegion(shape_array, 0, rank, jni_shape.data());
            jni->DeleteLocalRef(array);
            jni->DeleteLocalRef(shape_array);
            if (jni->ExceptionCheck()) return nullptr;
            const std::vector<int64_t> shape(jni_shape.begin(), jni_shape.end());
            ort.check(ort.api->CreateTensorWithDataAsOrtValue(
                memory.get(), input_data[i].data(), size * sizeof(float), shape.data(), shape.size(),
                ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tensors.values[i]
            ));
            input_tensors[i] = tensors.values[i];
        }

        OutputValues result(ort.api, outputs.size());
        ort.check(ort.api->Run(
            reinterpret_cast<OrtSession*>(handle), nullptr,
            input_names.data(), input_tensors.data(), inputs.size(),
            result_names.data(), outputs.size(), result.values.data()
        ));
        jclass float_array = jni->FindClass("[F");
        if (!float_array) return nullptr;
        jobjectArray response = jni->NewObjectArray(static_cast<jsize>(outputs.size()), float_array, nullptr);
        if (!response) return nullptr;
        for (size_t i = 0; i < outputs.size(); ++i) {
            OrtTensorTypeAndShapeInfo* raw_info = nullptr;
            ort.check(ort.api->GetTensorTypeAndShape(result.values[i], &raw_info));
            const auto info = owned(raw_info, ort.api->ReleaseTensorTypeAndShapeInfo);
            size_t count = 0;
            ONNXTensorElementDataType type;
            ort.check(ort.api->GetTensorElementType(info.get(), &type));
            ort.check(ort.api->GetTensorShapeElementCount(info.get(), &count));
            if (type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT ||
                count > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
                throw std::runtime_error("Unexpected ONNX audio output");
            }
            void* data = nullptr;
            ort.check(ort.api->GetTensorMutableData(result.values[i], &data));
            jfloatArray samples = jni->NewFloatArray(static_cast<jsize>(count));
            if (!samples) return nullptr;
            jni->SetFloatArrayRegion(samples, 0, static_cast<jsize>(count), static_cast<float*>(data));
            jni->SetObjectArrayElement(response, static_cast<jsize>(i), samples);
            jni->DeleteLocalRef(samples);
            if (jni->ExceptionCheck()) return nullptr;
        }
        return response;
    } catch (const std::exception& error) {
        throw_java(jni, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_stenograf_app_audio_AudioOnnxNative_release(JNIEnv*, jobject, jlong handle) {
    runtime().api->ReleaseSession(reinterpret_cast<OrtSession*>(handle));
}
