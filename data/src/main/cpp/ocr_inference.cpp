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

// C API for environment
#include "litert/c/litert_common.h"
#include "litert/c/litert_environment.h"
#include "litert/cc/litert_any.h"

#define LOG_TAG "MihonOCR_Inference"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace mihon {

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

bool OcrInference::WriteModelToTempFile(
    const uint8_t* data, 
    size_t size, 
    const char* cache_dir,
    const char* filename, 
    std::string& out_path
) {
    out_path = std::string(cache_dir) + "/" + filename;
    
    std::ofstream file(out_path, std::ios::binary | std::ios::trunc);
    if (!file.is_open()) {
        LOGE("Failed to open temp file for writing: %s", out_path.c_str());
        return false;
    }
    
    file.write(reinterpret_cast<const char*>(data), size);
    file.close();
    
    if (!file.good()) {
        LOGE("Failed to write model data to temp file: %s", out_path.c_str());
        return false;
    }
    
    LOGI("Wrote model to temp file: %s (%zu bytes)", out_path.c_str(), size);
    return true;
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

    LOGI("Initializing OcrInference with LiteRT Next C++ API...");

    try {
        // Write models to temporary files
        if (!WriteModelToTempFile(encoder_data, encoder_size, cache_dir, "encoder.tflite", encoder_model_path_)) {
            return false;
        }
        if (!WriteModelToTempFile(decoder_data, decoder_size, cache_dir, "decoder.tflite", decoder_model_path_)) {
            return false;
        }

        // Load embeddings into memory
        const size_t embedding_count = embeddings_size / sizeof(float);
        embeddings_.resize(embedding_count);
        std::memcpy(embeddings_.data(), embeddings_data, embeddings_size);
        LOGI("Loaded %zu embeddings (expected: %d)", embedding_count, VOCAB_SIZE * HIDDEN_SIZE);

        // Create LiteRT objects container
        litert_ = std::make_unique<LiteRtObjects>();

        // Create environment with dispatch library directory option
        // This tells LiteRT where to find accelerator libraries like GPU
        LOGI("Creating LiteRT environment with dispatch library dir: %s", native_lib_dir);
        
        std::vector<litert::Environment::Option> env_options;
        // Use the native library directory from the app's applicationInfo
        // This is where System.loadLibrary() expects to find native libs
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
        LOGI("LiteRT environment created successfully");

        // Create GPU environment explicitly to initialize OpenCL
        // This is required for GPU acceleration to work properly
        LOGI("Creating GPU environment with OpenCL...");
        
        // First, try to load the system OpenCL library explicitly
        // This helps the GPU delegate find OpenCL later
        void* opencl_lib = dlopen("libOpenCL.so", RTLD_NOW | RTLD_GLOBAL);
        if (opencl_lib != nullptr) {
            LOGI("Loaded system OpenCL library successfully");
        } else {
            // Try vendor path
            opencl_lib = dlopen("/vendor/lib64/libOpenCL.so", RTLD_NOW | RTLD_GLOBAL);
            if (opencl_lib != nullptr) {
                LOGI("Loaded vendor OpenCL library successfully");
            } else {
                LOGW("Failed to load OpenCL library: %s", dlerror());
            }
        }
        
        // LiteRtStatus gpu_env_status = LiteRtGpuEnvironmentCreate(
        //     litert_->env->Get(), 
        //     0,      // num_options - no extra options needed
        //     nullptr // options - let LiteRT create default OpenCL resources
        // );
        // if (gpu_env_status == kLiteRtStatusOk) {
        //     LOGI("GPU environment created successfully - OpenCL initialized");
        // } else {
        //     LOGW("Failed to create GPU environment: status=%d (GPU acceleration may not work)", 
        //          static_cast<int>(gpu_env_status));
        // }

        // LiteRT will automatically try to register GPU accelerator from the dispatch library dir.
        // We rely on auto-registration.
        LOGI("Relying on LiteRT auto-registration for GPU accelerator");

        // GPU acceleration is mandatory
        LOGI("=== STARTING GPU COMPILATION ATTEMPT ===");
        if (!TryCompileWithGpu()) {
            LOGE("GPU compilation failed; LiteRT Next GPU acceleration is required for OCR");
            return false;
        }
        litert_->using_gpu = (litert_->encoder_using_gpu && litert_->decoder_using_gpu);
        if (!litert_->using_gpu) {
            LOGE("GPU compilation completed but GPU flags are inconsistent; aborting initialization");
            return false;
        }
        LOGI("GPU compilation succeeded - encoder_using_gpu=%d, decoder_using_gpu=%d",
             litert_->encoder_using_gpu, litert_->decoder_using_gpu);

        LOGI("=== CREATING GPU BUFFERS ===");
        if (!CreateBuffers()) {
            LOGE("Failed to create GPU buffers");
            return false;
        }
        LOGI("GPU buffers created successfully");

        LOGI("=== STARTING GPU WARMUP ===");
        if (!PerformWarmup()) {
            LOGE("GPU warmup failed; unable to verify GPU execution");
            return false;
        }
        LOGI("GPU warmup completed successfully");

        // Allocate working memory
        embeddings_input_.resize(MAX_SEQUENCE_LENGTH * HIDDEN_SIZE, 0.0f);
        attention_mask_.resize(MAX_SEQUENCE_LENGTH, 0.0f);
        input_ids_.resize(MAX_SEQUENCE_LENGTH, PAD_TOKEN_ID);
        
        // Pre-allocate output buffers (these sizes are based on model outputs)
        // Encoder output: [1, seq_len, hidden_size] - typically 196 patches * 768 hidden
        litert_->encoder_hidden_states.resize(196 * HIDDEN_SIZE);
        // Decoder output: [1, max_seq_len, vocab_size]
        litert_->decoder_logits.resize(MAX_SEQUENCE_LENGTH * VOCAB_SIZE);

        initialized_ = true;
           LOGI("OcrInference initialized successfully (overall: %s)", 
               litert_->using_gpu ? "GPU" : "CPU");
           // Log per-model accelerator usage for clarity and automated tests
           LOGI("ACCELERATOR_ENCODER=%s", litert_->encoder_using_gpu ? "GPU" : "CPU");
           LOGI("ACCELERATOR_DECODER=%s", litert_->decoder_using_gpu ? "GPU" : "CPU");
           // Backwards-compatible single ACCELERATOR tag: encoder and decoder pair
           LOGI("ACCELERATOR=%s/%s", litert_->encoder_using_gpu ? "GPU" : "CPU", litert_->decoder_using_gpu ? "GPU" : "CPU");
        
        return true;

    } catch (const std::exception& e) {
        LOGE("Exception during initialization: %s", e.what());
        Close();
        return false;
    }
}

bool OcrInference::CreateBuffers() {
    // Create input/output buffers for encoder
    LOGI("Creating buffers for compiled models...");
    
    LOGI("[BUFFERS] Creating encoder input buffers...");
    auto encoder_input_result = litert_->compiled_encoder->CreateInputBuffers();
    if (!encoder_input_result.HasValue()) {
        LOGE("[BUFFERS] Failed to create encoder input buffers: %s", 
             encoder_input_result.Error().Message().c_str());
        return false;
    }
    litert_->encoder_input_buffers = std::move(encoder_input_result.Value());
    LOGI("[BUFFERS] Encoder input buffers created (%zu buffers)", 
         litert_->encoder_input_buffers.size());

    LOGI("[BUFFERS] Creating encoder output buffers...");
    auto encoder_output_result = litert_->compiled_encoder->CreateOutputBuffers();
    if (!encoder_output_result.HasValue()) {
        LOGE("[BUFFERS] Failed to create encoder output buffers: %s",
             encoder_output_result.Error().Message().c_str());
        return false;
    }
    litert_->encoder_output_buffers = std::move(encoder_output_result.Value());
    LOGI("[BUFFERS] Encoder output buffers created (%zu buffers)", 
         litert_->encoder_output_buffers.size());

    // Create input/output buffers for decoder
    LOGI("[BUFFERS] Creating decoder input buffers...");
    auto decoder_input_result = litert_->compiled_decoder->CreateInputBuffers();
    if (!decoder_input_result.HasValue()) {
        LOGE("[BUFFERS] Failed to create decoder input buffers: %s",
             decoder_input_result.Error().Message().c_str());
        return false;
    }
    litert_->decoder_input_buffers = std::move(decoder_input_result.Value());
    LOGI("[BUFFERS] Decoder input buffers created (%zu buffers)", 
         litert_->decoder_input_buffers.size());

    LOGI("[BUFFERS] Creating decoder output buffers...");
    auto decoder_output_result = litert_->compiled_decoder->CreateOutputBuffers();
    if (!decoder_output_result.HasValue()) {
        LOGE("[BUFFERS] Failed to create decoder output buffers: %s",
             decoder_output_result.Error().Message().c_str());
        return false;
    }
    litert_->decoder_output_buffers = std::move(decoder_output_result.Value());
    LOGI("[BUFFERS] Decoder output buffers created (%zu buffers)", 
         litert_->decoder_output_buffers.size());

    // Log buffer info summary
    LOGI("[BUFFERS] Summary: Encoder has %zu input + %zu output buffers", 
            litert_->encoder_input_buffers.size(), 
            litert_->encoder_output_buffers.size());
    LOGI("[BUFFERS] Summary: Decoder has %zu input + %zu output buffers", 
            litert_->decoder_input_buffers.size(), 
            litert_->decoder_output_buffers.size());
    LOGI("[BUFFERS] All buffers created successfully!");
            
    return true;
}

bool OcrInference::PerformWarmup() {
    LOGI("Performing warmup inference...");
    
    // Create dummy input for encoder
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

    // Read encoder output to feed decoder warmup
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
    
    LOGI("Warmup successful!");
    return true;
}


bool OcrInference::TryCompileWithGpu() {
    LOGI("Attempting gpu compilation for encoder");
    
    // Try GPU-only first to get clear error messages
    LOGI("[GPU-ENCODER] Step 1: Creating compilation options...");
    auto options_result = litert::Options::Create();
    if (!options_result.HasValue()) {
        LOGW("[GPU-ENCODER] Failed to create options for GPU compilation");
        return false;
    }
    auto options = std::move(options_result.Value());
    LOGI("[GPU-ENCODER] Options created successfully");
    
    // Set GPU-only to ensure we're really trying GPU
    LOGI("[GPU-ENCODER] Step 2: Setting hardware accelerators to GPU-only...");
    auto hw_result = options.SetHardwareAccelerators(litert::HwAccelerators::kGpu);
    if (!hw_result.HasValue()) {
        LOGW("[GPU-ENCODER] Failed to set hardware accelerators: %s", hw_result.Error().Message().c_str());
        return false;
    }
    LOGI("[GPU-ENCODER] Hardware accelerators set to GPU-only");
    
    // Configure GPU options explicitly
    LOGI("[GPU-ENCODER] Step 3: Configuring GPU options...");
    auto gpu_opts_result = options.GetGpuOptions();
    if (gpu_opts_result.HasValue()) {
        auto& gpu_opts = gpu_opts_result.Value();
        LOGI("[GPU-ENCODER] Configuring GPU precision...");
        
        // Let LiteRT choose the best backend (usually OpenCL on Android)
        // auto backend_result = gpu_opts.SetBackend(litert::GpuOptions::Backend::kOpenCl);
        // if (!backend_result.HasValue()) {
        //     LOGW("Failed to set GPU backend to OpenCL: %s", backend_result.Error().Message().c_str());
        // } else {
        //     LOGI("GPU backend set to OpenCL");
        // }

        // Use FP16 precision for better performance on mobile GPUs
        auto precision_result = gpu_opts.SetPrecision(litert::GpuOptions::Precision::kFp16);
        if (!precision_result.HasValue()) {
            LOGW("[GPU-ENCODER] Failed to set GPU precision to FP16: %s", precision_result.Error().Message().c_str());
        } else {
            LOGI("[GPU-ENCODER] GPU precision set to FP16");
        }
        
        // Enable serialization for faster subsequent loads
        // Use cache dir for storing compiled GPU programs
        // Disable serialization for now to rule out cache issues
        // std::string cache_path = encoder_model_path_.substr(0, encoder_model_path_.rfind('/'));
        // auto ser_result = gpu_opts.SetSerializationDir(cache_path.c_str());
        // if (ser_result == kLiteRtStatusOk) {
        //     LOGI("GPU serialization dir set to: %s", cache_path.c_str());
        //     gpu_opts.SetModelCacheKey("encoder_gpu_v1");
        //     gpu_opts.SetSerializeProgramCache(true);
        // }



    } else {
        LOGW("[GPU-ENCODER] Failed to get GPU options: %s", gpu_opts_result.Error().Message().c_str());
    }
    
    LOGI("[GPU-ENCODER] Step 4: Calling CompiledModel::Create (this may take a few seconds)...");
    auto compiled_encoder_result = litert::CompiledModel::Create(
        *litert_->env,
        encoder_model_path_,
        options
    );
    if (!compiled_encoder_result.HasValue()) {
        const auto& error = compiled_encoder_result.Error();
        LOGW("[GPU-ENCODER] Failed to compile encoder with GPU: status=%d, message=%s", 
             static_cast<int>(error.Status()), error.Message().c_str());
        return false;
    }
    LOGI("[GPU-ENCODER] Encoder compiled successfully with GPU!");


    
    // Check if the encoder is fully accelerated on GPU
    LOGI("[GPU-ENCODER] Step 5: Checking if encoder is fully GPU-accelerated...");
    auto encoder_accel_result = compiled_encoder_result.Value().IsFullyAccelerated();
    if (encoder_accel_result.HasValue()) {
        const bool encoder_fully = encoder_accel_result.Value();
        LOGI("[GPU-ENCODER] Fully accelerated: %s", encoder_fully ? "YES" : "NO (partial)");
        // Only accept GPU compilation if encoder is fully accelerated
        if (!encoder_fully) {
            LOGW("[GPU-ENCODER] Encoder is not fully GPU-accelerated; rejecting GPU-only compilation");
            return false;
        }
        LOGI("[GPU-ENCODER] Encoder is FULLY GPU-accelerated!");
    } else {
        LOGW("[GPU-ENCODER] Failed to query encoder acceleration status; rejecting GPU-only compilation");
        return false;
    }
    
    LOGI("Attempting GPU compilation for decoder...");
    
    LOGI("[GPU-DECODER] Step 1: Creating compilation options...");
    auto decoder_options_result = litert::Options::Create();
    if (!decoder_options_result.HasValue()) {
        LOGW("[GPU-DECODER] Failed to create options for decoder GPU compilation");
        return false;
    }
    auto decoder_options = std::move(decoder_options_result.Value());
    LOGI("[GPU-DECODER] Options created successfully");
    
    LOGI("[GPU-DECODER] Step 2: Setting hardware accelerators to GPU-only...");
    decoder_options.SetHardwareAccelerators(litert::HwAccelerators::kGpu);
    LOGI("[GPU-DECODER] Hardware accelerators set to GPU-only");
    
    // Configure decoder GPU options
    LOGI("[GPU-DECODER] Step 3: Configuring GPU options...");
    auto decoder_gpu_opts_result = decoder_options.GetGpuOptions();
    if (decoder_gpu_opts_result.HasValue()) {
        auto& decoder_gpu_opts = decoder_gpu_opts_result.Value();
        // decoder_gpu_opts.SetBackend(litert::GpuOptions::Backend::kOpenCl);
        auto precision_result = decoder_gpu_opts.SetPrecision(litert::GpuOptions::Precision::kFp16);
        if (!precision_result.HasValue()) {
            LOGW("[GPU-DECODER] Failed to set GPU precision to FP16");
        } else {
            LOGI("[GPU-DECODER] GPU precision set to FP16");
        }
        
        // std::string cache_path = decoder_model_path_.substr(0, decoder_model_path_.rfind('/'));
        // auto ser_result = decoder_gpu_opts.SetSerializationDir(cache_path.c_str());
        // if (ser_result == kLiteRtStatusOk) {
        //     decoder_gpu_opts.SetModelCacheKey("decoder_gpu_v1");
        //     decoder_gpu_opts.SetSerializeProgramCache(true);
        // }


    }
    
    LOGI("[GPU-DECODER] Step 4: Calling CompiledModel::Create (this may take a few seconds)...");
    auto compiled_decoder_result = litert::CompiledModel::Create(
        *litert_->env,
        decoder_model_path_,
        decoder_options
    );
    if (!compiled_decoder_result.HasValue()) {
        const auto& error = compiled_decoder_result.Error();
        LOGW("[GPU-DECODER] Failed to compile decoder with GPU: status=%d, message=%s",
             static_cast<int>(error.Status()), error.Message().c_str());
        return false;
    }
    LOGI("[GPU-DECODER] Decoder compiled successfully with GPU!");
    
    // Check if the decoder is fully accelerated
    LOGI("[GPU-DECODER] Step 5: Checking if decoder is fully GPU-accelerated...");
    auto decoder_accel_result = compiled_decoder_result.Value().IsFullyAccelerated();
    if (decoder_accel_result.HasValue()) {
        const bool decoder_fully = decoder_accel_result.Value();
        LOGI("[GPU-DECODER] Fully accelerated: %s", decoder_fully ? "YES" : "NO (partial)");
        // Only accept GPU compilation if decoder is fully accelerated
        if (!decoder_fully) {
            LOGW("[GPU-DECODER] Decoder is not fully GPU-accelerated; rejecting GPU-only compilation");
            return false;
        }
        LOGI("[GPU-DECODER] Decoder is FULLY GPU-accelerated!");
    } else {
        LOGW("[GPU-DECODER] Failed to query decoder acceleration status; rejecting GPU-only compilation");
        return false;
    }
    
    litert_->compiled_encoder.emplace(std::move(compiled_encoder_result.Value()));
    litert_->compiled_decoder.emplace(std::move(compiled_decoder_result.Value()));
    // Both compiled models are fully GPU-accelerated; set per-model flags
    litert_->encoder_using_gpu = true;
    litert_->decoder_using_gpu = true;
    litert_->using_gpu = true;
    
    LOGI("Both encoder and decoder are fully GPU-accelerated!");
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

void OcrInference::UpdateEmbedding(int token_id, int index) {
    const int embed_offset = token_id * HIDDEN_SIZE;
    const int output_offset = index * HIDDEN_SIZE;
    std::memcpy(
        embeddings_input_.data() + output_offset,
        embeddings_.data() + embed_offset,
        HIDDEN_SIZE * sizeof(float)
    );
}

int OcrInference::FindMaxLogitToken(const float* logits, int seq_len) {
    const int last_token_pos = seq_len - 1;
    const int logits_offset = last_token_pos * VOCAB_SIZE;

    float max_logit = -std::numeric_limits<float>::infinity();
    int max_token = PAD_TOKEN_ID;

    for (int vocab_idx = 0; vocab_idx < VOCAB_SIZE; vocab_idx++) {
        const float logit = logits[logits_offset + vocab_idx];
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
        LOGI("[ACCELERATOR] Encoder=%s, Decoder=%s",
             litert_->encoder_using_gpu ? "GPU" : "CPU",
             litert_->decoder_using_gpu ? "GPU" : "CPU");
        // 1. Run encoder
        LOGI("Running encoder...");
        const size_t image_data_size = IMAGE_SIZE * IMAGE_SIZE * 3;
        
        // Log buffer details
        auto input_buffer_size_result = litert_->encoder_input_buffers[0].Size();
        size_t input_buffer_size = 0;
        if (input_buffer_size_result.HasValue()) {
            input_buffer_size = input_buffer_size_result.Value();
            LOGI("Encoder input buffer size: %zu bytes, Expected write size: %zu bytes", 
                 input_buffer_size, image_data_size * sizeof(float));
        } else {
            LOGE("Failed to get encoder input buffer size");
        }


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
        auto encoder_run_end = std::chrono::steady_clock::now();
        const auto encoder_run_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            encoder_run_end - encoder_run_start
        ).count();
        LOGI("[PERF] Encoder GPU invocation took %lld ms", static_cast<long long>(encoder_run_ms));
        if (litert_->using_gpu && encoder_run_ms >= GPU_LATENCY_BUDGET_MS) {
            LOGW("[PERF] Encoder GPU invocation exceeded %lld ms budget", static_cast<long long>(GPU_LATENCY_BUDGET_MS));
        }


        // Read encoder hidden states into our pre-allocated buffer
        auto read_result = litert_->encoder_output_buffers[0].Read<float>(
            absl::MakeSpan(litert_->encoder_hidden_states)
        );
        if (!read_result.HasValue()) {
            LOGE("Failed to read encoder output");
            return 0;
        }
        LOGI("Encoder output read successfully (%zu floats)", 
             litert_->encoder_hidden_states.size());

        // 2. Initialize decoder state
        std::fill(embeddings_input_.begin(), embeddings_input_.end(), 0.0f);
        std::fill(attention_mask_.begin(), attention_mask_.end(), 0.0f);
        std::fill(input_ids_.begin(), input_ids_.end(), PAD_TOKEN_ID);

        out_tokens[0] = START_TOKEN_ID;
        input_ids_[0] = START_TOKEN_ID;
        UpdateEmbedding(START_TOKEN_ID, 0);
        attention_mask_[0] = 1.0f;
        int token_count = 1;
        long long decoder_gpu_time_ms = 0;
        int decoder_iterations = 0;

        // 3. Autoregressive decoder loop
        LOGI("Starting decoder loop...");
        for (int step = 0; step < MAX_SEQUENCE_LENGTH - 1; step++) {
            // Write inputs to decoder
            // Input 0: encoder hidden states
            auto write_hidden_result = litert_->decoder_input_buffers[0].Write<float>(
                absl::MakeConstSpan(litert_->encoder_hidden_states)
            );
            if (!write_hidden_result.HasValue()) {
                LOGE("Failed to write decoder hidden states input");
                break;
            }

            // Input 1: attention mask
            auto write_mask_result = litert_->decoder_input_buffers[1].Write<float>(
                absl::MakeConstSpan(attention_mask_.data(), MAX_SEQUENCE_LENGTH)
            );
            if (!write_mask_result.HasValue()) {
                LOGE("Failed to write decoder attention mask input");
                break;
            }

            // Input 2: embeddings
            auto write_emb_result = litert_->decoder_input_buffers[2].Write<float>(
                absl::MakeConstSpan(embeddings_input_.data(), MAX_SEQUENCE_LENGTH * HIDDEN_SIZE)
            );
            if (!write_emb_result.HasValue()) {
                LOGE("Failed to write decoder embeddings input");
                break;
            }

            // Run decoder
            auto decoder_run_start = std::chrono::steady_clock::now();
            auto decoder_run_result = litert_->compiled_decoder->Run(
                litert_->decoder_input_buffers,
                litert_->decoder_output_buffers
            );
            if (!decoder_run_result.HasValue()) {
                LOGE("Failed to run decoder at step %d: %s", step, decoder_run_result.Error().Message().c_str());
                break;
            }
            auto decoder_run_end = std::chrono::steady_clock::now();
            decoder_gpu_time_ms += std::chrono::duration_cast<std::chrono::milliseconds>(
                decoder_run_end - decoder_run_start
            ).count();
            decoder_iterations++;


            // Read logits into pre-allocated buffer
            auto logits_result = litert_->decoder_output_buffers[0].Read<float>(
                absl::MakeSpan(litert_->decoder_logits)
            );
            if (!logits_result.HasValue()) {
                LOGE("Failed to read decoder output at step %d", step);
                break;
            }

            const float* logits = litert_->decoder_logits.data();

            // Find next token
            const int next_token = FindMaxLogitToken(logits, token_count);

            // Check for end conditions
            if (next_token < 0 || next_token == END_TOKEN_ID) {
                LOGI("Decoder finished at step %d (token: %d)", step, next_token);
                break;
            }

            // Update state
            const int next_index = token_count;
            out_tokens[next_index] = next_token;
            input_ids_[next_index] = next_token;
            UpdateEmbedding(next_token, next_index);
            attention_mask_[next_index] = 1.0f;

            token_count++;
            if (token_count >= max_tokens || token_count >= MAX_SEQUENCE_LENGTH) {
                LOGI("Reached maximum token count: %d", token_count);
                break;
            }
        }

        LOGI("[PERF] Decoder GPU cumulative runtime: %lld ms across %d steps",
             static_cast<long long>(decoder_gpu_time_ms), decoder_iterations);
        if (litert_->using_gpu && decoder_gpu_time_ms >= GPU_LATENCY_BUDGET_MS) {
            LOGW("[PERF] Decoder GPU cumulative runtime exceeded %lld ms budget",
                 static_cast<long long>(GPU_LATENCY_BUDGET_MS));
        }
        if (litert_->using_gpu) {
            const long long total_gpu_time = encoder_run_ms + decoder_gpu_time_ms;
            LOGI("[PERF] Encoder+Decoder GPU total runtime: %lld ms", total_gpu_time);
        }

        LOGI("Decoder finished with %d tokens", token_count);
        return token_count;

    } catch (const std::exception& e) {
        LOGE("Exception during inference: %s", e.what());
        return 0;
    }
}

void OcrInference::Close() {
    if (initialized_) {
        LOGI("Closing OcrInference...");
        
        // Explicitly clear buffers first to release GPU resources
        if (litert_) {
            LOGI("Releasing GPU/CPU buffers...");
            litert_->encoder_input_buffers.clear();
            litert_->encoder_output_buffers.clear();
            litert_->decoder_input_buffers.clear();
            litert_->decoder_output_buffers.clear();
            
            LOGI("Releasing compiled models...");
            litert_->compiled_encoder.reset();
            litert_->compiled_decoder.reset();
            
            LOGI("Releasing environment...");
            litert_->env.reset();
            
            // Give GPU driver time to release resources
            // This helps prevent buffer creation failures on subsequent initializations
            if (litert_->using_gpu || litert_->encoder_using_gpu || litert_->decoder_using_gpu) {
                LOGI("Waiting for GPU resources to be released...");
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        }
        
        // LiteRT objects will be automatically cleaned up
        litert_.reset();
        
        // Clear vectors
        embeddings_.clear();
        embeddings_input_.clear();
        attention_mask_.clear();
        input_ids_.clear();
        
        // Delete temporary model files
        if (!encoder_model_path_.empty()) {
            std::remove(encoder_model_path_.c_str());
        }
        if (!decoder_model_path_.empty()) {
            std::remove(decoder_model_path_.c_str());
        }
        
        initialized_ = false;
        LOGI("OcrInference closed - all resources released");
    }
}

} // namespace mihon
