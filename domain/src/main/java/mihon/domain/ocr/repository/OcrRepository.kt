package mihon.domain.ocr.repository

import android.graphics.Bitmap

interface OcrRepository {
    suspend fun recognizeText(image: Bitmap): String
    fun close()
}
