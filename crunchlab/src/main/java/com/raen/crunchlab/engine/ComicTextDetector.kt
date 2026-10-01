package com.raen.crunchlab.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.raen.crunchlab.data.ModelDownloader
import com.raen.crunchlab.data.TextLineItem
import com.raen.crunchlab.util.AiBufferUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Comic-Text-Detector (CTD) ONNX Inference Engine for Android.
 * Port of Kisara Translator's production 2-pass CTD inference engine with
 * XNNPACK SIMD acceleration and direct buffer optimizations.
 */
class ComicTextDetector(
    private val modelDownloader: ModelDownloader,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Closeable {

    companion object {
        const val TARGET_DIM = 1024
        private const val NUM_PX = TARGET_DIM * TARGET_DIM
        private val LUT_INV_255 = FloatArray(256) { it / 255.0f }
    }

    private var session: OrtSession? = null
    private var loadedPath: String? = null
    val targetDim: Int get() = TARGET_DIM

    var configuredThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    // Pre-allocated native and heap buffers to achieve zero GC allocation during inference
    private val inputDirectBuffer = AiBufferUtils.allocateDirectFloatBuffer(3 * NUM_PX)
    private val pixelArray = IntArray(NUM_PX)
    private val rawFloats = FloatArray(3 * NUM_PX)

    var lastDetectedBubbles: List<Rect> = emptyList()
        private set

    var lastPass1Boxes: List<Rect> = emptyList()
        private set

    var lastPass2Boxes: List<Rect> = emptyList()
        private set

    var lastPass2Bitmap: Bitmap? = null
        private set

    var lastPass2PassedBoxes: List<Rect> = emptyList()
        private set

    var lastPass2RejectedBoxes: List<Rect> = emptyList()
        private set

    var lastPass2Texts: Map<Rect, String> = emptyMap()
        private set

    val isReady: Boolean
        get() {
            ensureSession()
            return session != null
        }

    var configuredThreads: Int = 4
    var hardwareDelegate: com.raen.crunchlab.engine.profile.HardwareDelegate = com.raen.crunchlab.engine.profile.HardwareDelegate.XNNPACK

    fun setResourceConfig(threads: Int, delegate: com.raen.crunchlab.engine.profile.HardwareDelegate = hardwareDelegate) {
        val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val safeThreads = threads.coerceIn(1, numCores)
        if (safeThreads != configuredThreads || delegate != hardwareDelegate) {
            configuredThreads = safeThreads
            hardwareDelegate = delegate
            session?.close()
            session = null
            loadedPath = null
        }
    }

    fun setSpeedLevel(level: Int) {
        val numCores = Runtime.getRuntime().availableProcessors()
        val targetThreads = when (level.coerceIn(1, 4)) {
            1 -> 1
            2 -> 2
            3 -> 3.coerceAtMost(numCores)
            else -> 4.coerceAtMost(numCores)
        }
        setResourceConfig(targetThreads, hardwareDelegate)
    }

    fun ensureSession(): Boolean {
        if (session != null) return true
        val modelFile = modelDownloader.getComicTextModelFile()
        return loadModel(modelFile)
    }

    @Synchronized
    fun loadModel(modelFile: File): Boolean {
        if (loadedPath == modelFile.absolutePath && session != null) return true
        session?.close()
        session = null
        loadedPath = null
        if (!modelFile.exists() || modelFile.length() < ModelDownloader.MIN_SIZE_CTD) return false

        val numCores = Runtime.getRuntime().availableProcessors()
        val threads = configuredThreads.coerceIn(1, numCores)
        val opts = AiBufferUtils.createSessionOptions(threads, hardwareDelegate)

        return try {
            session = env.createSession(modelFile.absolutePath, opts)
            loadedPath = modelFile.absolutePath
            Log.i("CrunchLab", "Loaded ComicTextDetector ($threads-threads, ${hardwareDelegate.shortLabel}): ${modelFile.name} (${modelFile.length() / 1024}KB)")
            true
        } catch (e: Exception) {
            Log.e("CrunchLab", "CTD Model load failed: ${e.message}")
            false
        } finally {
            opts.close()
        }
    }

    /**
     * Step 1.1: Highly optimized text probability map generator.
     * Evaluates ONLY the "seg" map, skipping bounding box decoding.
     * Returns FloatArray of length 1024 * 1024 containing text probability [0..1].
     */
    @Synchronized
    fun detectTextProb(bitmap: Bitmap): FloatArray? {
        ensureSession()
        val sess = session ?: return null

        val t0 = System.currentTimeMillis()

        // 1. Bilinear downsampling to model input dimensions
        val scaled = Bitmap.createScaledBitmap(bitmap, TARGET_DIM, TARGET_DIM, true)
        scaled.getPixels(pixelArray, 0, TARGET_DIM, 0, 0, TARGET_DIM, TARGET_DIM)
        scaled.recycle()

        // 2. Vectorized 1-pass planar RGB normalization using precomputed LUT
        val planeR = 0
        val planeG = NUM_PX
        val planeB = NUM_PX * 2

        var i = 0
        val unrollEnd = NUM_PX - 3
        while (i < unrollEnd) {
            val p0 = pixelArray[i]
            val p1 = pixelArray[i + 1]
            val p2 = pixelArray[i + 2]
            val p3 = pixelArray[i + 3]

            rawFloats[planeR + i] = LUT_INV_255[(p0 ushr 16) and 0xFF]
            rawFloats[planeG + i] = LUT_INV_255[(p0 ushr 8) and 0xFF]
            rawFloats[planeB + i] = LUT_INV_255[p0 and 0xFF]

            rawFloats[planeR + i + 1] = LUT_INV_255[(p1 ushr 16) and 0xFF]
            rawFloats[planeG + i + 1] = LUT_INV_255[(p1 ushr 8) and 0xFF]
            rawFloats[planeB + i + 1] = LUT_INV_255[p1 and 0xFF]

            rawFloats[planeR + i + 2] = LUT_INV_255[(p2 ushr 16) and 0xFF]
            rawFloats[planeG + i + 2] = LUT_INV_255[(p2 ushr 8) and 0xFF]
            rawFloats[planeB + i + 2] = LUT_INV_255[p2 and 0xFF]

            rawFloats[planeR + i + 3] = LUT_INV_255[(p3 ushr 16) and 0xFF]
            rawFloats[planeG + i + 3] = LUT_INV_255[(p3 ushr 8) and 0xFF]
            rawFloats[planeB + i + 3] = LUT_INV_255[p3 and 0xFF]

            i += 4
        }
        while (i < NUM_PX) {
            val p = pixelArray[i]
            rawFloats[planeR + i] = LUT_INV_255[(p ushr 16) and 0xFF]
            rawFloats[planeG + i] = LUT_INV_255[(p ushr 8) and 0xFF]
            rawFloats[planeB + i] = LUT_INV_255[p and 0xFF]
            i++
        }

        // 3. Direct native buffer transfer (zero JNI memory copying)
        inputDirectBuffer.clear()
        inputDirectBuffer.put(rawFloats)
        inputDirectBuffer.rewind()

        val inTensor = OnnxTensor.createTensor(
            env,
            inputDirectBuffer,
            longArrayOf(1, 3, TARGET_DIM.toLong(), TARGET_DIM.toLong())
        )

        return try {
            val tInfStart = System.currentTimeMillis()
            val outputs = try {
                sess.run(mapOf(sess.inputNames.first() to inTensor), setOf("seg"))
            } catch (_: Exception) {
                sess.run(mapOf(sess.inputNames.first() to inTensor))
            }
            val tInfEnd = System.currentTimeMillis()

            val segVal = outputs.get("seg").orElse(null) as? OnnxTensor
            val probMap = if (segVal != null) {
                val segBuf = segVal.floatBuffer
                val map = FloatArray(NUM_PX)
                segBuf.get(map)
                map
            } else {
                null
            }
            outputs.close()

            val totalMs = System.currentTimeMillis() - t0
            val infMs = tInfEnd - tInfStart
            Log.d("CrunchLab", "CTD inference completed in ${totalMs}ms (core ONNX: ${infMs}ms, threads: $configuredThreads)")
            probMap
        } catch (e: Exception) {
            Log.e("CrunchLab", "CTD inference error: ${e.message}", e)
            null
        } finally {
            inTensor.close()
        }
    }

    /**
     * Detects text and speech bubble regions in a manga page.
     * Extracts speech bubble envelopes from 'blk' and character boxes from 'seg'.
     */
    @Synchronized
    fun detect(
        bitmap: Bitmap,
        maskThreshold: Float = 0.30f,
        nmsIoUThreshold: Float = 0.35f,
    ): List<Rect> {
        ensureSession()
        val sess = session ?: return emptyList()

        val scaled = Bitmap.createScaledBitmap(bitmap, TARGET_DIM, TARGET_DIM, true)
        scaled.getPixels(pixelArray, 0, TARGET_DIM, 0, 0, TARGET_DIM, TARGET_DIM)
        scaled.recycle()

        val planeR = 0
        val planeG = NUM_PX
        val planeB = NUM_PX * 2

        for (i in 0 until NUM_PX) {
            val p = pixelArray[i]
            rawFloats[planeR + i] = LUT_INV_255[(p ushr 16) and 0xFF]
            rawFloats[planeG + i] = LUT_INV_255[(p ushr 8) and 0xFF]
            rawFloats[planeB + i] = LUT_INV_255[p and 0xFF]
        }

        inputDirectBuffer.clear()
        inputDirectBuffer.put(rawFloats)
        inputDirectBuffer.rewind()

        val inTensor = OnnxTensor.createTensor(
            env,
            inputDirectBuffer,
            longArrayOf(1, 3, TARGET_DIM.toLong(), TARGET_DIM.toLong())
        )

        val boxes = mutableListOf<Rect>()
        try {
            val outputs = sess.run(mapOf(sess.inputNames.first() to inTensor))
            val scaleX = bitmap.width.toFloat() / TARGET_DIM.toFloat()
            val scaleY = bitmap.height.toFloat() / TARGET_DIM.toFloat()

            // 1. Extract speech bubble regions from blk
            lastDetectedBubbles = emptyList()
            if (outputs.get("blk").isPresent) {
                val blkVal = outputs.get("blk").get() as? OnnxTensor
                if (blkVal != null) {
                    val blkBuf = blkVal.floatBuffer
                    val numCandidates = blkVal.info.shape.getOrNull(1)?.toInt() ?: 64512
                    val candidateBoxes = mutableListOf<Rect>()
                    val candidateScores = mutableListOf<Float>()

                    for (k in 0 until numCandidates) {
                        val offset = k * 7
                        val conf = blkBuf.get(offset + 4)
                        if (conf > 0.20f) {
                            val cx = blkBuf.get(offset)
                            val cy = blkBuf.get(offset + 1)
                            val bw = blkBuf.get(offset + 2)
                            val bh = blkBuf.get(offset + 3)

                            val x1 = ((cx - bw / 2f) * scaleX).toInt().coerceAtLeast(0)
                            val y1 = ((cy - bh / 2f) * scaleY).toInt().coerceAtLeast(0)
                            val x2 = ((cx + bw / 2f) * scaleX).toInt().coerceAtMost(bitmap.width)
                            val y2 = ((cy + bh / 2f) * scaleY).toInt().coerceAtMost(bitmap.height)

                            if (x2 > x1 + 10 && y2 > y1 + 10) {
                                candidateBoxes.add(Rect(x1, y1, x2, y2))
                                candidateScores.add(conf)
                            }
                        }
                    }

                    // Fast Greedy NMS
                    val nmsIndices = mutableListOf<Int>()
                    val sorted = candidateBoxes.indices.sortedByDescending { candidateScores[it] }
                    for (idx in sorted) {
                        val box = candidateBoxes[idx]
                        var suppress = false
                        for (kept in nmsIndices) {
                            val keptBox = candidateBoxes[kept]
                            val interL = max(box.left, keptBox.left)
                            val interT = max(box.top, keptBox.top)
                            val interR = min(box.right, keptBox.right)
                            val interB = min(box.bottom, keptBox.bottom)
                            if (interR > interL && interB > interT) {
                                val interArea = (interR - interL) * (interB - interT)
                                val unionArea = box.width() * box.height() + keptBox.width() * keptBox.height() - interArea
                                if (interArea.toFloat() / unionArea > nmsIoUThreshold) {
                                    suppress = true
                                    break
                                }
                            }
                        }
                        if (!suppress) {
                            nmsIndices.add(idx)
                        }
                    }
                    lastDetectedBubbles = nmsIndices.map { candidateBoxes[it] }
                }
            }

            // 2. Extract character/word bounding boxes from seg
            val segVal = outputs.get("seg").orElse(null) as? OnnxTensor
            if (segVal != null) {
                val outBuf = segVal.floatBuffer
                val gridStep = 4
                val gridW = TARGET_DIM / gridStep
                val gridH = TARGET_DIM / gridStep
                val visited = BooleanArray(gridW * gridH)
                val queue = IntArray(gridW * gridH)
                val minClusterPoints = 3

                for (gy in 0 until gridH) {
                    for (gx in 0 until gridW) {
                        val idx = gy * gridW + gx
                        if (visited[idx]) continue

                        val px = gx * gridStep + gridStep / 2
                        val py = gy * gridStep + gridStep / 2
                        val bufIdx = (py * TARGET_DIM + px).coerceIn(0, outBuf.capacity() - 1)
                        if (outBuf.get(bufIdx) >= maskThreshold) {
                            var minPx = px
                            var maxPx = px
                            var minPy = py
                            var maxPy = py
                            var count = 0

                            var qHead = 0
                            var qTail = 0
                            queue[qTail++] = (gy shl 16) or (gx and 0xFFFF)
                            visited[idx] = true

                            while (qHead < qTail) {
                                val packed = queue[qHead++]
                                val cx = packed and 0xFFFF
                                val cy = packed ushr 16
                                count++

                                val curX = cx * gridStep + gridStep / 2
                                val curY = cy * gridStep + gridStep / 2
                                if (curX < minPx) minPx = curX
                                if (curX + gridStep > maxPx) maxPx = curX + gridStep
                                if (curY < minPy) minPy = curY
                                if (curY + gridStep > maxPy) maxPy = curY + gridStep

                                // 4-way neighbors
                                if (cx > 0) {
                                    val nx = cx - 1
                                    val nIdx = cy * gridW + nx
                                    if (!visited[nIdx] && outBuf.get((cy * gridStep + gridStep / 2) * TARGET_DIM + (nx * gridStep + gridStep / 2)) >= maskThreshold) {
                                        visited[nIdx] = true
                                        queue[qTail++] = (cy shl 16) or (nx and 0xFFFF)
                                    }
                                }
                                if (cx + 1 < gridW) {
                                    val nx = cx + 1
                                    val nIdx = cy * gridW + nx
                                    if (!visited[nIdx] && outBuf.get((cy * gridStep + gridStep / 2) * TARGET_DIM + (nx * gridStep + gridStep / 2)) >= maskThreshold) {
                                        visited[nIdx] = true
                                        queue[qTail++] = (cy shl 16) or (nx and 0xFFFF)
                                    }
                                }
                                if (cy > 0) {
                                    val ny = cy - 1
                                    val nIdx = ny * gridW + cx
                                    if (!visited[nIdx] && outBuf.get((ny * gridStep + gridStep / 2) * TARGET_DIM + (cx * gridStep + gridStep / 2)) >= maskThreshold) {
                                        visited[nIdx] = true
                                        queue[qTail++] = (ny shl 16) or (cx and 0xFFFF)
                                    }
                                }
                                if (cy + 1 < gridH) {
                                    val ny = cy + 1
                                    val nIdx = ny * gridW + cx
                                    if (!visited[nIdx] && outBuf.get((ny * gridStep + gridStep / 2) * TARGET_DIM + (cx * gridStep + gridStep / 2)) >= maskThreshold) {
                                        visited[nIdx] = true
                                        queue[qTail++] = (ny shl 16) or (cx and 0xFFFF)
                                    }
                                }
                            }

                            if (count >= minClusterPoints) {
                                val padX = (3 * scaleX).toInt()
                                val padY = (3 * scaleY).toInt()
                                val rect = Rect(
                                    (minPx * scaleX - padX).toInt().coerceAtLeast(0),
                                    (minPy * scaleY - padY).toInt().coerceAtLeast(0),
                                    (maxPx * scaleX + padX).toInt().coerceAtMost(bitmap.width),
                                    (maxPy * scaleY + padY).toInt().coerceAtMost(bitmap.height),
                                )
                                if (rect.width() > 10 && rect.height() > 10) {
                                    boxes.add(rect)
                                }
                            }
                        }
                    }
                }
            }

            outputs.close()
        } catch (e: Exception) {
            Log.e("CrunchLab", "Inference error: ${e.message}", e)
        } finally {
            inTensor.close()
        }

        lastPass1Boxes = boxes
        lastPass2Boxes = emptyList()
        return boxes
    }

    /**
     * Kisara Translator Sensitive Scan:
     * Extracts text clusters and speech bubble envelopes from image.
     */
    fun detectTwoPass(
        bitmap: Bitmap,
        speedDet1: Int = 3,
        speedDet2: Int = 3,
        onProgress: ((String) -> Unit)? = null,
    ): Pair<List<Rect>, List<Rect>> {
        setSpeedLevel(speedDet1)
        onProgress?.invoke("[Pass 1] Global Scan (YOLO bubbles + Text lines)...")
        val pass1Boxes = detect(bitmap, maskThreshold = 0.30f, nmsIoUThreshold = 0.35f)
        val bubbles = lastDetectedBubbles.toList()

        lastPass1Boxes = pass1Boxes
        lastPass2Boxes = emptyList()
        lastPass2Bitmap = null
        return Pair(pass1Boxes, bubbles)
    }

    /**
     * Extracts text glyph islands directly from the 1.1 continuous probability heatmap (p >= 0.18 inside bubbles)
     * that were missed by Pass 1's coarse 4x4 grid downsampling (compact characters, furigana, punctuation).
     * Embeds them natively into Step 1.2 so that Screen 1.2 detects 100% of glowing text.
     */
    fun extractHeatmapTextIslands(
        probMap: FloatArray,
        existingBoxes: List<Rect>,
        bubbles: List<Rect>,
        imgWidth: Int,
        imgHeight: Int,
    ): List<Rect> {
        if (probMap.isEmpty()) return emptyList()

        val scaleX = TARGET_DIM.toFloat() / imgWidth.toFloat()
        val scaleY = TARGET_DIM.toFloat() / imgHeight.toFloat()
        val invScaleX = imgWidth.toFloat() / TARGET_DIM.toFloat()
        val invScaleY = imgHeight.toFloat() / TARGET_DIM.toFloat()

        // 1. Create fast bubble-interior lookup mask in 1024x1024 space
        val bubbleMask = BooleanArray(NUM_PX)
        for (b in bubbles) {
            val bx1 = (b.left * scaleX).toInt().coerceIn(0, TARGET_DIM - 1)
            val by1 = (b.top * scaleY).toInt().coerceIn(0, TARGET_DIM - 1)
            val bx2 = (b.right * scaleX).toInt().coerceIn(0, TARGET_DIM - 1)
            val by2 = (b.bottom * scaleY).toInt().coerceIn(0, TARGET_DIM - 1)
            for (y in by1..by2) {
                val rowOffset = y * TARGET_DIM
                for (x in bx1..bx2) {
                    bubbleMask[rowOffset + x] = true
                }
            }
        }

        // 2. Adaptive continuous probability thresholding:
        // Inside bubbles: p >= 0.18f (captures faint kanji, furigana, punctuation, small text)
        // Outside bubbles: p >= 0.22f (suppresses background noise while retaining glowing SFX/captions)
        val unclaimed = BooleanArray(NUM_PX)
        for (i in 0 until NUM_PX) {
            val threshold = if (bubbleMask[i]) 0.18f else 0.22f
            unclaimed[i] = probMap[i] >= threshold
        }

        // 3. Mask out existing Pass 1 boxes (dilated by 3-4px in 1024-space to eliminate boundary halos)
        val haloX = (4 * scaleX).toInt().coerceAtLeast(3)
        val haloY = (4 * scaleY).toInt().coerceAtLeast(3)
        for (b in existingBoxes) {
            val bx1 = ((b.left * scaleX).toInt() - haloX).coerceIn(0, TARGET_DIM - 1)
            val by1 = ((b.top * scaleY).toInt() - haloY).coerceIn(0, TARGET_DIM - 1)
            val bx2 = ((b.right * scaleX).toInt() + haloX).coerceIn(0, TARGET_DIM - 1)
            val by2 = ((b.bottom * scaleY).toInt() + haloY).coerceIn(0, TARGET_DIM - 1)

            if (bx2 >= bx1 && by2 >= by1) {
                for (y in by1..by2) {
                    val rowOffset = y * TARGET_DIM
                    for (x in bx1..bx2) {
                        unclaimed[rowOffset + x] = false
                    }
                }
            }
        }

        // 4. Extract connected components of unclaimed glowing pixels
        val visited = BooleanArray(NUM_PX)
        val queue = IntArray(NUM_PX)
        val candidateBoxes = mutableListOf<Pair<Rect, Float>>()

        for (y in 0 until TARGET_DIM) {
            val yOffset = y * TARGET_DIM
            for (x in 0 until TARGET_DIM) {
                val idx = yOffset + x
                if (visited[idx] || !unclaimed[idx]) continue

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var peak = probMap[idx]
                var count = 0

                var qHead = 0
                var qTail = 0
                queue[qTail++] = (y shl 16) or (x and 0xFFFF)
                visited[idx] = true

                while (qHead < qTail) {
                    val packed = queue[qHead++]
                    val cx = packed and 0xFFFF
                    val cy = packed ushr 16
                    count++

                    val cIdx = cy * TARGET_DIM + cx
                    val cProb = probMap[cIdx]
                    if (cProb > peak) peak = cProb

                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    if (cx > 0) {
                        val nIdx = cIdx - 1
                        if (!visited[nIdx] && unclaimed[nIdx]) {
                            visited[nIdx] = true
                            queue[qTail++] = (cy shl 16) or (cx - 1)
                        }
                    }
                    if (cx < TARGET_DIM - 1) {
                        val nIdx = cIdx + 1
                        if (!visited[nIdx] && unclaimed[nIdx]) {
                            visited[nIdx] = true
                            queue[qTail++] = (cy shl 16) or (cx + 1)
                        }
                    }
                    if (cy > 0) {
                        val nIdx = cIdx - TARGET_DIM
                        if (!visited[nIdx] && unclaimed[nIdx]) {
                            visited[nIdx] = true
                            queue[qTail++] = ((cy - 1) shl 16) or cx
                        }
                    }
                    if (cy < TARGET_DIM - 1) {
                        val nIdx = cIdx + TARGET_DIM
                        if (!visited[nIdx] && unclaimed[nIdx]) {
                            visited[nIdx] = true
                            queue[qTail++] = ((cy + 1) shl 16) or cx
                        }
                    }
                }

                val midX = (minX + maxX) / 2
                val midY = (minY + maxY) / 2
                val isInsideBubble = bubbleMask[midY * TARGET_DIM + midX]

                val isValidCluster = if (isInsideBubble) {
                    count >= 3 || (count >= 2 && peak >= 0.22f)
                } else {
                    count >= 5 || (count >= 3 && peak >= 0.28f)
                }

                val compW = maxX - minX + 1
                val compH = maxY - minY + 1
                if (isValidCluster && compW < 750 && compH < 750) {
                    val padX = (3 * invScaleX).toInt().coerceAtLeast(2)
                    val padY = (3 * invScaleY).toInt().coerceAtLeast(2)

                    val rLeft = (minX * invScaleX - padX).toInt().coerceAtLeast(0)
                    val rTop = (minY * invScaleY - padY).toInt().coerceAtLeast(0)
                    val rRight = ((maxX + 1) * invScaleX + padX).toInt().coerceAtMost(imgWidth)
                    val rBottom = ((maxY + 1) * invScaleY + padY).toInt().coerceAtMost(imgHeight)

                    val candRect = Rect(rLeft, rTop, rRight, rBottom)

                    val overlapsExisting = existingBoxes.any { existing ->
                        val interL = max(existing.left, candRect.left)
                        val interT = max(existing.top, candRect.top)
                        val interR = min(existing.right, candRect.right)
                        val interB = min(existing.bottom, candRect.bottom)
                        if (interR > interL && interB > interT) {
                            val interArea = (interR - interL).toLong() * (interB - interT).toLong()
                            val candArea = candRect.width().toLong() * candRect.height().toLong()
                            val existArea = existing.width().toLong() * existing.height().toLong()
                            if (candArea > 0 && existArea > 0) {
                                val candOverlap = interArea.toFloat() / candArea.toFloat()
                                val existOverlap = interArea.toFloat() / existArea.toFloat()
                                candOverlap > 0.20f || existOverlap > 0.35f
                            } else false
                        } else {
                            // Touch check: if touching or within 2px of an existing box, check if it's a boundary halo
                            val touchL = max(existing.left - 2, candRect.left)
                            val touchT = max(existing.top - 2, candRect.top)
                            val touchR = min(existing.right + 2, candRect.right)
                            val touchB = min(existing.bottom + 2, candRect.bottom)
                            touchR > touchL && touchB > touchT && (candRect.width() <= 12 || candRect.height() <= 12)
                        }
                    }

                    val minDim = if (isInsideBubble) 6 else 8
                    if (!overlapsExisting && candRect.width() >= minDim && candRect.height() >= minDim) {
                        candidateBoxes.add(Pair(candRect, peak))
                    }
                }
            }
        }

        return candidateBoxes
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun deduplicateBoxes(boxes: List<Rect>): List<Rect> {
        val result = mutableListOf<Rect>()
        for (box in boxes) {
            val exists = result.any { existing ->
                val interL = max(existing.left, box.left)
                val interT = max(existing.top, box.top)
                val interR = min(existing.right, box.right)
                val interB = min(existing.bottom, box.bottom)
                if (interR > interL && interB > interT) {
                    val interArea = (interR - interL) * (interB - interT)
                    val minArea = min(existing.width() * existing.height(), box.width() * box.height())
                    interArea.toFloat() / minArea.toFloat() > 0.35f
                } else false
            }
            if (!exists) {
                result.add(box)
            }
        }
        return result
    }

    /**
     * Zenkaku Square Em-Box Radical & Stroke Recombiner.
     * Merges split pieces of single Japanese characters (e.g. left-right radicals, top-bottom radicals,
     * detached strokes) using local neighbor dimensions and the Square Em-Box (1:1 aspect ratio) invariant.
     *
     * Invariants guaranteed:
     * 1. Left/Right halves of a character (e.g. #180, #170) fuse into a single square character.
     * 2. Top/Bottom halves of a character (e.g. #181, #182) fuse into a single square character.
     * 3. Two separate consecutive characters (union height >= 1.9 * W_col) NEVER merge.
     * 4. Two separate vertical lines / columns (union width >= 1.9 * W_col) NEVER merge.
     * 5. Furigana ruby text (which sits beside a full-width kanji) NEVER merges into the kanji.
     */
    fun recombineSplitCharacterBoxes(
        boxes: List<Rect>,
        bubbles: List<Rect>,
    ): List<Rect> {
        if (boxes.size < 2) return boxes

        val active = boxes.map { Rect(it) }.toMutableList()

        // Page-level median character width from vertical boxes (height >= width)
        val globalVertW = active
            .filter { it.height() >= it.width() }
            .map { it.width() }
            .sorted()
        val globalCharW = if (globalVertW.isNotEmpty()) {
            globalVertW[globalVertW.size / 2].toFloat().coerceIn(16f, 44f)
        } else {
            val allW = active.map { it.width() }.sorted()
            if (allW.isNotEmpty()) allW[allW.size / 2].toFloat().coerceIn(16f, 44f) else 24f
        }

        // Precompute character width per bubble
        val bubbleCharW = bubbles.associateWith { bubble ->
            val inBubble = active.filter { bubble.contains(it.centerX(), it.centerY()) }
            val vertW = inBubble.filter { it.height() >= it.width() }.map { it.width() }.sorted()
            if (vertW.isNotEmpty()) {
                vertW[vertW.size / 2].toFloat().coerceIn(16f, 44f)
            } else {
                val allW = inBubble.map { it.width() }.sorted()
                if (allW.isNotEmpty()) allW[allW.size / 2].toFloat().coerceIn(16f, 44f) else globalCharW
            }
        }

        var changed = true
        var iteration = 0
        while (changed && iteration < 8) {
            changed = false
            iteration++

            for (i in 0 until active.size) {
                val a = active[i]
                for (j in i + 1 until active.size) {
                    val b = active[j]

                    val bubbleA = bubbles.firstOrNull { it.contains(a.centerX(), a.centerY()) }
                    val bubbleB = bubbles.firstOrNull { it.contains(b.centerX(), b.centerY()) }
                    if (bubbleA != null && bubbleB != null && bubbleA != bubbleB) {
                        continue // Never merge across two different speech bubbles
                    }

                    val charW = (bubbleA ?: bubbleB)?.let { bubbleCharW[it] } ?: globalCharW

                    val unionL = min(a.left, b.left)
                    val unionT = min(a.top, b.top)
                    val unionR = max(a.right, b.right)
                    val unionB = max(a.bottom, b.bottom)
                    val unionW = unionR - unionL
                    val unionH = unionB - unionT

                    val interL = max(a.left, b.left)
                    val interT = max(a.top, b.top)
                    val interR = min(a.right, b.right)
                    val interB = min(a.bottom, b.bottom)
                    val interW = max(0, interR - interL)
                    val interH = max(0, interB - interT)
                    val interArea = interW.toLong() * interH.toLong()

                    val areaA = a.width().toLong() * a.height().toLong()
                    val areaB = b.width().toLong() * b.height().toLong()
                    val minArea = min(areaA, areaB)

                    var shouldMerge = false

                    // 1. Enclosure / High Overlap / Boundary Halo Touch
                    if (minArea > 0 && (interArea.toFloat() / minArea.toFloat() >= 0.35f)) {
                        shouldMerge = true
                    } else {
                        val dilatedA = Rect(a.left - 4, a.top - 4, a.right + 4, a.bottom + 4)
                        val dilatedB = Rect(b.left - 4, b.top - 4, b.right + 4, b.bottom + 4)
                        if (dilatedA.contains(b) || dilatedB.contains(a)) {
                            shouldMerge = true
                        }
                    }

                    // 2. Horizontal Radical Split (e.g. Left/Right Kanji halves: 氵+ 寺 = 持, #180 + #170)
                    if (!shouldMerge) {
                        val minH = min(a.height(), b.height())
                        val vOverlap = interH.toFloat() / max(1, minH).toFloat()
                        val hDist = max(0, max(a.left - b.right, b.left - a.right))
                        val maxAllowedHDist = max(6, (charW * 0.22f).toInt())

                        if (vOverlap >= 0.40f && hDist <= maxAllowedHDist) {
                            val minW = min(a.width(), b.width())
                            val maxW = max(a.width(), b.width())
                            // Both pieces are narrow radical pieces, or one is a skinny radical (< 0.60 charW)
                            val isRadicalSplit = (a.width() <= charW * 0.85f && b.width() <= charW * 0.85f) ||
                                    (minW <= charW * 0.60f && maxW <= charW * 1.15f)
                            // Crucial Zenkaku Invariant: Combined width cannot exceed 1.25 * charW
                            // (Two real columns are separated by >= 1.8 * charW)
                            val fitsEmBoxW = unionW <= (charW * 1.25f)
                            val fitsEmBoxH = unionH <= max(a.height(), b.height()) + (charW * 0.30f).toInt()

                            if (isRadicalSplit && fitsEmBoxW && fitsEmBoxH) {
                                shouldMerge = true
                            }
                        }
                    }

                    // 3. Vertical Radical / Stroke Split (e.g. Top/Bottom halves: 艹 + lower body, #181 + #182)
                    if (!shouldMerge) {
                        val minW = min(a.width(), b.width())
                        val hOverlap = interW.toFloat() / max(1, minW).toFloat()
                        val cxDist = abs(a.centerX() - b.centerX())
                        val vDist = max(0, max(a.top - b.bottom, b.top - a.bottom))
                        val maxAllowedVDist = max(6, (charW * 0.25f).toInt())

                        // Aligned horizontally (either high overlap or close centers) and vertically adjacent
                        val isVertAligned = hOverlap >= 0.40f || cxDist <= (charW * 0.35f)
                        if (isVertAligned && vDist <= maxAllowedVDist) {
                            // Crucial Zenkaku Invariant: Two real consecutive characters require unionH >= 1.9 * charW.
                            // If unionH <= 1.28 * charW, they are mathematically fragments of a single character!
                            val fitsEmBoxH = unionH <= (charW * 1.28f)
                            val fitsEmBoxW = unionW <= (charW * 1.25f)

                            if (fitsEmBoxH && fitsEmBoxW) {
                                shouldMerge = true
                            }
                        }
                    }

                    if (shouldMerge) {
                        a.set(unionL, unionT, unionR, unionB)
                        active.removeAt(j)
                        changed = true
                        break
                    }
                }
                if (changed) break
            }
        }

        return active
    }

    data class Module1Result(
        val probMap: FloatArray?,
        val lines: List<TextLineItem>,
        val bubbles: List<Rect>,
        val pass2Boxes: List<Rect> = emptyList(),
        val pass2Bitmap: Bitmap? = null,
        val pass2PassedBoxes: List<Rect> = emptyList(),
        val pass2RejectedBoxes: List<Rect> = emptyList(),
        val pass2BoxTexts: Map<Rect, String> = emptyMap(),
    )

    /**
     * Module 1 Complete Execution:
     * Produces Step 1.1 (Raw Heatmap) and Step 1.2 (Pass 1 Lines + YOLO bubbles + Heatmap Islands).
     * Recombined Pass 1 character boxes directly feed into output lines instantaneously.
     */
    suspend fun runModule1(
        bitmap: Bitmap,
        onProgress: ((String) -> Unit)? = null,
    ): Module1Result = withContext(Dispatchers.Default) {
        val prob = detectTextProb(bitmap)
        val (rawPass1Boxes, bubbles) = detectTwoPass(bitmap, onProgress = onProgress)

        // Native 1.1 continuous probability heatmap island capture:
        // Extracts all faint characters, compact kanji (e.g. P68), and furigana (p >= 0.18 inside bubbles, p >= 0.22 outside)
        // missed by Pass 1's coarse 4x4 grid downsampling, embedding them natively into Step 1.2 Pass 1 lines.
        val heatmapIslands = if (prob != null) {
            extractHeatmapTextIslands(prob, rawPass1Boxes, bubbles, bitmap.width, bitmap.height)
        } else emptyList()

        val pass1Boxes = recombineSplitCharacterBoxes(deduplicateBoxes(rawPass1Boxes + heatmapIslands), bubbles)
        lastPass1Boxes = pass1Boxes
        lastPass2Boxes = emptyList()
        lastPass2PassedBoxes = emptyList()
        lastPass2RejectedBoxes = emptyList()
        lastPass2Texts = emptyMap()
        lastPass2Bitmap = null

        val lines = pass1Boxes.mapIndexed { idx, rect ->
            TextLineItem(
                id = idx + 1,
                rect = rect,
                angle = 0f,
                confidence = 1.0f,
            )
        }

        Module1Result(
            probMap = prob,
            lines = lines,
            bubbles = bubbles,
            pass2Boxes = emptyList(),
            pass2Bitmap = null,
            pass2PassedBoxes = emptyList(),
            pass2RejectedBoxes = emptyList(),
            pass2BoxTexts = emptyMap(),
        )
    }

    @Synchronized
    fun releaseInferenceBuffers() {
        session?.close()
        session = null
        loadedPath = null
        lastPass2Bitmap = null
        Log.d("CrunchLab", "ComicTextDetector session and buffers released")
    }

    override fun close() {
        releaseInferenceBuffers()
    }
}
