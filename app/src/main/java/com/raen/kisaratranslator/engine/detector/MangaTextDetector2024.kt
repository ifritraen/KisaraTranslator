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
import com.raen.kisaratranslator.data.logger.AppLogger
import com.raen.kisaratranslator.data.model.TranslationModelType
import java.io.Closeable
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * DBNet Manga Text Detector (2024-12-25 release, ~292 MB).
 * Architecture: DBNet ResNet34.
 * Input: [1, 3, H, W], float32 in [-1, 1].
 * Output: db_map [1, 2, H/4, W/4].
 */
class MangaTextDetector2024(
    private val modelManager: TranslationModelManager,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Closeable {

    private var session: OrtSession? = null

    val isReady: Boolean
        get() = modelManager.isModelReady(TranslationModelType.MANGA_DETECTOR_2024)

    private fun ensureSession() {
        if (session != null) return
        val modelFile = modelManager.getModelFile(TranslationModelType.MANGA_DETECTOR_2024)
        if (!modelFile.exists() || modelFile.length() < TranslationModelType.MANGA_DETECTOR_2024.minSize) {
            throw IllegalStateException("Manga Text Detector 2024 model file (detect-20241225.onnx) is missing or incomplete.")
        }
        val opts = AiBufferUtils.createSessionOptions(4)
        try {
            session = env.createSession(modelFile.absolutePath, opts)
        } finally {
            opts.close()
        }
    }

    fun detect(bitmap: Bitmap): List<Rect> {
        if (!isReady) {
            throw IllegalStateException("Manga Text Detector 2024 is not ready. Please download it in Model Manager.")
        }
        ensureSession()
        val sess = session ?: throw IllegalStateException("MangaTextDetector2024 session could not be initialized.")

        // Scale image so longest side is 1024 or 1280 (multiple of 32)
        val maxSide = 1024
        val scale = min(1.0f, maxSide.toFloat() / max(bitmap.width, bitmap.height))
        var targetW = ((bitmap.width * scale) / 32).roundToInt() * 32
        var targetH = ((bitmap.height * scale) / 32).roundToInt() * 32
        targetW = max(32, targetW)
        targetH = max(32, targetH)

        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val numPixels = targetW * targetH
        val pixels = IntArray(numPixels)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

        // Preprocessing: (pixel / 127.5 - 1.0) -> [-1, 1] in NCHW
        val floatBuf = AiBufferUtils.allocateDirectFloatBuffer(3 * numPixels)
        for (c in 0..2) {
            for (i in 0 until numPixels) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> Color.red(p)
                    1 -> Color.green(p)
                    else -> Color.blue(p)
                }
                floatBuf.put((v / 127.5f) - 1.0f)
            }
        }
        floatBuf.rewind()
        scaled.recycle()

        val inputTensor = OnnxTensor.createTensor(
            env,
            floatBuf,
            longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong()),
        )

        val boxes = mutableListOf<Rect>()
        try {
            val inputName = sess.inputNames.firstOrNull() ?: "input"
            val outputs = sess.run(mapOf(inputName to inputTensor))
            try {
                val dbMapTensor = outputs["db_map"]?.get() as? OnnxTensor
                    ?: outputs.get(0) as? OnnxTensor
                    ?: throw IllegalStateException("db_map output not found in detect-20241225.onnx")

                val outInfo = dbMapTensor.info as ai.onnxruntime.TensorInfo
                val outShape = outInfo.shape // [1, 2, mapH, mapW]
                val mapH = outShape[2].toInt()
                val mapW = outShape[3].toInt()
                val outBuf = dbMapTensor.floatBuffer

                val shrinkMapChannel = 0 // Channel 0 is probability map
                val mapStride = mapW
                val channelOffset = shrinkMapChannel * mapH * mapW

                val textThreshold = 0.45f
                val boxThreshold = 0.55f
                val unclipRatio = 1.6f

                // Connected-components clustering on thresholded probability map
                val visited = BooleanArray(mapH * mapW)
                val queue = ArrayDeque<Pair<Int, Int>>()

                val scaleX = bitmap.width.toFloat() / mapW.toFloat()
                val scaleY = bitmap.height.toFloat() / mapH.toFloat()

                for (y in 0 until mapH) {
                    for (x in 0 until mapW) {
                        val mapIdx = y * mapStride + x
                        if (visited[mapIdx]) continue

                        val prob = outBuf.get(channelOffset + mapIdx)
                        if (prob < textThreshold) continue

                        // BFS to find connected component
                        var minX = x
                        var maxX = x
                        var minY = y
                        var maxY = y
                        var sumScore = 0f
                        var count = 0

                        visited[mapIdx] = true
                        queue.add(Pair(x, y))

                        while (queue.isNotEmpty()) {
                            val (cx, cy) = queue.removeFirst()
                            val cIdx = cy * mapStride + cx
                            val cProb = outBuf.get(channelOffset + cIdx)
                            sumScore += cProb
                            count++

                            minX = min(minX, cx)
                            maxX = max(maxX, cx)
                            minY = min(minY, cy)
                            maxY = max(maxY, cy)

                            // 4-neighborhood
                            val neighbors = listOf(
                                Pair(cx - 1, cy),
                                Pair(cx + 1, cy),
                                Pair(cx, cy - 1),
                                Pair(cx, cy + 1),
                            )
                            for ((nx, ny) in neighbors) {
                                if (nx in 0 until mapW && ny in 0 until mapH) {
                                    val nIdx = ny * mapStride + nx
                                    if (!visited[nIdx]) {
                                        val nProb = outBuf.get(channelOffset + nIdx)
                                        if (nProb >= textThreshold) {
                                            visited[nIdx] = true
                                            queue.add(Pair(nx, ny))
                                        }
                                    }
                                }
                            }
                        }

                        val avgScore = sumScore / max(1, count)
                        if (avgScore >= boxThreshold && count >= 6) {
                            // Un-clip expansion
                            val bw = (maxX - minX + 1).toFloat()
                            val bh = (maxY - minY + 1).toFloat()
                            val padX = (bw * (unclipRatio - 1.0f) * 0.5f).roundToInt()
                            val padY = (bh * (unclipRatio - 1.0f) * 0.5f).roundToInt()

                            val unclippedMinX = max(0, minX - padX)
                            val unclippedMaxX = min(mapW - 1, maxX + padX)
                            val unclippedMinY = max(0, minY - padY)
                            val unclippedMaxY = min(mapH - 1, maxY + padY)

                            val origLeft = (unclippedMinX * scaleX).roundToInt().coerceIn(0, bitmap.width - 1)
                            val origTop = (unclippedMinY * scaleY).roundToInt().coerceIn(0, bitmap.height - 1)
                            val origRight = ((unclippedMaxX + 1) * scaleX).roundToInt().coerceIn(origLeft + 1, bitmap.width)
                            val origBottom = ((unclippedMaxY + 1) * scaleY).roundToInt().coerceIn(origTop + 1, bitmap.height)

                            if (origRight - origLeft >= 6 && origBottom - origTop >= 6) {
                                boxes.add(Rect(origLeft, origTop, origRight, origBottom))
                            }
                        }
                    }
                }
            } finally {
                outputs.close()
            }
        } finally {
            inputTensor.close()
        }

        AppLogger.info("MangaTextDetector2024: Detected ${boxes.size} text blocks")
        return boxes
    }

    override fun close() {
        session?.close()
        session = null
    }
}
