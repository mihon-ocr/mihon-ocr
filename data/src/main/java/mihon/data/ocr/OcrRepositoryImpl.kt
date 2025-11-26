package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureNanoTime
import logcat.LogPriority
import android.util.Log
import tachiyomi.core.common.util.system.logcat
import mihon.domain.ocr.repository.OcrRepository

class OcrRepositoryImpl(
    private val context: Context,
) : OcrRepository {
    private val inferenceMutex = Mutex() // Guards inference calls
    private var initialized = false

    companion object {
        private const val IMAGE_SIZE = 224

        init {
            // Load the GPU accelerator library first (if available)
            // This must be done before loading mihon_ocr so that when LiteRT
            // initializes, dlopen("libLiteRtOpenClAccelerator.so") can find it
            try {
                System.loadLibrary("LiteRtOpenClAccelerator")
                Log.i("MihonOCR_Native", "Loaded LiteRtOpenClAccelerator.so for GPU acceleration")
            } catch (e: UnsatisfiedLinkError) {
                Log.w("MihonOCR_Native", "GPU accelerator library not available: ${e.message}")
            }
            
            // Now load the main library
            System.loadLibrary("mihon_ocr")
        }
    }

    init {
        // Initialize native OCR engine with models and embeddings
        val cacheDir = context.cacheDir.absolutePath
        
        // Get native library directory to help LiteRT find GPU accelerator
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        Log.i("MihonOCR_Native", "Native library directory: $nativeLibDir")
        
        initialized = nativeOcrInit(context.assets, cacheDir, nativeLibDir)
        
        if (!initialized) {
            throw RuntimeException("Failed to initialize native OCR engine")
        }
        
        logcat(LogPriority.INFO) { "Native OCR engine initialized successfully" }
    }

    override suspend fun recognizeText(image: Bitmap): String {
        val startTime = System.nanoTime()
        
        val result = inferenceMutex.withLock {
            require(!image.isRecycled) { "Input bitmap is recycled" }
            require(initialized) { "OCR engine not initialized" }

            // Prepare bitmap - ensure it's the right size and format
            val workingBitmap = prepareImage(image)
            
            try {
                // Call native inference - handles all encoding, decoding, and postprocessing
                val recognizedText: String
                val inferenceTime = measureNanoTime {
                    recognizedText = nativeRecognizeText(workingBitmap)
                }
                
                if (recognizedText.isEmpty()) {
                    logcat(LogPriority.WARN) { "OCR returned empty text" }
                }
                
                logcat(LogPriority.INFO) { "OCR Perf Test: native inference took ${inferenceTime / 1_000_000} ms" }
                // Duplicate to Android Log and include package name so the test harness picks it up
                Log.i("MihonOCR_Perf", "app.mihonocr.dev: OCR Perf Test: native inference took ${inferenceTime / 1_000_000} ms")
                
                recognizedText
            } finally {
                // Clean up working bitmap if we created a new one
                if (workingBitmap !== image) {
                    workingBitmap.recycle()
                }
            }
        }

        val totalTime = (System.nanoTime() - startTime) / 1_000_000
        logcat(LogPriority.INFO) { "OCR Perf Test: recognizeText total time: $totalTime ms" }
        Log.i("MihonOCR_Perf", "app.mihonocr.dev: OCR Perf Test: recognizeText total time: $totalTime ms")

        return result
    }

    /**
     * Prepare the input image for OCR by converting to the correct size and format.
     */
    private fun prepareImage(bitmap: Bitmap): Bitmap {
        val needsResize = bitmap.width != IMAGE_SIZE || bitmap.height != IMAGE_SIZE
        val needsConversion = bitmap.config != Bitmap.Config.ARGB_8888

        return when {
            needsConversion && needsResize -> {
                val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                val scaled = converted.scale(IMAGE_SIZE, IMAGE_SIZE, filter = true)
                if (scaled !== converted) converted.recycle()
                scaled
            }
            needsConversion -> bitmap.copy(Bitmap.Config.ARGB_8888, false)
            needsResize -> bitmap.scale(IMAGE_SIZE, IMAGE_SIZE, filter = true)
            else -> bitmap
        }
    }

    override fun close() {
        runBlocking {
            inferenceMutex.withLock {
                if (initialized) {
                    nativeOcrClose()
                    initialized = false
                    logcat(LogPriority.INFO) { "Native OCR engine closed successfully" }
                }
            }
        }
    }

    // Native methods for C++ inference
    private external fun nativeOcrInit(assetManager: android.content.res.AssetManager, cacheDir: String, nativeLibDir: String): Boolean
    private external fun nativeRecognizeText(bitmap: Bitmap): String
    private external fun nativeOcrClose()
}
