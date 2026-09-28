package com.raen.kisaratranslator.engine.detector

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.raen.kisaratranslator.core.util.AiBufferUtils
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.model.TranslationModelType
import com.raen.kisaratranslator.data.model.TranslationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Optional ONNX-based bubble/text-region detector using YOLOv8 fine-tuned on comics.
 */
class BubbleDetector(
    private val modelManager: TranslationModelManager,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Closeable {

    private var session: OrtSession? = null

    val isReady get() = modelManager.isModelReady(TranslationModelType.BUBBLE_DETECTOR)

    data class DetectedRegion(
        val rect: Rect,
        val score: Float,
        val isBubble: Boolean, // true = speech bubble, false = free text
    )

    private fun ensureSession() {
        val modelFile = modelManager.getModelFile(TranslationModelType.BUBBLE_DETECTOR)
        if (session == null && modelFile.exists() && modelFile.length() >= TranslationModelType.BUBBLE_DETECTOR.minSize) {
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

    suspend fun detect(bitmap: Bitmap): List<DetectedRegion> = withContext(Dispatchers.Default) {
        if (!isReady) {
            return@withContext listOf(
                DetectedRegion(Rect(0, 0, bitmap.width, bitmap.height), 1f, false),
            )
        }
        ensureSession()
        val sess = session ?: return@withContext listOf(
            DetectedRegion(Rect(0, 0, bitmap.width, bitmap.height), 1f, false),
        )

        val inputSize = 640
        val (scaled, scaleX, scaleY, padX, padY) = letterbox(bitmap, inputSize)
        val tensor = bitmapToTensor(scaled, inputSize)

        val inputsInfo = sess.inputInfo
        val origTargetSizesTensor = if (inputsInfo.containsKey("orig_target_sizes")) {
            val type = (inputsInfo["orig_target_sizes"]?.info as? ai.onnxruntime.TensorInfo)?.type
            if (type == OnnxJavaType.INT32) {
                OnnxTensor.createTensor(env, arrayOf(intArrayOf(bitmap.height, bitmap.width)))
            } else {
                OnnxTensor.createTensor(env, arrayOf(longArrayOf(bitmap.height.toLong(), bitmap.width.toLong())))
            }
        } else {
            null
        }

        val runInputs = mutableMapOf<String, OnnxTensor>("images" to tensor)
        if (origTargetSizesTensor != null) {
            runInputs["orig_target_sizes"] = origTargetSizesTensor
        }

        try {
            val outputs = sess.run(runInputs)
            val regions = try {
                var outputTensor: OnnxTensor? = null
                for (entry in outputs) {
                    val v = entry.value
                    if (v is OnnxTensor && v.info.type == OnnxJavaType.FLOAT) {
                        outputTensor = v
                        break
                    }
                }
                if (outputTensor == null) {
                    throw NoSuchElementException("No float output tensor found in model outputs")
                }
                val buffer = outputTensor.floatBuffer
                val rawOutput = FloatArray(buffer.remaining())
                buffer.get(rawOutput)

                val decoded = decodeYoloOutput(rawOutput, scaleX, scaleY, padX, padY, bitmap.width, bitmap.height)
                TranslationReport.log("INFO", "BubbleDetector", "Detected ${decoded.size} speech bubbles")
                decoded
            } finally {
                outputs.close()
            }
            regions
        } catch (e: Exception) {
            TranslationReport.log("ERROR", "BubbleDetector", "Bubble detection error: ${e.message}", e)
            listOf(DetectedRegion(Rect(0, 0, bitmap.width, bitmap.height), 1f, false))
        } finally {
            tensor.close()
            origTargetSizesTensor?.close()
            scaled.recycle()
        }
    }

    private data class LetterboxResult(
        val bitmap: Bitmap,
        val scaleX: Float,
        val scaleY: Float,
        val padX: Int,
        val padY: Int,
    )

    private fun letterbox(src: Bitmap, size: Int): LetterboxResult {
        val scale = min(size.toFloat() / src.width, size.toFloat() / src.height)
        val newW = (src.width * scale).roundToInt()
        val newH = (src.height * scale).roundToInt()
        val padX = (size - newW) / 2
        val padY = (size - newH) / 2
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.rgb(114, 114, 114))
            val scaled = Bitmap.createScaledBitmap(src, newW, newH, true)
            drawBitmap(scaled, padX.toFloat(), padY.toFloat(), Paint())
        }
        return LetterboxResult(result, scale, scale, padX, padY)
    }

    private fun bitmapToTensor(bitmap: Bitmap, size: Int): OnnxTensor {
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val buf = AiBufferUtils.allocateDirectFloatBuffer(3 * size * size)
        for (c in 0..2) {
            for (px in pixels) {
                buf.put(
                    when (c) {
                        0 -> Color.red(px) / 255f
                        1 -> Color.green(px) / 255f
                        else -> Color.blue(px) / 255f
                    },
                )
            }
        }
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, longArrayOf(1L, 3L, size.toLong(), size.toLong()))
    }

    private fun decodeYoloOutput(
        raw: FloatArray,
        scaleX: Float,
        scaleY: Float,
        padX: Int,
        padY: Int,
        imgW: Int,
        imgH: Int,
    ): List<DetectedRegion> {
        val numValues = 6 // [cx, cy, w, h, classId, conf]
        val numBoxes = raw.size / numValues
        val confThreshold = 0.4f

        val results = mutableListOf<DetectedRegion>()
        for (i in 0 until numBoxes) {
            val offset = i * numValues
            val conf = raw[offset + 5]
            if (conf < confThreshold) continue
            val cx = (raw[offset] - padX) / scaleX
            val cy = (raw[offset + 1] - padY) / scaleY
            val w = raw[offset + 2] / scaleX
            val h = raw[offset + 3] / scaleY
            val classId = raw[offset + 4].roundToInt()
            val rect = Rect(
                max(0, (cx - w / 2).roundToInt()),
                max(0, (cy - h / 2).roundToInt()),
                min(imgW, (cx + w / 2).roundToInt()),
                min(imgH, (cy + h / 2).roundToInt()),
            )
            if (rect.width() < 10 || rect.height() < 10) continue
            // Reject page borders, full-screen panels, and extreme stripes
            val area = rect.width().toLong() * rect.height().toLong()
            if (rect.width() > imgW * 0.65f || rect.height() > imgH * 0.65f) continue
            if (area > (imgW.toLong() * imgH.toLong() * 0.25).toLong()) continue
            if (rect.width() > rect.height() * 3.5f || rect.height() > rect.width() * 3.5f) continue
            results.add(DetectedRegion(rect, conf, classId == 1))
        }

        return nms(results, iouThreshold = 0.45f)
    }

    private fun nms(boxes: List<DetectedRegion>, iouThreshold: Float): List<DetectedRegion> {
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<DetectedRegion>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { iou(best.rect, it.rect) > iouThreshold }
        }
        return kept
    }

    private fun iou(a: Rect, b: Rect): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val inter = ((interRight - interLeft) * (interBottom - interTop)).toFloat()
        val union = (a.width() * a.height() + b.width() * b.height()).toFloat() - inter
        return if (union <= 0f) 0f else inter / union
    }

    override fun close() {
        session?.close()
        session = null
    }
}
