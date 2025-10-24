package mihon.data.ocr.di

import android.content.Context
import mihon.data.ocr.OcrRepositoryImpl
import mihon.domain.ocr.interactor.OcrProcessor
import mihon.domain.ocr.repository.OcrRepository

object OcrModule {

    /**
     * Provides the OCR repository implementation.
     * This is a singleton to avoid loading models multiple times.
     */
    @Volatile
    private var ocrRepository: OcrRepository? = null

    fun provideOcrRepository(context: Context): OcrRepository {
        return ocrRepository ?: synchronized(this) {
            ocrRepository ?: createOcrRepository(context).also {
                ocrRepository = it
            }
        }
    }

    private fun createOcrRepository(context: Context): OcrRepository {
        return OcrRepositoryImpl(
            context = context.applicationContext
        )
    }

    fun provideOcrProcessor(context: Context): OcrProcessor {
        val repository = provideOcrRepository(context)
        return OcrProcessor(repository)
    }

    fun cleanup() {
        synchronized(this) {
            val repository = ocrRepository
            if (repository != null) {
                (repository as? OcrRepositoryImpl)?.close()
                ocrRepository = null
            }
        }
    }
}
