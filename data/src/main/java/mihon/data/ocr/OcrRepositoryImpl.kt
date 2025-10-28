package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import mihon.domain.ocr.repository.OcrRepository

class OcrRepositoryImpl(
    context: Context,
) : OcrRepository {
    private val encoderModelPath: String = "ocr/encoder.tflite"
    private val decoderModelPath: String = "ocr/decoder.tflite"
    private val encoderModel: CompiledModel
    private val decoderModel: CompiledModel
    private val textPostprocessor: TextPostprocessor
    private val encoderInputBuffers: List<TensorBuffer>
    private val encoderOutputBuffers: List<TensorBuffer>
    private val decoderInputBuffers: List<TensorBuffer>
    private val decoderOutputBuffers: List<TensorBuffer>
    private val inputIdsArray: LongArray = LongArray(MAX_SEQUENCE_LENGTH)

    companion object {
        private const val IMAGE_SIZE = 224
        private const val NORMALIZATION_MEAN = 0.5f
        private const val NORMALIZATION_STD = 0.5f
        private const val NORMALIZATION_FACTOR = 1.0f / (255.0f * NORMALIZATION_STD)
        private const val NORMALIZED_MEAN = NORMALIZATION_MEAN / NORMALIZATION_STD
        private const val START_TOKEN_ID = 2
        private const val END_TOKEN_ID = 3
        private const val PAD_TOKEN_ID = 0
        private const val SPECIAL_TOKEN_THRESHOLD = 5
        private const val MAX_SEQUENCE_LENGTH = 300
        private const val VOCAB_SIZE = 6144 // This is what the model specifies, but the vocab list is 6142 long
    }

    init {
        val encoderOptions = CompiledModel.Options(Accelerator.CPU)
        val decoderOptions = CompiledModel.Options(Accelerator.CPU)

        encoderModel = CompiledModel.create(
            context.assets,
            encoderModelPath,
            encoderOptions,
        )

        decoderModel = CompiledModel.create(
            context.assets,
            decoderModelPath,
            decoderOptions,
        )

        encoderInputBuffers = encoderModel.createInputBuffers()
        encoderOutputBuffers = encoderModel.createOutputBuffers()
        decoderInputBuffers = decoderModel.createInputBuffers()
        decoderOutputBuffers = decoderModel.createOutputBuffers()

        textPostprocessor = TextPostprocessor()

        logcat(LogPriority.INFO) { "OCR models initialized" }
    }

    override suspend fun recognizeText(image: Bitmap): String = withContext(Dispatchers.Default) {
        require(!image.isRecycled) { "Input bitmap is recycled" }

        val encoderInputBuffer = encoderInputBuffers[0]
        preprocessImage(image, encoderInputBuffer)

        try {
            // Run encoder and get hidden states
            val encoderHiddenStates = runEncoder(encoderInputBuffer)

            // Run decoder with encoder states
            val tokenIds = runDecoder(encoderHiddenStates)

            val rawText = decodeTokens(tokenIds)

            textPostprocessor.postprocess(rawText)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "OCR recognition failed: ${e.message}" }
            throw e
        }
    }

    /**
     * Preprocesses the input bitmap for OCR recognition.
     * Optimized to minimize memory allocations and copies.
     */
    private fun preprocessImage(bitmap: Bitmap, inputBuffer: TensorBuffer) {
        val needsResize = bitmap.width != IMAGE_SIZE || bitmap.height != IMAGE_SIZE
        val needsConversion = bitmap.config != Bitmap.Config.ARGB_8888

        // Convert bitmap to the required format for the model
        val workingBitmap = when {
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

        try {
            // Direct pixel processing with pre-allocated arrays
            val pixelCount = IMAGE_SIZE * IMAGE_SIZE
            val pixels = IntArray(pixelCount)
            workingBitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

            // Normalize directly to output buffer
            val normalizedPixels = FloatArray(pixelCount * 3)
            normalizePixels(pixels, normalizedPixels)

            inputBuffer.writeFloat(normalizedPixels)
        } finally {
            // Clean up only if we created a new bitmap
            if (workingBitmap !== bitmap) {
                workingBitmap.recycle()
            }
        }
    }

    /**
     * Inline function for pixel normalization.
     * Converts RGB pixels to normalized float values.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun normalizePixels(pixels: IntArray, output: FloatArray) {
        var outIndex = 0
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)

            output[outIndex++] = r * NORMALIZATION_FACTOR - NORMALIZED_MEAN
            output[outIndex++] = g * NORMALIZATION_FACTOR - NORMALIZED_MEAN
            output[outIndex++] = b * NORMALIZATION_FACTOR - NORMALIZED_MEAN
        }
    }

    /**
     * Run encoder and return a persistent FloatArray copy of hidden states.
     * Uses dedicated output buffers created at initialization.
     */
    private fun runEncoder(imageBuffer: TensorBuffer): FloatArray {
        val outputBuffers = encoderOutputBuffers
        encoderModel.run(listOf(imageBuffer), outputBuffers)

        // Read and create persistent copy
        val encoderStates = outputBuffers[0].readFloat()
        return encoderStates
    }

    private fun runDecoder(encoderHiddenStates: FloatArray): List<Int> {
        val tokenIds = mutableListOf(START_TOKEN_ID)

        try {
            // Reset input IDs array to PAD tokens
            inputIdsArray.fill(PAD_TOKEN_ID.toLong())
            inputIdsArray[0] = START_TOKEN_ID.toLong()

            for (step in 0 until MAX_SEQUENCE_LENGTH - 1) {
                // Update input IDs array with current tokens
                val currentSeqLen = tokenIds.size
                for (i in 0 until currentSeqLen) {
                    inputIdsArray[i] = tokenIds[i].toLong()
                }

                // Write to pre-allocated buffers (reused across iterations)
                decoderInputBuffers[0].writeLong(inputIdsArray)
                decoderInputBuffers[1].writeFloat(encoderHiddenStates)

                // Run inference with reused buffers
                decoderModel.run(decoderInputBuffers, decoderOutputBuffers)

                // Extract next token
                val logits = decoderOutputBuffers[0].readFloat()
                val nextToken = findMaxLogitToken(logits, currentSeqLen)

                // Validate token
                if (nextToken < 0 || nextToken >= VOCAB_SIZE) {
                    logcat(LogPriority.ERROR) { "Invalid token: $nextToken at step $step" }
                    break
                }

                tokenIds.add(nextToken)

                // Check stopping conditions
                if (nextToken == END_TOKEN_ID) {
                    logcat(LogPriority.DEBUG) { "Reached END token at step $step" }
                    break
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Decoder failed at token ${tokenIds.size}: ${e.message}" }
            throw e
        }

        return tokenIds
    }

    /**
     * Find the token with maximum logit value for the current sequence position.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun findMaxLogitToken(logits: FloatArray, seqLen: Int): Int {
        val lastTokenPos = seqLen - 1
        val logitsOffset = lastTokenPos * VOCAB_SIZE

        // Validate bounds
        if (logitsOffset + VOCAB_SIZE > logits.size) {
            throw IllegalStateException("Logits buffer too small: need ${logitsOffset + VOCAB_SIZE}, got ${logits.size}")
        }

        // Find max logit
        var maxLogit = Float.NEGATIVE_INFINITY
        var maxToken = PAD_TOKEN_ID

        for (vocabIdx in 0 until VOCAB_SIZE) {
            val logit = logits[logitsOffset + vocabIdx]
            if (logit > maxLogit) {
                maxLogit = logit
                maxToken = vocabIdx
            }
        }

        return maxToken
    }

    /**
     * Convert token IDs to text using the vocabulary.
     */
    private fun decodeTokens(tokenIds: List<Int>): String {
        val text = StringBuilder(tokenIds.size * 2) // Pre-allocate approximate size

        for (tokenId in tokenIds) {
            // Skip special tokens
            if (tokenId < SPECIAL_TOKEN_THRESHOLD) continue

            if (tokenId >= vocab.size) {
                logcat(LogPriority.ERROR) { "Token $tokenId outside vocabulary range ${vocab.size}" }
                continue
            }

            text.append(vocab[tokenId])
        }

        return text.toString()
    }

    override fun close() {
        try {
            // Close reusable buffers
            encoderInputBuffers.forEach { it.close() }
            encoderOutputBuffers.forEach { it.close() }
            decoderInputBuffers.forEach { it.close() }
            decoderOutputBuffers.forEach { it.close() }

            // Close models
            encoderModel.close()
            decoderModel.close()

            logcat(LogPriority.INFO) { "OCR models closed successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Error closing OCR models: ${e.message}" }
        }
    }
}
