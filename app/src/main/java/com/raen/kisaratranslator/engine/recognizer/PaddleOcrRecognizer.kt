package com.raen.kisaratranslator.engine.recognizer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.raen.kisaratranslator.core.util.AiBufferUtils
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.model.TranslationModelType
import com.raen.kisaratranslator.data.model.TranslationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PaddleOCR PP-OCRv5 CTC Text Recognizer.
 */
class PaddleOcrRecognizer(
    private val modelManager: TranslationModelManager,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Closeable {

    private var session: OrtSession? = null
    private var dictionary: List<String>? = null

    val isReady: Boolean
        get() = modelManager.isPaddleOcrReady()

    private fun ensureSession() {
        if (!isReady) return
        val modelFile = modelManager.getModelFile(TranslationModelType.PADDLE_OCR_REC)
        val dictFile = modelManager.getModelFile(TranslationModelType.PADDLE_OCR_DICT)

        if (session == null && modelFile.exists() && modelFile.length() >= TranslationModelType.PADDLE_OCR_REC.minSize) {
            val opts = AiBufferUtils.createSessionOptions(2)
            try {
                session = env.createSession(modelFile.absolutePath, opts)
            } catch (e: Exception) {
                session = null
            } finally {
                opts.close()
            }
        }
        if (dictionary == null && dictFile.exists()) {
            try {
                dictionary = loadDictionary(dictFile)
            } catch (e: Exception) {
                dictionary = null
            }
        }
    }

    private fun loadDictionary(file: File): List<String> {
        val lines = file.readLines().toMutableList()
        lines.add(0, "blank")
        lines.add(" ")
        return lines
    }

    suspend fun recognize(srcCrop: Bitmap): String = withContext(Dispatchers.Default) {
        ensureSession()
        val sess = session ?: return@withContext ""
        val dict = dictionary ?: return@withContext ""

        // Vertical text normalization: if H/W >= 1.3, rotate 90 deg clockwise
        val isVertical = srcCrop.height >= srcCrop.width * 1.3f
        val crop = if (isVertical) {
            val matrix = Matrix().apply { postRotate(90f) }
            Bitmap.createBitmap(srcCrop, 0, 0, srcCrop.width, srcCrop.height, matrix, true)
        } else {
            srcCrop
        }

        // Target height 48px, width proportional (min 48px, max 960px)
        val targetH = 48
        val scale = targetH.toFloat() / crop.height
        val targetW = ((crop.width * scale).roundToInt()).coerceIn(48, 960)

        val scaled = Bitmap.createScaledBitmap(crop, targetW, targetH, true)
        val numPixels = targetW * targetH
        val floatBuf = AiBufferUtils.allocateDirectFloatBuffer(3 * numPixels)

        val pixels = IntArray(numPixels)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

        val mean = 0.5f
        val std = 0.5f

        for (c in 0..2) {
            for (i in 0 until numPixels) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> Color.red(p) / 255f
                    1 -> Color.green(p) / 255f
                    else -> Color.blue(p) / 255f
                }
                floatBuf.put((v - mean) / std)
            }
        }
        floatBuf.rewind()

        val inputTensor = OnnxTensor.createTensor(
            env,
            floatBuf,
            longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong()),
        )

        return@withContext try {
            val inputName = sess.inputNames.first()
            val result = sess.run(mapOf(inputName to inputTensor))
            val outputTensor = result.get(0) as OnnxTensor
            val shape = outputTensor.info.shape
            val timeSteps = shape[1].toInt()
            val numClasses = shape[2].toInt()

            val buffer = outputTensor.floatBuffer
            val preds = FloatArray(buffer.remaining())
            buffer.get(preds)

            // CTC Greedy Decode
            val sb = StringBuilder()
            var prevIndex = 0

            for (t in 0 until timeSteps) {
                var maxVal = Float.NEGATIVE_INFINITY
                var maxIdx = 0
                val offset = t * numClasses
                for (c in 0 until numClasses) {
                    val score = preds[offset + c]
                    if (score > maxVal) {
                        maxVal = score
                        maxIdx = c
                    }
                }

                if (maxIdx != 0 && maxIdx != prevIndex) {
                    if (maxIdx in dict.indices) {
                        sb.append(dict[maxIdx])
                    }
                }
                prevIndex = maxIdx
            }

            result.close()
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e("PaddleOcrRecognizer", "Recognition error", e)
            TranslationReport.log("ERROR", "PaddleOcrRecognizer", "CTC error: ${e.message}", e)
            ""
        } finally {
            inputTensor.close()
            scaled.recycle()
            if (isVertical && crop != srcCrop) {
                crop.recycle()
            }
        }
    }

    override fun close() {
        session?.close()
        session = null
    }
}
