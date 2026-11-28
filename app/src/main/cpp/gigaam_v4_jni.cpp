#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <onnxruntime_c_api.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#define LOG_TAG "GigaAmV4JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int32_t kSampleRate = 16000;
constexpr int32_t kMelBins = 64;
constexpr int32_t kWindowSize = 320;
constexpr int32_t kHopSize = 160;
constexpr int32_t kNumFreqs = kWindowSize / 2 + 1;  // 161
constexpr int32_t kStreamMelChunk = 64;
constexpr int32_t kBlankId = 1024;
constexpr int32_t kVocabSize = 1025;
constexpr int32_t kPredDim = 320;
constexpr int32_t kEncoderDim = 768;
constexpr int32_t kMaxSymbolsPerFrame = 10;
constexpr float kFrameShiftSec = 0.04f;
constexpr float kLogMelFloor = -20.7232658369f;  // ln(1e-9)

struct Runtime {
    const OrtApi* api = nullptr;
    OrtEnv* env = nullptr;
    OrtAllocator* allocator = nullptr;

    Runtime() {
        void* library = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error("ONNX Runtime unavailable");
        using GetApiBase = const OrtApiBase* (*)();
        const auto get_api_base = reinterpret_cast<GetApiBase>(dlsym(library, "OrtGetApiBase"));
        api = get_api_base ? get_api_base()->GetApi(ORT_API_VERSION) : nullptr;
        if (!api) throw std::runtime_error("Unsupported ONNX Runtime API");
        check(api->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "VantaGigaAmV4", &env));
        check(api->GetAllocatorWithDefaultOptions(&allocator));
    }

    ~Runtime() {
        if (env && api) api->ReleaseEnv(env);
    }

    void check(OrtStatus* status) const {
        if (!status) return;
        std::string message(api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        throw std::runtime_error(message);
    }
};

const Runtime& ort_runtime() {
    static const Runtime instance;
    return instance;
}

template <typename T>
struct OrtDeleter {
    void (ORT_API_CALL* fn)(T*) = nullptr;
    void operator()(T* ptr) const {
        if (ptr && fn) fn(ptr);
    }
};

template <typename T>
using OrtPtr = std::unique_ptr<T, OrtDeleter<T>>;

template <typename T>
OrtPtr<T> own(T* ptr, void (ORT_API_CALL* fn)(T*)) {
    return OrtPtr<T>(ptr, OrtDeleter<T>{fn});
}

struct OrtValueDeleter {
    const OrtApi* api = nullptr;
    void operator()(OrtValue* v) const {
        if (v && api) api->ReleaseValue(v);
    }
};

using OrtValuePtr = std::unique_ptr<OrtValue, OrtValueDeleter>;

OrtValuePtr own_value(const OrtApi* api, OrtValue* v) {
    return OrtValuePtr(v, OrtValueDeleter{api});
}

struct TensorSpec {
    std::string name;
    ONNXTensorElementDataType elem_type = ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;
    std::vector<int64_t> shape;
};

class OrtGraph {
   public:
    OrtGraph(const std::string& path, int threads, size_t expected_inputs, size_t expected_outputs)
        : api_(ort_runtime().api) {
        const auto& rt = ort_runtime();
        OrtSessionOptions* raw_opts = nullptr;
        rt.check(api_->CreateSessionOptions(&raw_opts));
        auto opts = own(raw_opts, api_->ReleaseSessionOptions);
        rt.check(api_->SetIntraOpNumThreads(opts.get(), std::max(1, threads)));
        rt.check(api_->SetInterOpNumThreads(opts.get(), 1));
        rt.check(api_->SetSessionGraphOptimizationLevel(opts.get(), ORT_ENABLE_ALL));
        rt.check(api_->AddSessionConfigEntry(opts.get(), "session.intra_op.allow_spinning", "0"));

        OrtSession* raw_sess = nullptr;
        rt.check(api_->CreateSession(rt.env, path.c_str(), opts.get(), &raw_sess));
        session_ = own(raw_sess, api_->ReleaseSession);

        size_t in_count = 0;
        size_t out_count = 0;
        rt.check(api_->SessionGetInputCount(session_.get(), &in_count));
        rt.check(api_->SessionGetOutputCount(session_.get(), &out_count));
        if (in_count != expected_inputs || out_count != expected_outputs) {
            throw std::runtime_error("Unexpected ONNX graph IO count: " + path);
        }

        inputs_.resize(in_count);
        input_ptrs_.resize(in_count);
        for (size_t i = 0; i < in_count; ++i) {
            char* name = nullptr;
            rt.check(api_->SessionGetInputName(session_.get(), i, rt.allocator, &name));
            inputs_[i].name = name ? name : "";
            if (name) rt.allocator->Free(rt.allocator, name);
            input_ptrs_[i] = inputs_[i].name.c_str();

            OrtTypeInfo* raw_ti = nullptr;
            rt.check(api_->SessionGetInputTypeInfo(session_.get(), i, &raw_ti));
            auto ti = own(raw_ti, api_->ReleaseTypeInfo);
            const OrtTensorTypeAndShapeInfo* tensor_info = nullptr;
            rt.check(api_->CastTypeInfoToTensorInfo(ti.get(), &tensor_info));
            rt.check(api_->GetTensorElementType(tensor_info, &inputs_[i].elem_type));
            size_t ndims = 0;
            rt.check(api_->GetDimensionsCount(tensor_info, &ndims));
            inputs_[i].shape.resize(ndims);
            if (ndims > 0) {
                rt.check(api_->GetDimensions(tensor_info, inputs_[i].shape.data(), ndims));
            }
        }

        output_names_.resize(out_count);
        output_ptrs_.resize(out_count);
        for (size_t i = 0; i < out_count; ++i) {
            char* name = nullptr;
            rt.check(api_->SessionGetOutputName(session_.get(), i, rt.allocator, &name));
            output_names_[i] = name ? name : "";
            if (name) rt.allocator->Free(rt.allocator, name);
            output_ptrs_[i] = output_names_[i].c_str();
        }
    }

    const std::vector<TensorSpec>& inputs() const { return inputs_; }

    std::vector<OrtValuePtr> zero_states(size_t start_idx) const {
        const auto& rt = ort_runtime();
        std::vector<OrtValuePtr> states;
        states.reserve(inputs_.size() - start_idx);
        for (size_t i = start_idx; i < inputs_.size(); ++i) {
            std::vector<int64_t> shape = inputs_[i].shape;
            size_t count = 1;
            for (auto& d : shape) {
                if (d < 0) d = 1;
                count *= static_cast<size_t>(d);
            }
            OrtValue* raw_val = nullptr;
            rt.check(api_->CreateTensorAsOrtValue(
                rt.allocator, shape.data(), shape.size(), inputs_[i].elem_type, &raw_val));
            OrtValuePtr val = own_value(api_, raw_val);
            void* data = nullptr;
            rt.check(api_->GetTensorMutableData(val.get(), &data));
            if (inputs_[i].elem_type == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
                std::fill_n(static_cast<float*>(data), count, 0.0f);
            } else if (inputs_[i].elem_type == ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64) {
                std::fill_n(static_cast<int64_t*>(data), count, int64_t{0});
            } else {
                throw std::runtime_error("Unsupported ONNX state tensor type");
            }
            states.push_back(std::move(val));
        }
        return states;
    }

    std::vector<OrtValuePtr> run(const std::vector<const OrtValue*>& input_values) const {
        const auto& rt = ort_runtime();
        if (input_values.size() != input_ptrs_.size()) {
            throw std::runtime_error("ONNX input count mismatch");
        }
        std::vector<OrtValue*> raw_outputs(output_ptrs_.size(), nullptr);
        OrtStatus* status = api_->Run(
            session_.get(), nullptr,
            input_ptrs_.data(), input_values.data(), input_values.size(),
            output_ptrs_.data(), output_ptrs_.size(), raw_outputs.data());
        if (status) {
            for (auto* v : raw_outputs) {
                if (v) api_->ReleaseValue(v);
            }
            rt.check(status);
        }
        std::vector<OrtValuePtr> outputs;
        outputs.reserve(raw_outputs.size());
        for (auto* v : raw_outputs) {
            outputs.push_back(own_value(api_, v));
        }
        return outputs;
    }

   private:
    const OrtApi* api_;
    OrtPtr<OrtSession> session_{};
    std::vector<TensorSpec> inputs_;
    std::vector<const char*> input_ptrs_;
    std::vector<std::string> output_names_;
    std::vector<const char*> output_ptrs_;
};

template <typename T>
OrtValuePtr make_tensor(const OrtMemoryInfo* mem, T* data, size_t count, const std::vector<int64_t>& shape,
                        ONNXTensorElementDataType elem_type) {
    const auto& rt = ort_runtime();
    OrtValue* raw = nullptr;
    rt.check(rt.api->CreateTensorWithDataAsOrtValue(
        mem, data, count * sizeof(T), shape.data(), shape.size(), elem_type, &raw));
    return own_value(rt.api, raw);
}

void read_tensor_shape_and_type(const OrtValue* v, ONNXTensorElementDataType* out_type,
                                std::vector<int64_t>* out_shape, size_t* out_count) {
    const auto& rt = ort_runtime();
    OrtTensorTypeAndShapeInfo* raw_info = nullptr;
    rt.check(rt.api->GetTensorTypeAndShape(v, &raw_info));
    auto info = own(raw_info, rt.api->ReleaseTensorTypeAndShapeInfo);
    rt.check(rt.api->GetTensorElementType(info.get(), out_type));
    rt.check(rt.api->GetTensorShapeElementCount(info.get(), out_count));
    size_t ndims = 0;
    rt.check(rt.api->GetDimensionsCount(info.get(), &ndims));
    out_shape->resize(ndims);
    if (ndims > 0) {
        rt.check(rt.api->GetDimensions(info.get(), out_shape->data(), ndims));
    }
}

template <typename T>
const T* tensor_data_ptr(const OrtValue* v) {
    const auto& rt = ort_runtime();
    void* ptr = nullptr;
    rt.check(rt.api->GetTensorMutableData(const_cast<OrtValue*>(v), &ptr));
    return static_cast<const T*>(ptr);
}

class FeatureExtractor {
   public:
    FeatureExtractor() {
        constexpr double kTwoPi = 6.283185307179586476925286766559;
        for (int32_t i = 0; i < kWindowSize; ++i) {
            window_[i] = static_cast<float>(0.5 - 0.5 * std::cos(kTwoPi * i / kWindowSize));
        }
        for (int32_t i = 0; i < 64; ++i) {
            int32_t rev = 0;
            for (int32_t b = 0; b < 6; ++b) {
                rev = (rev << 1) | ((i >> b) & 1);
            }
            bitrev64_[i] = static_cast<uint8_t>(rev);
        }
        for (int32_t k = 0; k < 32; ++k) {
            const double ang = -kTwoPi * k / 64.0;
            tw64_re_[k] = static_cast<float>(std::cos(ang));
            tw64_im_[k] = static_cast<float>(std::sin(ang));
        }
        for (int32_t r = 0; r < 5; ++r) {
            for (int32_t k = 0; k < kNumFreqs; ++k) {
                const double ang = -kTwoPi * r * k / static_cast<double>(kWindowSize);
                tw320_re_[r][k] = static_cast<float>(std::cos(ang));
                tw320_im_[r][k] = static_cast<float>(std::sin(ang));
            }
        }
        const double max_mel = 2595.0 * std::log10(1.0 + kSampleRate / 1400.0);
        std::array<double, kMelBins + 2> edges{};
        for (int32_t i = 0; i < kMelBins + 2; ++i) {
            const double mel = max_mel * i / (kMelBins + 1);
            edges[i] = 700.0 * (std::pow(10.0, mel / 2595.0) - 1.0);
        }
        for (int32_t bin = 0; bin < kMelBins; ++bin) {
            for (int32_t i = 0; i < kNumFreqs; ++i) {
                const double hz = i * kSampleRate / static_cast<double>(kWindowSize);
                const double left = (hz - edges[bin]) / (edges[bin + 1] - edges[bin]);
                const double right = (edges[bin + 2] - hz) / (edges[bin + 2] - edges[bin + 1]);
                filterbank_[bin][i] = static_cast<float>(std::max(0.0, std::min(left, right)));
            }
        }
    }

    void compute_frame_major(const float* samples, size_t num_samples, std::vector<float>* out_frames) const {
        if (num_samples < static_cast<size_t>(kWindowSize)) return;
        const size_t frames = 1 + (num_samples - kWindowSize) / kHopSize;
        const size_t old_size = out_frames->size();
        out_frames->resize(old_size + frames * kMelBins);

        std::array<float, kWindowSize> w{};
        std::array<float, 64> yr0{}, yi0{}, yr1{}, yi1{}, yr2{}, yi2{}, yr3{}, yi3{}, yr4{}, yi4{};
        std::array<float, 64> yr0_u{}, yi0_u{}, yr2_u{}, yi2_u{};
        std::array<float, kNumFreqs> power{};

        for (size_t t = 0; t < frames; ++t) {
            const float* src = samples + t * kHopSize;
            for (int32_t i = 0; i < kWindowSize; ++i) {
                w[i] = src[i] * window_[i];
            }
            for (int32_t q = 0; q < 64; ++q) {
                const uint8_t r = bitrev64_[q];
                yr0[r] = w[5 * q + 0];
                yi0[r] = w[5 * q + 1];
                yr2[r] = w[5 * q + 2];
                yi2[r] = w[5 * q + 3];
                yr4[r] = w[5 * q + 4];
                yi4[r] = 0.0f;
            }
            fft64(yr0.data(), yi0.data());
            fft64(yr2.data(), yi2.data());
            fft64(yr4.data(), yi4.data());

            for (int32_t k = 0; k < 64; ++k) {
                const int32_t km = (64 - k) & 63;
                yr0_u[k] = 0.5f * (yr0[k] + yr0[km]);
                yi0_u[k] = 0.5f * (yi0[k] - yi0[km]);
                yr1[k] = 0.5f * (yi0[k] + yi0[km]);
                yi1[k] = 0.5f * (yr0[km] - yr0[k]);

                yr2_u[k] = 0.5f * (yr2[k] + yr2[km]);
                yi2_u[k] = 0.5f * (yi2[k] - yi2[km]);
                yr3[k] = 0.5f * (yi2[k] + yi2[km]);
                yi3[k] = 0.5f * (yr2[km] - yr2[k]);
            }

            for (int32_t k = 0; k < kNumFreqs; ++k) {
                const int32_t k1 = k & 63;
                const float re =
                    (yr0_u[k1] * tw320_re_[0][k] - yi0_u[k1] * tw320_im_[0][k]) +
                    (yr1[k1] * tw320_re_[1][k] - yi1[k1] * tw320_im_[1][k]) +
                    (yr2_u[k1] * tw320_re_[2][k] - yi2_u[k1] * tw320_im_[2][k]) +
                    (yr3[k1] * tw320_re_[3][k] - yi3[k1] * tw320_im_[3][k]) +
                    (yr4[k1] * tw320_re_[4][k] - yi4[k1] * tw320_im_[4][k]);
                const float im =
                    (yr0_u[k1] * tw320_im_[0][k] + yi0_u[k1] * tw320_re_[0][k]) +
                    (yr1[k1] * tw320_im_[1][k] + yi1[k1] * tw320_re_[1][k]) +
                    (yr2_u[k1] * tw320_im_[2][k] + yi2_u[k1] * tw320_re_[2][k]) +
                    (yr3[k1] * tw320_im_[3][k] + yi3[k1] * tw320_re_[3][k]) +
                    (yr4[k1] * tw320_im_[4][k] + yi4[k1] * tw320_re_[4][k]);
                power[k] = re * re + im * im;
            }

            float* dst = out_frames->data() + old_size + t * kMelBins;
            for (int32_t bin = 0; bin < kMelBins; ++bin) {
                float mel = 0.0f;
                const float* fb = filterbank_[bin].data();
                for (int32_t i = 0; i < kNumFreqs; ++i) {
                    mel += power[i] * fb[i];
                }
                dst[bin] = std::log(std::clamp(mel, 1e-9f, 1e9f));
            }
        }
    }

    std::vector<float> compute_channel_major(const float* samples, size_t num_samples, size_t* out_frames) const {
        if (num_samples < static_cast<size_t>(kWindowSize)) {
            *out_frames = 0;
            return {};
        }
        std::vector<float> frame_major;
        compute_frame_major(samples, num_samples, &frame_major);
        const size_t frames = frame_major.size() / kMelBins;
        *out_frames = frames;
        std::vector<float> ch_major(kMelBins * frames);
        for (size_t t = 0; t < frames; ++t) {
            for (int32_t b = 0; b < kMelBins; ++b) {
                ch_major[static_cast<size_t>(b) * frames + t] = frame_major[t * kMelBins + b];
            }
        }
        return ch_major;
    }

   private:
    void fft64(float* re, float* im) const {
        for (int32_t len = 2; len <= 64; len <<= 1) {
            const int32_t half = len >> 1;
            const int32_t step = 64 / len;
            for (int32_t start = 0; start < 64; start += len) {
                for (int32_t j = 0; j < half; ++j) {
                    const float wr = tw64_re_[j * step];
                    const float wi = tw64_im_[j * step];
                    const int32_t i0 = start + j;
                    const int32_t i1 = i0 + half;
                    const float tr = re[i1] * wr - im[i1] * wi;
                    const float ti = re[i1] * wi + im[i1] * wr;
                    re[i1] = re[i0] - tr;
                    im[i1] = im[i0] - ti;
                    re[i0] += tr;
                    im[i0] += ti;
                }
            }
        }
    }

    std::array<float, kWindowSize> window_{};
    std::array<uint8_t, 64> bitrev64_{};
    std::array<float, 32> tw64_re_{};
    std::array<float, 32> tw64_im_{};
    std::array<std::array<float, kNumFreqs>, 5> tw320_re_{};
    std::array<std::array<float, kNumFreqs>, 5> tw320_im_{};
    std::array<std::array<float, kNumFreqs>, kMelBins> filterbank_{};
};

// Token table loaded from tokens.e2e.txt
struct TokenTable {
    std::vector<std::string> pieces;

    static TokenTable load(const std::string& path) {
        std::ifstream in(path);
        if (!in.is_open()) {
            throw std::runtime_error("Cannot open tokens file: " + path);
        }
        TokenTable table;
        table.pieces.resize(kVocabSize);
        std::vector<bool> seen(kVocabSize, false);
        std::string line;
        while (std::getline(in, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.empty()) continue;
            const size_t pos = line.rfind(' ');
            if (pos == std::string::npos) continue;
            const std::string piece = line.substr(0, pos);
            const int id = std::stoi(line.substr(pos + 1));
            if (id < 0 || id >= kVocabSize) {
                throw std::runtime_error("Invalid token id in tokens.e2e.txt");
            }
            table.pieces[id] = piece;
            seen[id] = true;
        }
        for (int32_t i = 0; i < kVocabSize; ++i) {
            if (!seen[i]) {
                throw std::runtime_error("Missing token id in tokens.e2e.txt");
            }
        }
        if (table.pieces[kBlankId] != "<blk>") {
            throw std::runtime_error("Unexpected blank token in tokens.e2e.txt");
        }
        return table;
    }
};

struct TokenFrame {
    int32_t id;
    int32_t frame;
};

struct WordItem {
    std::string text;
    float start = 0.0f;
    float end = 0.0f;
    size_t token_end_idx = 0;  // exclusive index in TokenFrame vector
};

inline bool starts_with_sp_space(const std::string& piece) {
    // U+2581 LOWER ONE EIGHTH BLOCK: E2 96 81
    return piece.size() >= 3 &&
           static_cast<uint8_t>(piece[0]) == 0xE2 &&
           static_cast<uint8_t>(piece[1]) == 0x96 &&
           static_cast<uint8_t>(piece[2]) == 0x81;
}

inline bool is_sentence_ending_word(const std::string& text) {
    // Strip trailing quotes / brackets
    size_t end = text.size();
    while (end > 0) {
        const unsigned char c = static_cast<unsigned char>(text[end - 1]);
        if (c == '"' || c == ')' || c == ']') {
            --end;
            continue;
        }
        // UTF-8 closing guillemet » (C2 BB) or right double quote ” (E2 80 9D)
        if (end >= 2 && static_cast<unsigned char>(text[end - 2]) == 0xC2 && c == 0xBB) {
            end -= 2;
            continue;
        }
        if (end >= 3 && static_cast<unsigned char>(text[end - 3]) == 0xE2 &&
            static_cast<unsigned char>(text[end - 2]) == 0x80 && c == 0x9D) {
            end -= 3;
            continue;
        }
        break;
    }
    if (end == 0) return false;
    const unsigned char last = static_cast<unsigned char>(text[end - 1]);
    if (last == '.' || last == '?' || last == '!') return true;
    // UTF-8 ellipsis … (E2 80 A6)
    if (end >= 3 && static_cast<unsigned char>(text[end - 3]) == 0xE2 &&
        static_cast<unsigned char>(text[end - 2]) == 0x80 && last == 0xA6) {
        return true;
    }
    return false;
}

std::vector<WordItem> group_tokens_into_words(const std::vector<TokenFrame>& tokens,
                                              const TokenTable& table,
                                              float max_duration_sec,
                                              float min_start_sec) {
    struct RawWord {
        std::string text;
        int32_t start_frame = 0;
        int32_t end_frame = 0;
        size_t token_end_idx = 0;
    };
    std::vector<RawWord> raw_words;
    bool force_new = true;

    for (size_t idx = 0; idx < tokens.size(); ++idx) {
        const int32_t id = tokens[idx].id;
        const int32_t frame = tokens[idx].frame;
        if (id <= 0 || id >= kBlankId) {
            // Skip <unk> (0) or invalid/blank; break current word on <unk>
            if (id == 0) force_new = true;
            continue;
        }
        const std::string& piece = table.pieces[id];
        if (piece.empty()) continue;

        const bool has_sp = starts_with_sp_space(piece);
        std::string clean = has_sp ? piece.substr(3) : piece;
        if (clean.empty()) {
            force_new = true;
            continue;
        }

        if (has_sp || force_new || raw_words.empty()) {
            RawWord w;
            w.text = std::move(clean);
            w.start_frame = frame;
            w.end_frame = frame + 1;
            w.token_end_idx = idx + 1;
            raw_words.push_back(std::move(w));
            force_new = false;
        } else {
            auto& w = raw_words.back();
            w.text += clean;
            w.end_frame = std::max(w.end_frame, frame + 1);
            w.token_end_idx = idx + 1;
        }
    }

    std::vector<WordItem> out;
    out.reserve(raw_words.size());
    float prev_start = std::max(0.0f, min_start_sec);
    for (size_t i = 0; i < raw_words.size(); ++i) {
        float st = raw_words[i].start_frame * kFrameShiftSec;
        float en = raw_words[i].end_frame * kFrameShiftSec;
        if (max_duration_sec > 0.0f) {
            st = std::min(st, max_duration_sec);
            en = std::min(en, max_duration_sec);
        }
        st = std::max(st, prev_start);
        en = std::max(en, st);
        // Clamp previous word end only if strictly inside next word's start
        if (!out.empty() && out.back().end > st && st > out.back().start) {
            out.back().end = st;
        }
        WordItem item;
        item.text = std::move(raw_words[i].text);
        item.start = st;
        item.end = en;
        item.token_end_idx = raw_words[i].token_end_idx;
        out.push_back(std::move(item));
        prev_start = st;
    }
    return out;
}

void count_scripts(const std::string& utf8, int32_t* out_cyr, int32_t* out_lat) {
    const unsigned char* s = reinterpret_cast<const unsigned char*>(utf8.data());
    const size_t n = utf8.size();
    for (size_t i = 0; i < n;) {
        const unsigned char b0 = s[i];
        if (b0 < 0x80) {
            if ((b0 >= 'A' && b0 <= 'Z') || (b0 >= 'a' && b0 <= 'z')) {
                ++(*out_lat);
            }
            ++i;
        } else if ((b0 & 0xE0) == 0xC0 && i + 1 < n) {
            const uint32_t cp = ((b0 & 0x1F) << 6) | (s[i + 1] & 0x3F);
            if (cp >= 0x0400 && cp <= 0x04FF) {
                ++(*out_cyr);
            }
            i += 2;
        } else if ((b0 & 0xF0) == 0xE0 && i + 2 < n) {
            i += 3;
        } else if ((b0 & 0xF8) == 0xF0 && i + 3 < n) {
            i += 4;
        } else {
            ++i;
        }
    }
}

std::string detect_language_from_words(const std::vector<WordItem>& words, bool allow_bilingual_tag) {
    int32_t cyr = 0;
    int32_t lat = 0;
    for (const auto& w : words) {
        count_scripts(w.text, &cyr, &lat);
    }
    if (allow_bilingual_tag && cyr > 0 && lat > 0) return "ru,en";
    if (lat > cyr) return "en";
    return "ru";
}

void append_json_escaped(std::string* dst, const std::string& s) {
    dst->push_back('"');
    for (unsigned char c : s) {
        switch (c) {
            case '"': *dst += "\\\""; break;
            case '\\': *dst += "\\\\"; break;
            case '\b': *dst += "\\b"; break;
            case '\f': *dst += "\\f"; break;
            case '\n': *dst += "\\n"; break;
            case '\r': *dst += "\\r"; break;
            case '\t': *dst += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned>(c));
                    *dst += buf;
                } else {
                    dst->push_back(static_cast<char>(c));
                }
        }
    }
    dst->push_back('"');
}

void append_words_json_array(std::string* dst, const std::vector<WordItem>& words, size_t from, size_t to) {
    dst->push_back('[');
    char num_buf[32];
    for (size_t i = from; i < to; ++i) {
        if (i > from) dst->push_back(',');
        *dst += "{\"text\":";
        append_json_escaped(dst, words[i].text);
        std::snprintf(num_buf, sizeof(num_buf), ",\"start\":%.3f,\"end\":%.3f}", words[i].start, words[i].end);
        *dst += num_buf;
    }
    dst->push_back(']');
}

// =========================================================================
// Streaming 640 ms RNN-T Model & Session
// =========================================================================

struct GigaAmV4OnlineModel {
    OrtGraph frontend;
    OrtGraph step;
    OrtGraph decoder;
    OrtGraph joiner;
    TokenTable tokens;
    FeatureExtractor features;

    GigaAmV4OnlineModel(const std::string& bundle_dir, int threads)
        : frontend(bundle_dir + "/rnnt-streaming-640ms/v4_rnnt_640ms_stream_frontend.onnx", threads, 6, 6),
          step(bundle_dir + "/rnnt-streaming-640ms/v4_rnnt_640ms_stream_step.int8.onnx", threads, 58, 58),
          decoder(bundle_dir + "/rnnt-streaming-640ms/v4_rnnt_640ms_e2e_decoder.onnx", 1, 3, 3),
          joiner(bundle_dir + "/rnnt-streaming-640ms/v4_rnnt_640ms_e2e_joint.onnx", 1, 2, 1),
          tokens(TokenTable::load(bundle_dir + "/rnnt-streaming-640ms/tokens.e2e.txt")) {
        LOGI("Loaded GigaAM-v4 online graphs (threads=%d)", threads);
    }
};

struct GigaAmV4StreamSession {
    const GigaAmV4OnlineModel* model;
    std::vector<OrtValuePtr> front_states;
    std::vector<OrtValuePtr> step_states;
    std::vector<OrtValuePtr> pred_state;  // [dec, ho, co]

    std::vector<float> pcm_buffer;
    std::vector<float> mel_buffer;  // frame-major [frame * 64 + bin]
    int64_t total_samples = 0;
    int32_t encoded_frames = 0;
    int32_t trailing_blanks = 0;
    float last_committed_sec = 0.0f;
    std::string last_emitted_lang;
    std::vector<TokenFrame> pending_tokens;

    explicit GigaAmV4StreamSession(const GigaAmV4OnlineModel* m) : model(m) {
        front_states = model->frontend.zero_states(2);
        step_states = model->step.zero_states(2);
        run_predictor(kBlankId);
    }

    void run_predictor(int64_t token) {
        const auto& rt = ort_runtime();
        OrtMemoryInfo* raw_mem = nullptr;
        rt.check(rt.api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &raw_mem));
        auto mem = own(raw_mem, rt.api->ReleaseMemoryInfo);

        if (pred_state.empty()) {
            auto zero_hc = model->decoder.zero_states(1);
            pred_state.push_back(OrtValuePtr(nullptr, OrtValueDeleter{rt.api}));
            for (auto& s : zero_hc) pred_state.push_back(std::move(s));
        }
        auto tok_tensor = make_tensor(mem.get(), &token, 1, {1, 1}, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);
        std::vector<const OrtValue*> inputs{tok_tensor.get(), pred_state[1].get(), pred_state[2].get()};
        pred_state = model->decoder.run(inputs);
    }

    int32_t run_joint(const float* enc_frame_768) {
        const auto& rt = ort_runtime();
        OrtMemoryInfo* raw_mem = nullptr;
        rt.check(rt.api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &raw_mem));
        auto mem = own(raw_mem, rt.api->ReleaseMemoryInfo);

        auto enc_tensor = make_tensor(mem.get(), const_cast<float*>(enc_frame_768), kEncoderDim,
                                      {1, kEncoderDim, 1}, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);
        const float* dec_ptr = tensor_data_ptr<float>(pred_state[0].get());
        auto dec_tensor = make_tensor(mem.get(), const_cast<float*>(dec_ptr), kPredDim,
                                      {1, kPredDim, 1}, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);
        auto outs = model->joiner.run({enc_tensor.get(), dec_tensor.get()});
        ONNXTensorElementDataType dtype;
        std::vector<int64_t> shape;
        size_t count = 0;
        read_tensor_shape_and_type(outs[0].get(), &dtype, &shape, &count);
        if (dtype != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || count != kVocabSize) {
            throw std::runtime_error("Unexpected GigaAM-v4 joint output");
        }
        const float* logits = tensor_data_ptr<float>(outs[0].get());
        return static_cast<int32_t>(std::max_element(logits, logits + kVocabSize) - logits);
    }

    void process_mel_chunk_64(const float* chunk_frame_major_64) {
        const auto& rt = ort_runtime();
        OrtMemoryInfo* raw_mem = nullptr;
        rt.check(rt.api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &raw_mem));
        auto mem = own(raw_mem, rt.api->ReleaseMemoryInfo);

        // Transpose [64 frames, 64 bins] -> [1, 64 bins, 64 frames]
        std::vector<float> mel_ch_major(kMelBins * kStreamMelChunk);
        for (int32_t t = 0; t < kStreamMelChunk; ++t) {
            for (int32_t b = 0; b < kMelBins; ++b) {
                mel_ch_major[b * kStreamMelChunk + t] = chunk_frame_major_64[t * kMelBins + b];
            }
        }
        int64_t valid_mel_len = kStreamMelChunk;
        auto mel_tensor = make_tensor(mem.get(), mel_ch_major.data(), mel_ch_major.size(),
                                      {1, kMelBins, kStreamMelChunk}, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);
        auto len_tensor = make_tensor(mem.get(), &valid_mel_len, 1, {1}, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);

        std::vector<const OrtValue*> f_in{mel_tensor.get(), len_tensor.get()};
        for (const auto& st : front_states) f_in.push_back(st.get());
        auto f_out = model->frontend.run(f_in);
        for (size_t i = 0; i < front_states.size(); ++i) {
            front_states[i] = std::move(f_out[2 + i]);
        }

        std::vector<const OrtValue*> s_in{f_out[0].get(), f_out[1].get()};
        for (const auto& st : step_states) s_in.push_back(st.get());
        auto s_out = model->step.run(s_in);
        for (size_t i = 0; i < step_states.size(); ++i) {
            step_states[i] = std::move(s_out[2 + i]);
        }

        ONNXTensorElementDataType enc_type, len_type;
        std::vector<int64_t> enc_shape, len_shape;
        size_t enc_count = 0, len_count = 0;
        read_tensor_shape_and_type(s_out[0].get(), &enc_type, &enc_shape, &enc_count);
        read_tensor_shape_and_type(s_out[1].get(), &len_type, &len_shape, &len_count);
        if (enc_type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || enc_shape.size() != 3 ||
            enc_shape[0] != 1 || enc_shape[1] != kEncoderDim ||
            len_type != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64 || len_count != 1) {
            throw std::runtime_error("Unexpected GigaAM-v4 stream step output");
        }
        const int64_t valid_frames = tensor_data_ptr<int64_t>(s_out[1].get())[0];
        if (valid_frames < 0 || valid_frames > enc_shape[2]) {
            throw std::runtime_error("Invalid GigaAM-v4 encoded frame length");
        }
        const float* enc_data = tensor_data_ptr<float>(s_out[0].get());
        const int64_t stride_t = enc_shape[2];
        std::array<float, kEncoderDim> frame_vec{};
        for (int64_t i = 0; i < valid_frames; ++i) {
            for (int32_t d = 0; d < kEncoderDim; ++d) {
                frame_vec[d] = enc_data[d * stride_t + i];
            }
            const int32_t frame_idx = encoded_frames + static_cast<int32_t>(i);
            for (int32_t sym = 0; sym < kMaxSymbolsPerFrame; ++sym) {
                const int32_t tok = run_joint(frame_vec.data());
                if (tok == kBlankId) {
                    ++trailing_blanks;
                    break;
                }
                pending_tokens.push_back(TokenFrame{tok, frame_idx});
                trailing_blanks = 0;
                run_predictor(tok);
            }
        }
        encoded_frames += static_cast<int32_t>(valid_frames);
    }

    void extract_ready_mel_from_pcm() {
        if (pcm_buffer.size() < static_cast<size_t>(kWindowSize)) return;
        const size_t num_frames = 1 + (pcm_buffer.size() - kWindowSize) / kHopSize;
        model->features.compute_frame_major(pcm_buffer.data(), pcm_buffer.size(), &mel_buffer);
        const size_t consumed = num_frames * kHopSize;
        pcm_buffer.erase(pcm_buffer.begin(), pcm_buffer.begin() + consumed);
    }

    void drain_ready_mel_chunks() {
        const size_t total_mel_frames = mel_buffer.size() / kMelBins;
        size_t consumed_frames = 0;
        while (total_mel_frames - consumed_frames >= static_cast<size_t>(kStreamMelChunk)) {
            process_mel_chunk_64(mel_buffer.data() + consumed_frames * kMelBins);
            consumed_frames += kStreamMelChunk;
        }
        if (consumed_frames > 0) {
            mel_buffer.erase(mel_buffer.begin(), mel_buffer.begin() + consumed_frames * kMelBins);
        }
    }

    std::string build_events_json(bool final_flush) {
        const float max_sec = static_cast<float>(total_samples) / static_cast<float>(kSampleRate);
        auto words = group_tokens_into_words(pending_tokens, model->tokens, max_sec, last_committed_sec);

        if (words.empty()) {
            return "";
        }

        size_t commit_count = 0;
        if (final_flush) {
            commit_count = words.size();
        } else if (trailing_blanks >= 25) {
            // >= 1.0 s of silence after last token: commit all pending words cleanly.
            commit_count = words.size();
        } else {
            // Commit through the latest completed sentence boundary (leaving at least 1 word open so subwords aren't split).
            for (size_t i = 0; i + 1 < words.size(); ++i) {
                if (is_sentence_ending_word(words[i].text)) {
                    commit_count = i + 1;
                }
            }
            if (commit_count == 0 && words.size() >= 14) {
                commit_count = words.size() - 2;
            }
        }

        std::string json = "[";
        bool need_comma = false;

        if (commit_count > 0) {
            json += "{\"type\":\"commit\",\"words\":";
            append_words_json_array(&json, words, 0, commit_count);
            json += "}";
            need_comma = true;
            last_committed_sec = std::max(last_committed_sec, words[commit_count - 1].end);

            const std::string detected = detect_language_from_words(
                std::vector<WordItem>(words.begin(), words.begin() + commit_count), true);
            if (!detected.empty() && detected != last_emitted_lang) {
                last_emitted_lang = detected;
                json += ",{\"type\":\"language\",\"lang\":\"" + detected + "\"}";
            }

            const size_t erase_tokens = words[commit_count - 1].token_end_idx;
            pending_tokens.erase(pending_tokens.begin(), pending_tokens.begin() + erase_tokens);
        }

        if (commit_count < words.size()) {
            if (need_comma) json.push_back(',');
            json += "{\"type\":\"partial\",\"open\":";
            append_words_json_array(&json, words, commit_count, words.size());
            json += ",\"tail\":[]}";
            need_comma = true;

            if (commit_count == 0) {
                const std::string detected = detect_language_from_words(words, false);
                if (!detected.empty() && detected != last_emitted_lang) {
                    last_emitted_lang = detected;
                    json += ",{\"type\":\"language\",\"lang\":\"" + detected + "\"}";
                }
            }
        } else if (final_flush) {
            if (need_comma) json.push_back(',');
            json += "{\"type\":\"partial\",\"open\":[],\"tail\":[]}";
        }

        json.push_back(']');
        return json;
    }

    std::string push_pcm16(const int16_t* pcm, int32_t length) {
        if (length <= 0) return "";
        const size_t old_size = pcm_buffer.size();
        pcm_buffer.resize(old_size + static_cast<size_t>(length));
        constexpr float kScale = 1.0f / 32768.0f;
        for (int32_t i = 0; i < length; ++i) {
            pcm_buffer[old_size + i] = static_cast<float>(pcm[i]) * kScale;
        }
        total_samples += length;
        extract_ready_mel_from_pcm();
        drain_ready_mel_chunks();
        return build_events_json(false);
    }

    std::string finish() {
        if (total_samples <= 0) return "";
        if (!pcm_buffer.empty()) {
            if (pcm_buffer.size() < static_cast<size_t>(kWindowSize)) {
                pcm_buffer.resize(kWindowSize, 0.0f);
            }
            extract_ready_mel_from_pcm();
        }
        // Pad any partial chunk + add one 640ms future-context silence chunk in log-mel floor
        // so the streaming frontend/step never sees valid_len < 64 and flushes the final word.
        const size_t current_mel_frames = mel_buffer.size() / kMelBins;
        const size_t rem = current_mel_frames % kStreamMelChunk;
        const size_t pad_frames = (rem > 0 ? (kStreamMelChunk - rem) : 0) + kStreamMelChunk;
        mel_buffer.resize((current_mel_frames + pad_frames) * kMelBins, kLogMelFloor);
        drain_ready_mel_chunks();
        return build_events_json(true);
    }
};

// =========================================================================
// Full-Context Offline RNN-T Model & Protobuf E2E Weight Extractor
// =========================================================================

namespace proto {
inline uint64_t read_varint(const uint8_t* buf, size_t len, size_t* pos) {
    uint64_t res = 0;
    int shift = 0;
    while (*pos < len) {
        const uint8_t b = buf[(*pos)++];
        res |= static_cast<uint64_t>(b & 0x7Fu) << shift;
        if ((b & 0x80u) == 0) return res;
        shift += 7;
        if (shift > 63) break;
    }
    throw std::runtime_error("Corrupt ONNX protobuf varint");
}

inline void skip_field(const uint8_t* buf, size_t len, size_t* pos, uint8_t wire_type) {
    switch (wire_type) {
        case 0: read_varint(buf, len, pos); break;
        case 1:
            if (*pos + 8 > len) throw std::runtime_error("Truncated ONNX field");
            *pos += 8;
            break;
        case 2: {
            const uint64_t n = read_varint(buf, len, pos);
            if (*pos + n > len) throw std::runtime_error("Truncated ONNX bytes");
            *pos += static_cast<size_t>(n);
            break;
        }
        case 5:
            if (*pos + 4 > len) throw std::runtime_error("Truncated ONNX fixed32");
            *pos += 4;
            break;
        default:
            throw std::runtime_error("Unsupported ONNX wire type");
    }
}

struct ParsedTensor {
    std::string name;
    std::vector<int64_t> dims;
    int32_t data_type = 0;
    std::vector<float> data;
};

ParsedTensor parse_tensor_proto(const uint8_t* buf, size_t len) {
    ParsedTensor t;
    size_t pos = 0;
    const uint8_t* raw_ptr = nullptr;
    size_t raw_len = 0;
    while (pos < len) {
        const uint64_t tag = read_varint(buf, len, &pos);
        const uint32_t fn = static_cast<uint32_t>(tag >> 3);
        const uint8_t wt = static_cast<uint8_t>(tag & 7);
        if (fn == 1 && wt == 0) {
            t.dims.push_back(static_cast<int64_t>(read_varint(buf, len, &pos)));
        } else if (fn == 1 && wt == 2) {
            const size_t n = static_cast<size_t>(read_varint(buf, len, &pos));
            const size_t end = pos + n;
            if (end > len) throw std::runtime_error("Truncated ONNX dims");
            while (pos < end) t.dims.push_back(static_cast<int64_t>(read_varint(buf, end, &pos)));
        } else if (fn == 2 && wt == 0) {
            t.data_type = static_cast<int32_t>(read_varint(buf, len, &pos));
        } else if (fn == 4 && wt == 5) {
            if (pos + 4 > len) throw std::runtime_error("Truncated float_data");
            float f = 0.0f;
            std::memcpy(&f, buf + pos, 4);
            t.data.push_back(f);
            pos += 4;
        } else if (fn == 4 && wt == 2) {
            const size_t n = static_cast<size_t>(read_varint(buf, len, &pos));
            if (pos + n > len || (n % 4) != 0) throw std::runtime_error("Invalid packed float_data");
            const size_t old = t.data.size();
            t.data.resize(old + n / 4);
            std::memcpy(t.data.data() + old, buf + pos, n);
            pos += n;
        } else if (fn == 8 && wt == 2) {
            const size_t n = static_cast<size_t>(read_varint(buf, len, &pos));
            if (pos + n > len) throw std::runtime_error("Truncated tensor name");
            t.name.assign(reinterpret_cast<const char*>(buf + pos), n);
            pos += n;
        } else if (fn == 9 && wt == 2) {
            const size_t n = static_cast<size_t>(read_varint(buf, len, &pos));
            if (pos + n > len) throw std::runtime_error("Truncated raw_data");
            raw_ptr = buf + pos;
            raw_len = n;
            pos += n;
        } else {
            skip_field(buf, len, &pos, wt);
        }
    }
    if (raw_ptr && raw_len > 0 && t.data_type == 1) {
        if ((raw_len % sizeof(float)) != 0) throw std::runtime_error("Invalid float raw_data");
        t.data.resize(raw_len / sizeof(float));
        std::memcpy(t.data.data(), raw_ptr, raw_len);
    }
    return t;
}

std::unordered_map<std::string, ParsedTensor> extract_initializers(const std::string& onnx_path) {
    std::ifstream file(onnx_path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) throw std::runtime_error("Cannot open ONNX file: " + onnx_path);
    const std::streamsize size = file.tellg();
    if (size <= 0) throw std::runtime_error("Empty ONNX file: " + onnx_path);
    file.seekg(0, std::ios::beg);
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    if (!file.read(reinterpret_cast<char*>(bytes.data()), size)) {
        throw std::runtime_error("Failed reading ONNX file: " + onnx_path);
    }

    std::unordered_map<std::string, ParsedTensor> result;
    const uint8_t* buf = bytes.data();
    const size_t len = bytes.size();
    size_t pos = 0;
    while (pos < len) {
        const uint64_t tag = read_varint(buf, len, &pos);
        const uint32_t fn = static_cast<uint32_t>(tag >> 3);
        const uint8_t wt = static_cast<uint8_t>(tag & 7);
        if (fn == 7 && wt == 2) {  // ModelProto.graph
            const size_t g_len = static_cast<size_t>(read_varint(buf, len, &pos));
            const size_t g_end = pos + g_len;
            if (g_end > len) throw std::runtime_error("Truncated ONNX graph");
            while (pos < g_end) {
                const uint64_t gtag = read_varint(buf, g_end, &pos);
                const uint32_t gfn = static_cast<uint32_t>(gtag >> 3);
                const uint8_t gwt = static_cast<uint8_t>(gtag & 7);
                if (gfn == 5 && gwt == 2) {  // GraphProto.initializer
                    const size_t t_len = static_cast<size_t>(read_varint(buf, g_end, &pos));
                    if (pos + t_len > g_end) throw std::runtime_error("Truncated initializer");
                    auto tensor = parse_tensor_proto(buf + pos, t_len);
                    pos += t_len;
                    if (!tensor.name.empty()) {
                        result.emplace(tensor.name, std::move(tensor));
                    }
                } else {
                    skip_field(buf, g_end, &pos, gwt);
                }
            }
        } else {
            skip_field(buf, len, &pos, wt);
        }
    }
    return result;
}
}  // namespace proto

struct E2eTransducerWeights {
    // Row-major layouts for sequential NEON vectorization:
    std::vector<float> emb;         // [1025, 320]
    std::vector<float> lstm_w_ih;   // [1280, 320]
    std::vector<float> lstm_w_hh;   // [1280, 320]
    std::vector<float> lstm_bias;   // [1280] = bias_ih + bias_hh
    std::vector<float> pred_w;      // [320, 320] (transposed from [320_in, 320_out] -> [out, in])
    std::vector<float> pred_b;      // [320]
    std::vector<float> joint_w;     // [1025, 320] (transposed from [320, 1025] -> [vocab, 320])
    std::vector<float> joint_b;     // [1025]

    static E2eTransducerWeights load_from_advance(const std::string& advance_path) {
        auto inits = proto::extract_initializers(advance_path);
        auto get = [&](const char* name, std::vector<int64_t> expected_dims) -> const proto::ParsedTensor& {
            auto it = inits.find(name);
            if (it == inits.end()) {
                throw std::runtime_error(std::string("Missing advance initializer: ") + name);
            }
            if (it->second.data_type != 1 || it->second.dims != expected_dims) {
                throw std::runtime_error(std::string("Unexpected shape/dtype for initializer: ") + name);
            }
            size_t total = 1;
            for (int64_t d : expected_dims) total *= static_cast<size_t>(d);
            if (it->second.data.size() != total) {
                throw std::runtime_error(std::string("Incomplete initializer data: ") + name);
            }
            return it->second;
        };

        E2eTransducerWeights w;
        const auto& emb_t = get("1040", {kPredDim, kVocabSize});
        w.emb.resize(kVocabSize * kPredDim);
        for (int32_t d = 0; d < kPredDim; ++d) {
            for (int32_t v = 0; v < kVocabSize; ++v) {
                w.emb[v * kPredDim + d] = emb_t.data[d * kVocabSize + v];
            }
        }

        w.lstm_w_ih = get("step.heads.e2e.decoder.lstm.weight_ih_l0", {4 * kPredDim, kPredDim}).data;
        w.lstm_w_hh = get("step.heads.e2e.decoder.lstm.weight_hh_l0", {4 * kPredDim, kPredDim}).data;
        const auto& bih = get("step.heads.e2e.decoder.lstm.bias_ih_l0", {4 * kPredDim}).data;
        const auto& bhh = get("step.heads.e2e.decoder.lstm.bias_hh_l0", {4 * kPredDim}).data;
        w.lstm_bias.resize(4 * kPredDim);
        for (int32_t i = 0; i < 4 * kPredDim; ++i) {
            w.lstm_bias[i] = bih[i] + bhh[i];
        }

        const auto& pred_t = get("1042", {kPredDim, kPredDim});
        w.pred_w.resize(kPredDim * kPredDim);
        for (int32_t in_d = 0; in_d < kPredDim; ++in_d) {
            for (int32_t out_d = 0; out_d < kPredDim; ++out_d) {
                w.pred_w[out_d * kPredDim + in_d] = pred_t.data[in_d * kPredDim + out_d];
            }
        }
        w.pred_b = get("step.heads.e2e.joint.pred.bias", {kPredDim}).data;

        const auto& joint_t = get("1044", {kPredDim, kVocabSize});
        w.joint_w.resize(kVocabSize * kPredDim);
        for (int32_t d = 0; d < kPredDim; ++d) {
            for (int32_t v = 0; v < kVocabSize; ++v) {
                w.joint_w[v * kPredDim + d] = joint_t.data[d * kVocabSize + v];
            }
        }
        w.joint_b = get("step.heads.e2e.joint.joint_net.1.bias", {kVocabSize}).data;
        return w;
    }

    void step_predictor(int32_t token, float* h, float* c, float* out_pred) const {
        std::array<float, 4 * kPredDim> gates{};
        const float* x = (token >= 0 && token < kBlankId) ? (emb.data() + token * kPredDim) : nullptr;
        for (int32_t row = 0; row < 4 * kPredDim; ++row) {
            const float* wh = lstm_w_hh.data() + row * kPredDim;
            float acc = lstm_bias[row];
            if (x) {
                const float* wi = lstm_w_ih.data() + row * kPredDim;
                for (int32_t k = 0; k < kPredDim; ++k) {
                    acc += wi[k] * x[k] + wh[k] * h[k];
                }
            } else {
                for (int32_t k = 0; k < kPredDim; ++k) {
                    acc += wh[k] * h[k];
                }
            }
            gates[row] = acc;
        }
        auto sigmoid = [](float z) { return 1.0f / (1.0f + std::exp(-z)); };
        for (int32_t k = 0; k < kPredDim; ++k) {
            const float ig = sigmoid(gates[k]);
            const float fg = sigmoid(gates[kPredDim + k]);
            const float gg = std::tanh(gates[2 * kPredDim + k]);
            const float og = sigmoid(gates[3 * kPredDim + k]);
            const float c_new = fg * c[k] + ig * gg;
            c[k] = c_new;
            h[k] = og * std::tanh(c_new);
        }
        for (int32_t out_d = 0; out_d < kPredDim; ++out_d) {
            const float* row = pred_w.data() + out_d * kPredDim;
            float acc = pred_b[out_d];
            for (int32_t k = 0; k < kPredDim; ++k) {
                acc += row[k] * h[k];
            }
            out_pred[out_d] = acc;
        }
    }

    void compute_joint_logits(const float* enc_t, const float* pred_u, float* out_logits) const {
        std::array<float, kPredDim> act{};
        for (int32_t d = 0; d < kPredDim; ++d) {
            const float v = enc_t[d] + pred_u[d];
            act[d] = v > 0.0f ? v : 0.0f;
        }
        for (int32_t v = 0; v < kVocabSize; ++v) {
            const float* row = joint_w.data() + v * kPredDim;
            float acc = joint_b[v];
            for (int32_t d = 0; d < kPredDim; ++d) {
                acc += row[d] * act[d];
            }
            out_logits[v] = acc;
        }
    }
};

struct GigaAmV4OfflineModel {
    OrtGraph encoder;
    OrtGraph advance;
    E2eTransducerWeights e2e;
    TokenTable tokens;
    FeatureExtractor features;

    GigaAmV4OfflineModel(const std::string& bundle_dir, int threads)
        : encoder(bundle_dir + "/rnnt/v4_rnnt_encoder.int8.onnx", threads, 2, 2),
          advance(bundle_dir + "/rnnt/v4_rnnt_advance.onnx", threads, 2, 1),
          e2e(E2eTransducerWeights::load_from_advance(bundle_dir + "/rnnt/v4_rnnt_advance.onnx")),
          tokens(TokenTable::load(bundle_dir + "/rnnt-streaming-640ms/tokens.e2e.txt")) {
        LOGI("Loaded GigaAM-v4 offline graphs (threads=%d)", threads);
    }

    // Teacher-forced acoustic Viterbi fallback if ORT advance ever differs by 1 tie from C++ greedy loop.
    std::vector<int32_t> viterbi_align(const float* proj_e2e, int32_t num_frames,
                                       const std::vector<int32_t>& target_ids) const {
        const int32_t U = static_cast<int32_t>(target_ids.size());
        if (U == 0 || num_frames <= 0) return {};

        std::vector<float> preds((U + 1) * kPredDim);
        std::array<float, kPredDim> h{};
        std::array<float, kPredDim> c{};
        e2e.step_predictor(kBlankId, h.data(), c.data(), preds.data());
        for (int32_t u = 0; u < U; ++u) {
            e2e.step_predictor(target_ids[u], h.data(), c.data(), preds.data() + (u + 1) * kPredDim);
        }

        constexpr float kNegInf = -1e30f;
        std::vector<float> dp_prev(U + 1, kNegInf);
        std::vector<float> dp_cur(U + 1, kNegInf);
        std::vector<uint8_t> emitted_bp(static_cast<size_t>(num_frames) * (U + 1), 0);
        std::vector<uint8_t> streak(U + 1, 0);
        std::vector<uint8_t> start_emitted(U + 1, 0);
        std::array<float, kVocabSize> logits{};

        dp_prev[0] = 0.0f;

        for (int32_t t = 0; t < num_frames; ++t) {
            const float* enc_t = proj_e2e + t * kPredDim;
            float best_prev = kNegInf;
            for (int32_t u = 0; u <= U; ++u) {
                if (dp_prev[u] > best_prev) best_prev = dp_prev[u];
                dp_cur[u] = dp_prev[u];
                streak[u] = 0;
                start_emitted[u] = 0;
            }
            const float prune_thresh = best_prev - 35.0f;
            std::vector<float> blank_next(U + 1, kNegInf);

            for (int32_t u = 0; u <= U; ++u) {
                if (dp_cur[u] < prune_thresh) continue;
                e2e.compute_joint_logits(enc_t, preds.data() + u * kPredDim, logits.data());
                const float max_l = *std::max_element(logits.begin(), logits.end());
                double sum_exp = 0.0;
                for (float v : logits) sum_exp += std::exp(static_cast<double>(v - max_l));
                const float lse = max_l + static_cast<float>(std::log(sum_exp));

                const float lp_blank = logits[kBlankId] - lse;
                if (dp_cur[u] + lp_blank > blank_next[u]) {
                    blank_next[u] = dp_cur[u] + lp_blank;
                }
                if (u < U && streak[u] < kMaxSymbolsPerFrame) {
                    const float lp_tok = logits[target_ids[u]] - lse;
                    const float cand = dp_cur[u] + lp_tok;
                    if (cand > dp_cur[u + 1]) {
                        dp_cur[u + 1] = cand;
                        streak[u + 1] = static_cast<uint8_t>(streak[u] + 1);
                        start_emitted[u + 1] = static_cast<uint8_t>(start_emitted[u] + 1);
                    }
                }
            }
            for (int32_t u = 0; u <= U; ++u) {
                emitted_bp[static_cast<size_t>(t) * (U + 1) + u] = start_emitted[u];
                dp_prev[u] = blank_next[u];
            }
        }

        std::vector<int32_t> frames(U, num_frames - 1);
        int32_t u = U;
        for (int32_t t = num_frames - 1; t >= 0 && u > 0; --t) {
            const int32_t k = std::min<int32_t>(u, emitted_bp[static_cast<size_t>(t) * (U + 1) + u]);
            for (int32_t j = 0; j < k; ++j) {
                frames[u - 1 - j] = t;
            }
            u -= k;
        }
        return frames;
    }

    std::string transcribe_pcm16(const int16_t* pcm, int32_t length) const {
        if (length < kHopSize) return "";
        const int64_t real_samples = length;
        const size_t padded_samples = std::max<size_t>(static_cast<size_t>(length), static_cast<size_t>(kWindowSize));
        std::vector<float> f32(padded_samples, 0.0f);
        constexpr float kScale = 1.0f / 32768.0f;
        for (int32_t i = 0; i < length; ++i) {
            f32[i] = static_cast<float>(pcm[i]) * kScale;
        }

        size_t mel_frames = 0;
        std::vector<float> mel = features.compute_channel_major(f32.data(), padded_samples, &mel_frames);
        if (mel_frames == 0) return "";

        const auto& rt = ort_runtime();
        OrtMemoryInfo* raw_mem = nullptr;
        rt.check(rt.api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &raw_mem));
        auto mem = own(raw_mem, rt.api->ReleaseMemoryInfo);

        int64_t mel_len = static_cast<int64_t>(mel_frames);
        auto mel_tensor = make_tensor(mem.get(), mel.data(), mel.size(),
                                      {1, kMelBins, mel_len}, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT);
        auto len_tensor = make_tensor(mem.get(), &mel_len, 1, {1}, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64);

        auto enc_outs = encoder.run({mel_tensor.get(), len_tensor.get()});
        ONNXTensorElementDataType len_type, proj_type;
        std::vector<int64_t> len_shape, proj_shape;
        size_t len_count = 0, proj_count = 0;
        read_tensor_shape_and_type(enc_outs[0].get(), &len_type, &len_shape, &len_count);
        read_tensor_shape_and_type(enc_outs[1].get(), &proj_type, &proj_shape, &proj_count);
        if (len_type != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64 || len_count != 1 ||
            proj_type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || proj_shape.size() != 4 ||
            proj_shape[0] != 2 || proj_shape[1] != 1 || proj_shape[3] != kPredDim) {
            throw std::runtime_error("Unexpected GigaAM-v4 offline encoder output");
        }
        const int64_t valid_frames = tensor_data_ptr<int64_t>(enc_outs[0].get())[0];
        if (valid_frames <= 0 || valid_frames > proj_shape[2]) {
            return "";
        }

        // Run authoritative advance.onnx graph
        auto adv_outs = advance.run({enc_outs[1].get(), enc_outs[0].get()});
        ONNXTensorElementDataType tok_type;
        std::vector<int64_t> tok_shape;
        size_t tok_count = 0;
        read_tensor_shape_and_type(adv_outs[0].get(), &tok_type, &tok_shape, &tok_count);
        if (tok_type != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64 || tok_shape.size() != 3 ||
            tok_shape[1] != 2 || tok_shape[2] != 1) {
            throw std::runtime_error("Unexpected GigaAM-v4 advance output");
        }
        const int64_t* adv_data = tensor_data_ptr<int64_t>(adv_outs[0].get());
        std::vector<int32_t> ort_ids;
        for (int64_t i = 0; i < tok_shape[0]; ++i) {
            const int64_t id = adv_data[i * 2];  // head 0 = e2e
            if (id < 0 || id >= kBlankId) continue;
            ort_ids.push_back(static_cast<int32_t>(id));
        }
        if (ort_ids.empty()) return "";

        // Head 0 projection lies in the first half of head_outputs: [1, proj_shape[2], 320]
        const float* proj_e2e = tensor_data_ptr<float>(enc_outs[1].get());

        // Fast exact greedy loop to capture acoustic frame emission indices
        std::vector<int32_t> cpp_ids;
        std::vector<int32_t> cpp_frames;
        cpp_ids.reserve(ort_ids.size());
        cpp_frames.reserve(ort_ids.size());

        std::array<float, kPredDim> h{};
        std::array<float, kPredDim> c{};
        std::array<float, kPredDim> pred{};
        std::array<float, kVocabSize> logits{};
        e2e.step_predictor(kBlankId, h.data(), c.data(), pred.data());

        for (int32_t t = 0; t < static_cast<int32_t>(valid_frames); ++t) {
            const float* enc_t = proj_e2e + t * kPredDim;
            for (int32_t sym = 0; sym < kMaxSymbolsPerFrame; ++sym) {
                e2e.compute_joint_logits(enc_t, pred.data(), logits.data());
                const int32_t tok = static_cast<int32_t>(std::max_element(logits.begin(), logits.end()) - logits.begin());
                if (tok == kBlankId) break;
                cpp_ids.push_back(tok);
                cpp_frames.push_back(t);
                e2e.step_predictor(tok, h.data(), c.data(), pred.data());
            }
        }

        std::vector<int32_t> aligned_frames;
        if (cpp_ids == ort_ids) {
            aligned_frames = std::move(cpp_frames);
        } else {
            LOGI("Offline tie fallback: Viterbi aligning %zu tokens over %lld frames",
                 ort_ids.size(), static_cast<long long>(valid_frames));
            aligned_frames = viterbi_align(proj_e2e, static_cast<int32_t>(valid_frames), ort_ids);
        }

        std::vector<TokenFrame> token_frames;
        token_frames.reserve(ort_ids.size());
        for (size_t i = 0; i < ort_ids.size(); ++i) {
            token_frames.push_back(TokenFrame{ort_ids[i], aligned_frames[i]});
        }

        const float duration_sec = static_cast<float>(real_samples) / static_cast<float>(kSampleRate);
        auto words = group_tokens_into_words(token_frames, tokens, duration_sec, 0.0f);
        if (words.empty()) return "";

        const std::string lang = detect_language_from_words(words, true);
        std::string json = "{\"language\":\"";
        json += lang;
        json += "\",\"words\":";
        append_words_json_array(&json, words, 0, words.size());
        json.push_back('}');
        return json;
    }
};

std::string read_jstring(JNIEnv* env, jstring value) {
    if (!value) return "";
    const char* raw = env->GetStringUTFChars(value, nullptr);
    if (!raw) return "";
    std::string out(raw);
    env->ReleaseStringUTFChars(value, raw);
    return out;
}

void write_err(JNIEnv* env, jbyteArray jErrOut, const char* msg) {
    if (!jErrOut || !msg) return;
    const jsize cap = env->GetArrayLength(jErrOut);
    if (cap <= 0) return;
    const jsize len = static_cast<jsize>(std::min<size_t>(std::strlen(msg), static_cast<size_t>(cap)));
    env->SetByteArrayRegion(jErrOut, 0, len, reinterpret_cast<const jbyte*>(msg));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_stenograf_app_asr_GigaAmV4Native_nativeLoadOnline(
    JNIEnv* env, jobject, jstring jBundleDir, jint threads, jbyteArray jErrOut
) {
    try {
        const std::string dir = read_jstring(env, jBundleDir);
        auto* model = new GigaAmV4OnlineModel(dir, threads > 0 ? threads : 2);
        return reinterpret_cast<jlong>(model);
    } catch (const std::exception& e) {
        LOGE("nativeLoadOnline failed: %s", e.what());
        write_err(env, jErrOut, e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_stenograf_app_asr_GigaAmV4Native_nativeFreeOnline(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<GigaAmV4OnlineModel*>(handle);
}

JNIEXPORT jlong JNICALL
Java_com_stenograf_app_asr_GigaAmV4Native_nativeStreamCreate(JNIEnv*, jobject, jlong onlineHandle) {
    auto* model = reinterpret_cast<const GigaAmV4OnlineModel*>(onlineHandle);
    if (!model) return 0;
    try {
        auto* session = new GigaAmV4StreamSession(model);
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception& e) {
        LOGE("nativeStreamCreate failed: %s", e.what());
        return 0;
    }
}

JNIEXPORT jstring JNICALL
Java_com_stenograf_app_asr_GigaAmV4Native_nativeStreamPushPcm16(
    JNIEnv* env, jobject, jlong streamHandle, jshortArray jPcm, jint offset, jint length
) {
    auto* session = reinterpret_cast<GigaAmV4StreamSession*>(streamHandle);
    if (!session || !jPcm || length <= 0) return nullptr;
    try {
        std::vector<int16_t> raw(static_cast<size_t>(length));
        env->GetShortArrayRegion(jPcm, offset, length, raw.data());
        if (env->ExceptionCheck()) return nullptr;
        const std::string json = session->push_pcm16(raw.data(), length);
        return json.empty() ? nullptr : env->NewStringUTF(json.c_str());
    } catch (const std::exception& e) {
        LOGE("nativeStreamPushPcm16 failed: %s", e.what());
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_com_stenograf_app_asr_GigaAmV4Native_nativeStreamFinish(JNIEnv* env, jobject, jlong streamHandle) {
    auto* session = reinterpret_cast<GigaAmV4StreamSession*>(streamHandle);
    if (!session) return nullptr;
    try {
        const std::string json = session->finish();
        return json.empty() ? nullptr : env->NewStringUTF(json.c_str());
    } catch (const std::exception& e) {
        LOGE("nativeStreamFinish failed: %s", e.what());
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_stenograf_app_asr_GigaAmV4Native_nativeStreamFree(JNIEnv*, jobject, jlong streamHandle) {
    delete reinterpret_cast<GigaAmV4StreamSession*>(streamHandle);
}

JNIEXPORT jlong JNICALL
Java_com_stenograf_app_asr_GigaAmV4Native_nativeLoadOffline(
    JNIEnv* env, jobject, jstring jBundleDir, jint threads, jbyteArray jErrOut
) {
    try {
        const std::string dir = read_jstring(env, jBundleDir);
        auto* model = new GigaAmV4OfflineModel(dir, threads > 0 ? threads : 2);
        return reinterpret_cast<jlong>(model);
    } catch (const std::exception& e) {
        LOGE("nativeLoadOffline failed: %s", e.what());
        write_err(env, jErrOut, e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_stenograf_app_asr_GigaAmV4Native_nativeFreeOffline(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<GigaAmV4OfflineModel*>(handle);
}

JNIEXPORT jstring JNICALL
Java_com_stenograf_app_asr_GigaAmV4Native_nativeOfflineTranscribePcm16(
    JNIEnv* env, jobject, jlong offlineHandle, jshortArray jPcm, jint offset, jint length, jbyteArray jErrOut
) {
    auto* model = reinterpret_cast<const GigaAmV4OfflineModel*>(offlineHandle);
    if (!model || !jPcm || length <= 0) return nullptr;
    try {
        std::vector<int16_t> raw(static_cast<size_t>(length));
        env->GetShortArrayRegion(jPcm, offset, length, raw.data());
        if (env->ExceptionCheck()) return nullptr;
        const std::string json = model->transcribe_pcm16(raw.data(), length);
        return json.empty() ? nullptr : env->NewStringUTF(json.c_str());
    } catch (const std::exception& e) {
        LOGE("nativeOfflineTranscribePcm16 failed: %s", e.what());
        write_err(env, jErrOut, e.what());
        return nullptr;
    }
}

}  // extern "C"
