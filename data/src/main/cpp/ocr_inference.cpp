#include "ocr_inference.h"
#include <android/log.h>
#include <fstream>
#include <cstring>
#include <cstdlib>
#include <optional>
#include <dlfcn.h>
#include <thread>
#include <chrono>

// LiteRT Next C++ API headers
#include "litert/cc/litert_compiled_model.h"
#include "litert/cc/litert_environment.h"
#include "litert/cc/litert_expected.h"
#include "litert/cc/litert_tensor_buffer.h"
#include "litert/cc/litert_common.h"
#include "litert/cc/litert_options.h"
#include "litert/cc/litert_buffer_ref.h"

// C API for environment
#include "litert/c/litert_common.h"
#include "litert/c/litert_environment.h"
#include "litert/cc/litert_any.h"

#define LOG_TAG "MihonOCR_Inference"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace mihon {

// Helper to log duration with a consistent message format
static void LogDurationMs(const char* label, const std::chrono::steady_clock::time_point& start) {
    const long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start
    ).count();
    LOGI("%s took %lld ms", label, ms);
}

// Internal structure to hold LiteRT objects using optional for lazy initialization
struct OcrInference::LiteRtObjects {
    std::optional<litert::Environment> env;
    std::optional<litert::CompiledModel> compiled_encoder;
    std::optional<litert::CompiledModel> compiled_decoder;

    std::vector<litert::TensorBuffer> encoder_input_buffers;
    std::vector<litert::TensorBuffer> encoder_output_buffers;
    std::vector<litert::TensorBuffer> decoder_input_buffers;
    std::vector<litert::TensorBuffer> decoder_output_buffers;

    // Pre-allocated output buffers for reading
    std::vector<float> encoder_hidden_states;
    std::vector<float> decoder_logits;

    // Track whether we're using GPU or CPU - per-model and overall
    bool using_gpu = false;
    bool encoder_using_gpu = false;
    bool decoder_using_gpu = false;
};

OcrInference::OcrInference() = default;

OcrInference::~OcrInference() {
    Close();
}

bool OcrInference::Initialize(
    const uint8_t* encoder_data,
    size_t encoder_size,
    const uint8_t* decoder_data,
    size_t decoder_size,
    const uint8_t* embeddings_data,
    size_t embeddings_size,
    const char* cache_dir,
    const char* native_lib_dir
) {
    if (initialized_) {
        LOGE("OcrInference already initialized");
        return false;
    }
    const auto overall_init_start = std::chrono::steady_clock::now();

    try {
        const size_t embedding_count = embeddings_size / sizeof(float);
        embeddings_.resize(embedding_count);
        std::memcpy(embeddings_.data(), embeddings_data, embeddings_size);

        litert_ = std::make_unique<LiteRtObjects>();

        const auto env_start = std::chrono::steady_clock::now();

        std::vector<litert::Environment::Option> env_options;
        env_options.push_back({
            litert::Environment::OptionTag::DispatchLibraryDir,
            litert::LiteRtVariant{native_lib_dir}
        });

        auto env_result = litert::Environment::Create(env_options);
        if (!env_result.HasValue()) {
            LOGE("Failed to create LiteRT environment: %s",
                 env_result.Error().Message().c_str());
            return false;
        }
        litert_->env.emplace(std::move(env_result.Value()));
        LogDurationMs("LiteRT Environment creation", env_start);

        void* opencl_lib = dlopen("libOpenCL.so", RTLD_NOW | RTLD_GLOBAL);
        if (!opencl_lib) {
            opencl_lib = dlopen("/vendor/lib64/libOpenCL.so", RTLD_NOW | RTLD_GLOBAL);
            if (!opencl_lib) {
                LOGW("Failed to load OpenCL library: %s", dlerror());
            }
        }

        if (!TryCompileWithGpu(encoder_data, encoder_size, decoder_data, decoder_size)) {
            LOGE("GPU compilation failed; LiteRT Next GPU acceleration is required for OCR");
            return false;
        }
        litert_->using_gpu = (litert_->encoder_using_gpu && litert_->decoder_using_gpu);
        if (!litert_->using_gpu) {
            LOGE("GPU compilation completed but GPU flags are inconsistent; aborting initialization");
            return false;
        }
        if (!CreateBuffers()) {
            LOGE("Failed to create GPU buffers");
            return false;
        }

        if (!PerformWarmup()) {
            LOGE("GPU warmup failed; unable to verify GPU execution");
            return false;
        }

        embeddings_input_.resize(MAX_SEQUENCE_LENGTH * HIDDEN_SIZE, 0.0f);
        attention_mask_.resize(MAX_SEQUENCE_LENGTH, 0.0f);
        input_ids_.resize(MAX_SEQUENCE_LENGTH, PAD_TOKEN_ID);

        initialized_ = true;
        LogDurationMs("Overall OcrInference Initialize", overall_init_start);
        LOGI("ACCELERATOR_ENCODER=%s", litert_->encoder_using_gpu ? "GPU" : "CPU");
        LOGI("ACCELERATOR_DECODER=%s", litert_->decoder_using_gpu ? "GPU" : "CPU");
        LOGI("ACCELERATOR=%s/%s", litert_->encoder_using_gpu ? "GPU" : "CPU", litert_->decoder_using_gpu ? "GPU" : "CPU");

        return true;

    } catch (const std::exception& e) {
        LOGE("Exception during initialization: %s", e.what());
        Close();
        return false;
    }
}

bool OcrInference::CreateBuffers() {
    const auto start = std::chrono::steady_clock::now();

    auto encoder_input_result = litert_->compiled_encoder->CreateInputBuffers();
    if (!encoder_input_result.HasValue()) {
        LOGE("Failed to create encoder input buffers: %s",
             encoder_input_result.Error().Message().c_str());
        return false;
    }
    litert_->encoder_input_buffers = std::move(encoder_input_result.Value());

    auto encoder_output_result = litert_->compiled_encoder->CreateOutputBuffers();
    if (!encoder_output_result.HasValue()) {
        LOGE("Failed to create encoder output buffers: %s",
             encoder_output_result.Error().Message().c_str());
        return false;
    }
    litert_->encoder_output_buffers = std::move(encoder_output_result.Value());

    auto decoder_input_result = litert_->compiled_decoder->CreateInputBuffers();
    if (!decoder_input_result.HasValue()) {
        LOGE("Failed to create decoder input buffers: %s",
             decoder_input_result.Error().Message().c_str());
        return false;
    }
    litert_->decoder_input_buffers = std::move(decoder_input_result.Value());

    auto decoder_output_result = litert_->compiled_decoder->CreateOutputBuffers();
    if (!decoder_output_result.HasValue()) {
        LOGE("Failed to create decoder output buffers: %s",
             decoder_output_result.Error().Message().c_str());
        return false;
    }
    litert_->decoder_output_buffers = std::move(decoder_output_result.Value());

    auto encoder_out_size_result = litert_->encoder_output_buffers[0].Size();
    if (encoder_out_size_result.HasValue()) {
        encoder_output_size_ = encoder_out_size_result.Value() / sizeof(float);
    } else {
        LOGE("Failed to get encoder output buffer size");
        return false;
    }

    auto decoder_out_size_result = litert_->decoder_output_buffers[0].Size();
    if (decoder_out_size_result.HasValue()) {
        decoder_output_size_ = decoder_out_size_result.Value() / sizeof(float);
    } else {
        LOGE("Failed to get decoder output buffer size");
        return false;
    }

    litert_->encoder_hidden_states.resize(encoder_output_size_);
    litert_->decoder_logits.resize(decoder_output_size_);

    LogDurationMs("CreateBuffers overhead", start);

    return true;
}

bool OcrInference::PerformWarmup() {
    const auto warmup_start = std::chrono::steady_clock::now();

    std::vector<float> dummy_image(IMAGE_SIZE * IMAGE_SIZE * 3, 0.0f);

    auto write_result = litert_->encoder_input_buffers[0].Write<float>(
        absl::MakeConstSpan(dummy_image)
    );
    if (!write_result.HasValue()) {
        LOGE("Warmup: Failed to write encoder input");
        return false;
    }

    auto encoder_run_result = litert_->compiled_encoder->Run(
        litert_->encoder_input_buffers,
        litert_->encoder_output_buffers
    );
    if (!encoder_run_result.HasValue()) {
        LOGE("Warmup: Failed to run encoder: %s", encoder_run_result.Error().Message().c_str());
        return false;
    }

    auto encoder_output_bytes = litert_->encoder_output_buffers[0].Size();
    size_t encoder_output_floats = 0;
    if (encoder_output_bytes.HasValue()) {
        encoder_output_floats = encoder_output_bytes.Value() / sizeof(float);
    }
    if (encoder_output_floats == 0) {
        LOGE("Warmup: Encoder output buffer size is 0");
        return false;
    }

    std::vector<float> warmup_hidden_states(encoder_output_floats, 0.0f);
    auto warmup_read = litert_->encoder_output_buffers[0].Read<float>(
        absl::MakeSpan(warmup_hidden_states)
    );
    if (!warmup_read.HasValue()) {
        LOGE("Warmup: Failed to read encoder output for decoder warmup");
        return false;
    }

    std::vector<float> warmup_attention(MAX_SEQUENCE_LENGTH, 0.0f);
    std::vector<float> warmup_embeddings(MAX_SEQUENCE_LENGTH * HIDDEN_SIZE, 0.0f);
    warmup_attention[0] = 1.0f;

    auto write_hidden_result = litert_->decoder_input_buffers[0].Write<float>(
        absl::MakeConstSpan(warmup_hidden_states)
    );
    if (!write_hidden_result.HasValue()) {
        LOGE("Warmup: Failed to write decoder hidden states input");
        return false;
    }

    auto write_mask_result = litert_->decoder_input_buffers[1].Write<float>(
        absl::MakeConstSpan(warmup_attention)
    );
    if (!write_mask_result.HasValue()) {
        LOGE("Warmup: Failed to write decoder attention mask input");
        return false;
    }

    auto write_embeddings_result = litert_->decoder_input_buffers[2].Write<float>(
        absl::MakeConstSpan(warmup_embeddings)
    );
    if (!write_embeddings_result.HasValue()) {
        LOGE("Warmup: Failed to write decoder embeddings input");
        return false;
    }

    auto decoder_run_result = litert_->compiled_decoder->Run(
        litert_->decoder_input_buffers,
        litert_->decoder_output_buffers
    );
    if (!decoder_run_result.HasValue()) {
        LOGE("Warmup: Failed to run decoder: %s", decoder_run_result.Error().Message().c_str());
        return false;
    }

    LogDurationMs("PerformWarmup total", warmup_start);
    return true;
}

bool OcrInference::TryCompileWithGpu(const uint8_t* encoder_data, size_t encoder_size, const uint8_t* decoder_data, size_t decoder_size) {
    const auto try_compile_start = std::chrono::steady_clock::now();

    auto options_result = litert::Options::Create();
    if (!options_result.HasValue()) {
        LOGW("Failed to create options for GPU compilation");
        return false;
    }
    auto options = std::move(options_result.Value());

    auto hw_result = options.SetHardwareAccelerators(litert::HwAccelerators::kGpu);
    if (!hw_result.HasValue()) {
        LOGW("Failed to set hardware accelerators: %s", hw_result.Error().Message().c_str());
        return false;
    }

    auto gpu_opts_result = options.GetGpuOptions();
    if (gpu_opts_result.HasValue()) {
        auto& gpu_opts = gpu_opts_result.Value();
        auto precision_result = gpu_opts.SetPrecision(litert::GpuOptions::Precision::kFp16);
        if (!precision_result.HasValue()) {
            LOGW("Failed to set GPU precision to FP16: %s", precision_result.Error().Message().c_str());
        }
    } else {
        LOGW("Failed to get GPU options: %s", gpu_opts_result.Error().Message().c_str());
    }

    const auto encoder_compile_start = std::chrono::steady_clock::now();
    auto compiled_encoder_result = litert::CompiledModel::Create(
        *litert_->env,
        litert::BufferRef<uint8_t>(encoder_data, encoder_size),
        options
    );
    if (!compiled_encoder_result.HasValue()) {
        const auto& error = compiled_encoder_result.Error();
        LOGW("Failed to compile encoder with GPU: status=%d, message=%s",
             static_cast<int>(error.Status()), error.Message().c_str());
        return false;
    }
    LogDurationMs("Encoder GPU compile", encoder_compile_start);

    auto encoder_accel_result = compiled_encoder_result.Value().IsFullyAccelerated();
    if (encoder_accel_result.HasValue()) {
        const bool encoder_fully = encoder_accel_result.Value();
        if (!encoder_fully) {
            LOGW("Encoder is not fully GPU-accelerated");
            return false;
        }
    } else {
        LOGW("Failed to query encoder acceleration status");
        return false;
    }

    const auto decoder_compile_start = std::chrono::steady_clock::now();

    auto decoder_options_result = litert::Options::Create();
    if (!decoder_options_result.HasValue()) {
        LOGW("Failed to create options for decoder GPU compilation");
        return false;
    }
    auto decoder_options = std::move(decoder_options_result.Value());
    decoder_options.SetHardwareAccelerators(litert::HwAccelerators::kGpu);

    auto decoder_gpu_opts_result = decoder_options.GetGpuOptions();
    if (decoder_gpu_opts_result.HasValue()) {
        auto& decoder_gpu_opts = decoder_gpu_opts_result.Value();
        auto precision_result = decoder_gpu_opts.SetPrecision(litert::GpuOptions::Precision::kFp16);
        if (!precision_result.HasValue()) {
            LOGW("Failed to set GPU precision to FP16");
        }
    }

    auto compiled_decoder_result = litert::CompiledModel::Create(
        *litert_->env,
        litert::BufferRef<uint8_t>(decoder_data, decoder_size),
        decoder_options
    );
    if (!compiled_decoder_result.HasValue()) {
        const auto& error = compiled_decoder_result.Error();
        LOGW("Failed to compile decoder with GPU: status=%d, message=%s",
             static_cast<int>(error.Status()), error.Message().c_str());
        return false;
    }
    LogDurationMs("Decoder GPU compile", decoder_compile_start);

    auto decoder_accel_result = compiled_decoder_result.Value().IsFullyAccelerated();
    if (decoder_accel_result.HasValue()) {
        const bool decoder_fully = decoder_accel_result.Value();
        if (!decoder_fully) {
            LOGW("Decoder is not fully GPU-accelerated");
            return false;
        }
    } else {
        LOGW("Failed to query decoder acceleration status");
        return false;
    }

    litert_->compiled_encoder.emplace(std::move(compiled_encoder_result.Value()));
    litert_->compiled_decoder.emplace(std::move(compiled_decoder_result.Value()));
    litert_->encoder_using_gpu = true;
    litert_->decoder_using_gpu = true;
    litert_->using_gpu = true;

    LogDurationMs("TryCompileWithGpu total", try_compile_start);
    return true;
}

bool OcrInference::IsUsingGpu() const {
    if (!litert_) return false;
    return litert_->using_gpu;
}

bool OcrInference::IsEncoderUsingGpu() const {
    if (!litert_) return false;
    return litert_->encoder_using_gpu;
}

bool OcrInference::IsDecoderUsingGpu() const {
    if (!litert_) return false;
    return litert_->decoder_using_gpu;
}

void OcrInference::UpdateEmbedding(int token_id, int index) noexcept {
    const int embed_offset = token_id * HIDDEN_SIZE;
    const int output_offset = index * HIDDEN_SIZE;
    std::memcpy(
        embeddings_input_.data() + output_offset,
        embeddings_.data() + embed_offset,
        HIDDEN_SIZE * sizeof(float)
    );
}

int OcrInference::FindMaxLogitToken(int seq_len) const noexcept {
    const int last_token_pos = seq_len - 1;
    const float* logits = litert_->decoder_logits.data() + (last_token_pos * VOCAB_SIZE);

    float max_logit = logits[0];
    int max_token = 0;

    for (int vocab_idx = 1; vocab_idx < VOCAB_SIZE; ++vocab_idx) {
        const float logit = logits[vocab_idx];
        if (logit > max_logit) {
            max_logit = logit;
            max_token = vocab_idx;
        }
    }

    return max_token;
}

int OcrInference::InferTokens(const float* image_data, int* out_tokens, int max_tokens) {
    if (!initialized_ || !litert_) {
        LOGE("OcrInference not initialized");
        return 0;
    }

    try {
        // Run encoder
        const size_t image_data_size = IMAGE_SIZE * IMAGE_SIZE * 3;

        auto write_result = litert_->encoder_input_buffers[0].Write<float>(
            absl::MakeConstSpan(image_data, image_data_size)
        );

        if (!write_result.HasValue()) {
            LOGE("Failed to write encoder input");
            return 0;
        }

        auto encoder_run_start = std::chrono::steady_clock::now();
        auto encoder_run_result = litert_->compiled_encoder->Run(
            litert_->encoder_input_buffers,
            litert_->encoder_output_buffers
        );
        if (!encoder_run_result.HasValue()) {
            LOGE("Failed to run encoder: %s", encoder_run_result.Error().Message().c_str());
            return 0;
        }

        // Read encoder hidden states into our pre-allocated buffer
        auto read_result = litert_->encoder_output_buffers[0].Read<float>(
            absl::MakeSpan(litert_->encoder_hidden_states)
        );
        if (!read_result.HasValue()) {
            LOGE("Failed to read encoder output");
            return 0;
        }

        auto encoder_run_end = std::chrono::steady_clock::now();
        const auto encoder_run_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            encoder_run_end - encoder_run_start
        ).count();
        LOGI("[PERF] Encoder GPU runtime took %lld ms", static_cast<long long>(encoder_run_ms));

        // Initialize decoder state
        std::fill(embeddings_input_.begin(), embeddings_input_.end(), 0.0f);
        std::fill(attention_mask_.begin(), attention_mask_.end(), 0.0f);
        std::fill(input_ids_.begin(), input_ids_.end(), PAD_TOKEN_ID);

        out_tokens[0] = START_TOKEN_ID;
        input_ids_[0] = START_TOKEN_ID;
        UpdateEmbedding(START_TOKEN_ID, 0);
        attention_mask_[0] = 1.0f;
        int token_count = 1;
        long long decoder_run_ms = 0;
        int decoder_iterations = 0;

        auto write_hidden_result = litert_->decoder_input_buffers[0].Write<float>(
            absl::MakeConstSpan(litert_->encoder_hidden_states)
        );
        if (!write_hidden_result.HasValue()) {
            LOGE("Failed to write decoder hidden states input");
            return 0;
        }

        for (int step = 0; step < MAX_SEQUENCE_LENGTH - 1; ++step) {
            auto write_mask_result = litert_->decoder_input_buffers[1].Write<float>(
                absl::MakeConstSpan(attention_mask_.data(), MAX_SEQUENCE_LENGTH)
            );
            if (!write_mask_result.HasValue()) {
                LOGE("Failed to write decoder attention mask input");
                break;
            }

            auto write_emb_result = litert_->decoder_input_buffers[2].Write<float>(
                absl::MakeConstSpan(embeddings_input_.data(), MAX_SEQUENCE_LENGTH * HIDDEN_SIZE)
            );
            if (!write_emb_result.HasValue()) {
                LOGE("Failed to write decoder embeddings input");
                break;
            }

            auto decoder_run_start = std::chrono::steady_clock::now();
            auto decoder_run_result = litert_->compiled_decoder->Run(
                litert_->decoder_input_buffers,
                litert_->decoder_output_buffers
            );
            if (!decoder_run_result.HasValue()) {
                LOGE("Failed to run decoder at step %d: %s", step, decoder_run_result.Error().Message().c_str());
                break;
            }
            decoder_iterations++;

            auto logits_result = litert_->decoder_output_buffers[0].Read<float>(
                absl::MakeSpan(litert_->decoder_logits)
            );
            auto decoder_run_end = std::chrono::steady_clock::now();
            decoder_run_ms += std::chrono::duration_cast<std::chrono::milliseconds>(
                decoder_run_end - decoder_run_start
            ).count();

            if (!logits_result.HasValue()) {
                LOGE("Failed to read decoder output at step %d", step);
                break;
            }

            const int next_token = FindMaxLogitToken(token_count);

            if (next_token < 0 || next_token == END_TOKEN_ID) {
                break;
            }
            const int next_index = token_count;
            out_tokens[next_index] = next_token;
            input_ids_[next_index] = next_token;
            UpdateEmbedding(next_token, next_index);
            attention_mask_[next_index] = 1.0f;

            token_count++;
            if (token_count >= max_tokens || token_count >= MAX_SEQUENCE_LENGTH) {
                break;
            }
        }

        LOGI("[PERF] Decoder GPU cumulative runtime: %lld ms across %d steps",
             static_cast<long long>(decoder_run_ms), decoder_iterations);

        if (litert_->using_gpu) {
            const long long total_gpu_time_ms = encoder_run_ms + decoder_run_ms;
            LOGI("[PERF] Encoder+Decoder GPU total runtime: %lld ms", total_gpu_time_ms);
        }

        return token_count;

    } catch (const std::exception& e) {
        LOGE("Exception during inference: %s", e.what());
        return 0;
    }
}

void OcrInference::Close() {
    if (initialized_) {
        const auto close_start = std::chrono::steady_clock::now();

        if (litert_) {
            litert_->encoder_input_buffers.clear();
            litert_->encoder_output_buffers.clear();
            litert_->decoder_input_buffers.clear();
            litert_->decoder_output_buffers.clear();

            litert_->compiled_encoder.reset();
            litert_->compiled_decoder.reset();
            litert_->env.reset();

            if (litert_->using_gpu || litert_->encoder_using_gpu || litert_->decoder_using_gpu) {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        }

        litert_.reset();

        embeddings_.clear();
        embeddings_input_.clear();
        attention_mask_.clear();
        input_ids_.clear();

        initialized_ = false;
        LogDurationMs("OcrInference Close total", close_start);
    }
}

} // namespace mihon
