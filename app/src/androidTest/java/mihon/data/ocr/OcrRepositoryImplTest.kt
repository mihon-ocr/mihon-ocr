package mihon.data.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import mihon.data.ocr.di.OcrModule
import mihon.domain.ocr.interactor.OcrProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test for OCR repository that runs against the device/emulator assets and native
 * inference libraries. 
 * TODO: Since, this requires a real Android runtime/device it's disabled by default
 */
@RunWith(AndroidJUnit4::class)
class OcrRepositoryImplTest {

    private lateinit var ocrRepository: OcrRepositoryImpl
    private lateinit var context: Context

    private val ocrProcessor: OcrProcessor
        get() = OcrModule.provideOcrProcessor(context)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ocrRepository = OcrRepositoryImpl(context)
    }

    @Test
    fun ocrTest() = runTest(timeout = 4.minutes) {
        val testCases = listOf(
            "mihon/data/ocr/ocr_test_image.base64" to "でもだいたい見当はついてるの",
            "mihon/data/ocr/ocr_test_image2.base64" to "ぼくが復活する前に",
        )

        for ((resourceName, expectedText) in testCases) {
            val inputStream = javaClass.classLoader?.getResourceAsStream(resourceName)
            require(inputStream != null) { "Test image not found: $resourceName" }

            val base64 = inputStream.bufferedReader().use { it.readText().trim() }
            val bytes = Base64.decode(base64, Base64.DEFAULT)

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            require(bitmap != null) { "Bitmap could not be decoded from $resourceName" }

            val text = ocrProcessor.getText(bitmap)
            assertNotNull(text)

            assertEquals(expectedText, text)
        }
    }
}
