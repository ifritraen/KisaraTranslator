package com.raen.kisaratranslator.engine.grouping

import android.graphics.Point
import android.graphics.Rect
import com.raen.kisaratranslator.data.logger.AppLogger
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detects and splits conjoined ("crunched") speech bubble masks.
 *
 * Dual-Engine Splitting Strategy:
 * 1. Geometric Crunch Engine:
 *    - Euclidean Distance Transform (2-pass Meijster).
 *    - Topological local maxima peak detection (detects unequal lobes).
 *    - Saddle point / waist depth verification along line between peak centers.
 *    - Marker-controlled watershed with exact boundary seam measurement.
 * 2. Text-Cluster Bottleneck Fallback:
 *    - If geometric peaks are smoothed out by segmentation, checks if inside
 *      text lines form distinct spatial clusters with a clear dead-space gap
 *      (e.g. upper speaker vs. lower speaker).
 *    - Splits the mask across the gap line.
 * 3. Method 8 v2 Laser Cut Engine:
 *    - High-Res Perimeter Width/Height Scanning.
 *    - Pinpoints Crunch Notch Point A and Point B.
 *    - Obstacle-Aware Laser Seam Cut deflecting around text line boundaries.
 */
object CrunchSplitter {

    /** Result of attempting to split a bubble mask. */
    data class SplitResult(
        val masks: List<BubbleMaskExtractor.BubbleMask>,
        val wasSplit: Boolean = false,
        val originalRect: Rect? = null,
        val splitLobeRects: List<Rect> = emptyList(),
        val seamPoints: List<Pair<Int, Int>> = emptyList(),
        val crunchPointA: Point? = null,
        val crunchPointB: Point? = null,
        val cutLinePoints: List<Point> = emptyList(),
        val waistY: Int? = null,
        val waistX: Int? = null,
        val waistRatio: Float? = null,
        val searchWinA: Rect? = null,
        val searchWinB: Rect? = null,
        val clashingLines: List<Rect> = emptyList(),
        val cutStrategy: String? = null,
        val originalMask: BubbleMaskExtractor.BubbleMask? = null,
        val insideLines: List<Rect> = emptyList(),
    )

    private const val MIN_COMPONENT_RATIO = 0.15f // each half must be >= 15% of total area
    private const val MIN_PEAK_RADIUS     = 6.0f  // peak must be at least 6px deep inside bubble

    /**
     * Attempts to split a conjoined bubble mask into independent speech lobes.
     *
     * @param original     The bubble mask to inspect.
     * @param insideLines  Text lines located inside or touching this bubble.
     */
    fun trySplit(
        original: BubbleMaskExtractor.BubbleMask,
        insideLines: List<Rect> = emptyList(),
    ): SplitResult {
        val w = original.width
        val h = original.height
        if (w < 20 || h < 20 || original.fillArea < 150) {
            return SplitResult(listOf(original))
        }

        // ── 1. Geometric Distance Transform Split ─────────────────────────────
        val dist = computeDistanceTransform(original.mask, w, h)
        val maxDist = dist.maxOrNull() ?: 0f
        if (maxDist >= MIN_PEAK_RADIUS) {
            val peaks = findTopologicalPeaks(dist, original.mask, w, h, maxDist)
            if (peaks.size >= 2) {
                // Verify that at least one pair of peaks has a saddle/crunch dip
                val validSeeds = findValidConstrictionPairs(peaks, dist, w, h)
                if (validSeeds.size >= 2) {
                    val watershedResult = runWatershedSplit(original, dist, validSeeds, w, h)
                    if (watershedResult != null && watershedResult.size >= 2) {
                        AppLogger.info("[CrunchSplitter] Geometric split succeeded for ${original.rect} -> ${watershedResult.size} lobes")
                        val absA = Point(original.rect.left + validSeeds[0].x, original.rect.top + validSeeds[0].y)
                        val absB = Point(original.rect.left + validSeeds[1].x, original.rect.top + validSeeds[1].y)
                        return SplitResult(
                            masks = watershedResult,
                            wasSplit = true,
                            originalRect = original.rect,
                            splitLobeRects = watershedResult.map { it.rect },
                            crunchPointA = absA,
                            crunchPointB = absB,
                            cutLinePoints = listOf(absA, absB),
                            cutStrategy = "Watershed Ridge",
                            originalMask = original,
                            insideLines = insideLines,
                        )
                    }
                }
            }
        }

        // ── 2. Text-Guided Cluster Split Fallback ──────────────────────────────
        // If geometric contour was slightly smoothed by detector, check interior text lines
        if (insideLines.size >= 2) {
            val textSplitResult = tryTextGuidedSplit(original, insideLines)
            if (textSplitResult != null && textSplitResult.size >= 2) {
                AppLogger.info("[CrunchSplitter] Text-guided split succeeded for ${original.rect} -> ${textSplitResult.size} lobes")
                return SplitResult(
                    masks = textSplitResult,
                    wasSplit = true,
                    originalRect = original.rect,
                    splitLobeRects = textSplitResult.map { it.rect },
                    originalMask = original,
                    insideLines = insideLines,
                )
            }
        }

        // Single bubble, no crunch detected
        return SplitResult(listOf(original))
    }

    // ─── 0. Method 8 v2: High-Res Contour Crunch Detection & Obstacle-Aware Laser Cut ───

    /**
     * Method 8 v2: High-Resolution Contour Crunch Detection & Obstacle-Aware Laser Cut.
     *
     * 1. Scans the perimeter width profile W(y) to pinpoint the exact waist constriction.
     * 2. Detects Crunch Notch Point A (left wall) and Point B (right wall).
     * 3. Performs Obstacle-Aware Seam Cut:
     *    - Clean: Straight cut line segment A -> B.
     *    - Same-Lobe Clash: Deflects above lower lines or below upper lines.
     *    - Both-Lobes Clash: Threads cleanly through the corridor midpoint between clash points.
     * 4. Slices the mask into two independent lobes with zero-pixel character clipping.
     */
    fun trySplitLaser(
        original: BubbleMaskExtractor.BubbleMask,
        insideLines: List<Rect> = emptyList(),
    ): SplitResult {
        val w = original.width
        val h = original.height
        if (w < 25 || h < 25 || original.fillArea < 180) {
            return SplitResult(listOf(original))
        }

        // 0. High-Accuracy Contour Angle Notch Detection (handles staggered `- | -`, diagonal, and unequal sizes)
        val angleNotchCut = tryAngleNotchSplit(original, insideLines, w, h)
        if (angleNotchCut != null) {
            return angleNotchCut
        }

        // 1. Calculate row-by-row width profile W(y)
        val rowLeft = IntArray(h) { -1 }
        val rowRight = IntArray(h) { -1 }
        val rowWidth = FloatArray(h)

        for (y in 0 until h) {
            var minX = -1; var maxX = -1
            for (x in 0 until w) {
                if (original.mask[y * w + x]) {
                    if (minX == -1) minX = x
                    maxX = x
                }
            }
            if (minX != -1) {
                rowLeft[y] = minX
                rowRight[y] = maxX
                rowWidth[y] = (maxX - minX + 1).toFloat()
            }
        }

        // 5-point moving average smoothing to eliminate boundary pixel noise
        val smoothW = FloatArray(h)
        for (y in 0 until h) {
            var sum = 0f; var count = 0
            for (dy in -2..2) {
                val ny = y + dy
                if (ny in 0 until h && rowWidth[ny] > 0) {
                    sum += rowWidth[ny]; count++
                }
            }
            smoothW[y] = if (count > 0) sum / count else 0f
        }

        // Dynamic waist search:
        // Find optimal waist Y in [0.15h .. 0.85h] that minimizes W(y) / min(W_upper_peak, W_lower_peak)
        var bestWaistY = -1
        var bestRatio = Float.MAX_VALUE
        var bestNarrowerLobe = 0f

        val scanStart = (h * 0.15f).toInt()
        val scanEnd = (h * 0.85f).toInt()

        for (y in scanStart..scanEnd) {
            val wy = smoothW[y]
            if (wy < 10f) continue

            // Peak in upper region [0.05h .. y - 8]
            var maxUp = 0f
            val upLimit = max(0, y - 8)
            for (uy in (h * 0.05f).toInt()..upLimit) {
                if (smoothW[uy] > maxUp) maxUp = smoothW[uy]
            }

            // Peak in lower region [y + 8 .. 0.95h]
            var maxDown = 0f
            val downStart = min(h - 1, y + 8)
            for (dy in downStart..(h * 0.95f).toInt()) {
                if (smoothW[dy] > maxDown) maxDown = smoothW[dy]
            }

            if (maxUp >= 18f && maxDown >= 18f) {
                val narrowerLobe = min(maxUp, maxDown)
                val ratio = wy / narrowerLobe
                if (ratio < bestRatio) {
                    bestRatio = ratio
                    bestWaistY = y
                    bestNarrowerLobe = narrowerLobe
                }
            }
        }

        // A waist is considered valid if it's at least 12% narrower than the narrower lobe
        if (bestWaistY != -1 && bestRatio <= 0.88f) {
            val waistY = bestWaistY

            // Refine local inward notch crunch points A & B in a ±10px neighborhood around waistY:
            // Point A (left boundary) has maximum x (deepest inward notch into bubble from left)
            var bestAx = -1; var bestAy = waistY
            val localWinStart = max(0, waistY - 10)
            val localWinEnd = min(h - 1, waistY + 10)
            val absWinA = Rect(original.rect.left, original.rect.top + localWinStart, original.rect.left + (w / 2), original.rect.top + localWinEnd)
            val absWinB = Rect(original.rect.left + (w / 2), original.rect.top + localWinStart, original.rect.right, original.rect.top + localWinEnd)

            for (ny in localWinStart..localWinEnd) {
                val lx = rowLeft[ny]
                if (lx != -1 && (bestAx == -1 || lx > bestAx)) {
                    bestAx = lx; bestAy = ny
                }
            }

            // Point B (right boundary) has minimum x (deepest inward notch into bubble from right)
            var bestBx = -1; var bestBy = waistY
            for (ny in localWinStart..localWinEnd) {
                val rx = rowRight[ny]
                if (rx != -1 && (bestBx == -1 || rx < bestBx)) {
                    bestBx = rx; bestBy = ny
                }
            }

            if (bestAx != -1 && bestBx != -1 && bestBx > bestAx) {
                val absA = Point(original.rect.left + bestAx, original.rect.top + bestAy)
                val absB = Point(original.rect.left + bestBx, original.rect.top + bestBy)

                // Laser cut with obstacle avoidance against inside text lines
                val cutResult = executeLaserCut(
                    original = original,
                    pointA = absA,
                    pointB = absB,
                    waistYLocal = waistY,
                    bestRatio = bestRatio,
                    searchWinA = absWinA,
                    searchWinB = absWinB,
                    insideLines = insideLines,
                    w = w,
                    h = h,
                )
                if (cutResult != null) {
                    AppLogger.info("[CrunchSplitter Laser] Vertical waist split succeeded: ratio=${"%.2f".format(bestRatio)}, A=(${absA.x},${absA.y}), B=(${absB.x},${absB.y}) -> 2 lobes")
                    return cutResult
                }
            }
        }

        // Horizontal profile check for side-by-side conjoined bubbles
        val horizontalCut = tryHorizontalWaistSplit(original, insideLines, w, h)
        if (horizontalCut != null) {
            return horizontalCut
        }

        // No valid waist or angle crunch detected — leave bubble unsplit (no watershed fallback)
        return SplitResult(
            masks = listOf(original),
            wasSplit = false,
            originalRect = original.rect,
            originalMask = original,
            insideLines = insideLines,
        )
    }

    private fun executeLaserCut(
        original: BubbleMaskExtractor.BubbleMask,
        pointA: Point,
        pointB: Point,
        waistYLocal: Int,
        bestRatio: Float,
        searchWinA: Rect,
        searchWinB: Rect,
        insideLines: List<Rect>,
        w: Int,
        h: Int,
    ): SplitResult? {
        val waistYAbs = (pointA.y + pointB.y) / 2

        // Determine which lines clash with the A-B cut corridor (within 6px vertically and spanning between A.x and B.x)
        val minX = min(pointA.x, pointB.x)
        val maxX = max(pointA.x, pointB.x)

        val clashingLines = insideLines.filter { line ->
            val overlapX = max(0, min(maxX, line.right) - max(minX, line.left))
            val overlapY = max(0, min(waistYAbs + 6, line.bottom) - max(waistYAbs - 6, line.top))
            overlapX > 0 && overlapY > 0
        }

        val cutPoints = mutableListOf<Point>()
        var strategy = "Clean Cut"

        if (clashingLines.isEmpty()) {
            // Case 1: Clean Cross (No clash)
            strategy = "Clean Cut (No clash)"
            cutPoints.add(pointA)
            cutPoints.add(pointB)
        } else {
            // Classify clashing lines into Upper Lobe vs Lower Lobe
            val upperClash = clashingLines.filter { it.centerY() < waistYAbs }
            val lowerClash = clashingLines.filter { it.centerY() >= waistYAbs }

            if (lowerClash.isNotEmpty() && upperClash.isEmpty()) {
                // Case 2a: Clashed with Lower Lobe lines -> Deflect ABOVE lower lines
                strategy = "Deflect Above Lower Lines"
                val defTop = lowerClash.minOf { it.top } - 4
                val defLeft = lowerClash.minOf { it.left } - 4
                val defRight = lowerClash.maxOf { it.right } + 4

                cutPoints.add(pointA)
                cutPoints.add(Point(defLeft.coerceIn(minX, maxX), defTop))
                cutPoints.add(Point(defRight.coerceIn(minX, maxX), defTop))
                cutPoints.add(pointB)
            } else if (upperClash.isNotEmpty() && lowerClash.isEmpty()) {
                // Case 2b: Clashed with Upper Lobe lines -> Deflect BELOW upper lines
                strategy = "Deflect Below Upper Lines"
                val defBottom = upperClash.maxOf { it.bottom } + 4
                val defLeft = upperClash.minOf { it.left } - 4
                val defRight = upperClash.maxOf { it.right } + 4

                cutPoints.add(pointA)
                cutPoints.add(Point(defLeft.coerceIn(minX, maxX), defBottom))
                cutPoints.add(Point(defRight.coerceIn(minX, maxX), defBottom))
                cutPoints.add(pointB)
            } else {
                // Case 3: Both Upper and Lower lobes clash -> Thread the corridor midpoint
                strategy = "Corridor Midpoint Thread"
                val upperBottom = upperClash.maxOf { it.bottom }
                val lowerTop = lowerClash.minOf { it.top }
                val midY = (upperBottom + lowerTop) / 2
                val midX = (pointA.x + pointB.x) / 2

                cutPoints.add(pointA)
                cutPoints.add(Point(midX, midY))
                cutPoints.add(pointB)
            }
        }

        // Partition the original mask using the cutPoints piecewise boundary
        val maskUpper = BooleanArray(w * h)
        val maskLower = BooleanArray(w * h)
        var areaUpper = 0; var areaLower = 0

        for (y in 0 until h) {
            val absY = original.rect.top + y
            for (x in 0 until w) {
                val idx = y * w + x
                if (!original.mask[idx]) continue
                val absX = original.rect.left + x

                // Determine if (absX, absY) is above the cut seam
                val cutYAtX = getSeamYAtX(cutPoints, absX, waistYAbs)
                if (absY < cutYAtX) {
                    maskUpper[idx] = true; areaUpper++
                } else {
                    maskLower[idx] = true; areaLower++
                }
            }
        }

        val minAllowed = (original.fillArea * MIN_COMPONENT_RATIO).toInt()
        if (areaUpper < minAllowed || areaLower < minAllowed) return null

        val upperObj = cropMask(maskUpper, w, h, original.rect, areaUpper) ?: return null
        val lowerObj = cropMask(maskLower, w, h, original.rect, areaLower) ?: return null

        // Tail & Spike Guard: When multiple text lines exist in the parent bubble,
        // both lobes must contain at least one text line.
        // This ensures speech bubble tails/pointers (which have 0 text) are never sliced off.
        if (insideLines.size >= 2) {
            val upperHasText = insideLines.any { line ->
                val overlap = max(0, min(upperObj.rect.right, line.right) - max(upperObj.rect.left, line.left)) *
                              max(0, min(upperObj.rect.bottom, line.bottom) - max(upperObj.rect.top, line.top))
                overlap > 0
            }
            val lowerHasText = insideLines.any { line ->
                val overlap = max(0, min(lowerObj.rect.right, line.right) - max(lowerObj.rect.left, line.left)) *
                              max(0, min(lowerObj.rect.bottom, line.bottom) - max(lowerObj.rect.top, line.top))
                overlap > 0
            }
            if (!upperHasText || !lowerHasText) {
                AppLogger.info("[CrunchSplitter Laser] Rejected cut: One lobe contains zero text lines (protected tail/pointer)")
                return null
            }
        }

        return SplitResult(
            masks = listOf(upperObj, lowerObj),
            wasSplit = true,
            originalRect = original.rect,
            splitLobeRects = listOf(upperObj.rect, lowerObj.rect),
            crunchPointA = pointA,
            crunchPointB = pointB,
            cutLinePoints = cutPoints,
            waistY = original.rect.top + waistYLocal,
            waistRatio = bestRatio,
            searchWinA = searchWinA,
            searchWinB = searchWinB,
            clashingLines = clashingLines,
            cutStrategy = strategy,
            originalMask = original,
            insideLines = insideLines,
        )
    }

    private fun getSeamYAtX(cutPoints: List<Point>, x: Int, fallbackY: Int): Float {
        if (cutPoints.isEmpty()) return fallbackY.toFloat()
        if (cutPoints.size == 1) return cutPoints[0].y.toFloat()

        val sortedPoints = cutPoints.sortedBy { it.x }
        if (x <= sortedPoints.first().x) return sortedPoints.first().y.toFloat()
        if (x >= sortedPoints.last().x) return sortedPoints.last().y.toFloat()

        for (i in 0 until sortedPoints.size - 1) {
            val p1 = sortedPoints[i]; val p2 = sortedPoints[i + 1]
            if (x in p1.x..p2.x) {
                if (p2.x == p1.x) return min(p1.y, p2.y).toFloat()
                val t = (x - p1.x).toFloat() / (p2.x - p1.x)
                return p1.y + t * (p2.y - p1.y)
            }
        }

        return fallbackY.toFloat()
    }

    private fun tryHorizontalWaistSplit(
        original: BubbleMaskExtractor.BubbleMask,
        insideLines: List<Rect>,
        w: Int,
        h: Int,
    ): SplitResult? {
        val colTop = IntArray(w) { -1 }
        val colBottom = IntArray(w) { -1 }
        val colHeight = FloatArray(w)

        for (x in 0 until w) {
            var minY = -1; var maxY = -1
            for (y in 0 until h) {
                if (original.mask[y * w + x]) {
                    if (minY == -1) minY = y
                    maxY = y
                }
            }
            if (minY != -1) {
                colTop[x] = minY
                colBottom[x] = maxY
                colHeight[x] = (maxY - minY + 1).toFloat()
            }
        }

        val smoothH = FloatArray(w)
        for (x in 0 until w) {
            var sum = 0f; var count = 0
            for (dx in -2..2) {
                val nx = x + dx
                if (nx in 0 until w && colHeight[nx] > 0) {
                    sum += colHeight[nx]; count++
                }
            }
            smoothH[x] = if (count > 0) sum / count else 0f
        }

        var bestWaistX = -1
        var bestRatio = Float.MAX_VALUE
        var bestNarrowerLobe = 0f

        val scanStart = (w * 0.15f).toInt()
        val scanEnd = (w * 0.85f).toInt()

        for (x in scanStart..scanEnd) {
            val hx = smoothH[x]
            if (hx < 10f) continue

            // Peak in left region [0.05w .. x - 8]
            var maxLeft = 0f
            val leftLimit = max(0, x - 8)
            for (lx in (w * 0.05f).toInt()..leftLimit) {
                if (smoothH[lx] > maxLeft) maxLeft = smoothH[lx]
            }

            // Peak in right region [x + 8 .. 0.95w]
            var maxRight = 0f
            val rightStart = min(w - 1, x + 8)
            for (rx in rightStart..(w * 0.95f).toInt()) {
                if (smoothH[rx] > maxRight) maxRight = smoothH[rx]
            }

            if (maxLeft >= 18f && maxRight >= 18f) {
                val narrowerLobe = min(maxLeft, maxRight)
                val ratio = hx / narrowerLobe
                if (ratio < bestRatio) {
                    bestRatio = ratio
                    bestWaistX = x
                    bestNarrowerLobe = narrowerLobe
                }
            }
        }

        if (bestWaistX != -1 && bestRatio <= 0.88f) {
            val waistX = bestWaistX

            // Inward notch for A (top boundary: maximum y)
            var bestAy = -1; var bestAx = waistX
            val localWinStart = max(0, waistX - 10)
            val localWinEnd = min(w - 1, waistX + 10)
            val absWinA = Rect(original.rect.left + localWinStart, original.rect.top, original.rect.left + localWinEnd, original.rect.top + (h / 2))
            val absWinB = Rect(original.rect.left + localWinStart, original.rect.top + (h / 2), original.rect.left + localWinEnd, original.rect.bottom)

            for (nx in localWinStart..localWinEnd) {
                val ty = colTop[nx]
                if (ty != -1 && (bestAy == -1 || ty > bestAy)) {
                    bestAy = ty; bestAx = nx
                }
            }

            // Inward notch for B (bottom boundary: minimum y)
            var bestBy = -1; var bestBx = waistX
            for (nx in localWinStart..localWinEnd) {
                val by = colBottom[nx]
                if (by != -1 && (bestBy == -1 || by < bestBy)) {
                    bestBy = by; bestBx = nx
                }
            }

            if (bestAy != -1 && bestBy != -1 && bestBy > bestAy) {
                val absA = Point(original.rect.left + bestAx, original.rect.top + bestAy)
                val absB = Point(original.rect.left + bestBx, original.rect.top + bestBy)

                val cutPoints = listOf(absA, absB)
                val maskLeft = BooleanArray(w * h)
                val maskRight = BooleanArray(w * h)
                var areaLeft = 0; var areaRight = 0

                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val idx = y * w + x
                        if (!original.mask[idx]) continue
                        if (x < waistX) {
                            maskLeft[idx] = true; areaLeft++
                        } else {
                            maskRight[idx] = true; areaRight++
                        }
                    }
                }

                val minAllowed = (original.fillArea * MIN_COMPONENT_RATIO).toInt()
                if (areaLeft >= minAllowed && areaRight >= minAllowed) {
                    val leftObj = cropMask(maskLeft, w, h, original.rect, areaLeft)
                    val rightObj = cropMask(maskRight, w, h, original.rect, areaRight)
                    if (leftObj != null && rightObj != null) {
                        if (insideLines.size >= 2) {
                            val leftHasText = insideLines.any { line ->
                                val overlap = max(0, min(leftObj.rect.right, line.right) - max(leftObj.rect.left, line.left)) *
                                              max(0, min(leftObj.rect.bottom, line.bottom) - max(leftObj.rect.top, line.top))
                                overlap > 0
                            }
                            val rightHasText = insideLines.any { line ->
                                val overlap = max(0, min(rightObj.rect.right, line.right) - max(rightObj.rect.left, line.left)) *
                                              max(0, min(rightObj.rect.bottom, line.bottom) - max(rightObj.rect.top, line.top))
                                overlap > 0
                            }
                            if (!leftHasText || !rightHasText) {
                                AppLogger.info("[CrunchSplitter Laser] Rejected horizontal cut: One lobe contains zero text lines (protected tail/pointer)")
                                return null
                            }
                        }
                        AppLogger.info("[CrunchSplitter Laser] Horizontal waist split succeeded: ratio=${"%.2f".format(bestRatio)}, A=(${absA.x},${absA.y}), B=(${absB.x},${absB.y}) -> 2 lobes")
                        return SplitResult(
                            masks = listOf(leftObj, rightObj),
                            wasSplit = true,
                            originalRect = original.rect,
                            splitLobeRects = listOf(leftObj.rect, rightObj.rect),
                            crunchPointA = absA,
                            crunchPointB = absB,
                            cutLinePoints = cutPoints,
                            waistX = original.rect.left + waistX,
                            waistRatio = bestRatio,
                            searchWinA = absWinA,
                            searchWinB = absWinB,
                            cutStrategy = "Horizontal Waist",
                            originalMask = original,
                            insideLines = insideLines,
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * Method 8 v2: Contour-Angle Inward Notch Crunch Detection.
     *
     * Traces the outer perimeter contour of the bubble polygon mask, computes local
     * interior angles (concavity defects), and identifies opposite sharp inward notches.
     *
     * Solves:
     * 1. Staggered / stepped bubbles (`- | -`) where 1D width scans fail.
     * 2. Unequal-sized compound bubbles (large speech balloon + small exclamation bubble).
     * 3. Diagonally fused speech lobes.
     * 4. 100% tail immunity: requires opposite-side facing, substantial depth, and text in both halves.
     */
    /**
     * Method 8 v3: Pure Border Angle Crunch Detection (Zero Fallback).
     *
     * Traces the outer perimeter contour of the bubble polygon mask,
     * calculates local chord angles across the perimeter to find inward concavity notches,
     * pairs opposite notches with interior line of sight, and executes an obstacle-aware laser cut.
     *
     * 100% ZERO FALLBACK:
     * If no valid inward angle notch pair is found, the bubble is returned completely untouched.
     * No 1D waist scanning, no watershed, no text cluster fallback.
     */
    fun trySplitBorderAngleOnly(
        original: BubbleMaskExtractor.BubbleMask,
        insideLines: List<Rect> = emptyList(),
    ): SplitResult {
        val w = original.width
        val h = original.height
        if (w < 20 || h < 20 || original.fillArea < 150) {
            return SplitResult(listOf(original), wasSplit = false, originalRect = original.rect, originalMask = original, insideLines = insideLines)
        }

        val result = tryAngleNotchSplit(original, insideLines, w, h)
        if (result != null) {
            AppLogger.info("[BorderAngle] Bubble ${original.rect}: SPLIT SUCCESS -> ${result.masks.size} lobes (${result.cutStrategy})")
            return result
        }

        AppLogger.info("[BorderAngle] Bubble ${original.rect}: No valid angle notch pair -> UNTOUCHED (Zero Fallback)")
        return SplitResult(
            masks = listOf(original),
            wasSplit = false,
            originalRect = original.rect,
            originalMask = original,
            insideLines = insideLines,
        )
    }

    /**
     * Method 8: Contour-Angle Inward Notch Crunch Detection.
     *
     * Traces the outer perimeter contour of the bubble polygon mask, computes local
     * interior angles (concavity defects), and identifies opposite sharp inward notches.
     *
     * Solves:
     * 1. Staggered / stepped bubbles (`- | -`) where 1D width scans fail.
     * 2. Unequal-sized compound bubbles (large speech balloon + small exclamation bubble).
     * 3. Diagonally fused speech lobes.
     * 4. 100% tail immunity: requires opposite-side facing, substantial depth, and text in both halves.
     */
    private fun tryAngleNotchSplit(
        original: BubbleMaskExtractor.BubbleMask,
        insideLines: List<Rect>,
        w: Int,
        h: Int,
    ): SplitResult? {
        val mask = original.mask
        val contour = extractOuterContour(mask, w, h)
        if (contour.size < 30) {
            AppLogger.info("[BorderAngle] ${original.rect}: Contour too small (${contour.size} < 30)")
            return null
        }

        val n = contour.size
        // Test multiple chord scales to catch both sharp notches and wider bends
        val kValues = listOf(
            (n / 35).coerceIn(4, 10),
            (n / 24).coerceIn(6, 14),
            (n / 16).coerceIn(8, 18),
        ).distinct()

        data class NotchCandidate(val point: Point, val index: Int, val angleDeg: Float, val depth: Float)
        val candidates = mutableListOf<NotchCandidate>()

        for (k in kValues) {
            for (i in 0 until n) {
                val curr = contour[i]
                val prev = contour[(i - k + n) % n]
                val next = contour[(i + k) % n]

                val e1x = (curr.x - prev.x).toFloat()
                val e1y = (curr.y - prev.y).toFloat()
                val e2x = (next.x - curr.x).toFloat()
                val e2y = (next.y - curr.y).toFloat()

                val uLen = sqrt(e1x * e1x + e1y * e1y)
                val vLen = sqrt(e2x * e2x + e2y * e2y)
                if (uLen < 2.5f || vLen < 2.5f) continue

                // Left turn in clockwise Y-down contour indicates inward concavity defect into bubble
                val turn = e1x * e2y - e1y * e2x

                val midX = (prev.x + next.x) / 2
                val midY = (prev.y + next.y) / 2
                val midInside = if (midX in 0 until w && midY in 0 until h) mask[midY * w + midX] else false

                val chordDistSq = (next.x - prev.x) * (next.x - prev.x) + (next.y - prev.y) * (next.y - prev.y)
                if (chordDistSq < 16) continue

                val lineDist = abs((next.y - prev.y) * curr.x - (next.x - prev.x) * curr.y + next.x * prev.y - next.y * prev.x) / sqrt(chordDistSq.toFloat())

                val cosVal = ((e1x * e2x + e1y * e2y) / (uLen * vLen)).coerceIn(-1f, 1f)
                val turnAngleDeg = Math.toDegrees(acos(cosVal.toDouble())).toFloat()
                val interiorAngleDeg = 180f - turnAngleDeg

                val isInward = (!midInside && turn > -0.5f) || (turn > 0f && lineDist >= 2.0f)

                if (isInward && interiorAngleDeg in 40f..155f && lineDist >= 2.0f) {
                    candidates.add(NotchCandidate(curr, i, interiorAngleDeg, lineDist))
                }
            }
        }

        if (candidates.isEmpty()) {
            AppLogger.info("[BorderAngle] ${original.rect}: Found 0 notch candidates (n=$n)")
            return null
        }

        // Sort candidates by contour index and cluster nearby points
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
                if (idxDiff <= 12 && distSq <= 144) {
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

        if (clusteredNotches.size < 2) {
            AppLogger.info("[BorderAngle] ${original.rect}: Only ${clusteredNotches.size} notch clusters found (need >= 2)")
            return null
        }

        // Find best opposite pair (Notch A, Notch B)
        var bestPair: Pair<NotchCandidate, NotchCandidate>? = null
        var bestScore = Float.MAX_VALUE

        for (i in 0 until clusteredNotches.size) {
            for (j in i + 1 until clusteredNotches.size) {
                val cA = clusteredNotches[i]
                val cB = clusteredNotches[j]

                // Opposite sides filter: contour index distance >= 15% of perimeter
                val indexDist = min(abs(cA.index - cB.index), n - abs(cA.index - cB.index))
                if (indexDist < (n * 0.15f).toInt()) continue

                val dx = (cA.point.x - cB.point.x).toFloat()
                val dy = (cA.point.y - cB.point.y).toFloat()
                val spatialDist = sqrt(dx * dx + dy * dy)
                if (spatialDist < 14f) continue

                // Check interior line-of-sight: segment A-B must pass primarily inside the mask
                var insideCount = 0
                val samples = 12
                for (s in 1 until samples) {
                    val t = s.toFloat() / samples
                    val sx = (cA.point.x + t * dx).toInt().coerceIn(0, w - 1)
                    val sy = (cA.point.y + t * dy).toInt().coerceIn(0, h - 1)
                    if (mask[sy * w + sx]) insideCount++
                }
                val interiorRatio = insideCount.toFloat() / (samples - 1)
                if (interiorRatio < 0.60f) continue

                // Score: prefer sharper notches and narrower waist
                val pairScore = (cA.angleDeg + cB.angleDeg) + spatialDist * 0.4f
                if (pairScore < bestScore) {
                    bestScore = pairScore
                    bestPair = Pair(cA, cB)
                }
            }
        }

        val pair = bestPair
        if (pair == null) {
            AppLogger.info("[BorderAngle] ${original.rect}: ${clusteredNotches.size} clusters, but no valid opposite pair with line of sight")
            return null
        }
        val (notchA, notchB) = pair

        val absA = Point(original.rect.left + notchA.point.x, original.rect.top + notchA.point.y)
        val absB = Point(original.rect.left + notchB.point.x, original.rect.top + notchB.point.y)

        val winSize = 12
        val absWinA = Rect(absA.x - winSize, absA.y - winSize, absA.x + winSize, absA.y + winSize)
        val absWinB = Rect(absB.x - winSize, absB.y - winSize, absB.x + winSize, absB.y + winSize)
        val waistYLocal = (notchA.point.y + notchB.point.y) / 2

        val cutResult = executeLaserCut(
            original = original,
            pointA = absA,
            pointB = absB,
            waistYLocal = waistYLocal,
            bestRatio = 0.50f,
            searchWinA = absWinA,
            searchWinB = absWinB,
            insideLines = insideLines,
            w = w,
            h = h,
        )

        if (cutResult != null) {
            AppLogger.info("[BorderAngle] Notch split succeeded: NotchA=(${absA.x},${absA.y}, ${notchA.angleDeg.toInt()}°), NotchB=(${absB.x},${absB.y}, ${notchB.angleDeg.toInt()}°) -> 2 lobes")
            return cutResult.copy(cutStrategy = "Border Angle (${cutResult.cutStrategy})")
        }

        AppLogger.info("[BorderAngle] ${original.rect}: Laser cut rejected (e.g. tail/text guard)")
        return null
    }

    /**
     * Extracts ordered clockwise outer perimeter contour pixels using Moore-Neighbor boundary tracing.
     */
    private fun extractOuterContour(mask: BooleanArray, w: Int, h: Int): List<Point> {
        var startX = -1; var startY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x]) {
                    startX = x; startY = y
                    break
                }
            }
            if (startX != -1) break
        }
        if (startX == -1) return emptyList()

        val contour = mutableListOf<Point>()
        val dirsX = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        val dirsY = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

        var currX = startX
        var currY = startY
        var dir = 0

        contour.add(Point(currX, currY))
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
        }

        return contour
    }

    // ─── 1. Distance Transform (Meijster 2-pass) ──────────────────────────────

    private fun computeDistanceTransform(mask: BooleanArray, w: Int, h: Int): FloatArray {
        val rowDist = IntArray(w * h) { Int.MAX_VALUE / 2 }
        for (y in 0 until h) {
            var lastBg = -w
            for (x in 0 until w) {
                val idx = y * w + x
                if (!mask[idx]) { lastBg = x; rowDist[idx] = 0 }
                else rowDist[idx] = if (lastBg < 0) Int.MAX_VALUE / 2 else (x - lastBg) * (x - lastBg)
            }
            var lastBg2 = w + w
            for (x in w - 1 downTo 0) {
                val idx = y * w + x
                if (!mask[idx]) { lastBg2 = x }
                else {
                    val d2 = (lastBg2 - x) * (lastBg2 - x)
                    if (d2 < rowDist[idx]) rowDist[idx] = d2
                }
            }
        }

        val result = FloatArray(w * h)
        val f = IntArray(h)
        val z = IntArray(h + 1)
        val v = IntArray(h)

        for (x in 0 until w) {
            for (y in 0 until h) f[y] = rowDist[y * w + x]

            var k = 0
            v[0] = 0; z[0] = Int.MIN_VALUE; z[1] = Int.MAX_VALUE

            for (q in 1 until h) {
                val fq = f[q]
                var s: Long
                while (true) {
                    val vk = v[k]
                    val fvk = f[vk]
                    s = ((fq.toLong() + q.toLong() * q - fvk.toLong() - vk.toLong() * vk) / (2L * (q - vk)))
                    if (s > z[k]) break
                    k--
                    if (k < 0) { k = 0; break }
                }
                k++
                v[k] = q
                z[k] = s.toInt()
                z[k + 1] = Int.MAX_VALUE
            }

            k = 0
            for (q in 0 until h) {
                while (z[k + 1] < q) k++
                val vk = v[k]
                val dq = q - vk
                val dt = f[vk] + dq * dq
                result[q * w + x] = sqrt(dt.toFloat())
            }
        }
        return result
    }

    // ─── 2. Topological Peak Detection (Local Maxima) ─────────────────────────

    data class Peak(val x: Int, val y: Int, val r: Float)

    private fun findTopologicalPeaks(
        dist: FloatArray,
        mask: BooleanArray,
        w: Int,
        h: Int,
        maxDist: Float,
    ): List<Peak> {
        val minCutoff = max(MIN_PEAK_RADIUS, maxDist * 0.22f)
        val candidatePeaks = mutableListOf<Peak>()

        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val idx = y * w + x
                val d = dist[idx]
                if (d < minCutoff || !mask[idx]) continue

                // Check 8-connected neighborhood
                var isLocalMax = true
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        if (dx == 0 && dy == 0) continue
                        val nIdx = (y + dy) * w + (x + dx)
                        if (dist[nIdx] > d) {
                            isLocalMax = false
                            break
                        }
                    }
                    if (!isLocalMax) break
                }
                if (isLocalMax) {
                    candidatePeaks.add(Peak(x, y, d))
                }
            }
        }

        // Cluster peaks that are close to each other (keep highest)
        val clusterRadius = max(14, (maxDist * 0.35f).toInt())
        val clusters = mutableListOf<Peak>()
        for (p in candidatePeaks.sortedByDescending { it.r }) {
            val closeToExisting = clusters.any { c ->
                val dx = p.x - c.x; val dy = p.y - c.y
                dx * dx + dy * dy < clusterRadius * clusterRadius
            }
            if (!closeToExisting) {
                clusters.add(p)
            }
        }

        return clusters.take(2) // strictly 2 lobes per conjoined bubble split
    }

    // ─── 3. Constriction / Saddle Verification ────────────────────────────────

    private fun findValidConstrictionPairs(
        peaks: List<Peak>,
        dist: FloatArray,
        w: Int,
        h: Int,
    ): List<Peak> {
        if (peaks.size < 2) return emptyList()

        var bestPair: Pair<Peak, Peak>? = null
        var lowestRatio = Float.MAX_VALUE

        for (i in peaks.indices) {
            for (j in i + 1 until peaks.size) {
                val p1 = peaks[i]; val p2 = peaks[j]
                val minR = min(p1.r, p2.r)

                // Sample points along the line segment connecting p1 and p2
                val distBetween = sqrt(((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y)).toDouble()).toFloat()
                if (distBetween < minR * 0.8f) continue // too close to be distinct lobes

                val steps = max(10, distBetween.toInt())
                var minValAlongLine = Float.MAX_VALUE
                for (s in 1 until steps) {
                    val t = s.toFloat() / steps
                    val sx = (p1.x + t * (p2.x - p1.x)).toInt().coerceIn(0, w - 1)
                    val sy = (p1.y + t * (p2.y - p1.y)).toInt().coerceIn(0, h - 1)
                    val v = dist[sy * w + sx]
                    if (v < minValAlongLine) minValAlongLine = v
                }

                // A waist/crunch exists if the distance dips to < 82% of the narrower lobe's radius
                val ratio = minValAlongLine / minR
                if (ratio < 0.82f && ratio < lowestRatio) {
                    lowestRatio = ratio
                    bestPair = Pair(p1, p2)
                }
            }
        }
        return if (bestPair != null) listOf(bestPair.first, bestPair.second) else emptyList()
    }

    // ─── 4. Marker-Controlled Watershed ──────────────────────────────────────

    private fun runWatershedSplit(
        original: BubbleMaskExtractor.BubbleMask,
        dist: FloatArray,
        seeds: List<Peak>,
        w: Int,
        h: Int,
    ): List<BubbleMaskExtractor.BubbleMask>? {
        val numSeeds = seeds.size
        val assignment = IntArray(w * h) { -1 }

        // Seeds
        val queue = ArrayDeque<Pair<Int, Int>>() // (flatIdx, label)
        for ((label, seed) in seeds.withIndex()) {
            val idx = seed.y * w + seed.x
            assignment[idx] = label
            queue.add(Pair(idx, label))
        }

        // Multi-source BFS flood constrained to bubble mask
        while (queue.isNotEmpty()) {
            val (currIdx, label) = queue.removeFirst()
            val cx = currIdx % w; val cy = currIdx / w

            fun tryStep(nx: Int, ny: Int) {
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) return
                val nIdx = ny * w + nx
                if (!original.mask[nIdx] || assignment[nIdx] >= 0) return
                assignment[nIdx] = label
                queue.add(Pair(nIdx, label))
            }

            tryStep(cx - 1, cy); tryStep(cx + 1, cy); tryStep(cx, cy - 1); tryStep(cx, cy + 1)
        }

        // Build individual sub-masks
        val subMasks = Array(numSeeds) { BooleanArray(w * h) }
        val subAreas = IntArray(numSeeds)
        for (i in original.mask.indices) {
            if (original.mask[i] && assignment[i] in 0 until numSeeds) {
                subMasks[assignment[i]][i] = true
                subAreas[assignment[i]]++
            }
        }

        // Validate area ratio
        val minAllowed = (original.fillArea * MIN_COMPONENT_RATIO).toInt()
        if (subAreas.any { it < minAllowed || it < 60 }) return null

        // Measure seam width (number of adjacent pixels between different labels)
        var seamContactPixels = 0
        for (y in 0 until h - 1) {
            for (x in 0 until w - 1) {
                val a = assignment[y * w + x]
                val b = assignment[y * w + (x + 1)]
                val c = assignment[(y + 1) * w + x]
                if (a >= 0 && b >= 0 && a != b) seamContactPixels++
                if (a >= 0 && c >= 0 && a != c) seamContactPixels++
            }
        }

        // Convert to BubbleMask objects
        val results = mutableListOf<BubbleMaskExtractor.BubbleMask>()
        for (s in 0 until numSeeds) {
            val sm = subMasks[s]
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            for (i in sm.indices) {
                if (sm[i]) {
                    val x = i % w; val y = i / w
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
            if (maxX <= minX || maxY <= minY) return null

            val subW = maxX - minX + 1; val subH = maxY - minY + 1
            val tight = BooleanArray(subW * subH)
            for (i in sm.indices) {
                if (sm[i]) {
                    val x = (i % w) - minX; val y = (i / w) - minY
                    tight[y * subW + x] = true
                }
            }
            val rect = Rect(
                original.rect.left + minX, original.rect.top + minY,
                original.rect.left + maxX + 1, original.rect.top + maxY + 1,
            )
            results.add(BubbleMaskExtractor.BubbleMask(tight, rect, subW, subH, subAreas[s]))
        }

        return results
    }

    // ─── 5. Text-Guided Cluster Split Fallback ─────────────────────────────────

    private fun tryTextGuidedSplit(
        original: BubbleMaskExtractor.BubbleMask,
        insideLines: List<Rect>,
    ): List<BubbleMaskExtractor.BubbleMask>? {
        if (insideLines.size < 2) return null
        val origW = original.width; val origH = original.height

        // Transform lines to mask-local coordinates
        val localLines = insideLines.map { l ->
            Rect(
                max(0, l.left - original.rect.left),
                max(0, l.top - original.rect.top),
                min(origW, l.right - original.rect.left),
                min(origH, l.bottom - original.rect.top),
            )
        }.filter { it.width() > 0 && it.height() > 0 }

        if (localLines.size < 2) return null

        // 1. Try vertical partition (upper bubble vs lower bubble)
        val sortedByY = localLines.sortedBy { it.centerY() }
        for (i in 1 until sortedByY.size) {
            val upperGroup = sortedByY.take(i)
            val lowerGroup = sortedByY.drop(i)

            val upperMaxBottom = upperGroup.maxOf { it.bottom }
            val lowerMinTop    = lowerGroup.minOf { it.top }
            val gapY = lowerMinTop - upperMaxBottom

            // Check if there is a clear vertical dead-space gap between the two text groups
            if (gapY >= 10 || (upperMaxBottom <= lowerMinTop && gapY >= 0)) {
                val cutY = (upperMaxBottom + lowerMinTop) / 2
                if (cutY in 10 until origH - 10) {
                    val splitMasks = splitMaskHorizontally(original, cutY)
                    if (splitMasks != null) return splitMasks
                }
            }
        }

        // 2. Try horizontal partition (left bubble vs right bubble)
        val sortedByX = localLines.sortedBy { it.centerX() }
        for (i in 1 until sortedByX.size) {
            val leftGroup  = sortedByX.take(i)
            val rightGroup = sortedByX.drop(i)

            val leftMaxRight = leftGroup.maxOf { it.right }
            val rightMinLeft = rightGroup.minOf { it.left }
            val gapX = rightMinLeft - leftMaxRight

            if (gapX >= 12) {
                val cutX = (leftMaxRight + rightMinLeft) / 2
                if (cutX in 10 until origW - 10) {
                    val splitMasks = splitMaskVertically(original, cutX)
                    if (splitMasks != null) return splitMasks
                }
            }
        }

        return null
    }

    private fun splitMaskHorizontally(
        original: BubbleMaskExtractor.BubbleMask,
        cutY: Int,
    ): List<BubbleMaskExtractor.BubbleMask>? {
        val w = original.width; val h = original.height
        val maskTop = BooleanArray(w * h)
        val maskBottom = BooleanArray(w * h)
        var areaTop = 0; var areaBottom = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!original.mask[idx]) continue
                if (y < cutY) {
                    maskTop[idx] = true; areaTop++
                } else {
                    maskBottom[idx] = true; areaBottom++
                }
            }
        }

        val minAllowed = (original.fillArea * MIN_COMPONENT_RATIO).toInt()
        if (areaTop < minAllowed || areaBottom < minAllowed) return null

        val topObj = cropMask(maskTop, w, h, original.rect, areaTop) ?: return null
        val bottomObj = cropMask(maskBottom, w, h, original.rect, areaBottom) ?: return null
        return listOf(topObj, bottomObj)
    }

    private fun splitMaskVertically(
        original: BubbleMaskExtractor.BubbleMask,
        cutX: Int,
    ): List<BubbleMaskExtractor.BubbleMask>? {
        val w = original.width; val h = original.height
        val maskLeft = BooleanArray(w * h)
        val maskRight = BooleanArray(w * h)
        var areaLeft = 0; var areaRight = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!original.mask[idx]) continue
                if (x < cutX) {
                    maskLeft[idx] = true; areaLeft++
                } else {
                    maskRight[idx] = true; areaRight++
                }
            }
        }

        val minAllowed = (original.fillArea * MIN_COMPONENT_RATIO).toInt()
        if (areaLeft < minAllowed || areaRight < minAllowed) return null

        val leftObj = cropMask(maskLeft, w, h, original.rect, areaLeft) ?: return null
        val rightObj = cropMask(maskRight, w, h, original.rect, areaRight) ?: return null
        return listOf(leftObj, rightObj)
    }

    private fun cropMask(
        fullMask: BooleanArray,
        w: Int,
        h: Int,
        origRect: Rect,
        area: Int,
    ): BubbleMaskExtractor.BubbleMask? {
        var minX = w; var maxX = 0; var minY = h; var maxY = 0
        for (i in fullMask.indices) {
            if (fullMask[i]) {
                val x = i % w; val y = i / w
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX <= minX || maxY <= minY) return null
        val cropW = maxX - minX + 1; val cropH = maxY - minY + 1
        val tight = BooleanArray(cropW * cropH)
        for (i in fullMask.indices) {
            if (fullMask[i]) {
                val x = (i % w) - minX; val y = (i / w) - minY
                tight[y * cropW + x] = true
            }
        }
        val r = Rect(
            origRect.left + minX, origRect.top + minY,
            origRect.left + maxX + 1, origRect.top + maxY + 1,
        )
        return BubbleMaskExtractor.BubbleMask(tight, r, cropW, cropH, area)
    }
}
