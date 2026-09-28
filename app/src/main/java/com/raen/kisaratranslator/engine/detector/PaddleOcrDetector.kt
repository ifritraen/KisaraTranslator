package com.raen.kisaratranslator.engine.detector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.raen.kisaratranslator.core.util.AiBufferUtils
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.model.TranslationModelType
import com.raen.kisaratranslator.data.model.TranslationReport
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * DBNet ONNX Text Detection engine for PaddleOCR.
 */
class PaddleOcrDetector(
    private val modelManager: TranslationModelManager,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Closeable {

    private var session: OrtSession? = null

    val isReady get() = modelManager.isModelReady(TranslationModelType.PADDLE_OCR_DET)

    private fun ensureSession() {
        val modelFile = modelManager.getModelFile(TranslationModelType.PADDLE_OCR_DET)
        if (session == null && modelFile.exists() && modelFile.length() >= TranslationModelType.PADDLE_OCR_DET.minSize) {
            val opts = AiBufferUtils.createSessionOptions(2)
            try {
                session = env.createSession(modelFile.absolutePath, opts)
            } catch (e: Exception) {
                session = null
            } finally {
                opts.close()
            }
        }
    }

    fun detect(bitmap: Bitmap): List<Rect> {
        if (!isReady) return emptyList()
        ensureSession()
        val sess = session ?: return emptyList()

        val maxSide = 960
        val scale = min(1.0f, maxSide.toFloat() / max(bitmap.width, bitmap.height))
        var targetW = ((bitmap.width * scale) / 32).toInt() * 32
        var targetH = ((bitmap.height * scale) / 32).toInt() * 32
        targetW = max(32, targetW)
        targetH = max(32, targetH)

        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val numPixels = targetW * targetH
        val floatBuf = AiBufferUtils.allocateDirectFloatBuffer(3 * numPixels)

        val pixels = IntArray(numPixels)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        for (c in 0..2) {
            for (i in 0 until numPixels) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> Color.red(p) / 255f
                    1 -> Color.green(p) / 255f
                    else -> Color.blue(p) / 255f
                }
                floatBuf.put((v - mean[c]) / std[c])
            }
        }
        floatBuf.rewind()

        val inputTensor = OnnxTensor.createTensor(
            env,
            floatBuf,
            longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong()),
        )

        val boxes = mutableListOf<Rect>()
        try {
            val inputName = sess.inputNames.first()
            val result = sess.run(mapOf(inputName to inputTensor))
            val outputTensor = result.get(0) as OnnxTensor
            val outBuf = outputTensor.floatBuffer

            val scaleX = bitmap.width.toFloat() / targetW.toFloat()
            val scaleY = bitmap.height.toFloat() / targetH.toFloat()

            val probThreshold = 0.3f
            val gridStep = 4
            val gw = targetW / gridStep
            val gh = targetH / gridStep
            val visited = BooleanArray(gw * gh)

            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    val idx = gy * gw + gx
                    if (visited[idx]) continue

                    val px = gx * gridStep + gridStep / 2
                    val py = gy * gridStep + gridStep / 2
                    val bufIdx = (py * targetW + px).coerceIn(0, outBuf.capacity() - 1)
                    val score = outBuf.get(bufIdx)

                    if (score >= probThreshold) {
                        var minPx = px
                        var maxPx = px
                        var minPy = py
                        var maxPy = py
                        var count = 0

                        val queue = java.util.ArrayDeque<Pair<Int, Int>>()
                        queue.add(Pair(gx, gy))
                        visited[idx] = true

                        while (queue.isNotEmpty()) {
                            val (cx, cy) = queue.removeFirst()
                            count++
                            val curX = cx * gridStep + gridStep / 2
                            val curY = cy * gridStep + gridStep / 2
                            minPx = min(minPx, curX)
                            maxPx = max(maxPx, curX + gridStep)
                            minPy = min(minPy, curY)
                            maxPy = max(maxPy, curY + gridStep)

                            val neighbors = listOf(
                                Pair(cx - 1, cy),
                                Pair(cx + 1, cy),
                                Pair(cx, cy - 1),
                                Pair(cx, cy + 1),
                            )

                            for ((nx, ny) in neighbors) {
                                if (nx in 0 until gw && ny in 0 until gh) {
                                    val nIdx = ny * gw + nx
                                    if (!visited[nIdx]) {
                                        val nPx = nx * gridStep + gridStep / 2
                                        val nPy = ny * gridStep + gridStep / 2
                                        val nBufIdx = (nPy * targetW + nPx).coerceIn(0, outBuf.capacity() - 1)
                                        if (outBuf.get(nBufIdx) >= probThreshold) {
                                            visited[nIdx] = true
                                            queue.add(Pair(nx, ny))
                                        }
                                    }
                                }
                            }
                        }

                        if (count >= 2) {
                            val rect = Rect(
                                (minPx * scaleX).toInt().coerceAtLeast(0),
                                (minPy * scaleY).toInt().coerceAtLeast(0),
                                (maxPx * scaleX).toInt().coerceAtMost(bitmap.width),
                                (maxPy * scaleY).toInt().coerceAtMost(bitmap.height),
                            )
                            if (rect.width() > 12 && rect.height() > 12) {
                                boxes.add(rect)
                            }
                        }
                    }
                }
            }

            result.close()
            TranslationReport.log("INFO", "PaddleOcrDetector", "Detected ${boxes.size} text regions")
        } catch (e: Exception) {
            Log.e("PaddleOcrDetector", "PaddleOCR detection failed", e)
            TranslationReport.log("ERROR", "PaddleOcrDetector", "Detection failed: ${e.message}", e)
        } finally {
            inputTensor.close()
            scaled.recycle()
        }

        return boxes
    }

    override fun close() {
        session?.close()
        session = null
    }
}
