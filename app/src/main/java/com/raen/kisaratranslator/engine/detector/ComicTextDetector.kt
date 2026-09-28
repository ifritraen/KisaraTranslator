package com.raen.kisaratranslator.engine.detector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.raen.kisaratranslator.core.util.AiBufferUtils
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.model.TranslationModelType
import com.raen.kisaratranslator.data.model.TranslationReport
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

/**
 * Comic-Text-Detector (CTD) ONNX Inference Engine.
 * Runs 1024x1024 text/bubble segmentation tailored for manga & comics.
 */
class ComicTextDetector(
    private val modelManager: TranslationModelManager,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Closeable {

    private var session: OrtSession? = null
    private var activePath: String? = null

    val isReady: Boolean
        get() = modelManager.isModelReady(TranslationModelType.COMIC_TEXT_DETECTOR)

    var configuredThreads: Int = 4

    fun setSpeedLevel(level: Int) {
        val numCores = Runtime.getRuntime().availableProcessors()
        val targetThreads = when (level.coerceIn(1, 5)) {
            1 -> 1
            2 -> 2
            3 -> 3.coerceAtMost(numCores)
            4 -> 4.coerceAtMost(numCores)
            else -> 4.coerceAtMost(numCores)
        }
        if (targetThreads != configuredThreads) {
            configuredThreads = targetThreads
            session?.close()
            session = null
            activePath = null
        }
    }

    @Synchronized
    private fun getSession(): OrtSession? {
        val modelFile = modelManager.getModelFile(TranslationModelType.COMIC_TEXT_DETECTOR)
        if (!modelFile.exists() || modelFile.length() < TranslationModelType.COMIC_TEXT_DETECTOR.minSize) {
            return null
        }

        if (session != null && activePath == modelFile.absolutePath) {
            return session
        }

        session?.close()
        val numCores = Runtime.getRuntime().availableProcessors()
        val threads = configuredThreads.coerceIn(1, numCores)
        val opts = AiBufferUtils.createSessionOptions(threads)
        return try {
            session = env.createSession(modelFile.absolutePath, opts)
            activePath = modelFile.absolutePath
            session
        } catch (e: Exception) {
            session = null
            activePath = null
            null
        } finally {
            opts.close()
        }
    }

    var lastDetectedBubbles: List<Rect> = emptyList()
        private set

    var lastPass1Boxes: List<Rect> = emptyList()
        private set

    var lastPass2Boxes: List<Rect> = emptyList()
        private set

    var lastPass2Bitmap: Bitmap? = null
        private set

    fun filterExcludedBoxes(predicate: (Rect) -> Boolean) {
        lastDetectedBubbles = lastDetectedBubbles.filter(predicate)
        lastPass1Boxes = lastPass1Boxes.filter(predicate)
        lastPass2Boxes = lastPass2Boxes.filter(predicate)
    }

    /**
     * Detects text and speech bubble regions in a manga page.
     * @param maskThreshold Segmentation confidence cutoff. Method 1=0.40, Method 2=0.30.
     * @param nmsIoUThreshold NMS suppress threshold. Method 1=0.40, Method 2=0.35.
     * @return List of bounding boxes around text/bubble regions.
     */
    fun detect(
        bitmap: Bitmap,
        maskThreshold: Float = 0.40f,
        nmsIoUThreshold: Float = 0.40f,
    ): List<Rect> {
        val sess = getSession() ?: run {
            TranslationReport.log("WARN", "ComicTextDetector", "Session not ready or model missing")
            return emptyList()
        }
        val targetDim = 1024

        val scaled = Bitmap.createScaledBitmap(bitmap, targetDim, targetDim, true)
        val numPixels = targetDim * targetDim
        val floatBuf = AiBufferUtils.allocateDirectFloatBuffer(3 * numPixels)

        val pixels = IntArray(numPixels)
        scaled.getPixels(pixels, 0, targetDim, 0, 0, targetDim, targetDim)

        // Vectorized 1-pass NCHW planar RGB normalized to [0..1] with bulk buffer write
        val rawFloats = FloatArray(3 * numPixels)
        val planeR = 0
        val planeG = numPixels
        val planeB = numPixels * 2
        val inv255 = 1f / 255f

        for (i in 0 until numPixels) {
            val p = pixels[i]
            rawFloats[planeR + i] = ((p ushr 16) and 0xFF) * inv255
            rawFloats[planeG + i] = ((p ushr 8) and 0xFF) * inv255
            rawFloats[planeB + i] = (p and 0xFF) * inv255
        }
        floatBuf.put(rawFloats)
        floatBuf.rewind()

        val inputName = sess.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(
            env,
            floatBuf,
            longArrayOf(1L, 3L, targetDim.toLong(), targetDim.toLong()),
        )

        val boxes = mutableListOf<Rect>()
        try {
            val result = sess.run(java.util.Collections.singletonMap(inputName, inputTensor))

            val scaleX = bitmap.width.toFloat() / targetDim.toFloat()
            val scaleY = bitmap.height.toFloat() / targetDim.toFloat()

            // 1. Extract speech bubble regions from YOLO-style blk tensor if available
            lastDetectedBubbles = emptyList()
            if (result.get("blk").isPresent) {
                val blkVal = result.get("blk").get()
                if (blkVal is OnnxTensor) {
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

            // 2. Extract character/word bounding boxes from seg tensor
            var outputTensor: OnnxTensor? = null
            if (result.get("seg").isPresent) {
                val v = result.get("seg").get()
                if (v is OnnxTensor) outputTensor = v
            }
            if (outputTensor == null && result.get("det").isPresent) {
                val v = result.get("det").get()
                if (v is OnnxTensor) outputTensor = v
            }
            if (outputTensor == null) {
                val v = result.get(0)
                if (v is OnnxTensor) outputTensor = v
            }
            val tensor = outputTensor ?: throw NoSuchElementException("No valid output tensor found in ComicTextDetector")
            val outBuf = tensor.floatBuffer

            // maskThreshold comes from the function parameter (Method 1=0.40, Method 2=0.30)
            val gridStep = 4
            val gridW = targetDim / gridStep
            val gridH = targetDim / gridStep

            val visited = BooleanArray(gridW * gridH)
            val minClusterPoints = 3
            val queue = IntArray(gridW * gridH)

            for (gy in 0 until gridH) {
                for (gx in 0 until gridW) {
                    val idx = gy * gridW + gx
                    if (visited[idx]) continue

                    val px = gx * gridStep + gridStep / 2
                    val py = gy * gridStep + gridStep / 2
                    val bufIdx = (py * targetDim + px).coerceIn(0, outBuf.capacity() - 1)
                    val score = outBuf.get(bufIdx)

                    if (score >= maskThreshold) {
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

                            // 4-way neighbors without object allocations
                            if (cx > 0) {
                                val nx = cx - 1
                                val nIdx = cy * gridW + nx
                                if (!visited[nIdx]) {
                                    val nPx = nx * gridStep + gridStep / 2
                                    val nPy = cy * gridStep + gridStep / 2
                                    val nBufIdx = (nPy * targetDim + nPx).coerceIn(0, outBuf.capacity() - 1)
                                    if (outBuf.get(nBufIdx) >= maskThreshold) {
                                        visited[nIdx] = true
                                        queue[qTail++] = (cy shl 16) or (nx and 0xFFFF)
                                    }
                                }
                            }
                            if (cx + 1 < gridW) {
                                val nx = cx + 1
                                val nIdx = cy * gridW + nx
                                if (!visited[nIdx]) {
                                    val nPx = nx * gridStep + gridStep / 2
                                    val nPy = cy * gridStep + gridStep / 2
                                    val nBufIdx = (nPy * targetDim + nPx).coerceIn(0, outBuf.capacity() - 1)
                                    if (outBuf.get(nBufIdx) >= maskThreshold) {
                                        visited[nIdx] = true
                                        queue[qTail++] = (cy shl 16) or (nx and 0xFFFF)
                                    }
                                }
                            }
                            if (cy > 0) {
                                val ny = cy - 1
                                val nIdx = ny * gridW + cx
                                if (!visited[nIdx]) {
                                    val nPx = cx * gridStep + gridStep / 2
                                    val nPy = ny * gridStep + gridStep / 2
                                    val nBufIdx = (nPy * targetDim + nPx).coerceIn(0, outBuf.capacity() - 1)
                                    if (outBuf.get(nBufIdx) >= maskThreshold) {
                                        visited[nIdx] = true
                                        queue[qTail++] = (ny shl 16) or (cx and 0xFFFF)
                                    }
                                }
                            }
                            if (cy + 1 < gridH) {
                                val ny = cy + 1
                                val nIdx = ny * gridW + cx
                                if (!visited[nIdx]) {
                                    val nPx = cx * gridStep + gridStep / 2
                                    val nPy = ny * gridStep + gridStep / 2
                                    val nBufIdx = (nPy * targetDim + nPx).coerceIn(0, outBuf.capacity() - 1)
                                    if (outBuf.get(nBufIdx) >= maskThreshold) {
                                        visited[nIdx] = true
                                        queue[qTail++] = (ny shl 16) or (cx and 0xFFFF)
                                    }
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

            result.close()
            TranslationReport.log("INFO", "ComicTextDetector", "Detected ${boxes.size} boxes, ${lastDetectedBubbles.size} bubbles")
        } catch (e: Exception) {
            Log.e("ComicTextDetector", "Inference error", e)
            TranslationReport.log("ERROR", "ComicTextDetector", "Inference failed: ${e.message}", e)
        } finally {
            inputTensor.close()
            scaled.recycle()
        }

        lastPass1Boxes = boxes
        lastPass2Boxes = emptyList()
        return boxes
    }

    /**
     * Method 3 Two-Pass Focused Scan:
     * Pass 1: Standard sensitive scan (0.30) to identify candidate text clusters and bubble envelopes.
     * Pass 2: Contrast/sharpness-enhanced focused scan on candidate regions with low cutoff (0.20),
     * with non-candidate regions masked with white to eliminate background noise.
     * Returns unioned & deduplicated text boxes and bubble envelopes.
     */
    fun detectTwoPass(
        bitmap: Bitmap,
        speedDet1: Int = 3,
        speedDet2: Int = 3,
        onProgress: ((String) -> Unit)? = null,
    ): Pair<List<Rect>, List<Rect>> {
        setSpeedLevel(speedDet1)
        onProgress?.invoke("[1/7] Det 1: Global Scan (YOLO + ComicText)...")
        val pass1Boxes = detect(bitmap, maskThreshold = 0.30f, nmsIoUThreshold = 0.35f)
        val bubbles = lastDetectedBubbles.toList()

        if (pass1Boxes.isEmpty() && bubbles.isEmpty()) {
            lastPass1Boxes = pass1Boxes
            lastPass2Boxes = emptyList()
            return Pair(pass1Boxes, bubbles)
        }

        setSpeedLevel(speedDet2)
        onProgress?.invoke("[1/7] Det 2: Contrast Boost & Halo Mask Canvas...")

        // Perimeter Halo Mask (*bcd*):
        // 1. Prepare contrast and paint configurations

        val contrastPaint = Paint().apply {
            // Boost darkness & edge sharpness: 1.45x contrast, darker text
            val cm = ColorMatrix(floatArrayOf(
                1.45f, 0f, 0f, 0f, -30f,
                0f, 1.45f, 0f, 0f, -30f,
                0f, 0f, 1.45f, 0f, -30f,
                0f, 0f, 0f, 1f, 0f,
            ))
            colorFilter = ColorMatrixColorFilter(cm)
        }

        val whitePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        // 2. Generate exact 1-unit adjacent neighbor cells (*b*)
        val neighborCandidates = generateNeighborCandidateBoxes(pass1Boxes, bubbles, bitmap.width, bitmap.height)

        // 3. Create Pass 2 diagnostic visualization bitmap showing probed candidate cells with contrast boost
        val enhanced = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        canvas.drawColor(Color.WHITE)

        // Draw candidate neighbor patches on the canvas with 1.45x contrast
        for (cand in neighborCandidates) {
            canvas.drawBitmap(bitmap, cand.rect, cand.rect, contrastPaint)
        }
        // Also draw any speech bubbles that have zero detected text inside
        for (b in bubbles) {
            val hasText = pass1Boxes.any { box -> b.contains(box.centerX(), box.centerY()) }
            if (!hasText) {
                canvas.drawBitmap(bitmap, b, b, contrastPaint)
            }
        }

        // Save clean copy for UI inspection
        lastPass2Bitmap = enhanced.copy(Bitmap.Config.ARGB_8888, false)
        enhanced.recycle()

        val extraBoxes = neighborCandidates.map { it.rect }
        val finalBubbles = if (lastDetectedBubbles.isNotEmpty()) lastDetectedBubbles else bubbles
        lastPass1Boxes = pass1Boxes
        lastPass2Boxes = extraBoxes
        return Pair(pass1Boxes, finalBubbles)
    }

    data class CandidateNeighbor(
        val rect: Rect,
        val direction: String, // "Left", "Right", "Top", "Bottom"
        val parentBox: Rect,
        val isInsideBubble: Boolean,
    )

    fun generateNeighborCandidateBoxes(
        pass1Boxes: List<Rect>,
        bubbles: List<Rect>,
        imgWidth: Int,
        imgHeight: Int,
    ): List<CandidateNeighbor> {
        val candidates = mutableListOf<CandidateNeighbor>()
        for (b in pass1Boxes) {
            val w = b.width()
            val h = b.height()
            if (w < 8 || h < 8) continue

            val parentBubble = bubbles.firstOrNull { it.contains(b.centerX(), b.centerY()) }
            if (parentBubble == null) continue // Orphans outside speech bubbles never look for neighbors!

            // 1-unit adjacent neighbors (*b*) in 4 cardinal directions
            val dirs = listOf(
                Triple("Left", Rect(b.left - w, b.top, b.left, b.bottom), true),
                Triple("Right", Rect(b.right, b.top, b.right + w, b.bottom), true),
                Triple("Top", Rect(b.left, b.top - h, b.right, b.top), false),
                Triple("Bottom", Rect(b.left, b.bottom, b.right, b.bottom + h), false),
            )

            for ((dir, r, _) in dirs) {
                val clamped = Rect(
                    r.left.coerceIn(0, imgWidth),
                    r.top.coerceIn(0, imgHeight),
                    r.right.coerceIn(0, imgWidth),
                    r.bottom.coerceIn(0, imgHeight),
                )
                if (clamped.width() < 10 || clamped.height() < 10) continue

                // If inside bubble, neighbor must remain within the bubble
                if (parentBubble != null) {
                    if (!parentBubble.contains(clamped.centerX(), clamped.centerY())) {
                        continue
                    }
                }

                // Discard if overlapping > 25% with any existing Pass 1 box
                val overlapsPass1 = pass1Boxes.any { other ->
                    val interL = max(other.left, clamped.left)
                    val interT = max(other.top, clamped.top)
                    val interR = min(other.right, clamped.right)
                    val interB = min(other.bottom, clamped.bottom)
                    if (interR > interL && interB > interT) {
                        val interArea = (interR - interL) * (interB - interT)
                        val candArea = clamped.width() * clamped.height()
                        interArea.toFloat() / candArea.toFloat() > 0.25f
                    } else false
                }
                if (overlapsPass1) continue

                // Avoid duplicate candidates
                val alreadyExists = candidates.any { c ->
                    val interL = max(c.rect.left, clamped.left)
                    val interT = max(c.rect.top, clamped.top)
                    val interR = min(c.rect.right, clamped.right)
                    val interB = min(c.rect.bottom, clamped.bottom)
                    if (interR > interL && interB > interT) {
                        val interArea = (interR - interL) * (interB - interT)
                        val minA = min(c.rect.width() * c.rect.height(), clamped.width() * clamped.height())
                        interArea.toFloat() / minA.toFloat() > 0.50f
                    } else false
                }
                if (!alreadyExists) {
                    candidates.add(
                        CandidateNeighbor(
                            rect = clamped,
                            direction = dir,
                            parentBox = b,
                            isInsideBubble = parentBubble != null,
                        )
                    )
                }
            }
        }
        return candidates
    }

    fun updatePass2Boxes(boxes: List<Rect>) {
        lastPass2Boxes = boxes
    }

    override fun close() {
        session?.close()
        session = null
        activePath = null
    }
}
