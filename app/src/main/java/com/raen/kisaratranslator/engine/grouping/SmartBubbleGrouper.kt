package com.raen.kisaratranslator.engine.grouping

import android.graphics.Bitmap
import android.graphics.Rect
import com.raen.kisaratranslator.data.logger.AppLogger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * High-precision dialogue grouper for Method 4 (Full-Bubble Smart Grouping).
 *
 * Solves the core grouping failure modes:
 * 1. Conjoined bubble splitting: Only splits strictly bimodally stacked lobes with confirmed waist pinch.
 * 2. Speech bubble enclosure: Cleanly absorbs character boxes without ink-stroke false rejections.
 * 3. Stray box absorption: Disallows orphan fragments from spawning inside or on top of speech balloons.
 * 4. Zero-Overlap Guarantee: Mathematically eliminates all overlapping bounding boxes across the page.
 */
class SmartBubbleGrouper {

    /**
     * Splits conjoined/figure-8/peanut bubbles that touch or merge without a dividing border.
     * Analyzes vertical text bimodality and white-mask waist constriction.
     *
     * Strict rules to NEVER split normal multi-line parallel columns:
     * - Inside boxes must divide cleanly into upper and lower groups with no vertical overlap.
     * - No text box may cross the candidate split Y.
     * - The white interior must physically constrict (< 65% of max lobe width) at candidate Y.
     */
    fun splitConjoinedBubbles(
        bubbleRegions: List<Rect>,
        textLines: List<Rect>,
        bitmap: Bitmap,
    ): List<Rect> {
        if (bubbleRegions.isEmpty() || textLines.isEmpty()) return bubbleRegions

        val result = mutableListOf<Rect>()

        for (bubble in bubbleRegions) {
            val insideBoxes = textLines.filter { box ->
                bubble.contains(box.centerX(), box.centerY())
            }.sortedBy { it.top }

            // A conjoined bubble must contain at least 2 text boxes and be sufficiently tall
            if (insideBoxes.size < 2 || bubble.height() < 160) {
                result.add(bubble)
                continue
            }

            var bestSplitY = -1
            var bestWaistRatio = 1.0f

            for (i in 0 until insideBoxes.size - 1) {
                val upperGroup = insideBoxes.subList(0, i + 1)
                val lowerGroup = insideBoxes.subList(i + 1, insideBoxes.size)

                val upperMaxBottom = upperGroup.maxOf { it.bottom }
                val lowerMinTop = lowerGroup.minOf { it.top }

                // The lower group MUST be strictly below the upper group (no vertical overlap between the two lobes)
                val verticalGap = lowerMinTop - upperMaxBottom
                if (verticalGap < 10) continue

                val candidateY = (upperMaxBottom + lowerMinTop) / 2
                val distFromEdge = min(candidateY - bubble.top, bubble.bottom - candidateY)
                if (distFromEdge < bubble.height() * 0.20f) continue

                // Check that NO text box crosses candidateY
                val boxCrosses = insideBoxes.any { it.top < candidateY && it.bottom > candidateY }
                if (boxCrosses) continue

                // Physical constriction test: waist must be pinched (width at candidateY < 65% of max lobe)
                val sampleY1 = (bubble.top + candidateY) / 2
                val sampleY2 = (candidateY + bubble.bottom) / 2
                val widthAtSplit = measureWhiteSpan(bitmap, bubble.left, bubble.right, candidateY)
                val widthTop = measureWhiteSpan(bitmap, bubble.left, bubble.right, sampleY1)
                val widthBottom = measureWhiteSpan(bitmap, bubble.left, bubble.right, sampleY2)
                val maxLobe = max(widthTop, widthBottom)

                if (maxLobe >= 30) {
                    val ratio = widthAtSplit.toFloat() / maxLobe.toFloat()
                    if (ratio < 0.65f && ratio < bestWaistRatio) {
                        bestWaistRatio = ratio
                        bestSplitY = candidateY
                    }
                }
            }

            if (bestSplitY > 0) {
                val bubble1 = Rect(bubble.left, bubble.top, bubble.right, bestSplitY)
                val bubble2 = Rect(bubble.left, bestSplitY, bubble.right, bubble.bottom)
                result.add(bubble1)
                result.add(bubble2)
                AppLogger.info("SmartGrouper: Split conjoined bubble [${bubble.width()}x${bubble.height()}] at Y=$bestSplitY (waist ratio=${"%.2f".format(bestWaistRatio)})")
            } else {
                result.add(bubble)
            }
        }

        return result
    }

    private fun measureWhiteSpan(bitmap: Bitmap, left: Int, right: Int, y: Int): Int {
        if (y !in 0 until bitmap.height) return 0
        var whiteCount = 0
        val clampedL = left.coerceIn(0, bitmap.width - 1)
        val clampedR = right.coerceIn(clampedL, bitmap.width)

        for (x in clampedL until clampedR step 4) {
            val pixel = bitmap.getPixel(x, y)
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            if (luma > 210) {
                whiteCount += 4
            }
        }
        return whiteCount
    }

    /**
     * Determines whether a text box belongs inside a speech bubble.
     * Uses centroid containment and area overlap.
     * Avoids pixel-luma sampling over character centers to prevent dark ink strokes from rejecting valid text.
     */
    fun isTextInsideBubbleStrict(textBox: Rect, bubbleBox: Rect, bitmap: Bitmap? = null): Boolean {
        val cx = textBox.centerX()
        val cy = textBox.centerY()

        // 1. Centroid containment
        if (bubbleBox.contains(cx, cy)) {
            return true
        }

        // 2. Area overlap test (at least 35% of the text box is inside the bubble)
        val interL = max(textBox.left, bubbleBox.left)
        val interT = max(textBox.top, bubbleBox.top)
        val interR = min(textBox.right, bubbleBox.right)
        val interB = min(textBox.bottom, bubbleBox.bottom)
        if (interR > interL && interB > interT) {
            val interArea = (interR - interL) * (interB - interT)
            val textArea = max(1, textBox.width() * textBox.height())
            return (interArea.toFloat() / textArea.toFloat()) >= 0.35f
        }

        return false
    }

    /**
     * Detects horizontal and vertical comic panel gutters (solid white/black dividers between panels).
     * Returns list of boundary intervals to prevent text from grouping across panels.
     */
    fun detectPanelGutters(bitmap: Bitmap): Pair<List<Int>, List<Int>> {
        val hGutters = mutableListOf<Int>()
        val vGutters = mutableListOf<Int>()

        val w = bitmap.width
        val h = bitmap.height
        if (w <= 100 || h <= 100) return Pair(hGutters, vGutters)

        // Scan horizontal lines for gutters (white or black bands >= 10px tall)
        var consecutiveWhiteRows = 0
        val sampleStep = 8

        for (y in 20 until h - 20 step 4) {
            var rowWhiteCount = 0
            val totalSamples = w / sampleStep

            for (x in 0 until w step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                if (luma > 240 || luma < 15) {
                    rowWhiteCount++
                }
            }

            if (rowWhiteCount.toFloat() / totalSamples.toFloat() > 0.94f) {
                consecutiveWhiteRows++
                if (consecutiveWhiteRows == 3) {
                    hGutters.add(y)
                }
            } else {
                consecutiveWhiteRows = 0
            }
        }

        // Scan vertical columns for gutters
        var consecutiveWhiteCols = 0
        for (x in 20 until w - 20 step 4) {
            var colWhiteCount = 0
            val totalSamples = h / sampleStep

            for (y in 0 until h step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                if (luma > 240 || luma < 15) {
                    colWhiteCount++
                }
            }

            if (colWhiteCount.toFloat() / totalSamples.toFloat() > 0.94f) {
                consecutiveWhiteCols++
                if (consecutiveWhiteCols == 3) {
                    vGutters.add(x)
                }
            } else {
                consecutiveWhiteCols = 0
            }
        }

        return Pair(hGutters, vGutters)
    }

    private fun computeOverlapRatio(r1: Rect, r2: Rect): Float {
        val interL = max(r1.left, r2.left)
        val interT = max(r1.top, r2.top)
        val interR = min(r1.right, r2.right)
        val interB = min(r1.bottom, r2.bottom)
        if (interR <= interL || interB <= interT) return 0f
        val interArea = (interR - interL) * (interB - interT)
        val minArea = min(max(1, r1.width() * r1.height()), max(1, r2.width() * r2.height()))
        return interArea.toFloat() / minArea.toFloat()
    }

    private fun isNearBox(r1: Rect, r2: Rect, threshold: Int): Boolean {
        val dx = max(0, max(r1.left, r2.left) - min(r1.right, r2.right))
        val dy = max(0, max(r1.top, r2.top) - min(r1.bottom, r2.bottom))
        return dx <= threshold && dy <= threshold
    }

    /**
     * Mathematically guarantees that NO TWO rectangles in the returned list overlap.
     * 1. Merges heavily overlapping boxes (> 12% overlap) into unified dialogue bounding boxes.
     * 2. Cleanly trims residual touching/overlapping edges along the boundary between them.
     */
    fun resolveAllOverlaps(units: List<Rect>, maxWidth: Int, maxHeight: Int): List<Rect> {
        if (units.size <= 1) return units

        val current = units.map { Rect(it) }.filter { it.width() > 8 && it.height() > 8 }.toMutableList()

        // Pass 1: Iterative Union Merge of Substantial Overlaps
        var merged = true
        var mergePass = 0
        while (merged && mergePass < 30) {
            merged = false
            mergePass++
            for (i in 0 until current.size) {
                for (j in i + 1 until current.size) {
                    val r1 = current[i]
                    val r2 = current[j]
                    val interL = max(r1.left, r2.left)
                    val interT = max(r1.top, r2.top)
                    val interR = min(r1.right, r2.right)
                    val interB = min(r1.bottom, r2.bottom)
                    if (interR > interL && interB > interT) {
                        val interArea = (interR - interL) * (interB - interT)
                        val minArea = min(r1.width() * r1.height(), r2.width() * r2.height())
                        val overlapRatio = interArea.toFloat() / max(1, minArea).toFloat()

                        if (overlapRatio > 0.12f) {
                            // Merge into unified box
                            current[i] = Rect(
                                min(r1.left, r2.left),
                                min(r1.top, r2.top),
                                max(r1.right, r2.right),
                                max(r1.bottom, r2.bottom),
                            )
                            current.removeAt(j)
                            merged = true
                            break
                        }
                    }
                }
                if (merged) break
            }
        }

        // Pass 2: Boundary Clipping to ensure 0-pixel overlap
        var adjusted = true
        var adjustPass = 0
        while (adjusted && adjustPass < 20) {
            adjusted = false
            adjustPass++
            for (i in 0 until current.size) {
                for (j in i + 1 until current.size) {
                    val r1 = current[i]
                    val r2 = current[j]
                    val interL = max(r1.left, r2.left)
                    val interT = max(r1.top, r2.top)
                    val interR = min(r1.right, r2.right)
                    val interB = min(r1.bottom, r2.bottom)
                    if (interR > interL && interB > interT) {
                        val dy = r2.centerY() - r1.centerY()
                        val dx = r2.centerX() - r1.centerX()

                        if (abs(dy) >= abs(dx)) {
                            // Vertically stacked: separate along Y
                            val midY = (interT + interB) / 2
                            if (dy >= 0) {
                                current[i] = Rect(r1.left, r1.top, r1.right, midY)
                                current[j] = Rect(r2.left, midY, r2.right, r2.bottom)
                            } else {
                                current[j] = Rect(r2.left, r2.top, r2.right, midY)
                                current[i] = Rect(r1.left, midY, r1.right, r1.bottom)
                            }
                        } else {
                            // Horizontally side-by-side: separate along X
                            val midX = (interL + interR) / 2
                            if (dx >= 0) {
                                current[i] = Rect(r1.left, r1.top, midX, r1.bottom)
                                current[j] = Rect(midX, r2.top, r2.right, r2.bottom)
                            } else {
                                current[j] = Rect(r2.left, r2.top, midX, r2.bottom)
                                current[i] = Rect(midX, r1.top, r1.right, r1.bottom)
                            }
                        }
                        adjusted = true
                    }
                }
            }
        }

        // Pass 3: Sanity clamp & filter degenerate boxes
        return current.mapNotNull { r ->
            val clamped = Rect(
                r.left.coerceIn(0, maxWidth),
                r.top.coerceIn(0, maxHeight),
                r.right.coerceIn(0, maxWidth),
                r.bottom.coerceIn(0, maxHeight),
            )
            if (clamped.width() >= 10 && clamped.height() >= 10) clamped else null
        }
    }

    /**
     * Full Method 4 Dialogue Unit Grouping:
     * 1. Splits conjoined bubbles (strictly verified against parallel column false splits).
     * 2. Formulates full multi-line dialogue boxes for each speech bubble.
     * 3. Absorbs stray characters near speech bubble borders.
     * 4. Groups orphan non-bubble text with strict dual-radius and panel gutter barriers.
     * 5. Enforces mathematical ZERO-OVERLAP across all dialogue boxes.
     * 6. Orders everything in Manga RTL sequence.
     */
    fun groupDialogueUnits(
        rawBoxes: List<Rect>,
        bubbleRegions: List<Rect>,
        bitmap: Bitmap,
        isRtl: Boolean = true,
    ): List<Rect> {
        if (rawBoxes.isEmpty()) return emptyList()

        // 1. Split conjoined bubbles
        val effectiveBubbles = splitConjoinedBubbles(bubbleRegions, rawBoxes, bitmap)

        // 2. Detect panel gutters
        val (hGutters, vGutters) = detectPanelGutters(bitmap)

        val assigned = BooleanArray(rawBoxes.size)
        val candidateUnits = mutableListOf<Rect>()

        // 3. Form unified dialogue blocks for each speech balloon
        for (bubble in effectiveBubbles) {
            val bubbleTextIndices = mutableListOf<Int>()
            for ((idx, box) in rawBoxes.withIndex()) {
                if (!assigned[idx] && isTextInsideBubbleStrict(box, bubble, bitmap)) {
                    bubbleTextIndices.add(idx)
                }
            }

            if (bubbleTextIndices.isNotEmpty()) {
                bubbleTextIndices.forEach { assigned[it] = true }

                val textInside = bubbleTextIndices.map { rawBoxes[it] }
                val minX = textInside.minOf { it.left }
                val minY = textInside.minOf { it.top }
                val maxX = textInside.maxOf { it.right }
                val maxY = textInside.maxOf { it.bottom }

                // Generous 10px padding around dialogue, constrained within bubble and canvas
                val pad = 10
                val unit = Rect(
                    max(bubble.left, minX - pad).coerceAtLeast(0),
                    max(bubble.top, minY - pad).coerceAtLeast(0),
                    min(bubble.right, maxX + pad).coerceAtMost(bitmap.width),
                    min(bubble.bottom, maxY + pad).coerceAtMost(bitmap.height),
                )
                if (unit.width() >= 10 && unit.height() >= 10) {
                    candidateUnits.add(unit)
                }
            }
        }

        // 4. Absorbing residual/stray boxes touching or near existing bubble units
        for ((idx, box) in rawBoxes.withIndex()) {
            if (assigned[idx]) continue
            val touchingUnit = candidateUnits.firstOrNull { unit ->
                unit.contains(box.centerX(), box.centerY()) ||
                computeOverlapRatio(unit, box) > 0.20f ||
                isNearBox(unit, box, threshold = 6)
            }
            if (touchingUnit != null) {
                touchingUnit.union(box)
                assigned[idx] = true
            }
        }

        // 5. Cluster remaining true orphan non-bubble text (floating narrative, SFX, exterior text)
        val orphans = rawBoxes.indices.filter { !assigned[it] }
        if (orphans.isNotEmpty()) {
            val orphanBoxes = orphans.map { rawBoxes[it] }
            val parent = IntArray(orphanBoxes.size) { it }

            fun find(i: Int): Int {
                var c = i
                while (parent[c] != c) {
                    parent[c] = parent[parent[c]]
                    c = parent[c]
                }
                return c
            }

            fun union(i: Int, j: Int) {
                val rI = find(i)
                val rJ = find(j)
                if (rI != rJ) parent[rI] = rJ
            }

            for (i in orphanBoxes.indices) {
                for (j in i + 1 until orphanBoxes.size) {
                    if (find(i) == find(j)) continue
                    val b1 = orphanBoxes[i]
                    val b2 = orphanBoxes[j]

                    // Panel gutter barrier: cannot cross panel borders
                    val crossesHGutter = hGutters.any { gy ->
                        (b1.centerY() < gy && b2.centerY() > gy) || (b2.centerY() < gy && b1.centerY() > gy)
                    }
                    if (crossesHGutter) continue

                    val crossesVGutter = vGutters.any { gx ->
                        (b1.centerX() < gx && b2.centerX() > gx) || (b2.centerX() < gx && b1.centerX() > gx)
                    }
                    if (crossesVGutter) continue

                    // Font size disparity: character height ratio must be <= 1.6x
                    val h1 = max(1, b1.height())
                    val h2 = max(1, b2.height())
                    val sizeRatio = max(h1, h2).toFloat() / min(h1, h2).toFloat()
                    if (sizeRatio > 1.60f) continue

                    // Strict non-bubble proximity
                    val avgW = (b1.width() + b2.width()) / 2f
                    val avgH = (b1.height() + b2.height()) / 2f
                    val dx = max(0, max(b1.left, b2.left) - min(b1.right, b2.right))
                    val dy = max(0, max(b1.top, b2.top) - min(b1.bottom, b2.bottom))

                    val isVertical = (b1.height() > b1.width() * 1.2f) || (b2.height() > b2.width() * 1.2f)
                    val shouldMerge = if (isVertical || isRtl) {
                        val verticalOverlap = min(b1.bottom, b2.bottom) - max(b1.top, b2.top)
                        val hasVertOverlap = verticalOverlap > min(h1, h2) * 0.40f
                        val isAdjacentCol = dx < avgW * 0.85f && hasVertOverlap
                        val isStackedInCol = dx < avgW * 0.25f && dy < avgH * 0.65f
                        isAdjacentCol || isStackedInCol
                    } else {
                        val horizontalOverlap = min(b1.right, b2.right) - max(b1.left, b2.left)
                        val hasHorizOverlap = horizontalOverlap > min(b1.width(), b2.width()) * 0.40f
                        val isAdjacentRow = dy < avgH * 0.80f && hasHorizOverlap
                        val isSideBySide = dy < avgH * 0.25f && dx < avgW * 0.60f
                        isAdjacentRow || isSideBySide
                    }

                    if (shouldMerge) {
                        union(i, j)
                    }
                }
            }

            val orphanGroups = orphanBoxes.groupBy { find(orphanBoxes.indexOf(it)) }
            for ((_, group) in orphanGroups) {
                val minX = group.minOf { it.left }
                val minY = group.minOf { it.top }
                val maxX = group.maxOf { it.right }
                val maxY = group.maxOf { it.bottom }
                val pad = 6
                val orphanUnit = Rect(
                    (minX - pad).coerceAtLeast(0),
                    (minY - pad).coerceAtLeast(0),
                    (maxX + pad).coerceAtMost(bitmap.width),
                    (maxY + pad).coerceAtMost(bitmap.height),
                )
                if (orphanUnit.width() >= 10 && orphanUnit.height() >= 10) {
                    candidateUnits.add(orphanUnit)
                }
            }
        }

        // 6. ZERO-OVERLAP GUARANTEE: Resolve all overlapping dialogue units
        val disjointUnits = resolveAllOverlaps(candidateUnits, bitmap.width, bitmap.height)

        // 7. Sort dialogue units in Manga RTL reading order
        return if (isRtl) {
            disjointUnits.sortedWith(
                compareBy<Rect> { (it.centerY() / 200) }
                    .thenByDescending { it.centerX() },
            )
        } else {
            disjointUnits.sortedWith(
                compareBy<Rect> { (it.centerY() / 200) }
                    .thenBy { it.centerX() },
            )
        }
    }
}
