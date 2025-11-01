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
import kotlin.system.measureNanoTime
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
    private val inferenceMutex = Mutex() // Guards shared inference buffers
    private val pixelsBuffer = IntArray(IMAGE_SIZE * IMAGE_SIZE)
    private val normalizedBuffer = FloatArray(IMAGE_SIZE * IMAGE_SIZE * 3)
    private val tokenBuffer = IntArray(MAX_SEQUENCE_LENGTH)
    private val textBuilder = StringBuilder(MAX_SEQUENCE_LENGTH * 2)

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
        private const val MIN_CPU_THREADS = 2
        private const val MAX_CPU_THREADS = 4
    }

    init {
        val cpuThreads = resolveCpuThreads()
        val sharedCpuOptions = cpuThreads?.let { CompiledModel.CpuOptions(numThreads = it) }

        val encoderOptions = CompiledModel.Options(Accelerator.CPU).apply {
            sharedCpuOptions?.let { cpuOptions = it }
        }
        // decoder does not support GPU operations
        val decoderOptions = CompiledModel.Options(Accelerator.CPU).apply {
            sharedCpuOptions?.let { cpuOptions = it }
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

        encoderInputBuffers = encoderModel.createInputBuffers()
        encoderOutputBuffers = encoderModel.createOutputBuffers()
        decoderInputBuffers = decoderModel.createInputBuffers()
        decoderOutputBuffers = decoderModel.createOutputBuffers()

        textPostprocessor = TextPostprocessor()

        val threadInfo = cpuThreads?.let { " (threads=$it)" }.orEmpty()
        logcat(LogPriority.INFO) { "OCR models initialized$threadInfo" }
    }

    override suspend fun recognizeText(image: Bitmap): String {
        val startTime = System.nanoTime()
        val rawText = inferenceMutex.withLock {
            require(!image.isRecycled) { "Input bitmap is recycled" }

            val encoderInputBuffer = encoderInputBuffers[0]
            val preprocessTime = measureNanoTime {
                preprocessImage(image, encoderInputBuffer)
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
            postprocessedText = textPostprocessor.postprocess(rawText)
        }
        logcat(LogPriority.INFO) { "OCR Perf Test: postprocess took ${postprocessTime / 1_000_000} ms" }

        val totalTime = (System.nanoTime() - startTime) / 1_000_000
        logcat(LogPriority.INFO) { "OCR Perf Test: recognizeText total time: $totalTime ms" }

        return postprocessedText
    }

    /**
     * Preprocesses the input bitmap for OCR recognition.
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
            workingBitmap.getPixels(pixelsBuffer, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

            // Normalize directly to output buffer
            normalizePixels(pixelsBuffer, normalizedBuffer)

            inputBuffer.writeFloat(normalizedBuffer)
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
     * Manually unrolled for better performance.
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
    private fun runEncoder(): FloatArray {
        val outputBuffers = encoderOutputBuffers
        encoderModel.run(encoderInputBuffers, outputBuffers)

        // Read and create persistent copy for decoder
        return encoderOutputBuffers[0].readFloat()
    }

    private fun runDecoder(encoderHiddenStates: FloatArray): Int {
        val tokenIds = tokenBuffer
        tokenIds[0] = START_TOKEN_ID
        var tokenCount = 1

        // Reset input IDs array to PAD tokens
        inputIdsArray.fill(PAD_TOKEN_ID.toLong())
        inputIdsArray[0] = START_TOKEN_ID.toLong()

        var totalInferenceTime = 0L

        val decoderInputs = decoderInputBuffers
        // Write encoder states once - they don't change during decoding
        decoderInputs[1].writeFloat(encoderHiddenStates)

        val decoderOutputs = decoderOutputBuffers
        val decoderInput = decoderInputs[0]
        val decoderOutput = decoderOutputs[0]

        @Suppress("UNUSED_PARAMETER")
        for (step in 0 until MAX_SEQUENCE_LENGTH - 1) {
            val currentSeqLen = tokenCount

            if (currentSeqLen >= MAX_SEQUENCE_LENGTH) {
                break
            }

            // Write to pre-allocated buffers (reused across iterations)
            decoderInput.writeLong(inputIdsArray)

            // Run inference
            totalInferenceTime += measureNanoTime {
                decoderModel.run(decoderInputs, decoderOutputs)
            }

            // Extract next token
            val logits = decoderOutput.readFloat()
            val nextToken = findMaxLogitToken(logits, currentSeqLen)

            // Validate token
            if (nextToken < 0) {
                break
            }

            val nextIndex = tokenCount
            tokenIds[nextIndex] = nextToken
            inputIdsArray[nextIndex] = nextToken.toLong()
            tokenCount = nextIndex + 1

            // Check stopping conditions
            if (nextToken == END_TOKEN_ID) {
                break
            }
        }

        logcat(LogPriority.INFO) { "OCR Perf Test: decoderModel.run sub-time took ${totalInferenceTime / 1_000_000} ms" }

        return tokenCount
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
     * Convert token IDs to text using the vocabulary.
     */
    private fun decodeTokens(tokenIds: IntArray, tokenCount: Int): String {
        // Reuse builder to minimize per-call allocations
        val text = textBuilder
        text.setLength(0)

        for (index in 0 until tokenCount) {
            val tokenId = tokenIds[index]

            if (tokenId < SPECIAL_TOKEN_THRESHOLD) continue

            // Bounds check once per token
            if (tokenId < VOCAB_SIZE) {
                text.append(vocab[tokenId])
            }
        }

        return text.toString()
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

    private fun resolveCpuThreads(): Int? {
        val available = Runtime.getRuntime().availableProcessors()
        if (available <= 1) return null
        return available.coerceIn(MIN_CPU_THREADS, MAX_CPU_THREADS)
    }
}
