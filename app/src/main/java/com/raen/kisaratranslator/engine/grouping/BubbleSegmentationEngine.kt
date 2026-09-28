package com.raen.kisaratranslator.engine.grouping

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Rect
import com.raen.kisaratranslator.core.util.AiBufferUtils
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.logger.AppLogger
import com.raen.kisaratranslator.data.model.TranslationModelType
import java.io.Closeable
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Method 8: YOLO11n-seg Bubble Segmentation Engine.
 *
 * Runs manga109-segmentation-bubble ONNX inference and returns pixel-accurate
 * bubble instance masks as BubbleMask objects compatible with CrunchSplitter
 * and the IoA association pipeline.
 *
 * ONNX format (standard ultralytics YOLO-seg export):
 *   Input  "images"  : [1, 3, 640, 640] float32
 *   Output "output0" : [1, 37, 8400]    — 4 box + 1 conf + 32 mask coeffs
 *   Output "output1" : [1, 32, 160, 160]— prototype mask feature map
 *
 * Export command (run once on PC with ultralytics installed):
 *   yolo export model=best.pt format=onnx imgsz=640 simplify=True opset=12
 *
 * Place the resulting best.onnx in app's files dir under models/bubbleSeg/manga109_seg.onnx
 */
class BubbleSegmentationEngine(
    private val modelManager: TranslationModelManager? = null,
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
        val mgr = modelManager ?: return false
        val modelFile = mgr.getModelFile(TranslationModelType.MANGA109_BUBBLE_SEG)
        return loadModel(modelFile)
    }

    fun loadModel(modelFile: File): Boolean {
        if (loadedPath == modelFile.absolutePath && session != null) return true
        session?.close(); session = null; loadedPath = null
        if (!modelFile.exists() || modelFile.length() < 1_000_000L) return false
        val opts = AiBufferUtils.createSessionOptions(2)
        return try {
            session = env.createSession(modelFile.absolutePath, opts)
            loadedPath = modelFile.absolutePath
            AppLogger.info("[M8] Segmentation model loaded: ${modelFile.name} (${modelFile.length() / 1024}KB)")
            true
        } catch (e: Exception) {
            AppLogger.info("[M8] Model load failed: ${e.message}")
            false
        } finally {
            opts.close()
        }
    }

    /**
     * Runs inference and returns bubble instance masks.
     * Returns empty list if model not ready — caller falls back to Method 7 flood-fill.
     */
    fun detectMasks(
        bitmap: Bitmap,
        confThresh: Float = 0.35f,
        iouThresh: Float  = 0.45f,
    ): List<BubbleMaskExtractor.BubbleMask> {
        ensureSession()
        val sess = session ?: return emptyList()
        val inputSize = 1024
        val origW = bitmap.width; val origH = bitmap.height

        // ── 1. Preprocess ─────────────────────────────────────────────────────
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
        val buf = java.nio.FloatBuffer.wrap(raw)
        val inTensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))

        val results = mutableListOf<BubbleMaskExtractor.BubbleMask>()
        try {
            val out = sess.run(mapOf(sess.inputNames.first() to inTensor))

            val o0 = out.get("output0").orElse(null) as? OnnxTensor ?: run { out.close(); return emptyList() }
            val o1 = out.get("output1").orElse(null) as? OnnxTensor ?: run { out.close(); return emptyList() }

            // ── 2. Parse output shapes dynamically ──────
            val NA = o0.info.shape[2].toInt()
            val NM = o1.info.shape[1].toInt()
            val PH = o1.info.shape[2].toInt()
            val PW = o1.info.shape[3].toInt()
            val o0buf = o0.floatBuffer
            val o1buf = o1.floatBuffer

            // Read proto into FloatArray for fast indexed access
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

            // ── 3. NMS ────────────────────────────────────────────────────────
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
                    val iou = boxIou(
                        a.cx - a.bw/2, a.cy - a.bh/2, a.cx + a.bw/2, a.cy + a.bh/2,
                        b.cx - b.bw/2, b.cy - b.bh/2, b.cx + b.bw/2, b.cy + b.bh/2,
                    )
                    val centerDx = a.cx - b.cx
                    val centerDy = a.cy - b.cy
                    val centerDist = sqrt(centerDx * centerDx + centerDy * centerDy)
                    val minDim = min(min(a.bw, a.bh), min(b.bw, b.bh))
                    val effectiveIouThresh = if (centerDist > minDim * 0.45f) 0.75f else iouThresh
                    if (iou > effectiveIouThresh) suppress[j] = true
                }
            }

            AppLogger.info("[M8] Detections: ${rawDets.size} raw → ${kept.size} post-NMS")

            // ── 4. Decode masks ───────────────────────────────────────────────
            val scaleX = origW.toFloat() / inputSize
            val scaleY = origH.toFloat() / inputSize

            for (det in kept) {
                // Box in original coords
                val x1 = ((det.cx - det.bw/2) * scaleX).toInt().coerceIn(0, origW - 1)
                val y1 = ((det.cy - det.bh/2) * scaleY).toInt().coerceIn(0, origH - 1)
                val x2 = ((det.cx + det.bw/2) * scaleX).toInt().coerceIn(x1 + 1, origW)
                val y2 = ((det.cy + det.bh/2) * scaleY).toInt().coerceIn(y1 + 1, origH)
                val bW = x2 - x1; val bH = y2 - y1
                if (bW < 8 || bH < 8) continue

                // Proto crop in 160-space
                val px1 = ((det.cx - det.bw/2) / inputSize * PW).toInt().coerceIn(0, PW - 1)
                val py1 = ((det.cy - det.bh/2) / inputSize * PH).toInt().coerceIn(0, PH - 1)
                val px2 = ((det.cx + det.bw/2) / inputSize * PW).toInt().coerceIn(px1 + 1, PW)
                val py2 = ((det.cy + det.bh/2) / inputSize * PH).toInt().coerceIn(py1 + 1, PH)
                val cpW = px2 - px1; val cpH = py2 - py1

                // Compute mask in proto-crop space
                val protoMask = BooleanArray(cpW * cpH)
                var protoArea = 0
                for (py in 0 until cpH) {
                    for (px in 0 until cpW) {
                        val pIdx = (py + py1) * PW + (px + px1)
                        var dot = 0f
                        for (k in 0 until NM) dot += det.coeffs[k] * proto[k * PH * PW + pIdx]
                        if (sigmoid(dot) >= 0.5f) { protoMask[py * cpW + px] = true; protoArea++ }
                    }
                }
                if (protoArea < 10) continue

                // Scale proto-crop mask to box pixel size (nearest-neighbour)
                val mask = scaleMask(protoMask, cpW, cpH, bW, bH)
                val fillArea = mask.count { it }
                if (fillArea < 50) continue

                results.add(BubbleMaskExtractor.BubbleMask(mask, Rect(x1, y1, x2, y2), bW, bH, fillArea))
            }

            out.close()
        } catch (e: Exception) {
            AppLogger.info("[M8] Inference error: ${e.message}")
        } finally {
            inTensor.close()
        }

        AppLogger.info("[M8] Produced ${results.size} segmentation masks")
        return results
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    private fun boxIou(ax1: Float, ay1: Float, ax2: Float, ay2: Float,
                       bx1: Float, by1: Float, bx2: Float, by2: Float): Float {
        val iL = max(ax1, bx1); val iT = max(ay1, by1)
        val iR = min(ax2, bx2); val iB = min(ay2, by2)
        if (iR <= iL || iB <= iT) return 0f
        val inter = (iR - iL) * (iB - iT)
        val aA = (ax2 - ax1) * (ay2 - ay1)
        val bA = (bx2 - bx1) * (by2 - by1)
        return inter / (aA + bA - inter)
    }

    /** Nearest-neighbour scale of a boolean mask from [srcW×srcH] to [dstW×dstH]. */
    private fun scaleMask(src: BooleanArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): BooleanArray {
        val dst = BooleanArray(dstW * dstH)
        for (dy in 0 until dstH) {
            val sy = (dy.toFloat() / dstH * srcH).toInt().coerceIn(0, srcH - 1)
            for (dx in 0 until dstW) {
                val sx = (dx.toFloat() / dstW * srcW).toInt().coerceIn(0, srcW - 1)
                dst[dy * dstW + dx] = src[sy * srcW + sx]
            }
        }
        return dst
    }

    override fun close() {
        session?.close(); session = null; loadedPath = null
    }
}
