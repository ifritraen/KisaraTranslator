package com.raen.crunchlab.engine.grouping

import android.graphics.Point
import android.graphics.Rect
import com.raen.crunchlab.data.CrunchPartitionItem
import com.raen.crunchlab.data.DialogueGroupItem
import com.raen.crunchlab.data.DialogueLineItem
import com.raen.crunchlab.data.TextCategory
import com.raen.crunchlab.data.TextLineItem
import com.raen.crunchlab.engine.BubbleMask
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Module 4.1: Dialogue Data Preparation Engine.
 *
 * Responsibilities:
 * 1. Groups Module 2 vertical line strips into coherent dialogue units (Bubbled + Orphans).
 * 2. Strictly excludes SFX lines (brush/art effects).
 * 3. Preserves Module 3 waist crunch split lobes (Lobe A and Lobe B) as separate dialogue units.
 * 4. Sorts lines within each dialogue unit in strict Japanese Right-to-Left (RTL) reading order.
 * 5. Extracts actual speech bubble boundary contours (Moore-Neighbor 8-connected polygon contour)
 *    so the UI and step exports can colorize the real oval/irregular bubble perimeter.
 * 6. Sorts dialogue units across the page in Japanese manga reading order (top-to-bottom, RTL).
 */
object DialogueGroupPreparer {

    private const val TYPOGRAPHY_PAD = 10

    fun prepareGroups(
        verticalLines: List<TextLineItem>,
        bubbleRegions: List<Rect>,
        bubbleMasks: List<BubbleMask>,
        partitions: List<CrunchPartitionItem>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        isRtl: Boolean = true,
    ): List<DialogueGroupItem> {
        // Step 1: Filter out SFX lines
        val candidateLines = verticalLines.filter { it.category != TextCategory.SFX }
        if (candidateLines.isEmpty()) return emptyList()

        val assignedLineIds = mutableSetOf<Int>()
        val rawGroups = mutableListOf<DialogueGroupItem>()
        var nextGroupId = 1

        val medianW = calculateMedianWidth(candidateLines)

        // Step 2: Handle Module 3 Partitioned Conjoined Bubbles (Lobe A & Lobe B)
        val conjoinedParts = partitions.filter {
            it.isConjoined && !it.isCutRejected && (it.lobeALineIds.isNotEmpty() || it.lobeBLineIds.isNotEmpty())
        }

        for (part in conjoinedParts) {
            val linesA = candidateLines.filter { it.id in part.lobeALineIds && it.id !in assignedLineIds }
            val linesB = candidateLines.filter { it.id in part.lobeBLineIds && it.id !in assignedLineIds }

            // Find matching lobe masks from Module 3
            val lobeMasks = bubbleMasks.filter { it.isLobe && it.parentBubbleIndex == part.bubbleIndex }
            var maskA = lobeMasks.getOrNull(0)
            var maskB = lobeMasks.getOrNull(1)

            // If lobe masks are not directly tagged with parent index, associate by spatial overlap
            if (maskA == null || maskB == null) {
                val candidateLobeMasks = bubbleMasks.filter { it.isLobe || part.bubbleRect.contains(it.rect.centerX(), it.rect.centerY()) }
                if (linesA.isNotEmpty()) {
                    val boundsA = computeEnclosingRect(linesA.map { it.rect })
                    maskA = candidateLobeMasks.maxByOrNull { rectIntersectionArea(it.rect, boundsA) }
                }
                if (linesB.isNotEmpty()) {
                    val boundsB = computeEnclosingRect(linesB.map { it.rect })
                    maskB = candidateLobeMasks.filter { it != maskA }.maxByOrNull { rectIntersectionArea(it.rect, boundsB) }
                }
            }

            // Create Dialogue Group for Lobe A
            if (linesA.isNotEmpty()) {
                val rtlLinesA = sortLinesRtl(linesA, isRtl)
                val boundsA = computePaddedBounds(rtlLinesA.map { it.rect }, maskA?.rect ?: part.bubbleRect, bitmapWidth, bitmapHeight)
                val contourA = maskA?.let { extractBubbleContour(it) } ?: emptyList()
                rawGroups.add(
                    DialogueGroupItem(
                        groupId = nextGroupId++,
                        isBubble = true,
                        bubbleIndex = part.bubbleIndex,
                        bounds = boundsA,
                        lines = rtlLinesA,
                        category = TextCategory.BUBBLED,
                        contourPoints = contourA,
                    )
                )
                assignedLineIds.addAll(linesA.map { it.id })
            }

            // Create Dialogue Group for Lobe B
            if (linesB.isNotEmpty()) {
                val rtlLinesB = sortLinesRtl(linesB, isRtl)
                val boundsB = computePaddedBounds(rtlLinesB.map { it.rect }, maskB?.rect ?: part.bubbleRect, bitmapWidth, bitmapHeight)
                val contourB = maskB?.let { extractBubbleContour(it) } ?: emptyList()
                rawGroups.add(
                    DialogueGroupItem(
                        groupId = nextGroupId++,
                        isBubble = true,
                        bubbleIndex = part.bubbleIndex,
                        bounds = boundsB,
                        lines = rtlLinesB,
                        category = TextCategory.BUBBLED,
                        contourPoints = contourB,
                    )
                )
                assignedLineIds.addAll(linesB.map { it.id })
            }
        }

        // Step 3: Handle Regular Speech Bubbles
        // Consolidate bubbles from bubbleRegions and un-partitioned bubbleMasks
        val processedBubbleRects = conjoinedParts.map { it.bubbleRect }
        val regularBubbles = (if (bubbleMasks.isNotEmpty()) {
            bubbleMasks.filter { !it.isLobe }.map { it.rect }
        } else {
            bubbleRegions
        }).distinct()
          .filter { b -> processedBubbleRects.none { p -> rectIntersectionRatio(b, p) > 0.70f } }

        for ((bIdx, bubble) in regularBubbles.withIndex()) {
            // Find unassigned lines belonging to this bubble
            val bubbleLines = candidateLines.filter { line ->
                line.id !in assignedLineIds &&
                    (bubble.contains(line.rect.centerX(), line.rect.centerY()) ||
                     rectIntersectionRatio(line.rect, bubble) >= 0.40f) &&
                    isLineBestAssignedToBubble(line.rect, bubble, regularBubbles)
            }

            if (bubbleLines.isNotEmpty()) {
                val rtlLines = sortLinesRtl(bubbleLines, isRtl)
                val matchingMask = findMatchingBubbleMask(bubble, bubbleMasks)
                val bounds = computePaddedBounds(rtlLines.map { it.rect }, matchingMask?.rect ?: bubble, bitmapWidth, bitmapHeight)
                val contour = matchingMask?.let { extractBubbleContour(it) } ?: emptyList()

                rawGroups.add(
                    DialogueGroupItem(
                        groupId = nextGroupId++,
                        isBubble = true,
                        bubbleIndex = bIdx,
                        bounds = bounds,
                        lines = rtlLines,
                        category = TextCategory.BUBBLED,
                        contourPoints = contour,
                    )
                )
                assignedLineIds.addAll(bubbleLines.map { it.id })
            }
        }

        // Step 4: Handle Orphan Dialogue Lines (Dialogue / Narration on Art)
        val orphanLines = candidateLines.filter { it.id !in assignedLineIds }
        if (orphanLines.isNotEmpty()) {
            val clusters = clusterOrphanLines(orphanLines, medianW)
            for (cluster in clusters) {
                if (cluster.isEmpty()) continue
                val rtlLines = sortLinesRtl(cluster, isRtl)
                val bounds = computeEnclosingRect(rtlLines.map { it.rect }).let { r ->
                    Rect(
                        max(0, r.left - 6),
                        max(0, r.top - 6),
                        min(bitmapWidth, r.right + 6),
                        min(bitmapHeight, r.bottom + 6)
                    )
                }

                rawGroups.add(
                    DialogueGroupItem(
                        groupId = nextGroupId++,
                        isBubble = false,
                        bubbleIndex = null,
                        bounds = bounds,
                        lines = rtlLines,
                        category = TextCategory.ORPHAN,
                        contourPoints = emptyList(),
                    )
                )
                assignedLineIds.addAll(cluster.map { it.id })
            }
        }

        // Step 5: Sort Dialogue Groups in Japanese Manga Reading Order (Top-to-Bottom, RTL)
        val sortedGroups = if (isRtl) {
            rawGroups.sortedWith(
                compareBy<DialogueGroupItem> { it.bounds.centerY() / 250 }
                    .thenByDescending { it.bounds.centerX() }
            )
        } else {
            rawGroups.sortedWith(
                compareBy<DialogueGroupItem> { it.bounds.centerY() / 250 }
                    .thenBy { it.bounds.centerX() }
            )
        }

        // Re-number sequentially and assign in-group reading orders
        return sortedGroups.mapIndexed { gIdx, group ->
            val finalGroupId = gIdx + 1
            val numberedLines = group.lines.mapIndexed { lIdx, line ->
                line.copy(readingOrder = lIdx + 1)
            }
            group.copy(
                groupId = finalGroupId,
                lines = numberedLines,
            )
        }
    }

    /**
     * Sorts lines within a dialogue unit in strict Right-to-Left (or LTR) order.
     */
    private fun sortLinesRtl(lines: List<TextLineItem>, isRtl: Boolean): List<DialogueLineItem> {
        val sorted = if (isRtl) {
            lines.sortedWith(
                compareByDescending<TextLineItem> { it.rect.centerX() }
                    .thenBy { it.rect.top }
            )
        } else {
            lines.sortedWith(
                compareBy<TextLineItem> { it.rect.centerX() }
                    .thenBy { it.rect.top }
            )
        }
        return sorted.mapIndexed { idx, item ->
            DialogueLineItem(
                lineId = item.id,
                rect = item.rect,
                readingOrder = idx + 1
            )
        }
    }

    /**
     * Clusters unbubbled orphan vertical lines into dialogue units based on spatial proximity.
     */
    private fun clusterOrphanLines(lines: List<TextLineItem>, medianW: Float): List<List<TextLineItem>> {
        if (lines.size <= 1) return listOf(lines)

        val n = lines.size
        val adj = Array(n) { BooleanArray(n) }

        for (i in 0 until n) {
            adj[i][i] = true
            for (j in i + 1 until n) {
                val l1 = lines[i].rect
                val l2 = lines[j].rect

                val hDist = max(0, max(l1.left, l2.left) - min(l1.right, l2.right))
                val vOverlap = max(0, min(l1.bottom, l2.bottom) - max(l1.top, l2.top))
                val minH = min(l1.height(), l2.height())
                val vGap = max(0, max(l1.top, l2.top) - min(l1.bottom, l2.bottom))

                // Adjacency 1: Parallel columns side-by-side with vertical overlap
                val isSideBySide = hDist <= (1.85f * medianW) && (vOverlap >= 0.22f * minH)
                // Adjacency 2: Collinear vertically stacked segments in the same column corridor
                val isCollinear = (abs(l1.centerX() - l2.centerX()) <= 0.75f * medianW) && (vGap <= 1.4f * medianW)

                if (isSideBySide || isCollinear) {
                    adj[i][j] = true
                    adj[j][i] = true
                }
            }
        }

        val visited = BooleanArray(n)
        val clusters = mutableListOf<List<TextLineItem>>()

        for (i in 0 until n) {
            if (visited[i]) continue
            val cluster = mutableListOf<TextLineItem>()
            val queue = ArrayDeque<Int>()
            queue.add(i)
            visited[i] = true

            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                cluster.add(lines[u])
                for (v in 0 until n) {
                    if (adj[u][v] && !visited[v]) {
                        visited[v] = true
                        queue.add(v)
                    }
                }
            }
            clusters.add(cluster)
        }

        return clusters
    }

    /**
     * Extracts outer perimeter contour points of a BubbleMask using Moore-Neighbor 8-connected boundary tracing.
     * Simplifies with Ramer-Douglas-Peucker closed polygon algorithm.
     */
    fun extractBubbleContour(mask: BubbleMask): List<Point> {
        val w = mask.width
        val h = mask.height
        val m = mask.mask
        if (m.isEmpty() || w <= 4 || h <= 4) return emptyList()

        val visited = BooleanArray(w * h)
        var bestContour = emptyList<Point>()
        var maxArea = 0.0

        val dirsX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dirsY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

        for (y in 0 until h) {
            val rOff = y * w
            for (x in 0 until w) {
                val idx = rOff + x
                if (!m[idx] || visited[idx]) continue

                val isBoundary = (x == 0 || !m[idx - 1]) ||
                                 (x == w - 1 || !m[idx + 1]) ||
                                 (y == 0 || !m[idx - w]) ||
                                 (y == h - 1 || !m[idx + w])
                if (!isBoundary) continue

                val contour = traceContour(m, w, h, x, y, visited, dirsX, dirsY)
                if (contour.size >= 8) {
                    val area = shoelaceArea(contour)
                    if (area > maxArea) {
                        maxArea = area
                        bestContour = contour
                    }
                }
            }
        }

        if (bestContour.size < 6) return emptyList()

        // Translate to global page coordinates
        val globalPts = bestContour.map { Point(mask.rect.left + it.x, mask.rect.top + it.y) }
        // Simplify polygon with closed Ramer-Douglas-Peucker
        return rdpClosed(globalPts, eps = 2.0f)
    }

    private fun traceContour(
        mask: BooleanArray,
        w: Int,
        h: Int,
        startX: Int,
        startY: Int,
        visited: BooleanArray,
        dirsX: IntArray,
        dirsY: IntArray
    ): List<Point> {
        val contour = mutableListOf<Point>()
        var currX = startX
        var currY = startY
        var backtrackDir = 4 // Start scanning from West

        contour.add(Point(currX, currY))
        visited[currY * w + currX] = true
        val maxSteps = w * h

        for (step in 0 until maxSteps) {
            var foundNext = false
            for (i in 0 until 8) {
                val d = (backtrackDir + 2 + i) % 8
                val nx = currX + dirsX[d]
                val ny = currY + dirsY[d]

                if (nx in 0 until w && ny in 0 until h && mask[ny * w + nx]) {
                    currX = nx
                    currY = ny
                    backtrackDir = (d + 4) % 8
                    contour.add(Point(currX, currY))
                    visited[currY * w + currX] = true
                    foundNext = true
                    break
                }
            }

            if (!foundNext) break
            if (currX == startX && currY == startY) break
        }

        return contour
    }

    private fun shoelaceArea(pts: List<Point>): Double {
        val n = pts.size
        if (n < 3) return 0.0
        var area = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += pts[i].x.toDouble() * pts[j].y.toDouble()
            area -= pts[j].x.toDouble() * pts[i].y.toDouble()
        }
        return abs(area) / 2.0
    }

    /**
     * Closed-loop Ramer-Douglas-Peucker polygon simplification.
     */
    private fun rdpClosed(pts: List<Point>, eps: Float): List<Point> {
        if (pts.size <= 8) return pts

        // Split closed ring into two halves between point 0 and opposite point n/2
        val half = pts.size / 2
        val part1 = rdpOpen(pts.subList(0, half + 1), eps)
        val part2 = rdpOpen(pts.subList(half, pts.size) + pts[0], eps)

        val combined = (part1.dropLast(1) + part2.dropLast(1)).distinct()
        return if (combined.size >= 4) combined else pts
    }

    private fun rdpOpen(pts: List<Point>, eps: Float): List<Point> {
        if (pts.size <= 2) return pts
        var dMax = 0f
        var index = 0
        val p1 = pts.first()
        val p2 = pts.last()

        val lineDx = (p2.x - p1.x).toFloat()
        val lineDy = (p2.y - p1.y).toFloat()
        val lineLen = sqrt(lineDx * lineDx + lineDy * lineDy).coerceAtLeast(1e-4f)

        for (i in 1 until pts.size - 1) {
            val p = pts[i]
            val num = abs(lineDy * p.x - lineDx * p.y + p2.x * p1.y - p2.y * p1.x)
            val d = num / lineLen
            if (d > dMax) {
                dMax = d
                index = i
            }
        }

        return if (dMax > eps) {
            val rec1 = rdpOpen(pts.subList(0, index + 1), eps)
            val rec2 = rdpOpen(pts.subList(index, pts.size), eps)
            rec1.dropLast(1) + rec2
        } else {
            listOf(p1, p2)
        }
    }

    private fun findMatchingBubbleMask(bubble: Rect, masks: List<BubbleMask>): BubbleMask? {
        if (masks.isEmpty()) return null
        return masks.maxByOrNull { mask ->
            val inter = rectIntersectionArea(bubble, mask.rect)
            if (inter <= 0) 0f
            else inter.toFloat() / max(1, bubble.width() * bubble.height())
        }?.takeIf { rectIntersectionArea(bubble, it.rect) > 0 }
    }

    private fun isLineBestAssignedToBubble(line: Rect, bubble: Rect, allBubbles: List<Rect>): Boolean {
        var maxArea = 0
        var bestB: Rect? = null
        for (b in allBubbles) {
            val a = rectIntersectionArea(line, b)
            if (a > maxArea) {
                maxArea = a
                bestB = b
            }
        }
        return bestB == bubble
    }

    private fun computePaddedBounds(
        lineRects: List<Rect>,
        bubbleBounds: Rect,
        bitmapW: Int,
        bitmapH: Int
    ): Rect {
        if (lineRects.isEmpty()) return bubbleBounds
        val tL = lineRects.minOf { it.left }
        val tT = lineRects.minOf { it.top }
        val tR = lineRects.maxOf { it.right }
        val tB = lineRects.maxOf { it.bottom }

        val bL = max(0, max(bubbleBounds.left, tL - TYPOGRAPHY_PAD))
        val bT = max(0, max(bubbleBounds.top, tT - TYPOGRAPHY_PAD))
        val bR = min(bitmapW, min(bubbleBounds.right, tR + TYPOGRAPHY_PAD))
        val bB = min(bitmapH, min(bubbleBounds.bottom, tB + TYPOGRAPHY_PAD))

        return Rect(
            min(bL, tL),
            min(bT, tT),
            max(bR, tR),
            max(bB, tB)
        )
    }

    private fun computeEnclosingRect(rects: List<Rect>): Rect {
        if (rects.isEmpty()) return Rect()
        return Rect(
            rects.minOf { it.left },
            rects.minOf { it.top },
            rects.maxOf { it.right },
            rects.maxOf { it.bottom }
        )
    }

    private fun calculateMedianWidth(lines: List<TextLineItem>): Float {
        val widths = lines.map { it.rect.width().coerceAtLeast(10) }.sorted()
        return if (widths.isEmpty()) 24f else widths[widths.size / 2].toFloat()
    }

    private fun rectIntersectionArea(r1: Rect, r2: Rect): Int {
        val interL = max(r1.left, r2.left)
        val interT = max(r1.top, r2.top)
        val interR = min(r1.right, r2.right)
        val interB = min(r1.bottom, r2.bottom)
        return if (interR > interL && interB > interT) (interR - interL) * (interB - interT) else 0
    }

    private fun rectIntersectionRatio(r1: Rect, r2: Rect): Float {
        val area = rectIntersectionArea(r1, r2)
        val a1 = r1.width() * r1.height()
        return if (a1 > 0) area.toFloat() / a1.toFloat() else 0f
    }
}
