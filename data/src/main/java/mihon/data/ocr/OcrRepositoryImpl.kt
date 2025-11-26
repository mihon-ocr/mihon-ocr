package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.ocr.repository.OcrRepository
import tachiyomi.core.common.util.system.logcat

/**
 * OCR repository implementation using native LiteRT inference.
 *
 * This class manages the lifecycle of the native OCR engine and provides
 * thread-safe text recognition from bitmap images.
 */
class OcrRepositoryImpl(
    private val context: Context,
) : OcrRepository {

    private val inferenceMutex = Mutex()

    @Volatile
    private var initialized = false

    companion object {
        private const val TAG = "MihonOCR_Native"
        private const val PERF_TAG = "MihonOCR_Perf"
        private const val IMAGE_SIZE = 224
        private const val NS_TO_MS = 1_000_000L

        init {
            // Load the GPU accelerator library first (if available)
            // This must be done before loading mihon_ocr so that when LiteRT
            // initializes, dlopen("libLiteRtOpenClAccelerator.so") can find it
            try {
                val startLibLoad = System.nanoTime()
                System.loadLibrary("LiteRtOpenClAccelerator")
                val ms = (System.nanoTime() - startLibLoad) / NS_TO_MS
                Log.i(TAG, "Loaded LiteRtOpenClAccelerator.so for GPU acceleration (took $ms ms)")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "GPU accelerator library not available: ${e.message}")
            }

            // Now load the main library
            try {
                val startLoadMain = System.nanoTime()
                System.loadLibrary("mihon_ocr")
                val ms = (System.nanoTime() - startLoadMain) / NS_TO_MS
                Log.i(TAG, "Loaded mihon_ocr main native library (took $ms ms)")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load mihon_ocr native library: ${e.message}")
                throw e
            }
        }
    }

    init {
        val cacheDir = context.cacheDir.absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        Log.i(TAG, "Native library directory: $nativeLibDir")

        val initStartNanos = System.nanoTime()
        initialized = nativeOcrInit(context.assets, cacheDir, nativeLibDir)
        val initDurationMs = (System.nanoTime() - initStartNanos) / NS_TO_MS

        if (!initialized) {
            Log.e(TAG, "Native OCR engine failed to initialize (took $initDurationMs ms)")
            throw RuntimeException("Failed to initialize native OCR engine")
        }

        logcat(LogPriority.INFO) { "Native OCR engine initialized successfully (took $initDurationMs ms)" }
    }

    override suspend fun recognizeText(image: Bitmap): String {
        val result = inferenceMutex.withLock {
            check(!image.isRecycled) { "Input bitmap is recycled" }
            check(initialized) { "OCR engine not initialized" }

            val prepStart = System.nanoTime()
            val workingBitmap = prepareImage(image)
            val prepMs = (System.nanoTime() - prepStart) / NS_TO_MS
            if (prepMs > 0) {
                // Log only if there was measurable preparation time to reduce noise
                logcat(LogPriority.INFO) { "OCR: prepareImage took $prepMs ms" }
                Log.i(PERF_TAG, "app.mihonocr.dev: OCR Prep: prepareImage took $prepMs ms")
            }

            try {
                val recognizedText = nativeRecognizeText(workingBitmap)

                if (recognizedText.isEmpty()) {
                    logcat(LogPriority.WARN) { "OCR returned empty text" }
                }

                recognizedText
            } finally {
                // Clean up working bitmap if we created a new one
                if (workingBitmap !== image && !workingBitmap.isRecycled) {
                    workingBitmap.recycle()
                }
            }
        }

        return result
    }

    /**
     * Prepare the input image for OCR by converting to the correct size and format.
     * Returns the original bitmap if no conversion is needed.
     */
    private fun prepareImage(bitmap: Bitmap): Bitmap {
        val needsResize = bitmap.width != IMAGE_SIZE || bitmap.height != IMAGE_SIZE
        val needsConversion = bitmap.config != Bitmap.Config.ARGB_8888

        return when {
            needsConversion && needsResize -> {
                val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("Failed to convert bitmap to ARGB_8888")
                val scaled = converted.scale(IMAGE_SIZE, IMAGE_SIZE, filter = true)
                if (scaled !== converted) {
                    converted.recycle()
                }
                scaled
            }
            needsConversion -> {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("Failed to convert bitmap to ARGB_8888")
            }
            needsResize -> bitmap.scale(IMAGE_SIZE, IMAGE_SIZE, filter = true)
            else -> bitmap
        }
    }

    override fun close() {
        runBlocking {
            inferenceMutex.withLock {
                if (initialized) {
                    val closeStart = System.nanoTime()
                    nativeOcrClose()
                    val closeMs = (System.nanoTime() - closeStart) / NS_TO_MS
                    initialized = false
                    logcat(LogPriority.INFO) { "Native OCR engine closed successfully (took $closeMs ms)" }
                    Log.i(PERF_TAG, "app.mihonocr.dev: OCR Close: nativeOcrClose took $closeMs ms")
                }
            }
        }
    }

    // Native methods for C++ inference
    private external fun nativeOcrInit(
        assetManager: android.content.res.AssetManager,
        cacheDir: String,
        nativeLibDir: String
    ): Boolean

    private external fun nativeRecognizeText(bitmap: Bitmap): String

    private external fun nativeOcrClose()
}
