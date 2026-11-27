#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <vector>

#include "tinyasr.h"

#define LOG_TAG "TinyAsrJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static double monotonic_clock(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<double>(ts.tv_sec) + static_cast<double>(ts.tv_nsec) * 1e-9;
}

static int pin_to_big_cores(void) {
#if defined(__linux__) || defined(__ANDROID__)
    int cap[CPU_SETSIZE];
    int n = 0;
    int max_cap = 0;
    int big = 0;
    for (; n < CPU_SETSIZE; n++) {
        char path[96];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", n);
        FILE *f = fopen(path, "r");
        if (f == nullptr) break;
        if (fscanf(f, "%d", &cap[n]) != 1) cap[n] = 0;
        fclose(f);
        if (cap[n] > max_cap) max_cap = cap[n];
    }
    cpu_set_t set;
    CPU_ZERO(&set);
    // Include all big & performance cores (>= 55% of max capacity) so multi-threaded
    // TinyASR inference never gets throttled onto a single Prime core.
    for (int i = 0; i < n; i++) {
        if (max_cap > 0 && cap[i] * 20 >= max_cap * 11) {
            CPU_SET(i, &set);
            big++;
        }
    }
    if (big >= 2) {
        sched_setaffinity(0, sizeof(set), &set);
    }
    return big > 0 ? big : n;
#else
    return 0;
#endif
}

struct MappedFile {
    void *data = nullptr;
    size_t size = 0;
    int fd = -1;

    bool map(const char *path) {
        fd = open(path, O_RDONLY);
        if (fd < 0) return false;
        struct stat st {};
        if (fstat(fd, &st) != 0 || st.st_size <= 0) {
            close(fd);
            fd = -1;
            return false;
        }
        size = static_cast<size_t>(st.st_size);
        data = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
        if (data == MAP_FAILED) {
            data = nullptr;
            close(fd);
            fd = -1;
            return false;
        }
        return true;
    }

    ~MappedFile() {
        if (data != nullptr && data != MAP_FAILED) {
            munmap(data, size);
        }
        if (fd >= 0) {
            close(fd);
        }
    }
};

extern "C" {

JNIEXPORT jint JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeVersion(JNIEnv *, jobject) {
    return static_cast<jint>(tinyasr_version());
}

JNIEXPORT jint JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativePinToBigCores(JNIEnv *, jobject) {
    return static_cast<jint>(pin_to_big_cores());
}

JNIEXPORT jlong JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeLoadModels(
    JNIEnv *env, jobject, jstring jAsrPath, jstring jVadPath, jint requestedThreads, jbyteArray jErrOut
) {
    const char *asrPath = env->GetStringUTFChars(jAsrPath, nullptr);
    const char *vadPath = env->GetStringUTFChars(jVadPath, nullptr);
    if (!asrPath || !vadPath) {
        if (asrPath) env->ReleaseStringUTFChars(jAsrPath, asrPath);
        if (vadPath) env->ReleaseStringUTFChars(jVadPath, vadPath);
        return 0;
    }

    MappedFile asrMap;
    MappedFile vadMap;
    bool okAsr = asrMap.map(asrPath);
    bool okVad = vadMap.map(vadPath);

    env->ReleaseStringUTFChars(jAsrPath, asrPath);
    env->ReleaseStringUTFChars(jVadPath, vadPath);

    char err[256] = {0};
    if (!okAsr || !okVad) {
        snprintf(err, sizeof(err), "%s", !okAsr ? "Cannot open ASR model file" : "Cannot open Silero VAD file");
        if (jErrOut) {
            jsize cap = env->GetArrayLength(jErrOut);
            jsize len = static_cast<jsize>(strnlen(err, sizeof(err)));
            if (len > cap) len = cap;
            env->SetByteArrayRegion(jErrOut, 0, len, reinterpret_cast<const jbyte *>(err));
        }
        return 0;
    }

    tinyasr_models *m = tinyasr_load(
        static_cast<const uint8_t *>(asrMap.data), asrMap.size,
        static_cast<const uint8_t *>(vadMap.data), vadMap.size,
        err, sizeof(err)
    );
    if (!m) {
        LOGE("tinyasr_load failed: %s", err);
        if (jErrOut) {
            jsize cap = env->GetArrayLength(jErrOut);
            jsize len = static_cast<jsize>(strnlen(err, sizeof(err)));
            if (len > cap) len = cap;
            env->SetByteArrayRegion(jErrOut, 0, len, reinterpret_cast<const jbyte *>(err));
        }
        return 0;
    }

    int threads = requestedThreads;
    if (threads <= 0) {
        int big = pin_to_big_cores();
        threads = big > 0 ? (big > 4 ? 4 : big) : 2;
    }
    tinyasr_set_threads(m, static_cast<uint32_t>(threads));
    LOGI("Loaded tinyasr v%u (chunk=%ums, threads=%d)", tinyasr_version(), tinyasr_chunk_ms(m), threads);
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT void JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeFreeModels(JNIEnv *, jobject, jlong handle) {
    auto *m = reinterpret_cast<tinyasr_models *>(handle);
    if (m) tinyasr_free(m);
}

JNIEXPORT jint JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeChunkMs(JNIEnv *, jobject, jlong handle) {
    auto *m = reinterpret_cast<const tinyasr_models *>(handle);
    return static_cast<jint>(tinyasr_chunk_ms(m));
}

JNIEXPORT jobjectArray JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeGetLangs(JNIEnv *env, jobject, jlong handle) {
    auto *m = reinterpret_cast<const tinyasr_models *>(handle);
    uint32_t count = tinyasr_lang_count(m);
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(count), strCls, nullptr);
    for (uint32_t i = 0; i < count; i++) {
        const char *code = tinyasr_lang(m, i);
        jstring s = env->NewStringUTF(code ? code : "");
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeSessionCreate(
    JNIEnv *env, jobject, jlong modelHandle, jstring jLangs, jstring jStartLang, jint beam
) {
    auto *m = reinterpret_cast<const tinyasr_models *>(modelHandle);
    if (!m) return 0;
    const char *langs = jLangs ? env->GetStringUTFChars(jLangs, nullptr) : nullptr;
    const char *start = jStartLang ? env->GetStringUTFChars(jStartLang, nullptr) : nullptr;
    tinyasr_session *s = tinyasr_session_new(m, langs, start, monotonic_clock, static_cast<uint32_t>(beam));
    if (langs) env->ReleaseStringUTFChars(jLangs, langs);
    if (start) env->ReleaseStringUTFChars(jStartLang, start);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jstring JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeSessionPushPcm16(
    JNIEnv *env, jobject, jlong sessionHandle, jshortArray jPcm, jint offset, jint length
) {
    auto *s = reinterpret_cast<tinyasr_session *>(sessionHandle);
    if (!s || !jPcm || length <= 0) return nullptr;
    std::vector<int16_t> raw(static_cast<size_t>(length));
    env->GetShortArrayRegion(jPcm, offset, length, raw.data());
    std::vector<float> f32(static_cast<size_t>(length));
    constexpr float kScale = 1.0f / 32768.0f;
    for (int i = 0; i < length; i++) {
        f32[i] = static_cast<float>(raw[i]) * kScale;
    }
    const char *json = tinyasr_session_push(s, f32.data(), f32.size());
    return json ? env->NewStringUTF(json) : nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeSessionPushFloat(
    JNIEnv *env, jobject, jlong sessionHandle, jfloatArray jPcm, jint length
) {
    auto *s = reinterpret_cast<tinyasr_session *>(sessionHandle);
    if (!s || !jPcm || length <= 0) return nullptr;
    std::vector<float> f32(static_cast<size_t>(length));
    env->GetFloatArrayRegion(jPcm, 0, length, f32.data());
    const char *json = tinyasr_session_push(s, f32.data(), f32.size());
    return json ? env->NewStringUTF(json) : nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeSessionFinish(JNIEnv *env, jobject, jlong sessionHandle) {
    auto *s = reinterpret_cast<tinyasr_session *>(sessionHandle);
    if (!s) return nullptr;
    const char *json = tinyasr_session_finish(s);
    return json ? env->NewStringUTF(json) : nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeSessionLang(JNIEnv *env, jobject, jlong sessionHandle) {
    auto *s = reinterpret_cast<const tinyasr_session *>(sessionHandle);
    if (!s) return nullptr;
    const char *lang = tinyasr_session_lang(s);
    return lang ? env->NewStringUTF(lang) : nullptr;
}

JNIEXPORT void JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeSessionFree(JNIEnv *, jobject, jlong sessionHandle) {
    auto *s = reinterpret_cast<tinyasr_session *>(sessionHandle);
    if (s) tinyasr_session_free(s);
}

JNIEXPORT jlong JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeFileCreateFromPcm16(
    JNIEnv *env, jobject, jlong modelHandle, jstring jPcmPath, jlong byteOffset, jlong sampleCount, jstring jLangs, jint beam
) {
    auto *m = reinterpret_cast<const tinyasr_models *>(modelHandle);
    if (!m || !jPcmPath || sampleCount <= 0) return 0;

    const char *path = env->GetStringUTFChars(jPcmPath, nullptr);
    if (!path) return 0;
    FILE *f = fopen(path, "rb");
    env->ReleaseStringUTFChars(jPcmPath, path);
    if (!f) return 0;

    if (fseeko(f, static_cast<off_t>(byteOffset), SEEK_SET) != 0) {
        fclose(f);
        return 0;
    }

    size_t count = static_cast<size_t>(sampleCount);
    std::vector<int16_t> raw(count);
    size_t readCount = fread(raw.data(), sizeof(int16_t), count, f);
    fclose(f);
    if (readCount == 0) return 0;

    std::vector<float> f32(readCount);
    constexpr float kScale = 1.0f / 32768.0f;
    for (size_t i = 0; i < readCount; i++) {
        f32[i] = static_cast<float>(raw[i]) * kScale;
    }
    raw.clear();
    raw.shrink_to_fit();

    const char *langs = jLangs ? env->GetStringUTFChars(jLangs, nullptr) : nullptr;
    tinyasr_file *tf = tinyasr_file_new(m, f32.data(), f32.size(), langs, static_cast<uint32_t>(beam));
    if (langs) env->ReleaseStringUTFChars(jLangs, langs);
    return reinterpret_cast<jlong>(tf);
}

JNIEXPORT jlong JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeFileCreateFromFloat(
    JNIEnv *env, jobject, jlong modelHandle, jfloatArray jPcm, jint length, jstring jLangs, jint beam
) {
    auto *m = reinterpret_cast<const tinyasr_models *>(modelHandle);
    if (!m || !jPcm || length <= 0) return 0;
    std::vector<float> f32(static_cast<size_t>(length));
    env->GetFloatArrayRegion(jPcm, 0, length, f32.data());
    const char *langs = jLangs ? env->GetStringUTFChars(jLangs, nullptr) : nullptr;
    tinyasr_file *tf = tinyasr_file_new(m, f32.data(), f32.size(), langs, static_cast<uint32_t>(beam));
    if (langs) env->ReleaseStringUTFChars(jLangs, langs);
    return reinterpret_cast<jlong>(tf);
}

JNIEXPORT jstring JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeFileNext(JNIEnv *env, jobject, jlong fileHandle) {
    auto *tf = reinterpret_cast<tinyasr_file *>(fileHandle);
    if (!tf) return nullptr;
    const char *json = tinyasr_file_next(tf);
    return json ? env->NewStringUTF(json) : nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeFileLang(JNIEnv *env, jobject, jlong fileHandle) {
    auto *tf = reinterpret_cast<const tinyasr_file *>(fileHandle);
    if (!tf) return nullptr;
    const char *lang = tinyasr_file_lang(tf);
    return lang ? env->NewStringUTF(lang) : nullptr;
}

JNIEXPORT jfloat JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeFileProgress(JNIEnv *, jobject, jlong fileHandle) {
    auto *tf = reinterpret_cast<const tinyasr_file *>(fileHandle);
    return tf ? static_cast<jfloat>(tinyasr_file_progress(tf)) : -1.0f;
}

JNIEXPORT void JNICALL
Java_com_stenograf_app_asr_TinyAsrNative_nativeFileFree(JNIEnv *, jobject, jlong fileHandle) {
    auto *tf = reinterpret_cast<tinyasr_file *>(fileHandle);
    if (tf) tinyasr_file_free(tf);
}

} // extern "C"
