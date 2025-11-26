#ifndef MIHON_OCR_INFERENCE_H
#define MIHON_OCR_INFERENCE_H

#include <vector>
#include <string>
#include <memory>
#include <cstdint>

namespace mihon {

class OcrInference {
public:
    OcrInference();
    ~OcrInference();

    // Initialize with model data from memory buffers
    // Returns true on success, false on failure
    bool Initialize(
        const uint8_t* encoder_data, size_t encoder_size,
        const uint8_t* decoder_data, size_t decoder_size,
        const uint8_t* embeddings_data, size_t embeddings_size,
        const char* cache_dir,
        const char* native_lib_dir
    );

    // Main inference method
    // Takes preprocessed image data (224x224x3 float array)
    // Returns the number of tokens generated, fills outTokens array
    int InferTokens(const float* image_data, int* out_tokens, int max_tokens);

    // Cleanup resources
    void Close();

    bool IsInitialized() const { return initialized_; }

    // Returns true if the inference pipeline was initialized to use GPU
    bool IsUsingGpu() const;
    // Per-model GPU status checks
    bool IsEncoderUsingGpu() const;
    bool IsDecoderUsingGpu() const;

private:
    // Constants from Kotlin implementation
    static constexpr int IMAGE_SIZE = 224;
    static constexpr int MAX_SEQUENCE_LENGTH = 300;
    static constexpr int VOCAB_SIZE = 6144;
    static constexpr int HIDDEN_SIZE = 768;
    static constexpr int START_TOKEN_ID = 2;
    static constexpr int END_TOKEN_ID = 3;
    static constexpr int PAD_TOKEN_ID = 0;
    static constexpr int64_t GPU_LATENCY_BUDGET_MS = 500;

    // Opaque pointers to LiteRT objects (forward declared to avoid exposing LiteRT headers)
    struct LiteRtObjects;
    std::unique_ptr<LiteRtObjects> litert_;

    // Embeddings and working memory
    std::vector<float> embeddings_;
    std::vector<float> embeddings_input_;
    std::vector<float> attention_mask_;
    std::vector<int> input_ids_;

    // Temporary file paths for models
    std::string encoder_model_path_;
    std::string decoder_model_path_;

    bool initialized_ = false;

    // Helper methods
    void UpdateEmbedding(int token_id, int index);
    int FindMaxLogitToken(const float* logits, int seq_len);
    bool WriteModelToTempFile(const uint8_t* data, size_t size, const char* cache_dir, const char* filename, std::string& out_path);
    bool TryCompileWithGpu();
    bool PerformWarmup();
    bool CreateBuffers();
};



} // namespace mihon

#endif // MIHON_OCR_INFERENCE_H
