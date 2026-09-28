package com.raen.crunchlab.engine

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure Border Angle Crunch Splitter (100% Zero Fallback).
 *
 * Slices compound/conjoined speech bubbles into independent lobes
 * by detecting sharp inward concavity notches along the outer perimeter contour.
 */
object PureBorderAngleSplitter {

    data class CheckedBorderPoint(
        val point: Point,
        val angleDeg: Float,
        val isInward: Boolean,
        val isNotchCandidate: Boolean = false,
    )

    data class CandidateNotch(
        val label: String,
        val point: Point,
        val angleDeg: Float,
        val depth: Float,
    )

    data class SplitResult(
        val masks: List<BubbleMask>,
        val wasSplit: Boolean = false,
        val originalRect: Rect,
        val splitLobeRects: List<Rect> = emptyList(),
        val crunchPointA: Point? = null,
        val crunchPointB: Point? = null,
        val cutLinePoints: List<Point> = emptyList(),
        val waistY: Int? = null,
        val waistRatio: Float? = null,
        val searchWinA: Rect? = null,
        val searchWinB: Rect? = null,
        val cutStrategy: String? = null,
        val originalMask: BubbleMask? = null,
        val checkedBorderPoints: List<CheckedBorderPoint> = emptyList(),
        val contourPoints: List<Point> = emptyList(),
        val candidateNotches: List<CandidateNotch> = emptyList(),
    )

    enum class DetectionMethod {
        EVERY_POINT_ANGLE, // Method 2 (Default): Comprehensive perimeter angle analysis across every contour point with chord scale sweep
        WAIST_FIRST_ANGLE  // Method 1: Scan horizontal/vertical waist constriction first, then verify border angle at waist notches
    }

    private const val MIN_COMPONENT_RATIO = 0.15f // Each half must be at least 15% of bubble area

    fun trySplitBorderAngleOnly(
        original: BubbleMask,
        depth: Int = 0,
        method: DetectionMethod = DetectionMethod.EVERY_POINT_ANGLE,
        sourceBitmap: Bitmap? = null
    ): SplitResult {
        val w = original.width
        val h = original.height
        val aspectRatio = w.toFloat() / h.toFloat()
        // Reject sliver gutter strips (e.g. 51x538 panel gutters) or tiny specks
        if (w < 35 || h < 35 || original.fillArea < 300 || aspectRatio < 0.14f || aspectRatio > 7.0f) {
            return SplitResult(listOf(original), wasSplit = false, originalRect = original.rect, originalMask = original)
        }

        val result = when (method) {
            DetectionMethod.EVERY_POINT_ANGLE -> tryEveryPointAngleSplit(original, w, h, sourceBitmap)
            DetectionMethod.WAIST_FIRST_ANGLE -> tryWaistFirstAngleSplit(original, w, h, sourceBitmap)
        }

        if (result != null && result.wasSplit) {
            // Check for multi-lobe recursion (e.g. 3-lobe conjoined compound bubbles)
            if (depth < 2) {
                val subSplits = mutableListOf<BubbleMask>()
                val subCutPoints = mutableListOf<Point>().apply { addAll(result.cutLinePoints) }
                val subLobeRects = mutableListOf<Rect>()
                var anySubSplit = false

                for (childMask in result.masks) {
                    val childResult = trySplitBorderAngleOnly(childMask, depth + 1, method, sourceBitmap)
                    if (childResult.wasSplit) {
                        subSplits.addAll(childResult.masks)
                        subCutPoints.addAll(childResult.cutLinePoints)
                        subLobeRects.addAll(childResult.splitLobeRects)
                        anySubSplit = true
                    } else {
                        subSplits.add(childMask)
                        subLobeRects.add(childMask.rect)
                    }
                }

                if (anySubSplit && subSplits.size > result.masks.size) {
                    Log.i("CrunchLab", "[BorderAngle] Bubble ${original.rect}: RECURSIVE SPLIT SUCCESS -> ${subSplits.size} lobes")
                    return result.copy(
                        masks = subSplits,
                        splitLobeRects = subLobeRects,
                        cutLinePoints = subCutPoints,
                    )
                }
            }

            Log.i("CrunchLab", "[BorderAngle] Bubble ${original.rect}: SPLIT SUCCESS (${method.name}) -> ${result.masks.size} lobes")
            return result
        }

        if (depth == 0) {
            Log.i("CrunchLab", "[BorderAngle] Bubble ${original.rect}: No valid angle notch pair (${method.name}) -> UNTOUCHED (Zero Fallback)")
        }
        return result ?: SplitResult(
            masks = listOf(original),
            wasSplit = false,
            originalRect = original.rect,
            originalMask = original,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // METHOD 1: WAIST FIRST, THEN ANGLE
    // Finds narrowest waist neck along primary axis, then verifies concavity angle at border points
    // ─────────────────────────────────────────────────────────────────────────────
    private fun tryWaistFirstAngleSplit(
        original: BubbleMask,
        w: Int,
        h: Int,
        sourceBitmap: Bitmap? = null
    ): SplitResult? {
        val mask = original.mask
        val isInk = extractLocalInk(sourceBitmap, original.rect, w, h, mask)
        val contour = extractOuterContour(mask, w, h)
        if (contour.size < 24) {
            val abs = contour.map { Point(original.rect.left + it.x, original.rect.top + it.y) }
            val pts = abs.map {
                CheckedBorderPoint(
                    point = it,
                    angleDeg = 180f,
                    isInward = false,
                    isNotchCandidate = false
                )
            }
            return SplitResult(listOf(original), wasSplit = false, originalRect = original.rect, originalMask = original, checkedBorderPoints = pts, contourPoints = abs)
        }

        val n = contour.size
        val smoothed = smoothContour(contour, windowSize = 5)
        val absContour = smoothed.map { Point((original.rect.left + it.first + 0.5f).toInt(), (original.rect.top + it.second + 0.5f).toInt()) }
        // Longer chord baseline to measure macro waist concavities
        val k = (n / 14).coerceIn(12, 28)

        // 1. Scan cross-sections to locate candidate waist bottlenecks
        data class WaistCandidate(val axisPos: Int, val span: Int, val pLeft: Point, val pRight: Point, val ratio: Float, val isVerticalStack: Boolean)
        val waistCandidates = mutableListOf<WaistCandidate>()

        // 1A. Horizontal waist scan (for vertically stacked lobes)
        val yMargin = (h * 0.15f).toInt().coerceAtLeast(6)
        var maxSpanH = 0
        val spansH = IntArray(h)
        for (y in 0 until h) {
            var minX = -1; var maxX = -1
            val rOff = y * w
            for (x in 0 until w) {
                if (mask[rOff + x]) {
                    if (minX == -1) minX = x
                    maxX = x
                }
            }
            val span = if (minX != -1) maxX - minX + 1 else 0
            spansH[y] = span
            if (span > maxSpanH) maxSpanH = span
        }
        if (maxSpanH >= 16) {
            for (y in yMargin until h - yMargin) {
                val span = spansH[y]
                if (span <= 0) continue
                val prevSpan = spansH[y - 3]
                val nextSpan = spansH[y + 3]
                if (span < prevSpan && span < nextSpan && span < maxSpanH * 0.75f) {
                    var minX = 0; var maxX = w - 1
                    val rOff = y * w
                    while (minX < w && !mask[rOff + minX]) minX++
                    while (maxX >= 0 && !mask[rOff + maxX]) maxX--
                    val ratio = span.toFloat() / maxSpanH
                    waistCandidates.add(WaistCandidate(y, span, Point(minX, y), Point(maxX, y), ratio, true))
                }
            }
        }

        // 1B. Vertical waist scan (for horizontally arranged lobes)
        val xMargin = (w * 0.15f).toInt().coerceAtLeast(6)
        var maxSpanW = 0
        val spansW = IntArray(w)
        for (x in 0 until w) {
            var minY = -1; var maxY = -1
            for (y in 0 until h) {
                if (mask[y * w + x]) {
                    if (minY == -1) minY = y
                    maxY = y
                }
            }
            val span = if (minY != -1) maxY - minY + 1 else 0
            spansW[x] = span
            if (span > maxSpanW) maxSpanW = span
        }
        if (maxSpanW >= 16) {
            for (x in xMargin until w - xMargin) {
                val span = spansW[x]
                if (span <= 0) continue
                val prevSpan = spansW[x - 3]
                val nextSpan = spansW[x + 3]
                if (span < prevSpan && span < nextSpan && span < maxSpanW * 0.75f) {
                    var minY = 0; var maxY = h - 1
                    while (minY < h && !mask[minY * w + x]) minY++
                    while (maxY >= 0 && !mask[maxY * w + x]) maxY--
                    val ratio = span.toFloat() / maxSpanW
                    waistCandidates.add(WaistCandidate(x, span, Point(x, minY), Point(x, maxY), ratio, false))
                }
            }
        }

        // Compute angle for all contour points to visualize in Screen 1
        val allCheckedPoints = ArrayList<CheckedBorderPoint>(n)
        for (i in 0 until n) {
            val (angle, depth) = computeInteriorAngleAndDepth(contour, smoothed, i, k, n, mask, w, h)
            val isInward = angle < 170f && depth >= 2.5f
            val isCandidate = isInward && angle in 40f..155f && depth >= 4.5f
            allCheckedPoints.add(
                CheckedBorderPoint(
                    point = absContour[i],
                    angleDeg = if (isInward) angle else 180f,
                    isInward = isInward,
                    isNotchCandidate = isCandidate
                )
            )
        }

        if (waistCandidates.isEmpty()) {
            return SplitResult(listOf(original), wasSplit = false, originalRect = original.rect, originalMask = original, checkedBorderPoints = allCheckedPoints, contourPoints = absContour)
        }

        for (cand in waistCandidates.sortedBy { it.ratio }) {
            val idxLeft = contour.indices.minByOrNull {
                val pt = contour[it]
                (pt.x - cand.pLeft.x) * (pt.x - cand.pLeft.x) + (pt.y - cand.pLeft.y) * (pt.y - cand.pLeft.y)
            } ?: continue
            val idxRight = contour.indices.minByOrNull {
                val pt = contour[it]
                (pt.x - cand.pRight.x) * (pt.x - cand.pRight.x) + (pt.y - cand.pRight.y) * (pt.y - cand.pRight.y)
            } ?: continue

            val (angleLeft, depthLeft) = computeInteriorAngleAndDepth(contour, smoothed, idxLeft, k, n, mask, w, h)
            val (angleRight, depthRight) = computeInteriorAngleAndDepth(contour, smoothed, idxRight, k, n, mask, w, h)

            // Both sides must be genuine concavities (interior angle <= 155 deg, depth >= 4.5px)
            if (angleLeft in 40f..155f && depthLeft >= 4.5f && angleRight in 40f..155f && depthRight >= 4.5f) {
                val path = findCollisionlessPath(contour[idxRight], contour[idxLeft], mask, isInk, w, h) ?: continue
                val splitRes = executeSplit(
                    original = original,
                    w = w,
                    h = h,
                    mask = mask,
                    notchA = contour[idxRight],
                    notchB = contour[idxLeft],
                    isVertical = cand.isVerticalStack,
                    methodName = "WaistFirstAngle",
                    checkedPoints = allCheckedPoints,
                    contourPoints = absContour,
                    customCutPath = path
                )
                if (splitRes != null) return splitRes
            }
        }

        return SplitResult(listOf(original), wasSplit = false, originalRect = original.rect, originalMask = original, checkedBorderPoints = allCheckedPoints, contourPoints = absContour)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // METHOD 2: EVERY-POINT BORDER ANGLE (DEFAULT)
    // Analyzes the interior turn angle at every contour point across multiple chord scales
    // ─────────────────────────────────────────────────────────────────────────────
    private fun tryEveryPointAngleSplit(
        original: BubbleMask,
        w: Int,
        h: Int,
        sourceBitmap: Bitmap? = null
    ): SplitResult? {
        val mask = original.mask
        val isInk = extractLocalInk(sourceBitmap, original.rect, w, h, mask)
        val contour = extractOuterContour(mask, w, h)
        if (contour.size < 24) {
            val abs = contour.map { Point(original.rect.left + it.x, original.rect.top + it.y) }
            val pts = abs.map {
                CheckedBorderPoint(
                    point = it,
                    angleDeg = 180f,
                    isInward = false,
                    isNotchCandidate = false
                )
            }
            return SplitResult(listOf(original), wasSplit = false, originalRect = original.rect, originalMask = original, checkedBorderPoints = pts, contourPoints = abs)
        }

        val n = contour.size
        val smoothed = smoothContour(contour, windowSize = 5)
        val absContour = smoothed.map { Point((original.rect.left + it.first + 0.5f).toInt(), (original.rect.top + it.second + 0.5f).toInt()) }

        // Multi-scale chord sweep: fine scale (6px) captures compact triangular notches,
        // medium and macro scales (8-38px) evaluate smooth curved hourglass waists.
        val kValues = listOf(
            6,
            (n / 40).coerceIn(8, 13),
            (n / 20).coerceIn(14, 24),
            (n / 10).coerceIn(25, 38),
        ).distinct()

        data class NotchCandidate(val point: Point, val index: Int, val angleDeg: Float, val depth: Float)
        val candidates = mutableListOf<NotchCandidate>()
        val allCheckedPoints = ArrayList<CheckedBorderPoint>(n)

        for (i in 0 until n) {
            val curr = contour[i]
            val currS = smoothed[i]
            var bestInteriorAngle = 180f
            var bestDepth = 0f
            var isInward = false

            for (k in kValues) {
                val prevS = smoothed[(i - k + n) % n]
                val nextS = smoothed[(i + k) % n]

                val e1x = currS.first - prevS.first
                val e1y = currS.second - prevS.second
                val e2x = nextS.first - currS.first
                val e2y = nextS.second - currS.second

                val uLen = sqrt(e1x * e1x + e1y * e1y)
                val vLen = sqrt(e2x * e2x + e2y * e2y)
                if (uLen < 4.0f || vLen < 4.0f) continue

                // In clockwise perimeter with y-down, inward concavity is a left turn (cross-product < 0)
                val turn = e1x * e2y - e1y * e2x

                val midX = ((prevS.first + nextS.first) / 2).toInt()
                val midY = ((prevS.second + nextS.second) / 2).toInt()
                val midInside = if (midX in 0 until w && midY in 0 until h) mask[midY * w + midX] else false

                val chordDx = nextS.first - prevS.first
                val chordDy = nextS.second - prevS.second
                val chordDistSq = chordDx * chordDx + chordDy * chordDy
                if (chordDistSq < 64) continue // At least 8px Euclidean chord baseline

                val lineDist = abs(chordDy * currS.first - chordDx * currS.second + nextS.first * prevS.second - nextS.second * prevS.first) / sqrt(chordDistSq)

                val cosVal = ((e1x * e2x + e1y * e2y) / (uLen * vLen)).coerceIn(-1f, 1f)
                val turnAngleDeg = Math.toDegrees(acos(cosVal.toDouble())).toFloat()
                val interiorAngleDeg = 180f - turnAngleDeg

                val inwardK = (!midInside && lineDist >= 2.5f) || (turn < -0.15f && lineDist >= 2.5f)
                if (inwardK) {
                    isInward = true
                    if (interiorAngleDeg < bestInteriorAngle) {
                        bestInteriorAngle = interiorAngleDeg
                    }
                    if (lineDist > bestDepth) {
                        bestDepth = lineDist
                    }
                }
            }

            // Real notch candidate requires genuine inward concavity:
            // Sharp angles (<= 120°) need depth >= 4.0px
            // Moderate angles (120°..138°) need depth >= 5.0px
            // Flat angles (> 138°) are surface ripples unless depth is massive (>= 11.5px)
            val isCandidate = isInward && bestInteriorAngle in 40f..155f && (
                (bestInteriorAngle <= 120f && bestDepth >= 4.0f) ||
                (bestInteriorAngle <= 138f && bestDepth >= 5.0f) ||
                (bestDepth >= 11.5f)
            )
            if (isCandidate) {
                candidates.add(NotchCandidate(curr, i, bestInteriorAngle, bestDepth))
            }

            allCheckedPoints.add(
                CheckedBorderPoint(
                    point = absContour[i],
                    angleDeg = if (isInward) bestInteriorAngle else 180f,
                    isInward = isInward,
                    isNotchCandidate = isCandidate
                )
            )
        }

        if (candidates.isEmpty()) {
            return SplitResult(listOf(original), wasSplit = false, originalRect = original.rect, originalMask = original, checkedBorderPoints = allCheckedPoints, contourPoints = absContour)
        }

        // Sort candidates by contour index and cluster nearby points (cluster radius ~20px)
        val sortedCands = candidates.sortedBy { it.index }
        val clusteredNotches = mutableListOf<NotchCandidate>()
        var cluster = mutableListOf<NotchCandidate>()

        for (cand in sortedCands) {
            if (cluster.isEmpty()) {
                cluster.add(cand)
            } else {
                val prevCand = cluster.last()
                val idxDiff = cand.index - prevCand.index
                val distSq = (cand.point.x - prevCand.point.x) * (cand.point.x - prevCand.point.x) +
                             (cand.point.y - prevCand.point.y) * (cand.point.y - prevCand.point.y)
                if (idxDiff <= 20 && distSq <= 400) {
                    cluster.add(cand)
                } else {
                    clusteredNotches.add(cluster.minByOrNull { it.angleDeg }!!)
                    cluster = mutableListOf(cand)
                }
            }
        }
        if (cluster.isNotEmpty()) {
            clusteredNotches.add(cluster.minByOrNull { it.angleDeg }!!)
        }

        // Merge circular wrap-around cluster
        if (clusteredNotches.size >= 2) {
            val first = clusteredNotches.first()
            val last = clusteredNotches.last()
            val wrapIdx = (first.index + n) - last.index
            val wrapDistSq = (first.point.x - last.point.x) * (first.point.x - last.point.x) +
                             (first.point.y - last.point.y) * (first.point.y - last.point.y)
            if (wrapIdx <= 20 && wrapDistSq <= 400) {
                clusteredNotches.removeAt(clusteredNotches.size - 1)
                if (last.angleDeg < first.angleDeg) {
                    clusteredNotches[0] = last
                }
            }
        }

        // Directed Opposite Panel-Border Search:
        // ONLY run if the bubble has exactly ONE isolated deep waist concavity (e.g. Page 6 Bubble 9 clipped flush by a panel border).
        // If the bubble already has multiple candidate notches, all natural notches are already detected.
        if (clusteredNotches.size == 1 && clusteredNotches[0].depth >= 11.0f && clusteredNotches[0].angleDeg <= 118f) {
            val deepNotch = clusteredNotches[0]
            val p = deepNotch.point
            val isLeftSide = p.x < w * 0.5f
            val maxTolY = max(28, (h * 0.20f).toInt())

            // Look through contour points on the opposite horizontal flank
            var bestOppositeIdx = -1
            var bestOppositeScore = Float.MAX_VALUE
            for (ci in 0 until n) {
                val cpt = contour[ci]
                val onOppositeFlank = if (isLeftSide) cpt.x > w * 0.48f else cpt.x < w * 0.52f
                if (!onOppositeFlank) continue
                if (abs(cpt.y - p.y) > maxTolY) continue

                val cp = allCheckedPoints[ci]
                if (cp.isInward && cp.angleDeg in 40f..145f) {
                    val score = cp.angleDeg + abs(cpt.y - p.y) * 0.8f
                    if (score < bestOppositeScore) {
                        bestOppositeScore = score
                        bestOppositeIdx = ci
                    }
                }
            }

            if (bestOppositeIdx >= 0) {
                val oppPoint = contour[bestOppositeIdx]
                val oppAngle = allCheckedPoints[bestOppositeIdx].angleDeg
                val oppDepth = 3.0f // panel border concavity
                clusteredNotches.add(NotchCandidate(oppPoint, bestOppositeIdx, oppAngle, oppDepth))
                allCheckedPoints[bestOppositeIdx] = allCheckedPoints[bestOppositeIdx].copy(isNotchCandidate = true)
            }
        }

        if (clusteredNotches.isEmpty()) {
            return SplitResult(listOf(original), wasSplit = false, originalRect = original.rect, originalMask = original, checkedBorderPoints = allCheckedPoints, contourPoints = absContour)
        }

        // Label all candidate notches with clean A, B, C, D... letter identifiers
        val labeledNotches = clusteredNotches.mapIndexed { idx, notch ->
            val letter = if (idx < 26) ('A' + idx).toString() else ('A' + (idx / 26 - 1)).toString() + ('A' + (idx % 26)).toString()
            CandidateNotch(
                label = letter,
                point = Point(original.rect.left + notch.point.x, original.rect.top + notch.point.y),
                angleDeg = notch.angleDeg,
                depth = notch.depth
            )
        }

        // ─────────────────────────────────────────────────────────────────────────
        // CANDIDATE PAIR GENERATION & OPPOSITE BOTTLENECK SEARCH
        // ─────────────────────────────────────────────────────────────────────────
        data class EvaluatedPair(
            val notchA: Point,
            val notchB: Point,
            val angleA: Float,
            val angleB: Float,
            val isVerticalStack: Boolean,
            val score: Float,
            val path: List<Point>
        )

        val candidatePairs = mutableListOf<EvaluatedPair>()

        fun evaluateAndAddPair(pA: Point, pB: Point, angA: Float, angB: Float, depthA: Float, depthB: Float) {
            val dirX = (pB.x - pA.x).toFloat()
            val dirY = (pB.y - pA.y).toFloat()
            val absDx = abs(dirX)
            val absDy = abs(dirY)
            val spatialDist = sqrt(dirX * dirX + dirY * dirY)
            if (spatialDist < 12f) return

            val minX = min(pA.x, pB.x)
            val maxX = max(pA.x, pB.x)
            val minY = min(pA.y, pB.y)
            val maxY = max(pA.y, pB.y)
            val midX = (pA.x + pB.x) / 2f
            val midY = (pA.y + pB.y) / 2f

            // Strict Notch Pair Quality Rule:
            // 1. Both notches must be inward concavities <= 140° (rejects flat ripples)
            if (angA > 140f || angB > 140f) return
            // 2. At least one notch must be a distinct waist concavity <= 118°
            if (min(angA, angB) > 118f) return
            // 3. Combined concavity depth must reflect a genuine waist (>= 14.5px total)
            if (depthA + depthB < 14.5f) return

            // Dual Topology Evaluation:
            // Topology 1: Left Flank <-> Right Flank (Vertical Stack: separates upper and lower lobes)
            val isOppHorizontal = (minX <= w * 0.52f && maxX >= w * 0.48f)
            // Topology 2A: Top Flank <-> Bottom Flank (Full Horizontal Stack: separates left and right lobes)
            val isOppVertical = (minY <= h * 0.52f && maxY >= h * 0.48f)
            // Topology 2B: Side / Shoulder Satellite Lobe (deep waist notches defining satellite neck)
            val sameFlank = (minX >= w * 0.45f || maxX <= w * 0.55f)
            val isSatelliteNeck = sameFlank && (absDy in 35f..(h * 0.38f)) && (depthA + depthB >= 25f) &&
                                  spatialDist <= max(w, h) * 0.55f && absDx <= w * 0.40f

            if (!isOppHorizontal && !isOppVertical && !isSatelliteNeck) return

            var bestIsVertStack: Boolean? = null
            var bestPenalty = Float.MAX_VALUE

            if (isOppHorizontal) {
                // For vertically stacked lobes:
                // 1. Waist cannot be at extreme vertical poles
                val notExtremeY = (minY >= h * 0.10f && maxY <= h * 0.90f && midY >= h * 0.18f && midY <= h * 0.82f)
                // 2. Neck constriction: neck distance must be narrower than the bubble width
                val narrowNeck = (spatialDist <= w * 0.90f)
                // 3. Vertical slant limit: max 45% of bubble height
                val slantOk = (absDy <= h * 0.45f)
                if (notExtremeY && narrowNeck && slantOk) {
                    val penalty = absDy * 0.6f
                    if (penalty < bestPenalty) {
                        bestPenalty = penalty
                        bestIsVertStack = true
                    }
                }
            }

            if (isOppVertical) {
                // For horizontally arranged lobes:
                // 1. Bubble must be wide enough to host two side-by-side lobes
                val aspectOk = (w.toFloat() / h >= 0.50f && w >= 60)
                // 2. Waist cannot be at extreme horizontal flanks
                val notExtremeX = (minX >= w * 0.12f && maxX <= w * 0.88f && midX >= w * 0.20f && midX <= w * 0.80f)
                val notExtremeY = (minY >= h * 0.12f && maxY <= h * 0.88f)
                // 3. Neck constriction: neck distance must be narrower than the bubble height
                val narrowNeck = (spatialDist <= h * 0.90f)
                // 4. Horizontal slant limit: max 45% of bubble width
                val slantOk = (absDx <= w * 0.45f)
                if (aspectOk && notExtremeX && notExtremeY && narrowNeck && slantOk) {
                    val penalty = absDx * 0.6f
                    if (penalty < bestPenalty) {
                        bestPenalty = penalty
                        bestIsVertStack = false
                    }
                }
            }

            if (isSatelliteNeck) {
                val notExtremeY = (minY >= h * 0.12f && maxY <= h * 0.88f)
                if (notExtremeY) {
                    val penalty = absDx * 0.6f
                    if (penalty < bestPenalty) {
                        bestPenalty = penalty
                        bestIsVertStack = false
                    }
                }
            }

            if (bestIsVertStack == null) return
            val isVerticalStack = bestIsVertStack
            val alignmentPenalty = bestPenalty

            // Find collisionless shortest path through white space
            val path = findCollisionlessPath(pA, pB, mask, isInk, w, h) ?: return

            // Calculate path length
            var pathLen = 0f
            for (k in 0 until path.size - 1) {
                val p1 = path[k]
                val p2 = path[k + 1]
                pathLen += sqrt(((p2.x - p1.x) * (p2.x - p1.x) + (p2.y - p1.y) * (p2.y - p1.y)).toFloat())
            }

            // If path has to detour heavily around text (> 1.75x direct distance), reject
            if (pathLen > spatialDist * 1.75f) return

            // Angle scoring:
            // Waist notches have sharp inward angles (50°–90°).
            // Penalize flatter angles (> 90°)
            val angleCostA = if (angA > 90f) (angA - 90f) * 1.5f else 0f
            val angleCostB = if (angB > 90f) (angB - 90f) * 1.5f else 0f

            // Concavity depth bonus: deeper concavities indicate true macro waist pinches
            val depthBonus = (depthA + depthB) * 1.5f

            val score = angleCostA + angleCostB + pathLen * 0.35f + alignmentPenalty - depthBonus

            candidatePairs.add(EvaluatedPair(pA, pB, angA, angB, isVerticalStack, score, path))
        }

        // Evaluate pairs strictly between detected notch candidates (pink matches pink ONLY)
        for (i in 0 until clusteredNotches.size) {
            for (j in i + 1 until clusteredNotches.size) {
                val cA = clusteredNotches[i]
                val cB = clusteredNotches[j]
                val indexDist = min(abs(cA.index - cB.index), n - abs(cA.index - cB.index))
                if (indexDist < (n * 0.18f).toInt()) continue
                evaluateAndAddPair(cA.point, cB.point, cA.angleDeg, cB.angleDeg, cA.depth, cB.depth)
            }
        }

        if (candidatePairs.isEmpty()) {
            return SplitResult(listOf(original), wasSplit = false, originalRect = original.rect, originalMask = original, checkedBorderPoints = allCheckedPoints, contourPoints = absContour, candidateNotches = labeledNotches)
        }

        // Try candidate pairs in order of score until one yields a valid geometric split
        val sortedPairs = candidatePairs.sortedBy { it.score }
        for (candidate in sortedPairs) {
            val splitRes = executeSplit(
                original = original,
                w = w,
                h = h,
                mask = mask,
                notchA = candidate.notchA,
                notchB = candidate.notchB,
                isVertical = candidate.isVerticalStack,
                methodName = "EveryPointAngle (${candidate.angleA.toInt()}° / ${candidate.angleB.toInt()}°)",
                checkedPoints = allCheckedPoints,
                contourPoints = absContour,
                customCutPath = candidate.path,
                candidateNotches = labeledNotches
            )
            if (splitRes != null) return splitRes
        }

        return SplitResult(listOf(original), wasSplit = false, originalRect = original.rect, originalMask = original, checkedBorderPoints = allCheckedPoints, contourPoints = absContour, candidateNotches = labeledNotches)
    }

    private fun smoothContour(raw: List<Point>, windowSize: Int = 5): List<Pair<Float, Float>> {
        val n = raw.size
        if (n < windowSize) return raw.map { Pair(it.x.toFloat(), it.y.toFloat()) }
        val half = windowSize / 2
        return List(n) { i ->
            var sumX = 0f
            var sumY = 0f
            var weightSum = 0f
            for (w in -half..half) {
                val idx = (i + w + n) % n
                val weight = (half + 1 - abs(w)).toFloat()
                sumX += raw[idx].x * weight
                sumY += raw[idx].y * weight
                weightSum += weight
            }
            Pair(sumX / weightSum, sumY / weightSum)
        }
    }

    private fun computeInteriorAngleAndDepth(
        contour: List<Point>,
        smoothed: List<Pair<Float, Float>>,
        i: Int,
        k: Int,
        n: Int,
        mask: BooleanArray,
        w: Int,
        h: Int
    ): Pair<Float, Float> {
        val currS = smoothed[i]
        val prevS = smoothed[(i - k + n) % n]
        val nextS = smoothed[(i + k) % n]

        val e1x = currS.first - prevS.first
        val e1y = currS.second - prevS.second
        val e2x = nextS.first - currS.first
        val e2y = nextS.second - currS.second

        val uLen = sqrt(e1x * e1x + e1y * e1y)
        val vLen = sqrt(e2x * e2x + e2y * e2y)
        if (uLen < 4.0f || vLen < 4.0f) return Pair(180f, 0f)

        val turn = e1x * e2y - e1y * e2x
        val midX = ((prevS.first + nextS.first) / 2).toInt()
        val midY = ((prevS.second + nextS.second) / 2).toInt()
        val midInside = if (midX in 0 until w && midY in 0 until h) mask[midY * w + midX] else false

        val chordDx = nextS.first - prevS.first
        val chordDy = nextS.second - prevS.second
        val chordDistSq = chordDx * chordDx + chordDy * chordDy
        if (chordDistSq < 64) return Pair(180f, 0f)
        val lineDist = abs(chordDy * currS.first - chordDx * currS.second + nextS.first * prevS.second - nextS.second * prevS.first) / sqrt(chordDistSq)

        val cosVal = ((e1x * e2x + e1y * e2y) / (uLen * vLen)).coerceIn(-1f, 1f)
        val turnAngleDeg = Math.toDegrees(acos(cosVal.toDouble())).toFloat()
        val interiorAngleDeg = 180f - turnAngleDeg

        val isInward = (!midInside && lineDist >= 2.5f) || (turn < -0.15f && lineDist >= 2.5f)
        return if (isInward) Pair(interiorAngleDeg, lineDist) else Pair(180f, 0f)
    }

    private fun executeSplit(
        original: BubbleMask,
        w: Int,
        h: Int,
        mask: BooleanArray,
        notchA: Point,
        notchB: Point,
        isVertical: Boolean,
        methodName: String,
        checkedPoints: List<CheckedBorderPoint> = emptyList(),
        contourPoints: List<Point> = emptyList(),
        customCutPath: List<Point>? = null,
        candidateNotches: List<CandidateNotch> = emptyList()
    ): SplitResult? {
        val absA = Point(original.rect.left + notchA.x, original.rect.top + notchA.y)
        val absB = Point(original.rect.left + notchB.x, original.rect.top + notchB.y)

        val winSize = 14
        val absWinA = Rect(absA.x - winSize, absA.y - winSize, absA.x + winSize, absA.y + winSize)
        val absWinB = Rect(absB.x - winSize, absB.y - winSize, absB.x + winSize, absB.y + winSize)
        val waistYLocal = (notchA.y + notchB.y) / 2

        val cutPoints = customCutPath?.map { Point(original.rect.left + it.x, original.rect.top + it.y) }
            ?: listOf(absA, absB)

        // Partition the binary mask along seam segment between A and B
        val maskUpper = BooleanArray(w * h)
        val maskLower = BooleanArray(w * h)
        var areaUpper = 0
        var areaLower = 0

        if (isVertical) {
            for (y in 0 until h) {
                val yAbs = original.rect.top + y
                val rOff = y * w
                for (x in 0 until w) {
                    val idx = rOff + x
                    if (!mask[idx]) continue
                    val xAbs = original.rect.left + x

                    val seamY = getSeamYAtX(cutPoints, xAbs, original.rect.top + waistYLocal)
                    if (yAbs < seamY) {
                        maskUpper[idx] = true; areaUpper++
                    } else {
                        maskLower[idx] = true; areaLower++
                    }
                }
            }
        } else {
            val waistXLocal = (notchA.x + notchB.x) / 2
            for (y in 0 until h) {
                val yAbs = original.rect.top + y
                val rOff = y * w
                for (x in 0 until w) {
                    val idx = rOff + x
                    if (!mask[idx]) continue
                    val xAbs = original.rect.left + x

                    val seamX = getSeamXAtY(cutPoints, yAbs, original.rect.left + waistXLocal)
                    if (xAbs < seamX) {
                        maskUpper[idx] = true; areaUpper++
                    } else {
                        maskLower[idx] = true; areaLower++
                    }
                }
            }
        }

        val minAllowed = (original.fillArea * MIN_COMPONENT_RATIO).toInt()
        if (areaUpper < minAllowed || areaLower < minAllowed) return null

        val upperObj = cropMask(maskUpper, w, h, original.rect, areaUpper) ?: return null
        val lowerObj = cropMask(maskLower, w, h, original.rect, areaLower) ?: return null

        // Constriction validation: ensure the waist is genuinely narrower than the surrounding lobes
        val neckDist = sqrt(((notchA.x - notchB.x) * (notchA.x - notchB.x) + (notchA.y - notchB.y) * (notchA.y - notchB.y)).toFloat())
        val maxLobeSpan = if (isVertical) {
            max(upperObj.rect.width(), lowerObj.rect.width()).toFloat()
        } else {
            max(upperObj.rect.height(), lowerObj.rect.height()).toFloat()
        }
        val maxNeckRatio = if (isVertical) 0.70f else 0.75f
        if (maxLobeSpan > 0f && (neckDist / maxLobeSpan) > maxNeckRatio) {
            Log.i("CrunchLab", "[BorderAngle] Rejected split: neck ratio ${neckDist / maxLobeSpan} exceeds $maxNeckRatio limit")
            return null
        }

        return SplitResult(
            masks = listOf(upperObj, lowerObj),
            wasSplit = true,
            originalRect = original.rect,
            splitLobeRects = listOf(upperObj.rect, lowerObj.rect),
            crunchPointA = absA,
            crunchPointB = absB,
            cutLinePoints = cutPoints,
            waistY = original.rect.top + waistYLocal,
            waistRatio = if (maxLobeSpan > 0f) neckDist / maxLobeSpan else 0.50f,
            searchWinA = absWinA,
            searchWinB = absWinB,
            cutStrategy = "Pure Border Angle ($methodName)",
            originalMask = original,
            checkedBorderPoints = checkedPoints,
            contourPoints = contourPoints,
            candidateNotches = candidateNotches,
        )
    }

    private fun getSeamYAtX(cutPoints: List<Point>, x: Int, fallbackY: Int): Float {
        if (cutPoints.isEmpty()) return fallbackY.toFloat()
        if (cutPoints.size == 1) return cutPoints[0].y.toFloat()
        val sorted = cutPoints.sortedBy { it.x }
        if (x <= sorted.first().x) return sorted.first().y.toFloat()
        if (x >= sorted.last().x) return sorted.last().y.toFloat()
        for (i in 0 until sorted.size - 1) {
            val p1 = sorted[i]
            val p2 = sorted[i + 1]
            if (x in p1.x..p2.x) {
                if (p2.x == p1.x) return min(p1.y, p2.y).toFloat()
                val t = (x - p1.x).toFloat() / (p2.x - p1.x)
                return p1.y + t * (p2.y - p1.y)
            }
        }
        return fallbackY.toFloat()
    }

    private fun getSeamXAtY(cutPoints: List<Point>, y: Int, fallbackX: Int): Float {
        if (cutPoints.isEmpty()) return fallbackX.toFloat()
        if (cutPoints.size == 1) return cutPoints[0].x.toFloat()
        val sorted = cutPoints.sortedBy { it.y }
        if (y <= sorted.first().y) return sorted.first().x.toFloat()
        if (y >= sorted.last().y) return sorted.last().x.toFloat()
        for (i in 0 until sorted.size - 1) {
            val p1 = sorted[i]
            val p2 = sorted[i + 1]
            if (y in p1.y..p2.y) {
                if (p2.y == p1.y) return min(p1.x, p2.x).toFloat()
                val t = (y - p1.y).toFloat() / (p2.y - p1.y)
                return p1.x + t * (p2.x - p1.x)
            }
        }
        return fallbackX.toFloat()
    }

    private fun cropMask(mask: BooleanArray, w: Int, h: Int, parentRect: Rect, fillArea: Int): BubbleMask? {
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (y in 0 until h) {
            val rOff = y * w
            for (x in 0 until w) {
                if (mask[rOff + x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        val cw = maxX - minX + 1
        val ch = maxY - minY + 1
        val cropped = BooleanArray(cw * ch)
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                cropped[y * cw + x] = mask[(minY + y) * w + (minX + x)]
            }
        }
        return BubbleMask(
            rect = Rect(parentRect.left + minX, parentRect.top + minY, parentRect.left + maxX + 1, parentRect.top + maxY + 1),
            mask = cropped,
            width = cw,
            height = ch,
            fillArea = fillArea,
        )
    }

    private fun morphClose(mask: BooleanArray, w: Int, h: Int, radius: Int = 2): BooleanArray {
        // Fast separable 1D dilation
        val hDilated = BooleanArray(w * h)
        for (y in 0 until h) {
            val rOff = y * w
            for (x in 0 until w) {
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx in 0 until w && mask[rOff + nx]) {
                        hDilated[rOff + x] = true
                        break
                    }
                }
            }
        }
        val dilated = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny in 0 until h && hDilated[ny * w + x]) {
                        dilated[y * w + x] = true
                        break
                    }
                }
            }
        }

        // Fast separable 1D erosion
        val hEroded = BooleanArray(w * h)
        for (y in 0 until h) {
            val rOff = y * w
            for (x in 0 until w) {
                var allTrue = true
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx !in 0 until w || !dilated[rOff + nx]) {
                        allTrue = false
                        break
                    }
                }
                hEroded[rOff + x] = allTrue
            }
        }
        val closed = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var allTrue = true
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny !in 0 until h || !hEroded[ny * w + x]) {
                        allTrue = false
                        break
                    }
                }
                closed[y * w + x] = allTrue
            }
        }
        return closed
    }

    private fun extractOuterContour(mask: BooleanArray, w: Int, h: Int): List<Point> {
        val closed = morphClose(mask, w, h, radius = 2)
        val visited = BooleanArray(w * h)
        var largestContour = emptyList<Point>()

        for (y in 0 until h) {
            val rOff = y * w
            for (x in 0 until w) {
                val idx = rOff + x
                if (!closed[idx] || visited[idx]) continue

                val isBoundary = (x == 0 || !closed[idx - 1]) ||
                                 (x == w - 1 || !closed[idx + 1]) ||
                                 (y == 0 || !closed[idx - w]) ||
                                 (y == h - 1 || !closed[idx + w])
                if (!isBoundary) continue

                val contour = traceContour(closed, w, h, x, y, visited)
                if (contour.size > largestContour.size) {
                    largestContour = contour
                }
            }
        }

        return largestContour
    }

    private fun traceContour(
        mask: BooleanArray,
        w: Int,
        h: Int,
        startX: Int,
        startY: Int,
        visited: BooleanArray
    ): List<Point> {
        val contour = mutableListOf<Point>()
        val dirsX = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        val dirsY = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

        var currX = startX
        var currY = startY
        var dir = 0

        contour.add(Point(currX, currY))
        visited[currY * w + currX] = true
        val maxSteps = w * h

        for (step in 0 until maxSteps) {
            var foundNext = false
            val startSearchDir = (dir + 5) % 8
            for (i in 0 until 8) {
                val checkDir = (startSearchDir + i) % 8
                val nx = currX + dirsX[checkDir]
                val ny = currY + dirsY[checkDir]

                if (nx in 0 until w && ny in 0 until h && mask[ny * w + nx]) {
                    currX = nx
                    currY = ny
                    dir = checkDir
                    foundNext = true
                    break
                }
            }

            if (!foundNext) break
            if (currX == startX && currY == startY) break

            contour.add(Point(currX, currY))
            visited[currY * w + currX] = true
        }

        return contour
    }

    private fun extractLocalInk(
        sourceBitmap: Bitmap?,
        rect: Rect,
        w: Int,
        h: Int,
        mask: BooleanArray
    ): BooleanArray? {
        if (sourceBitmap == null) return null
        val bw = sourceBitmap.width
        val bh = sourceBitmap.height

        val rawInk = BooleanArray(w * h)
        var anyInk = false

        for (y in 0 until h) {
            val sy = rect.top + y
            if (sy !in 0 until bh) continue
            val rOff = y * w
            for (x in 0 until w) {
                if (!mask[rOff + x]) continue
                val sx = rect.left + x
                if (sx !in 0 until bw) continue

                val color = sourceBitmap.getPixel(sx, sy)
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                // Text ink in manga speech bubbles is dark black/gray (lum < 115)
                if (lum < 115) {
                    rawInk[rOff + x] = true
                    anyInk = true
                }
            }
        }

        if (!anyInk) return null

        // 1px dilation safety buffer around text ink
        val dilatedInk = BooleanArray(w * h)
        for (y in 0 until h) {
            val rOff = y * w
            for (x in 0 until w) {
                if (!rawInk[rOff + x]) continue
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until h) continue
                    val nOff = ny * w
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx in 0 until w) {
                            dilatedInk[nOff + nx] = true
                        }
                    }
                }
            }
        }
        return dilatedInk
    }

    private fun findCollisionlessPath(
        start: Point,
        target: Point,
        mask: BooleanArray,
        isInk: BooleanArray?,
        w: Int,
        h: Int
    ): List<Point>? {
        val dx = (target.x - start.x).toFloat()
        val dy = (target.y - start.y).toFloat()
        val directDist = sqrt(dx * dx + dy * dy)
        if (directDist < 4f) return listOf(start, target)

        // If no ink detected, verify line-of-sight through mask
        if (isInk == null) {
            var insideCount = 0
            val samples = 16
            for (s in 1 until samples) {
                val t = s.toFloat() / samples
                val sx = (start.x + t * dx).toInt().coerceIn(0, w - 1)
                val sy = (start.y + t * dy).toInt().coerceIn(0, h - 1)
                if (mask[sy * w + sx]) insideCount++
            }
            return if (insideCount >= (samples - 1) * 0.70f) listOf(start, target) else null
        }

        // 8-directional A* pathfinding through white space
        val dirsX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        val dirsY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        val stepCost = floatArrayOf(1f, 1f, 1f, 1f, 1.414f, 1.414f, 1.414f, 1.414f)

        data class AStarNode(val x: Int, val y: Int, val g: Float, val f: Float) : Comparable<AStarNode> {
            override fun compareTo(other: AStarNode): Int = f.compareTo(other.f)
        }

        val totalPixels = w * h
        val gScore = FloatArray(totalPixels) { Float.MAX_VALUE }
        val parent = IntArray(totalPixels) { -1 }
        val openSet = java.util.PriorityQueue<AStarNode>()

        val startIdx = start.y * w + start.x
        gScore[startIdx] = 0f
        openSet.add(AStarNode(start.x, start.y, 0f, directDist))

        val maxIterations = 14000
        var iterations = 0
        var reached = false

        while (openSet.isNotEmpty() && iterations++ < maxIterations) {
            val curr = openSet.poll() ?: break
            val currIdx = curr.y * w + curr.x
            if (curr.g > gScore[currIdx]) continue

            // Goal reached within 1.5px
            if (abs(curr.x - target.x) <= 1 && abs(curr.y - target.y) <= 1) {
                reached = true
                if (currIdx != target.y * w + target.x) {
                    val targetIdx = target.y * w + target.x
                    parent[targetIdx] = currIdx
                }
                break
            }

            for (d in 0 until 8) {
                val nx = curr.x + dirsX[d]
                val ny = curr.y + dirsY[d]
                if (nx !in 0 until w || ny !in 0 until h) continue
                val nIdx = ny * w + nx

                // Must stay inside bubble mask (allow 5px tolerance right at notch border ink)
                val distStartSq = (nx - start.x) * (nx - start.x) + (ny - start.y) * (ny - start.y)
                val distTargetSq = (nx - target.x) * (nx - target.x) + (ny - target.y) * (ny - target.y)
                val isNearEndpoint = distStartSq <= 25 || distTargetSq <= 25
                if (!mask[nIdx] && !isNearEndpoint) continue

                // Avoid ink obstacles (allow reaching notch endpoints)
                if (isInk[nIdx] && !isNearEndpoint) continue

                val tentativeG = curr.g + stepCost[d]
                if (tentativeG < gScore[nIdx]) {
                    gScore[nIdx] = tentativeG
                    parent[nIdx] = currIdx
                    val hDist = sqrt(((target.x - nx) * (target.x - nx) + (target.y - ny) * (target.y - ny)).toFloat())
                    openSet.add(AStarNode(nx, ny, tentativeG, tentativeG + hDist))
                }
            }
        }

        if (!reached) return null

        // Reconstruct path
        val rawPath = mutableListOf<Point>()
        var currIdx = target.y * w + target.x
        rawPath.add(target)
        var stepLimit = totalPixels
        while (currIdx != startIdx && currIdx != -1 && stepLimit-- > 0) {
            val pIdx = parent[currIdx]
            if (pIdx == -1) break
            val px = pIdx % w
            val py = pIdx / w
            rawPath.add(Point(px, py))
            currIdx = pIdx
        }
        if (rawPath.last() != start) rawPath.add(start)
        val path = rawPath.reversed()

        // Simplify path: keep every 4th point plus start and target
        val simplified = mutableListOf<Point>()
        simplified.add(path.first())
        for (i in 4 until path.size - 4 step 4) {
            simplified.add(path[i])
        }
        simplified.add(path.last())
        return simplified
    }
}
