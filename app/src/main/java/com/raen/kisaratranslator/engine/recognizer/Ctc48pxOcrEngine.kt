package com.raen.kisaratranslator.engine.recognizer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
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
 * High-speed single-pass CTC OCR engine using Manga Translator 48px CTC (ocr-48px-ctc.onnx).
 * Scales text crops to 48px height with greedy CTC sequence decoding against alphabet-all-v5.txt.
 */
class Ctc48pxOcrEngine(
    private val modelManager: TranslationModelManager,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Closeable {

    private var session: OrtSession? = null
    private var alphabet: List<String> = emptyList()

    val isReady: Boolean
        get() = modelManager.is48pxCtcReady()

    @Synchronized
    private fun ensureSession() {
        if (!isReady) return
        val modelFile = modelManager.getModelFile(TranslationModelType.OCR_48PX_CTC)
        val dictFile = modelManager.getModelFile(TranslationModelType.OCR_48PX_ALPHABET)

        if (session == null && modelFile.exists()) {
            val opts = AiBufferUtils.createSessionOptions(2)
            try {
                session = env.createSession(modelFile.absolutePath, opts)
            } catch (e: Exception) {
                Log.e("Ctc48pxOcrEngine", "Failed to create 48px CTC session", e)
                session = null
            } finally {
                opts.close()
            }
        }

        if (alphabet.isEmpty() && dictFile.exists()) {
            try {
                alphabet = dictFile.readLines(Charsets.UTF_8).map { it.trimEnd('\r', '\n') }
            } catch (e: Exception) {
                Log.e("Ctc48pxOcrEngine", "Failed to load 48px CTC alphabet", e)
                alphabet = emptyList()
            }
        }
    }

    suspend fun recognize(crop: Bitmap): String = withContext(Dispatchers.Default) {
        if (crop.width < 4 || crop.height < 4) return@withContext ""
        if (!isReady) {
            throw IllegalStateException("Manga Translator 48px CTC model files are missing. Download them in Model Manager.")
        }
        ensureSession()

        val sess = session ?: throw IllegalStateException("48px CTC ONNX session could not be initialized.")
        if (alphabet.isEmpty()) throw IllegalStateException("48px CTC alphabet vocabulary is empty.")

        // 1. Orient vertical vs horizontal text
        // If height > width * 1.15, textline is vertical: rotate 90° counter-clockwise to horizontal
        val isVertical = crop.height > crop.width * 1.15f
        val oriented = if (isVertical) {
            val matrix = Matrix().apply { postRotate(-90f) }
            Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, matrix, true)
        } else {
            crop
        }

        // 2. Proportional resize to fixed 48px height
        val targetH = 48
        val ratio = targetH.toFloat() / oriented.height.toFloat()
        val targetW = max(16, (oriented.width * ratio).roundToInt())
        val scaled = Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
        if (oriented != crop) {
            oriented.recycle()
        }

        val numPixels = targetW * targetH
        val pixels = IntArray(numPixels)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

        // 3. Populate NCHW float buffer in range [0..1]
        val floatBuf = AiBufferUtils.allocateDirectFloatBuffer(3 * numPixels)
        for (c in 0..2) {
            for (i in 0 until numPixels) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> Color.red(p) / 255f
                    1 -> Color.green(p) / 255f
                    else -> Color.blue(p) / 255f
                }
                floatBuf.put(v)
            }
        }
        floatBuf.rewind()
        scaled.recycle()

        val inputTensor = OnnxTensor.createTensor(
            env,
            floatBuf,
            longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong()),
        )

        try {
            val inputName = sess.inputNames.firstOrNull() ?: "img"
            val outputs = sess.run(mapOf(inputName to inputTensor))
            val result = try {
                val logitsTensor = outputs.get(0) as? OnnxTensor ?: return@withContext ""

                val shape = logitsTensor.info.shape // [1, seq_len, 19264]
                val seqLen = shape[1].toInt()
                val vocabSize = shape[2].toInt()

                val logitsBuf = logitsTensor.floatBuffer
                val logitsArr = FloatArray(logitsBuf.remaining())
                logitsBuf.get(logitsArr)
                logitsTensor.close()

                // 4. Greedy CTC decoding
                val sb = StringBuilder()
                var lastToken = 0 // Blank token is index 0 (<PAD>)

                for (t in 0 until seqLen) {
                    val offset = t * vocabSize
                    var maxVal = Float.NEGATIVE_INFINITY
                    var maxIdx = 0
                    for (v in 0 until vocabSize) {
                        val score = logitsArr[offset + v]
                        if (score > maxVal) {
                            maxVal = score
                            maxIdx = v
                        }
                    }

                    if (maxIdx != 0 && maxIdx != lastToken) {
                        val char = alphabet.getOrNull(maxIdx)
                        if (char != null && !isSpecialToken(char)) {
                            sb.append(char)
                        }
                    }
                    lastToken = maxIdx
                }

                sb.toString()
            } finally {
                outputs.close()
            }
            result
        } catch (e: Exception) {
            Log.e("Ctc48pxOcrEngine", "48px CTC recognition failed", e)
            TranslationReport.log("ERROR", "Ctc48pxOcrEngine", "Inference error: ${e.message}", e)
            throw IllegalStateException("48px CTC inference failed: ${e.message}", e)
        } finally {
            inputTensor.close()
        }
    }

    private fun isSpecialToken(token: String): Boolean {
        return token == "<PAD>" || token == "<S>" || token == "</S>" || token == "<SEP>" || token == "<UNK>"
    }

    override fun close() {
        session?.close()
        session = null
    }
}
