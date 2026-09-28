package com.raen.kisaratranslator.engine.recognizer

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.raen.kisaratranslator.data.model.TranslationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Google ML Kit Text Recognition wrapper for on-device OCR.
 */
class MlKitOcrRecognizer(val languageCode: String = "ja") : Closeable {

    private val recognizer = when (languageCode.lowercase()) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(crop: Bitmap): String = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(crop, 0)
            val result = Tasks.await(recognizer.process(image))
            result.text.trim()
        } catch (e: Exception) {
            TranslationReport.log("WARN", "MLKitOCR", "Recognition failed: ${e.message}", e)
            ""
        }
    }

    suspend fun detectBlocks(bitmap: Bitmap): List<Pair<String, Rect>> = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image))
            result.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                val text = block.text.trim()
                if (text.isNotBlank()) text to box else null
            }
        } catch (e: Exception) {
            TranslationReport.log("WARN", "MLKitOCR", "Full-page block detection failed: ${e.message}", e)
            emptyList()
        }
    }

    override fun close() {
        recognizer.close()
    }
}
