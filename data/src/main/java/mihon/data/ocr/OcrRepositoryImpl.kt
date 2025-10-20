package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import mihon.domain.ocr.repository.OcrRepository
import androidx.core.graphics.scale

class OcrRepositoryImpl(
    context: Context,
) : OcrRepository {
    private val encoderModelPath: String = "ocr/encoder.tflite"
    private val decoderModelPath: String = "ocr/decoder.tflite"
    private val encoderModel: CompiledModel
    private val decoderModel: CompiledModel
    private val textPostprocessor: TextPostprocessor
    private val maxSequenceLength: Int

    companion object {
        private const val IMAGE_SIZE = 224
        private const val NORMALIZATION_MEAN = 0.5f
        private const val NORMALIZATION_STD = 0.5f
        private const val START_TOKEN_ID = 2
        private const val END_TOKEN_ID = 3
        private const val PAD_TOKEN_ID = 0
        private const val SPECIAL_TOKEN_THRESHOLD = 5
        private const val MAX_SEQUENCE_LENGTH = 300
        private const val VOCAB_SIZE = 6144 // This is what the model specifies, but the vocab list is 6142 long
    }

    init {
        val options = CompiledModel.Options(Accelerator.CPU)

        encoderModel = CompiledModel.create(
            context.assets,
            encoderModelPath,
            options,
        )

        decoderModel = CompiledModel.create(
            context.assets,
            decoderModelPath,
            options,
        )

        maxSequenceLength = MAX_SEQUENCE_LENGTH
        textPostprocessor = TextPostprocessor()

        logcat(LogPriority.DEBUG) { "Model initialization completed" }
    }

    override suspend fun recognizeText(image: Bitmap): String = withContext(Dispatchers.Default) {
        var preprocessedImage: TensorBuffer? = null

        try {
            preprocessedImage = preprocessImage(image)

            // Run encoder and get persistent copy of hidden states
            val encoderHiddenStates = runEncoderAndGetStates(preprocessedImage)

            // Run decoder with the persistent encoder states
            val tokenIds = runDecoder(encoderHiddenStates)

            val rawText = decodeTokens(tokenIds)
            logcat(LogPriority.DEBUG) { "Raw recognized text: $rawText" }

            textPostprocessor.postprocess(rawText)
        } finally {
            preprocessedImage?.close()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): TensorBuffer {
        logcat(LogPriority.DEBUG) { "Preprocessing image..." }
        require(!bitmap.isRecycled) { "Input bitmap was recycled before OCR preprocessing" }

        val workingBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        val resizedBitmap = if (workingBitmap.width != IMAGE_SIZE || workingBitmap.height != IMAGE_SIZE) {
            val scaled = workingBitmap.scale(IMAGE_SIZE, IMAGE_SIZE)
            if (scaled !== workingBitmap) {
                workingBitmap.recycle()
            }
            scaled
        } else {
            workingBitmap
        }

        val inputBuffers = encoderModel.createInputBuffers()
        val inputBuffer = inputBuffers[0]

        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        resizedBitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
        resizedBitmap.recycle()

        val floatArray = FloatArray(IMAGE_SIZE * IMAGE_SIZE * 3)
        var index = 0

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            floatArray[index++] = (r - NORMALIZATION_MEAN) / NORMALIZATION_STD
            floatArray[index++] = (g - NORMALIZATION_MEAN) / NORMALIZATION_STD
            floatArray[index++] = (b - NORMALIZATION_MEAN) / NORMALIZATION_STD
        }

        inputBuffer.writeFloat(floatArray)

        for (i in 1 until inputBuffers.size) {
            inputBuffers[i].close()
        }

        return inputBuffer
    }

    /**
     * Run encoder and return a persistent FloatArray copy of hidden states
     * This avoids any buffer reuse issues with TensorBuffer
     */
    private fun runEncoderAndGetStates(imageBuffer: TensorBuffer): FloatArray {
        logcat(LogPriority.DEBUG) { "Running encoder..." }

        val outputBuffers = encoderModel.createOutputBuffers()

        try {
            encoderModel.run(listOf(imageBuffer), outputBuffers)

            // Read the encoder hidden states and create a persistent copy
            val encoderStates = outputBuffers[0].readFloat()

            logcat(LogPriority.DEBUG) { "Encoder hidden states size: ${encoderStates.size}" }

            // Return a copy to ensure no buffer corruption
            return encoderStates.copyOf()
        } finally {
            outputBuffers.forEach { it.close() }
        }
    }

    /**
     * CRITICAL FIX: Completely rewritten decoder to avoid all buffer reuse issues
     */
    private fun runDecoder(encoderHiddenStatesCopy: FloatArray): List<Int> {
        logcat(LogPriority.DEBUG) { "Running decoder with encoder states size: ${encoderHiddenStatesCopy.size}" }

        val tokenIds = mutableListOf(START_TOKEN_ID)

        try {
            for (step in 0 until maxSequenceLength - 1) {
                logcat(LogPriority.DEBUG) {
                    "=== Decoder step $step === Token count: ${tokenIds.size}, Tokens: $tokenIds"
                }

                // CRITICAL: Create COMPLETELY fresh buffers each iteration
                val inputBuffers = decoderModel.createInputBuffers()
                val outputBuffers = decoderModel.createOutputBuffers()

                try {
                    val inputIdsArray = LongArray(maxSequenceLength) { PAD_TOKEN_ID.toLong() }

                    // Validate token bounds before copying
                    logcat(LogPriority.DEBUG) { "Preparing ${tokenIds.size} tokens for decoder input" }
                    for (i in tokenIds.indices) {
                        val token = tokenIds[i]

                        when {
                            token < 0 -> {
                                logcat(LogPriority.ERROR) { "CRITICAL: Negative token at position $i: $token" }
                                throw IllegalStateException("Negative token ID detected: $token at position $i")
                            }
                            token >= VOCAB_SIZE -> {
                                logcat(LogPriority.ERROR) { "CRITICAL: Token exceeds vocab at position $i: $token" }
                                throw IllegalStateException("Token ID $token exceeds vocab size $VOCAB_SIZE at position $i")
                            }
                            else -> {
                                inputIdsArray[i] = token.toLong()
                            }
                        }
                    }

                    logcat(LogPriority.DEBUG) {
                        "Input IDs array (first 10): ${inputIdsArray.take(10).joinToString(",")}"
                    }

                    // Write to fresh buffers
                    val inputIdsBuffer = inputBuffers[0]
                    val encoderStatesBuffer = inputBuffers[1]

                    inputIdsBuffer.writeLong(inputIdsArray)
                    logcat(LogPriority.DEBUG) { "Wrote ${inputIdsArray.size} input IDs (INT64) to buffer" }

                    // Write encoder hidden states from the persistent copy
                    encoderStatesBuffer.writeFloat(encoderHiddenStatesCopy)
                    logcat(LogPriority.DEBUG) { "Wrote ${encoderHiddenStatesCopy.size} encoder states to buffer" }

                    // Run inference
                    logcat(LogPriority.DEBUG) { "Invoking decoder model..." }
                    decoderModel.run(inputBuffers, outputBuffers)
                    logcat(LogPriority.DEBUG) { "Decoder model invoked successfully" }

                    // Read logits
                    val logitsFloatArray = outputBuffers[0].readFloat()
                    logcat(LogPriority.DEBUG) { "Read ${logitsFloatArray.size} logits from output" }

                    // Calculate position for current sequence length
                    val currentSeqLen = tokenIds.size
                    val lastTokenPos = currentSeqLen - 1
                    val logitsOffset = lastTokenPos * VOCAB_SIZE

                    logcat(LogPriority.DEBUG) {
                        "Extracting logits: seqLen=$currentSeqLen, lastPos=$lastTokenPos, offset=$logitsOffset"
                    }

                    // Validate logits array bounds
                    if (logitsOffset + VOCAB_SIZE > logitsFloatArray.size) {
                        val error = "Logits buffer too small! Expected at least ${logitsOffset + VOCAB_SIZE}, got ${logitsFloatArray.size}"
                        logcat(LogPriority.ERROR) { error }
                        throw IllegalStateException(error)
                    }

                    // Find token with max logit
                    var maxLogit = Float.NEGATIVE_INFINITY
                    var nextTokenId = PAD_TOKEN_ID

                    for (vocabIdx in 0 until VOCAB_SIZE) {
                        val logit = logitsFloatArray[logitsOffset + vocabIdx]
                        if (logit > maxLogit) {
                            maxLogit = logit
                            nextTokenId = vocabIdx
                        }
                    }

                    // Final validation
                    if (nextTokenId < 0 || nextTokenId >= VOCAB_SIZE) {
                        val error = "Invalid next token: $nextTokenId (vocab size: $VOCAB_SIZE)"
                        logcat(LogPriority.ERROR) { error }
                        throw IllegalStateException(error)
                    }

                    logcat(LogPriority.DEBUG) {
                        "Selected token $nextTokenId with logit $maxLogit"
                    }

                    tokenIds.add(nextTokenId)
                    logcat(LogPriority.DEBUG) { "Added token. New sequence: $tokenIds" }

                    // Check stopping conditions
                    if (nextTokenId == END_TOKEN_ID) {
                        logcat(LogPriority.DEBUG) { "Reached END token, stopping" }
                        break
                    }

                    if (tokenIds.size >= maxSequenceLength) {
                        logcat(LogPriority.DEBUG) { "Reached max sequence length, stopping" }
                        break
                    }

                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) {
                        "Error at decoder step $step with ${tokenIds.size} tokens: ${e.message}"
                    }
                    e.printStackTrace()
                    throw e
                } finally {
                    inputBuffers.forEach { it.close() }
                    outputBuffers.forEach { it.close() }
                    logcat(LogPriority.DEBUG) { "Closed buffers for step $step" }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Decoder failed: ${e.message}" }
            throw e
        }

        logcat(LogPriority.DEBUG) { "Decoder complete with ${tokenIds.size} tokens: $tokenIds" }
        return tokenIds
    }

    private fun decodeTokens(tokenIds: List<Int>): String {
        logcat(LogPriority.DEBUG) { "Decoding ${tokenIds.size} tokens..." }
        val text = StringBuilder()

        for (tokenId in tokenIds) {
            if (tokenId < SPECIAL_TOKEN_THRESHOLD) {
                continue
            }

            if (tokenId >= vocab.size) {
                logcat(LogPriority.ERROR) { "Token $tokenId outside vocabulary range ${vocab.size}" }
                continue
            }

            val token = vocab[tokenId]
            text.append(token)
        }

        return text.toString()
    }

    override fun close() {
        encoderModel.close()
        decoderModel.close()
    }
}
