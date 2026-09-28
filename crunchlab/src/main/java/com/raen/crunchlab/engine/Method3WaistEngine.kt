package com.raen.crunchlab.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.raen.crunchlab.data.CrunchPartitionItem
import com.raen.crunchlab.data.ModelDownloader
import com.raen.crunchlab.data.ReadingGroupItem
import com.raen.crunchlab.data.TextLineItem
import com.raen.crunchlab.util.AiBufferUtils
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Module 3: Conjoined Speech Bubble Crunch & Geometric Line Partitioning (Method 3).
 *
 * Architecture:
 * 1. YOLOv8-pose Inference: Letterboxed 640x640 inference on speech bubble crops
 *    using manga_waist_model.onnx (best.onnx).
 *    Evaluates conjoined vs single classification and raw waist keypoints P1, P2.
 * 2. Ink Contour Snapping: Snaps raw keypoints to nearest dark ink pixels within radius r <= 25px.
 * 3. Text Line Geometric Partitioning: Divides Module 1 & 2 text lines into Lobe A and Lobe B
 *    using the signed cross-product dividing line without destructive clipping.
 * 4. Rejection Invariant: Rejects cuts if all text lines fall on one side (unbalanced partition)
 *    or if the dividing line clips through any text line bounding box.
 */
class Method3WaistEngine(
    private val modelDownloader: ModelDownloader,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Closeable {

    companion object {
        const val TARGET_DIM = 640
        private const val NUM_PX = TARGET_DIM * TARGET_DIM
        private const val SEARCH_RADIUS = 25
    }

    private var session: OrtSession? = null
    private var loadedPath: String? = null

    // Pre-allocated buffers for zero GC overhead during multi-bubble scanning
    private val inputDirectBuffer = AiBufferUtils.allocateDirectFloatBuffer(3 * NUM_PX)
    private val pixelArray = IntArray(NUM_PX)
    private val rawFloats = FloatArray(3 * NUM_PX)

    val isReady: Boolean
        get() {
            ensureSession()
            return session != null
        }

    fun ensureSession(): Boolean {
        if (session != null) return true
        val modelFile = modelDownloader.getWaistModelFile()
        return loadModel(modelFile)
    }

    @Synchronized
    fun loadModel(modelFile: File): Boolean {
        if (loadedPath == modelFile.absolutePath && session != null) return true
        session?.close()
        session = null
        loadedPath = null
        if (!modelFile.exists() || modelFile.length() < ModelDownloader.MIN_SIZE_WAIST) return false

        val opts = AiBufferUtils.createSessionOptions(threads = 2)
        return try {
            session = env.createSession(modelFile.absolutePath, opts)
            loadedPath = modelFile.absolutePath
            Log.i("Method3WaistEngine", "Loaded waist model: ${modelFile.name} (${modelFile.length() / 1024}KB)")
            true
        } catch (e: Exception) {
            Log.e("Method3WaistEngine", "Waist model load failed: ${e.message}")
            false
        } finally {
            opts.close()
        }
    }

    data class WaistCropPrediction(
        val isConjoined: Boolean,
        val confConj: Float,
        val confSingle: Float,
        val p1RawCrop: PointF,
        val p2RawCrop: PointF,
        val p1SnappedCrop: Point,
        val p2SnappedCrop: Point,
        val candidatesCrop: List<Point> = emptyList()
    )

    /**
     * Predicts waist notch keypoints and conjoined classification for a single speech bubble crop.
     * Extracts concavity candidates matching Crunch Annotator auto_suggest and snaps AI keypoints to them.
     */
    @Synchronized
    fun predictCrop(cropBmp: Bitmap): WaistCropPrediction? {
        ensureSession()
        val sess = session ?: return null

        val w0 = cropBmp.width.toFloat()
        val h0 = cropBmp.height.toFloat()
        if (w0 < 8 || h0 < 8) return null

        val scale = min(TARGET_DIM / w0, TARGET_DIM / h0)
        val nw = (w0 * scale).roundToInt().coerceIn(1, TARGET_DIM)
        val nh = (h0 * scale).roundToInt().coerceIn(1, TARGET_DIM)
        val dx = (TARGET_DIM - nw) / 2
        val dy = (TARGET_DIM - nh) / 2

        // 1. Letterbox onto 640x640 canvas filled with grey (114)
        val canvasBmp = Bitmap.createBitmap(TARGET_DIM, TARGET_DIM, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.rgb(114, 114, 114))

        val scaledCrop = Bitmap.createScaledBitmap(cropBmp, nw, nh, true)
        canvas.drawBitmap(scaledCrop, dx.toFloat(), dy.toFloat(), null)
        scaledCrop.recycle()

        canvasBmp.getPixels(pixelArray, 0, TARGET_DIM, 0, 0, TARGET_DIM, TARGET_DIM)
        canvasBmp.recycle()

        // 2. Vectorized planar RGB normalization [0..1]
        val planeR = 0
        val planeG = NUM_PX
        val planeB = NUM_PX * 2
        val inv255 = 1f / 255f

        for (i in 0 until NUM_PX) {
            val p = pixelArray[i]
            rawFloats[planeR + i] = ((p ushr 16) and 0xFF) * inv255
            rawFloats[planeG + i] = ((p ushr 8) and 0xFF) * inv255
            rawFloats[planeB + i] = (p and 0xFF) * inv255
        }

        inputDirectBuffer.clear()
        inputDirectBuffer.put(rawFloats)
        inputDirectBuffer.rewind()

        val inTensor = OnnxTensor.createTensor(
            env,
            inputDirectBuffer,
            longArrayOf(1, 3, TARGET_DIM.toLong(), TARGET_DIM.toLong())
        )

        try {
            val outputs = sess.run(mapOf(sess.inputNames.first() to inTensor))
            val outTensor = outputs.get(0) as? OnnxTensor ?: run {
                outputs.close()
                return null
            }

            val buf = outTensor.floatBuffer
            val numCandidates = 8400

            // Row 4: class 0 (conjoined), Row 5: class 1 (single)
            var bestIdx = 0
            var maxConf = -1f
            for (k in 0 until numCandidates) {
                val c0 = buf.get(4 * numCandidates + k)
                val c1 = buf.get(5 * numCandidates + k)
                val m = max(c0, c1)
                if (m > maxConf) {
                    maxConf = m
                    bestIdx = k
                }
            }

            val c0 = buf.get(4 * numCandidates + bestIdx)
            val c1 = buf.get(5 * numCandidates + bestIdx)
            val rawK0x = buf.get(6 * numCandidates + bestIdx)
            val rawK0y = buf.get(7 * numCandidates + bestIdx)
            val rawK1x = buf.get(9 * numCandidates + bestIdx)
            val rawK1y = buf.get(10 * numCandidates + bestIdx)
            outputs.close()

            // Map keypoints back to original crop coordinates
            var k0x = (rawK0x - dx) / scale
            var k0y = (rawK0y - dy) / scale
            var k1x = (rawK1x - dx) / scale
            var k1y = (rawK1y - dy) / scale

            // Canonical order: left-most or top-most first
            if (k0x > k1x || (k0x == k1x && k0y > k1y)) {
                val tx = k0x; val ty = k0y
                k0x = k1x; k0y = k1y
                k1x = tx; k1y = ty
            }

            // Extract concavity candidates matching Crunch Annotator auto_suggest
            val candidates = ConvexityDefectDetector.detectCandidates(cropBmp)

            // Candidate snapping: raw AI crunch points snap to closest concavity defect candidate
            val tauSnap = max(45f, 0.12f * min(w0, h0))
            val s0 = snapToCandidateOrInk(k0x, k0y, candidates, cropBmp, tauSnap)
            val s1 = snapToCandidateOrInk(k1x, k1y, candidates, cropBmp, tauSnap, usedCandidate = s0)

            return WaistCropPrediction(
                isConjoined = c0 >= c1,
                confConj = c0,
                confSingle = c1,
                p1RawCrop = PointF(k0x, k0y),
                p2RawCrop = PointF(k1x, k1y),
                p1SnappedCrop = s0,
                p2SnappedCrop = s1,
                candidatesCrop = candidates
            )
        } catch (e: Exception) {
            Log.e("Method3WaistEngine", "Prediction error: ${e.message}", e)
            return null
        } finally {
            inTensor.close()
        }
    }

    /**
     * Snaps a predicted AI keypoint to the nearest concavity candidate point within snapRadius,
     * or falls back to radial nearest dark ink contour pixel.
     */
    private fun snapToCandidateOrInk(
        ptX: Float,
        ptY: Float,
        candidates: List<Point>,
        cropBmp: Bitmap,
        snapRadius: Float,
        usedCandidate: Point? = null
    ): Point {
        var bestCand: Point? = null
        var minCandDist = Float.MAX_VALUE
        for (cand in candidates) {
            if (cand == usedCandidate) continue
            val dx = cand.x - ptX
            val dy = cand.y - ptY
            val d = sqrt(dx * dx + dy * dy)
            if (d <= snapRadius && d < minCandDist) {
                minCandDist = d
                bestCand = cand
            }
        }
        return bestCand ?: snapToInk(cropBmp, ptX, ptY, (snapRadius * 0.6f).roundToInt().coerceAtLeast(25))
    }

    /**
     * Radial nearest-dark-pixel snapping within search window [x0 - r, x0 + r], [y0 - r, y0 + r].
     */
    private fun snapToInk(cropBmp: Bitmap, ptX: Float, ptY: Float, searchR: Int = 25): Point {
        val w = cropBmp.width
        val h = cropBmp.height
        val x0 = ptX.roundToInt()
        val y0 = ptY.roundToInt()
        val x1 = max(0, x0 - searchR)
        val x2 = min(w - 1, x0 + searchR)
        val y1 = max(0, y0 - searchR)
        val y2 = min(h - 1, y0 + searchR)

        var minDistSq = Double.MAX_VALUE
        var bestX = x0.coerceIn(0, w - 1)
        var bestY = y0.coerceIn(0, h - 1)

        for (y in y1..y2) {
            for (x in x1..x2) {
                val pixel = cropBmp.getPixel(x, y)
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
                if (gray < 100) {
                    val dx = x - ptX
                    val dy = y - ptY
                    val distSq = (dx * dx + dy * dy).toDouble()
                    if (distSq < minDistSq) {
                        minDistSq = distSq
                        bestX = x
                        bestY = y
                    }
                }
            }
        }
        return Point(bestX, bestY)
    }

    /**
     * Full page processing: crops each detected bubble, infers waist geometry,
     * snaps to OpenCV concavity candidate notches, slices straddling M2 vertical lines,
     * and partitions vertical lines into Lobe A and Lobe B.
     */
    fun processPage(
        bitmap: Bitmap,
        masks: List<BubbleMask>,
        textLines: List<TextLineItem>,
        readingGroups: List<ReadingGroupItem> = emptyList()
    ): List<CrunchPartitionItem> {
        val partitions = mutableListOf<CrunchPartitionItem>()
        val bmpW = bitmap.width
        val bmpH = bitmap.height

        for ((bIdx, bMask) in masks.withIndex()) {
            val bRect = bMask.rect
            val pad = 4
            val left = (bRect.left - pad).coerceIn(0, bmpW - 1)
            val top = (bRect.top - pad).coerceIn(0, bmpH - 1)
            val right = (bRect.right + pad).coerceIn(left + 1, bmpW)
            val bottom = (bRect.bottom + pad).coerceIn(top + 1, bmpH)
            val cropW = right - left
            val cropH = bottom - top

            if (cropW < 12 || cropH < 12) continue

            val cropBmp = try {
                Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
            } catch (e: Exception) {
                null
            } ?: continue

            val pred = predictCrop(cropBmp)
            cropBmp.recycle()

            if (pred == null) {
                // Fallback single item
                partitions.add(
                    CrunchPartitionItem(
                        bubbleIndex = bIdx,
                        bubbleRect = bRect,
                        isConjoined = false,
                        confConj = 0.0f,
                        p1Raw = null,
                        p2Raw = null,
                        p1Snapped = null,
                        p2Snapped = null
                    )
                )
                continue
            }

            // Map keypoints and concavity candidates from crop space to full page coordinate space
            val p1RawPage = Point((pred.p1RawCrop.x + left).roundToInt(), (pred.p1RawCrop.y + top).roundToInt())
            val p2RawPage = Point((pred.p2RawCrop.x + left).roundToInt(), (pred.p2RawCrop.y + top).roundToInt())
            val p1SnappedPage = Point(pred.p1SnappedCrop.x + left, pred.p1SnappedCrop.y + top)
            val p2SnappedPage = Point(pred.p2SnappedCrop.x + left, pred.p2SnappedCrop.y + top)
            val pageCandidates = pred.candidatesCrop.map { Point(it.x + left, it.y + top) }

            // Find all text lines belonging to this bubble
            val bubbleLines = textLines.filter { line ->
                val cx = line.rect.centerX()
                val cy = line.rect.centerY()
                bRect.contains(cx, cy)
            }

            val lobeALineIds = mutableListOf<Int>()
            val lobeBLineIds = mutableListOf<Int>()
            val splitLinesList = mutableListOf<TextLineItem>()

            if (pred.isConjoined) {
                // Cutline is drawn directly between true crunch points (p1SnappedPage -> p2SnappedPage)
                val p1Sep = p1SnappedPage
                val p2Sep = p2SnappedPage

                val dx = abs(p2Sep.x - p1Sep.x)
                val dy = abs(p2Sep.y - p1Sep.y)
                val isVerticalCut = dy >= dx

                // Dynamic neighbor dimension baselines
                val medianCharW = if (bubbleLines.isNotEmpty()) {
                    val sorted = bubbleLines.map { it.rect.width() }.sorted()
                    sorted[sorted.size / 2].coerceIn(16, 40)
                } else 22

                val medianCharH = if (bubbleLines.isNotEmpty()) {
                    val sorted = bubbleLines.map { it.rect.height() }.sorted()
                    sorted[sorted.size / 2].coerceIn(20, 60)
                } else 35

                fun dSign(x: Int, y: Int): Float =
                    (x - p1Sep.x).toFloat() * (p2Sep.y - p1Sep.y).toFloat() -
                    (y - p1Sep.y).toFloat() * (p2Sep.x - p1Sep.x).toFloat()

                for (line in bubbleLines) {
                    val r = line.rect
                    val c1 = dSign(r.left, r.top) >= 0f
                    val c2 = dSign(r.right, r.top) >= 0f
                    val c3 = dSign(r.left, r.bottom) >= 0f
                    val c4 = dSign(r.right, r.bottom) >= 0f
                    val numPos = (if (c1) 1 else 0) + (if (c2) 1 else 0) + (if (c3) 1 else 0) + (if (c4) 1 else 0)

                    when {
                        // Majority in Lobe A (>= 75% of corners)
                        numPos >= 3 -> {
                            lobeALineIds.add(line.id)
                        }
                        // Majority in Lobe B (<= 25% of corners)
                        numPos <= 1 -> {
                            lobeBLineIds.add(line.id)
                        }
                        // Straddling line (crosses cutline)
                        else -> {
                            // Edge case: slice misread long vertical line from M2 that crosses between lobes
                            val isJoinedMisread = if (isVerticalCut) {
                                r.width() >= 1.30f * medianCharW || (r.width() >= 26 && min(abs(r.left - p1Sep.x), abs(r.right - p1Sep.x)) >= 6)
                            } else {
                                r.height() >= 1.30f * medianCharH || (r.height() >= 30 && min(abs(r.top - p1Sep.y), abs(r.bottom - p1Sep.y)) >= 6)
                            }

                            val sliced = if (isJoinedMisread) {
                                trySliceStraddlingLine(bitmap, line, p1Sep, p2Sep, isVerticalCut)
                            } else null

                            if (sliced != null) {
                                val (lineA, lineB) = sliced
                                splitLinesList.add(lineA)
                                splitLinesList.add(lineB)
                                lobeALineIds.add(lineA.id)
                                lobeBLineIds.add(lineB.id)
                            } else {
                                // Fallback: assign to lobe containing center
                                val cSign = dSign(r.centerX(), r.centerY())
                                if (cSign >= 0f) {
                                    lobeALineIds.add(line.id)
                                } else {
                                    lobeBLineIds.add(line.id)
                                }
                            }
                        }
                    }
                }
            } else {
                // Single bubble: all lines assigned to Lobe A
                lobeALineIds.addAll(bubbleLines.map { it.id })
            }

            partitions.add(
                CrunchPartitionItem(
                    bubbleIndex = bIdx,
                    bubbleRect = bRect,
                    isConjoined = pred.isConjoined,
                    confConj = pred.confConj,
                    p1Raw = if (pred.isConjoined) p1RawPage else null,
                    p2Raw = if (pred.isConjoined) p2RawPage else null,
                    p1Snapped = if (pred.isConjoined) p1SnappedPage else null,
                    p2Snapped = if (pred.isConjoined) p2SnappedPage else null,
                    candidatePoints = pageCandidates,
                    lobeALineIds = lobeALineIds,
                    lobeBLineIds = lobeBLineIds,
                    isCutRejected = false,
                    rejectionReason = null,
                    splitLines = splitLinesList
                )
            )
        }

        return partitions
    }

    fun processPageWithRects(
        bitmap: Bitmap,
        bubbleRects: List<Rect>,
        textLines: List<TextLineItem>,
        readingGroups: List<ReadingGroupItem> = emptyList()
    ): List<CrunchPartitionItem> {
        val dummyMasks = bubbleRects.map { r ->
            BubbleMask(rect = r, mask = BooleanArray(0), width = r.width(), height = r.height(), fillArea = r.width() * r.height())
        }
        return processPage(bitmap, dummyMasks, textLines, readingGroups)
    }


    /**
     * Slices a misread text line that joins two vertical or horizontal lines from different lobes.
     * Evaluates neighbor conditions, identifies where the separation line passes through the box,
     * and uses 1D pixel projection (column or row ink density profile) to locate the ink valley/gap.
     */
    private fun trySliceStraddlingLine(
        bitmap: Bitmap,
        line: TextLineItem,
        p1: Point,
        p2: Point,
        isVerticalCut: Boolean
    ): Pair<TextLineItem, TextLineItem>? {
        val r = line.rect
        val bmpW = bitmap.width
        val bmpH = bitmap.height
        val rL = r.left.coerceIn(0, bmpW - 1)
        val rT = r.top.coerceIn(0, bmpH - 1)
        val rR = r.right.coerceIn(rL + 1, bmpW)
        val rB = r.bottom.coerceIn(rT + 1, bmpH)
        val w = rR - rL
        val h = rB - rT
        if (w < 12 || h < 12) return null

        val cropPixels = IntArray(w * h)
        try {
            bitmap.getPixels(cropPixels, 0, w, rL, rT, w, h)
        } catch (e: Exception) {
            return null
        }

        if (isVerticalCut) {
            val yMid = (rT + rB) / 2f
            val dy = (p2.y - p1.y).toFloat()
            val xCut = if (abs(dy) > 0.001f) {
                val t = (yMid - p1.y) / dy
                (p1.x + t * (p2.x - p1.x)).roundToInt()
            } else {
                (p1.x + p2.x) / 2
            }

            val relCutX = xCut - rL
            val win = (w * 0.35f).roundToInt().coerceAtLeast(4)
            val xStart = (relCutX - win).coerceIn(4, w - 5)
            val xEnd = (relCutX + win).coerceIn(xStart + 1, w - 4)
            if (xStart >= xEnd) return null

            var minDensity = Float.MAX_VALUE
            var bestRelX = -1
            var bestGapLength = 0
            var currentGapLength = 0
            var bestGapStart = -1

            val colDensities = FloatArray(w)
            for (col in xStart..xEnd) {
                var darkCount = 0
                for (row in 0 until h) {
                    val pix = cropPixels[row * w + col]
                    val rC = (pix ushr 16) and 0xFF
                    val gC = (pix ushr 8) and 0xFF
                    val bC = pix and 0xFF
                    val gray = (0.299f * rC + 0.587f * gC + 0.114f * bC).toInt()
                    if (gray < 165) darkCount++
                }
                val density = darkCount.toFloat() / h.toFloat()
                colDensities[col] = density

                if (density < 0.15f) {
                    currentGapLength++
                    if (currentGapLength > bestGapLength) {
                        bestGapLength = currentGapLength
                        bestGapStart = col - currentGapLength + 1
                    }
                } else {
                    currentGapLength = 0
                }

                if (density < minDensity) {
                    minDensity = density
                    bestRelX = col
                }
            }

            val splitRelX = if (bestGapLength >= 2 && bestGapStart >= 0) {
                bestGapStart + bestGapLength / 2
            } else {
                bestRelX
            }

            if (splitRelX < 4 || splitRelX > w - 4) return null
            if (colDensities[splitRelX] > 0.40f) return null

            val splitPageX = rL + splitRelX
            val leftRect = Rect(rL, rT, splitPageX, rB)
            val rightRect = Rect(splitPageX, rT, rR, rB)

            val dLeft = (leftRect.centerX() - p1.x) * (p2.y - p1.y) - (leftRect.centerY() - p1.y) * (p2.x - p1.x)
            val (rectA, rectB) = if (dLeft >= 0) Pair(leftRect, rightRect) else Pair(rightRect, leftRect)

            val idA = line.id * 1000 + 1
            val idB = line.id * 1000 + 2
            return Pair(
                TextLineItem(id = idA, rect = rectA, angle = line.angle, confidence = line.confidence),
                TextLineItem(id = idB, rect = rectB, angle = line.angle, confidence = line.confidence)
            )
        } else {
            val xMid = (rL + rR) / 2f
            val dx = (p2.x - p1.x).toFloat()
            val yCut = if (abs(dx) > 0.001f) {
                val t = (xMid - p1.x) / dx
                (p1.y + t * (p2.y - p1.y)).roundToInt()
            } else {
                (p1.y + p2.y) / 2
            }

            val relCutY = yCut - rT
            val win = (h * 0.35f).roundToInt().coerceAtLeast(4)
            val yStart = (relCutY - win).coerceIn(4, h - 5)
            val yEnd = (relCutY + win).coerceIn(yStart + 1, h - 4)
            if (yStart >= yEnd) return null

            var minDensity = Float.MAX_VALUE
            var bestRelY = -1
            var bestGapLength = 0
            var currentGapLength = 0
            var bestGapStart = -1

            val rowDensities = FloatArray(h)
            for (row in yStart..yEnd) {
                var darkCount = 0
                for (col in 0 until w) {
                    val pix = cropPixels[row * w + col]
                    val rC = (pix ushr 16) and 0xFF
                    val gC = (pix ushr 8) and 0xFF
                    val bC = pix and 0xFF
                    val gray = (0.299f * rC + 0.587f * gC + 0.114f * bC).toInt()
                    if (gray < 165) darkCount++
                }
                val density = darkCount.toFloat() / w.toFloat()
                rowDensities[row] = density

                if (density < 0.15f) {
                    currentGapLength++
                    if (currentGapLength > bestGapLength) {
                        bestGapLength = currentGapLength
                        bestGapStart = row - currentGapLength + 1
                    }
                } else {
                    currentGapLength = 0
                }

                if (density < minDensity) {
                    minDensity = density
                    bestRelY = row
                }
            }

            val splitRelY = if (bestGapLength >= 2 && bestGapStart >= 0) {
                bestGapStart + bestGapLength / 2
            } else {
                bestRelY
            }

            if (splitRelY < 4 || splitRelY > h - 4) return null
            if (rowDensities[splitRelY] > 0.40f) return null

            val splitPageY = rT + splitRelY
            val topRect = Rect(rL, rT, rR, splitPageY)
            val bottomRect = Rect(rL, splitPageY, rR, rB)

            val dTop = (topRect.centerX() - p1.x) * (p2.y - p1.y) - (topRect.centerY() - p1.y) * (p2.x - p1.x)
            val (rectA, rectB) = if (dTop >= 0) Pair(topRect, bottomRect) else Pair(bottomRect, topRect)

            val idA = line.id * 1000 + 1
            val idB = line.id * 1000 + 2
            return Pair(
                TextLineItem(id = idA, rect = rectA, angle = line.angle, confidence = line.confidence),
                TextLineItem(id = idB, rect = rectB, angle = line.angle, confidence = line.confidence)
            )
        }
    }


    override fun close() {
        session?.close()
        session = null
        loadedPath = null
    }
}
