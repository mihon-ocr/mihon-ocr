#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <memory>
#include "text_postprocessor.h"
#include "vocab_data.h"

#define LOG_TAG "MihonOCR_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Constants matching Kotlin implementation
static constexpr int IMAGE_SIZE = 224;
static constexpr float NORMALIZATION_FACTOR = 1.0f / (255.0f * 0.5f);
static constexpr float NORMALIZED_MEAN = 0.5f / 0.5f;
static constexpr int SPECIAL_TOKEN_THRESHOLD = 5;

// Global instances
static std::unique_ptr<mihon::TextPostprocessor> g_textPostprocessor;
static std::vector<std::string> g_vocab;

extern "C" {

JNIEXPORT void JNICALL
Java_mihon_data_ocr_OcrRepositoryImpl_nativeInit(JNIEnv* env, jobject /* this */) {
    LOGI("Initializing native OCR helpers");
    
    try {
        g_textPostprocessor = std::make_unique<mihon::TextPostprocessor>();
        g_vocab = mihon::getVocabulary();
        LOGI("Native OCR helpers initialized successfully (vocab size: %zu)", g_vocab.size());
    } catch (const std::exception& e) {
        LOGE("Exception during initialization: %s", e.what());
        jclass exClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exClass, e.what());
    }
}

JNIEXPORT void JNICALL
Java_mihon_data_ocr_OcrRepositoryImpl_nativePreprocessImage(
    JNIEnv* env,
    jobject /* this */,
    jobject bitmap,
    jfloatArray outputArray) {
    
    AndroidBitmapInfo info;
    void* pixels;
    
    // Get bitmap info
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("Failed to get bitmap info");
        return;
    }
    
    // Lock pixels
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        return;
    }
    
    try {
        int width = info.width;
        int height = info.height;
        
        // Get output array
        jfloat* output = env->GetFloatArrayElements(outputArray, nullptr);
        if (!output) {
            AndroidBitmap_unlockPixels(env, bitmap);
            return;
        }
        
        // Preprocess: resize and normalize
        uint32_t* srcPixels = static_cast<uint32_t*>(pixels);
        
        if (width == IMAGE_SIZE && height == IMAGE_SIZE) {
            // Direct normalization
            int outIndex = 0;
            for (int i = 0; i < IMAGE_SIZE * IMAGE_SIZE; i++) {
                uint32_t pixel = srcPixels[i];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                
                output[outIndex++] = r * NORMALIZATION_FACTOR - NORMALIZED_MEAN;
                output[outIndex++] = g * NORMALIZATION_FACTOR - NORMALIZED_MEAN;
                output[outIndex++] = b * NORMALIZATION_FACTOR - NORMALIZED_MEAN;
            }
        } else {
            // Resize with nearest neighbor then normalize
            float scaleX = static_cast<float>(width) / IMAGE_SIZE;
            float scaleY = static_cast<float>(height) / IMAGE_SIZE;
            
            int outIndex = 0;
            for (int y = 0; y < IMAGE_SIZE; y++) {
                for (int x = 0; x < IMAGE_SIZE; x++) {
                    int srcX = static_cast<int>(x * scaleX);
                    int srcY = static_cast<int>(y * scaleY);
                    uint32_t pixel = srcPixels[srcY * width + srcX];
                    
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    
                    output[outIndex++] = r * NORMALIZATION_FACTOR - NORMALIZED_MEAN;
                    output[outIndex++] = g * NORMALIZATION_FACTOR - NORMALIZED_MEAN;
                    output[outIndex++] = b * NORMALIZATION_FACTOR - NORMALIZED_MEAN;
                }
            }
        }
        
        env->ReleaseFloatArrayElements(outputArray, output, 0);
    } catch (const std::exception& e) {
        LOGE("Exception during preprocessing: %s", e.what());
    }
    
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT jstring JNICALL
Java_mihon_data_ocr_OcrRepositoryImpl_nativeDecodeTokens(
    JNIEnv* env,
    jobject /* this */,
    jintArray tokenIdsArray,
    jint tokenCount) {
    
    if (!g_vocab.empty()) {
        try {
            jint* tokenIds = env->GetIntArrayElements(tokenIdsArray, nullptr);
            if (!tokenIds) {
                return env->NewStringUTF("");
            }
            
            std::string result;
            result.reserve(tokenCount * 3); // Rough estimate for Japanese chars
            
            for (int i = 0; i < tokenCount; i++) {
                int tokenId = tokenIds[i];
                
                if (tokenId < SPECIAL_TOKEN_THRESHOLD) {
                    continue;
                }
                
                if (tokenId < static_cast<int>(g_vocab.size())) {
                    result += g_vocab[tokenId];
                }
            }
            
            env->ReleaseIntArrayElements(tokenIdsArray, tokenIds, JNI_ABORT);
            
            return env->NewStringUTF(result.c_str());
        } catch (const std::exception& e) {
            LOGE("Exception during token decoding: %s", e.what());
        }
    }
    
    return env->NewStringUTF("");
}

JNIEXPORT jstring JNICALL
Java_mihon_data_ocr_OcrRepositoryImpl_nativePostprocessText(
    JNIEnv* env,
    jobject /* this */,
    jstring inputText) {
    
    if (!g_textPostprocessor) {
        return inputText;
    }
    
    try {
        const char* nativeString = env->GetStringUTFChars(inputText, nullptr);
        if (!nativeString) {
            return inputText;
        }
        
        std::string input(nativeString);
        env->ReleaseStringUTFChars(inputText, nativeString);
        
        std::string result = g_textPostprocessor->postprocess(input);
        
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& e) {
        LOGE("Exception during postprocessing: %s", e.what());
        return inputText;
    }
}

JNIEXPORT void JNICALL
Java_mihon_data_ocr_OcrRepositoryImpl_nativeClose(JNIEnv* env, jobject /* this */) {
    LOGI("Closing native OCR helpers");
    g_textPostprocessor.reset();
    g_vocab.clear();
}

} // extern "C"
