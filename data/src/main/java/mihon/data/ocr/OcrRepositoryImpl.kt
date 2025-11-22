package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureNanoTime
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import mihon.domain.ocr.repository.OcrRepository

class OcrRepositoryImpl(
    context: Context,
) : OcrRepository {
    private val encoderModelPath: String = "ocr/encoder.tflite"
    private val decoderModelPath: String = "ocr/decoder.tflite"
    private val embeddingsPath: String = "ocr/embeddings.bin"
    private val encoderModel: CompiledModel
    private val decoderModel: CompiledModel
    private val textPostprocessor: TextPostprocessor
    private var encoderImageInput: TensorBuffer
    private var encoderHiddenStatesOutput: TensorBuffer
    private var decoderHiddenStatesInput: TensorBuffer
    private var decoderEmbeddingsInput: TensorBuffer
    private var decoderAttentionMaskInput: TensorBuffer
    private var decoderLogitsOutput: TensorBuffer
    private val inputIdsArray: IntArray = IntArray(MAX_SEQUENCE_LENGTH)
    private val inferenceMutex = Mutex() // Guards shared inference buffers
    private val pixelsBuffer = IntArray(IMAGE_SIZE * IMAGE_SIZE)
    private val normalizedBuffer = FloatArray(IMAGE_SIZE * IMAGE_SIZE * 3)
    private val tokenBuffer = IntArray(MAX_SEQUENCE_LENGTH)
    private val textBuilder = StringBuilder(MAX_SEQUENCE_LENGTH * 2)
    private val embeddings: FloatArray
    private val embeddingsInputBuffer = FloatArray(MAX_SEQUENCE_LENGTH * HIDDEN_SIZE)
    private val attentionMaskBuffer = FloatArray(MAX_SEQUENCE_LENGTH)

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
        private const val VOCAB_SIZE = 6144
        private const val HIDDEN_SIZE = 768
        
        init {
            System.loadLibrary("mihon_ocr")
        }
    }

    init {
        val encoderOptions = CompiledModel.Options(Accelerator.GPU).apply {
            CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP16)
        }

        val decoderOptions = CompiledModel.Options(Accelerator.GPU).apply {
            CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP16)
        }

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

        // Load embeddings
        val embeddingsBytes = context.assets.open(embeddingsPath).use { it.readBytes() }
        val embeddingsBuffer = ByteBuffer.wrap(embeddingsBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        embeddings = FloatArray(embeddingsBuffer.remaining())
        embeddingsBuffer.get(embeddings)

        val encoderInputBuffers = encoderModel.createInputBuffers()
        val encoderOutputBuffers = encoderModel.createOutputBuffers()
        val decoderInputBuffers = decoderModel.createInputBuffers()
        val decoderOutputBuffers = decoderModel.createOutputBuffers()

        encoderImageInput = encoderInputBuffers[0]
        encoderHiddenStatesOutput = encoderOutputBuffers[0]

        decoderHiddenStatesInput = decoderInputBuffers[0]
        decoderAttentionMaskInput = decoderInputBuffers[1]
        decoderEmbeddingsInput = decoderInputBuffers[2]

        decoderLogitsOutput = decoderOutputBuffers[0]

        textPostprocessor = TextPostprocessor()
        
        // Initialize native helpers
        nativeInit()

        logcat(LogPriority.INFO) { "OCR models initialized" }
    }

    override suspend fun recognizeText(image: Bitmap): String {
        val startTime = System.nanoTime()
        val rawText = inferenceMutex.withLock {
            require(!image.isRecycled) { "Input bitmap is recycled" }

            val preprocessTime = measureNanoTime {
                preprocessImage(image, encoderImageInput)
            }
            logcat(LogPriority.INFO) { "OCR Perf Test: preprocessImage took ${preprocessTime / 1_000_000} ms" }

            // Run encoder and get hidden states
            val encoderHiddenStates: FloatArray
            val encoderTime = measureNanoTime {
                encoderHiddenStates = runEncoder()
            }
            logcat(LogPriority.INFO) { "OCR Perf Test: runEncoder took ${encoderTime / 1_000_000} ms" }

            // Run decoder with encoder states
            val tokenCount: Int
            val decoderTime = measureNanoTime {
                tokenCount = runDecoder(encoderHiddenStates)
            }
            logcat(LogPriority.INFO) { "OCR Perf Test: runDecoder took ${decoderTime / 1_000_000} ms" }

            val decodedText: String
            val decodeTokensTime = measureNanoTime {
                decodedText = decodeTokens(tokenBuffer, tokenCount)
            }
            logcat(LogPriority.INFO) { "OCR Perf Test: decodeTokens took ${decodeTokensTime / 1_000_000} ms" }

            decodedText
        }

        val postprocessedText: String
        val postprocessTime = measureNanoTime {
            postprocessedText = nativePostprocessText(rawText)
        }
        logcat(LogPriority.INFO) { "OCR Perf Test: postprocess took ${postprocessTime / 1_000_000} ms" }

        val totalTime = (System.nanoTime() - startTime) / 1_000_000
        logcat(LogPriority.INFO) { "OCR Perf Test: recognizeText total time: $totalTime ms" }

        return postprocessedText
    }

    /**
     * Preprocesses the input bitmap for OCR recognition using native code.
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
            // Use native preprocessing for better performance
            nativePreprocessImage(workingBitmap, normalizedBuffer)
            inputBuffer.writeFloat(normalizedBuffer)
        } finally {
            // Clean up only if we created a new bitmap
            if (workingBitmap !== bitmap) {
                workingBitmap.recycle()
            }
        }
    }

    /**
     * Run encoder and return a persistent FloatArray copy of hidden states.
     * Uses dedicated output buffers created at initialization.
     */
    private fun runEncoder(): FloatArray {
        val inputBuffers = listOf(encoderImageInput)
        val outputBuffers = listOf(encoderHiddenStatesOutput)
        encoderModel.run(inputBuffers, outputBuffers)

        // Read and create persistent copy for decoder
        return encoderHiddenStatesOutput.readFloat()
    }

    private fun runDecoder(encoderHiddenStates: FloatArray): Int {
        val tokenIds = tokenBuffer
        tokenIds[0] = START_TOKEN_ID
        var tokenCount = 1

        // Reset input IDs array to PAD tokens
        inputIdsArray.fill(PAD_TOKEN_ID)
        inputIdsArray[0] = START_TOKEN_ID

        // Clear buffers to ensure no artifacts from previous images
        embeddingsInputBuffer.fill(0f)
        attentionMaskBuffer.fill(0f)

        // Pre-calculate the Start Token embedding/mask
        updateEmbedding(START_TOKEN_ID, 0)
        attentionMaskBuffer[0] = 1.0f

        var totalInferenceTime = 0L

        @Suppress("UNUSED_PARAMETER")
        for (step in 0 until MAX_SEQUENCE_LENGTH - 1) {
            // Write encoder states and input IDs to reused buffers
            decoderHiddenStatesInput.writeFloat(encoderHiddenStates)
            decoderEmbeddingsInput.writeFloat(embeddingsInputBuffer)
            decoderAttentionMaskInput.writeFloat(attentionMaskBuffer)

            val decoderInputs = listOf(decoderHiddenStatesInput, decoderAttentionMaskInput, decoderEmbeddingsInput)
            val decoderOutputs = listOf(decoderLogitsOutput)

            // Run inference
            totalInferenceTime += measureNanoTime {
                decoderModel.run(decoderInputs, decoderOutputs)
            }

            val logits = decoderLogitsOutput.readFloat()
            val nextToken = findMaxLogitToken(logits, tokenCount)

            // Validate token
            if (nextToken < 0 || nextToken == END_TOKEN_ID) {
                break
            }

            val nextIndex = tokenCount
            tokenIds[nextIndex] = nextToken
            inputIdsArray[nextIndex] = nextToken

            updateEmbedding(nextToken, nextIndex)
            attentionMaskBuffer[nextIndex] = 1.0f

            tokenCount++
            if (tokenCount >= MAX_SEQUENCE_LENGTH) {
                break
            }

        }

        logcat(LogPriority.INFO) { "OCR Perf Test: decoderModel.run sub-time took ${totalInferenceTime / 1_000_000} ms" }

        return tokenCount
    }

    /**
    * Only copies the specific slice needed for the current index.
    */
    private fun updateEmbedding(tokenId: Int, index: Int) {
        val embedOffset = tokenId * HIDDEN_SIZE
        val outputOffset = index * HIDDEN_SIZE
        System.arraycopy(embeddings, embedOffset, embeddingsInputBuffer, outputOffset, HIDDEN_SIZE)
    }

    /**
     * Find the token with maximum logit value for the current sequence position.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun findMaxLogitToken(logits: FloatArray, seqLen: Int): Int {
        val lastTokenPos = seqLen - 1
        val logitsOffset = lastTokenPos * VOCAB_SIZE

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
     * Convert token IDs to text using the vocabulary (native implementation).
     */
    private fun decodeTokens(tokenIds: IntArray, tokenCount: Int): String {
        return nativeDecodeTokens(tokenIds, tokenCount)
    }

    override fun close() {
        runBlocking {
            inferenceMutex.withLock {
                closeInternal()
            }
        }
    }

    private fun closeInternal() {
        try {
            // Close reusable buffers
            encoderImageInput.close()
            encoderHiddenStatesOutput.close()
            decoderHiddenStatesInput.close()
            decoderEmbeddingsInput.close()
            decoderAttentionMaskInput.close()
            decoderLogitsOutput.close()

            // Close models
            encoderModel.close()
            decoderModel.close()
            
            // Close native resources
            nativeClose()

            logcat(LogPriority.INFO) { "OCR models closed successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Error closing OCR models: ${e.message}" }
        }
    }
    
    // Native methods for optimized operations
    private external fun nativeInit()
    private external fun nativePreprocessImage(bitmap: Bitmap, output: FloatArray)
    private external fun nativeDecodeTokens(tokenIds: IntArray, tokenCount: Int): String
    private external fun nativePostprocessText(text: String): String
    private external fun nativeClose()
}
