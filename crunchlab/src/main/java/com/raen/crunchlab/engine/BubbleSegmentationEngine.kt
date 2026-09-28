package com.raen.crunchlab.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.raen.crunchlab.data.ModelDownloader
import java.io.Closeable
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Runs Manga109 YOLO11n-seg ONNX inference and returns pixel-accurate
 * bubble instance masks as BubbleMask objects.
 */
class BubbleSegmentationEngine(
    private val modelDownloader: ModelDownloader,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Closeable {

    private var session: OrtSession? = null
    private var loadedPath: String? = null

    val isReady: Boolean
        get() {
            ensureSession()
            return session != null
        }

    fun ensureSession(): Boolean {
        if (session != null) return true
        val modelFile = modelDownloader.getModelFile()
        return loadModel(modelFile)
    }

    fun loadModel(modelFile: File): Boolean {
        if (loadedPath == modelFile.absolutePath && session != null) return true
        session?.close(); session = null; loadedPath = null
        if (!modelFile.exists() || modelFile.length() < ModelDownloader.MIN_SIZE) return false

        val opts = com.raen.crunchlab.util.AiBufferUtils.createSessionOptions(2)

        return try {
            session = env.createSession(modelFile.absolutePath, opts)
            loadedPath = modelFile.absolutePath
            Log.i("CrunchLab", "Loaded segmentation model: ${modelFile.name} (${modelFile.length() / 1024}KB)")
            true
        } catch (e: Exception) {
            Log.e("CrunchLab", "Model load failed: ${e.message}")
            false
        } finally {
            opts.close()
        }
    }

    /**
     * Runs inference on input bitmap and returns binary bubble masks.
     */
    fun detectMasks(
        bitmap: Bitmap,
        confThresh: Float = 0.35f,
        iouThresh: Float  = 0.45f,
    ): List<BubbleMask> {
        ensureSession()
        val sess = session ?: return emptyList()
        val inputSize = 1024
        val origW = bitmap.width
        val origH = bitmap.height

        // 1. Preprocess: 1024x1024 Planar RGB FloatArray
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val numPx = inputSize * inputSize
        val pixels = IntArray(numPx)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        scaled.recycle()

        val raw = FloatArray(3 * numPx)
        val inv = 1f / 255f
        for (i in 0 until numPx) {
            val p = pixels[i]
            raw[i]             = ((p ushr 16) and 0xFF) * inv
            raw[numPx + i]     = ((p ushr 8)  and 0xFF) * inv
            raw[numPx * 2 + i] = (p           and 0xFF) * inv
        }
        val buf = com.raen.crunchlab.util.AiBufferUtils.allocateDirectFloatBuffer(3 * numPx)
        buf.put(raw)
        buf.rewind()
        val inTensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))

        val results = mutableListOf<BubbleMask>()
        try {
            val out = sess.run(mapOf(sess.inputNames.first() to inTensor))

            val o0 = out.get("output0").orElse(null) as? OnnxTensor ?: run { out.close(); return emptyList() }
            val o1 = out.get("output1").orElse(null) as? OnnxTensor ?: run { out.close(); return emptyList() }

            // 2. Parse shapes dynamically
            val NA = o0.info.shape[2].toInt()
            val NM = o1.info.shape[1].toInt()
            val PH = o1.info.shape[2].toInt()
            val PW = o1.info.shape[3].toInt()
            val o0buf = o0.floatBuffer
            val o1buf = o1.floatBuffer

            val protoLen = NM * PH * PW
            val proto = FloatArray(protoLen)
            o1buf.get(proto)

            // Collect raw detections above threshold
            data class RawDet(
                val cx: Float, val cy: Float, val bw: Float, val bh: Float,
                val conf: Float, val coeffs: FloatArray,
            )
            val rawDets = mutableListOf<RawDet>()
            for (a in 0 until NA) {
                val conf = o0buf.get(4 * NA + a)
                if (conf < confThresh) continue
                val cx = o0buf.get(0 * NA + a)
                val cy = o0buf.get(1 * NA + a)
                val bw = o0buf.get(2 * NA + a)
                val bh = o0buf.get(3 * NA + a)
                val c  = FloatArray(NM) { k -> o0buf.get((5 + k) * NA + a) }
                rawDets.add(RawDet(cx, cy, bw, bh, conf, c))
            }

            // 3. NMS
            val sorted = rawDets.sortedByDescending { it.conf }
            val suppress = BooleanArray(sorted.size)
            val kept = mutableListOf<RawDet>()
            for (i in sorted.indices) {
                if (suppress[i]) continue
                kept.add(sorted[i])
                val a = sorted[i]
                for (j in i + 1 until sorted.size) {
                    if (suppress[j]) continue
                    val b = sorted[j]
                    val aArea = a.bw * a.bh
                    val bArea = b.bw * b.bh
                    val ix1 = max(a.cx - a.bw/2, b.cx - b.bw/2)
                    val iy1 = max(a.cy - a.bh/2, b.cy - b.bh/2)
                    val ix2 = min(a.cx + a.bw/2, b.cx + b.bw/2)
                    val iy2 = min(a.cy + a.bh/2, b.cy + b.bh/2)
                    val inter = max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
                    val union = aArea + bArea - inter
                    val iou = if (union > 0f) inter / union else 0f
                    val smallerArea = min(aArea, bArea)
                    val containment = if (smallerArea > 0f) inter / smallerArea else 0f
                    if (iou > iouThresh || containment > 0.60f) suppress[j] = true
                }
            }

            // 4. Decode masks
            val scaleX = origW.toFloat() / inputSize
            val scaleY = origH.toFloat() / inputSize

            for (det in kept) {
                // Tight margin around detected bounding box (0.5% + 1px) to prevent capturing outside panel lines
                val padX = (det.bw * 0.005f + 1f) * scaleX
                val padY = (det.bh * 0.005f + 1f) * scaleY

                val x1 = ((det.cx - det.bw/2) * scaleX - padX).toInt().coerceIn(0, origW - 1)
                val y1 = ((det.cy - det.bh/2) * scaleY - padY).toInt().coerceIn(0, origH - 1)
                val x2 = ((det.cx + det.bw/2) * scaleX + padX).toInt().coerceIn(x1 + 1, origW)
                val y2 = ((det.cy + det.bh/2) * scaleY + padY).toInt().coerceIn(y1 + 1, origH)
                val bW = x2 - x1
                val bH = y2 - y1
                if (bW < 8 || bH < 8) continue

                // Proto crop in 160-space matching the padded box
                val px1 = (x1.toFloat() / origW * PW).toInt().coerceIn(0, PW - 1)
                val py1 = (y1.toFloat() / origH * PH).toInt().coerceIn(0, PH - 1)
                val px2 = ((x2.toFloat() / origW * PW).toInt() + 1).coerceIn(px1 + 1, PW)
                val py2 = ((y2.toFloat() / origH * PH).toInt() + 1).coerceIn(py1 + 1, PH)
                val cpW = px2 - px1
                val cpH = py2 - py1

                val protoMask = BooleanArray(cpW * cpH)
                var protoArea = 0
                for (py in 0 until cpH) {
                    for (px in 0 until cpW) {
                        val pIdx = (py + py1) * PW + (px + px1)
                        var dot = 0f
                        for (k in 0 until NM) dot += det.coeffs[k] * proto[k * PH * PW + pIdx]
                        if (sigmoid(dot) >= 0.45f) { protoMask[py * cpW + px] = true; protoArea++ }
                    }
                }
                if (protoArea < 10) continue

                val mask = scaleMask(protoMask, cpW, cpH, bW, bH)
                val fillArea = mask.count { it }
                if (fillArea < 50) continue

                results.add(BubbleMask(Rect(x1, y1, x2, y2), mask, bW, bH, fillArea))
            }

            out.close()
        } catch (e: Exception) {
            Log.e("CrunchLab", "Inference error: ${e.message}")
        } finally {
            inTensor.close()
        }

        return results
    }

    private fun scaleMask(src: BooleanArray, sw: Int, sh: Int, dw: Int, dh: Int): BooleanArray {
        val dst = BooleanArray(dw * dh)
        val xRatio = sw.toFloat() / dw
        val yRatio = sh.toFloat() / dh
        for (dy in 0 until dh) {
            val sy = (dy * yRatio).toInt().coerceIn(0, sh - 1)
            val dRow = dy * dw
            val sRow = sy * sw
            for (dx in 0 until dw) {
                val sx = (dx * xRatio).toInt().coerceIn(0, sw - 1)
                dst[dRow + dx] = src[sRow + sx]
            }
        }
        return dst
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    private fun boxIou(ax1: Float, ay1: Float, ax2: Float, ay2: Float,
                       bx1: Float, by1: Float, bx2: Float, by2: Float): Float {
        val ix1 = max(ax1, bx1); val iy1 = max(ay1, by1)
        val ix2 = min(ax2, bx2); val iy2 = min(ay2, by2)
        val iw = max(0f, ix2 - ix1); val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        val aArea = (ax2 - ax1) * (ay2 - ay1)
        val bArea = (bx2 - bx1) * (by2 - by1)
        val union = aArea + bArea - inter
        return if (union > 0f) inter / union else 0f
    }

    override fun close() {
        session?.close()
        session = null
    }
}
