package com.raen.kisaratranslator.engine.grouping

import android.graphics.Bitmap
import android.graphics.Rect
import com.raen.kisaratranslator.data.logger.AppLogger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Method 5: Intra-Bubble Lobe Grouper & Sweet-Spot Dialogue Parser.
 *
 * Implements:
 * 1. Character-to-Line column extraction (individual vertical line strips).
 * 2. Intra-Bubble Neighborhood Clustering:
 *    Groups adjacent, vertically-aligned columns into coherent speech lobes.
 *    Joined/conjoined bubbles (diagonal or stacked) naturally separate into distinct lobes!
 * 3. Sweet-Spot Limit Tagging:
 *    Lobes with <= 2 lines -> Direct Multi-Line OCR (fast & contextual).
 *    Lobes with > 2 lines or oversized dimensions -> Line-by-line fallback with Japanese RTL joining.
 * 4. Zero-Overlap Guarantee across all dialogue units.
 */
class Method5LobeGrouper {

    data class DialogueLobe(
        val id: Int,
        val bounds: Rect,
        val lines: List<Rect>,
        val isBubble: Boolean,
        val requiresLineFallback: Boolean,
    )

    /**
     * Extracts individual vertical line strips from raw character bounding boxes.
     *
     * Adaptive Algorithm (Strict 1-Column Rule):
     * 1. Dynamic Character Width: Estimates median character width per speech bubble.
     * 2. Pre-Split Wide Smear Boxes: Slices Step 1 boxes spanning multiple columns (W >= 1.85 * medianCharWidth).
     * 3. X-Strip Bucketing: Divides bubble width into N = round(bubbleW / medianCharW) equal strips.
     *    Each box is assigned to the strip its centerX falls in. Zero drift, zero threshold sensitivity.
     * 4. Vertical Stacking: Characters within the same strip are bridged vertically (dy <= 4.5 * medianCharWidth)
     *    into contiguous single-column line strips.
     */
    data class ExtractedLinesResult(
        val allLines: List<Rect>,
        val bubbleLines: List<Rect>,
        val orphanLines: List<Rect>,
    )

    fun extractVerticalLines(
        rawBoxes: List<Rect>,
        bubbleRegions: List<Rect>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        isRtl: Boolean = true,
    ): List<Rect> {
        return extractVerticalLinesDetailed(rawBoxes, bubbleRegions, bitmapWidth, bitmapHeight, isRtl).allLines
    }

    fun extractVerticalLinesDetailed(
        rawBoxes: List<Rect>,
        bubbleRegions: List<Rect>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        isRtl: Boolean = true,
    ): ExtractedLinesResult {
        if (rawBoxes.isEmpty()) return ExtractedLinesResult(emptyList(), emptyList(), emptyList())

        AppLogger.info("[extractVerticalLines] rawBoxes=${rawBoxes.size}, bubbles=${bubbleRegions.size}")
        val bubbleLines = mutableListOf<Rect>()
        val orphanLines = mutableListOf<Rect>()
        val assignedRaw = BooleanArray(rawBoxes.size)

        // 1. Process boxes inside speech bubbles
        for (bubble in bubbleRegions) {
            val insideIndices = rawBoxes.indices.filter { idx ->
                !assignedRaw[idx] && bubble.contains(rawBoxes[idx].centerX(), rawBoxes[idx].centerY())
            }
            if (insideIndices.isEmpty()) continue
            insideIndices.forEach { assignedRaw[it] = true }

            val bubbleBoxes = insideIndices.map { rawBoxes[it] }
            val lines = extractLinesFromCluster(bubbleBoxes, bubble, bitmapWidth, bitmapHeight, isRtl)
            AppLogger.info("[extractVerticalLines] bubble [${bubble.left},${bubble.top},${bubble.right},${bubble.bottom}] inside=${insideIndices.size} -> lines=${lines.size}")
            bubbleLines.addAll(lines)
        }

        // 2. Process orphan boxes outside speech bubbles (Orphans never look for neighbors!)
        val orphanIndices = rawBoxes.indices.filter { !assignedRaw[it] }
        AppLogger.info("[extractVerticalLines] orphans outside bubbles: ${orphanIndices.size}")
        for (idx in orphanIndices) {
            val b = rawBoxes[idx]
            val pad = 0
            orphanLines.add(
                Rect(
                    (b.left - pad).coerceIn(0, bitmapWidth),
                    (b.top - pad).coerceIn(0, bitmapHeight),
                    (b.right + pad).coerceIn(0, bitmapWidth),
                    (b.bottom + pad).coerceIn(0, bitmapHeight),
                )
            )
        }

        val allCombined = bubbleLines + orphanLines
        // Sort in Japanese reading order (top reading bands -> right-to-left)
        val sortedAll = if (isRtl) {
            allCombined.sortedWith(
                compareBy<Rect> { (it.centerY() / 200) }
                    .thenByDescending { it.centerX() }
            )
        } else {
            allCombined.sortedWith(
                compareBy<Rect> { (it.centerY() / 200) }
                    .thenBy { it.centerX() }
            )
        }

        return ExtractedLinesResult(
            allLines = sortedAll,
            bubbleLines = bubbleLines,
            orphanLines = orphanLines,
        )
    }

    /**
     * Extracts strictly single-column vertical line strips from a localized cluster of character boxes.
     * Uses deterministic X-strip bucketing: divides the bubble width into N equal strips where
     * N = round(bubbleWidth / medianCharW). Each box is assigned to the strip its centerX falls in.
     * This avoids all threshold drift issues from the old ColumnTrack approach.
     */
    private fun extractLinesFromCluster(
        boxes: List<Rect>,
        bubbleRect: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int,
        isRtl: Boolean,
    ): List<Rect> {
        if (boxes.isEmpty()) return emptyList()

        // 1. Dynamic median character width calculation
        val medianCharW = if (boxes.isNotEmpty()) {
            val sortedW = boxes.map { it.width() }.sorted()
            sortedW[sortedW.size / 2].toFloat().coerceIn(16f, 34f)
        } else {
            22f
        }

        // 2. Pre-split genuine multi-column smear boxes (W >= 1.85 * medianCharWidth)
        val splitBoxes = mutableListOf<Rect>()
        for (box in boxes) {
            val w = box.width()
            val numCols = Math.round(w.toFloat() / medianCharW)
            if (numCols >= 2 && w >= 1.85f * medianCharW && medianCharW > 10f) {
                val colW = w.toFloat() / numCols
                for (c in 0 until numCols) {
                    val left = (box.left + c * colW).toInt()
                    val right = if (c == numCols - 1) box.right else (box.left + (c + 1) * colW).toInt()
                    splitBoxes.add(Rect(left, box.top, right, box.bottom))
                }
            } else {
                splitBoxes.add(box)
            }
        }

        // 3. X-strip bucketing: divide bubble width into N equal strips, assign box to nearest strip.
        //    No drift, no threshold — purely geometric. Fixes cascading splintering from ColumnTrack.
        val bubbleW = (bubbleRect.right - bubbleRect.left).coerceAtLeast(1)
        val nCols = max(1, Math.round(bubbleW.toFloat() / medianCharW))
        val stripW = bubbleW.toFloat() / nCols

        val strips = Array(nCols) { mutableListOf<Rect>() }
        for (box in splitBoxes) {
            val relX = (box.centerX() - bubbleRect.left).toFloat().coerceIn(0f, bubbleW.toFloat() - 1f)
            val stripIdx = (relX / stripW).toInt().coerceIn(0, nCols - 1)
            strips[stripIdx].add(box)
        }

        // RTL: rightmost strip = column 0 (first column to read)
        val orderedStrips = if (isRtl) strips.reversed() else strips.toList()

        // 4. Vertical line extraction within each strip (same vertical chaining as before)
        val clusterLines = mutableListOf<Rect>()
        for (strip in orderedStrips) {
            if (strip.isEmpty()) continue
            val colBoxes = strip.sortedBy { it.top }

            var currentMinX = colBoxes[0].left
            var currentMinY = colBoxes[0].top
            var currentMaxX = colBoxes[0].right
            var currentMaxY = colBoxes[0].bottom

            for (k in 1 until colBoxes.size) {
                val nextBox = colBoxes[k]
                val dy = max(0, nextBox.top - currentMaxY)
                if (dy <= 4.5f * medianCharW) {
                    currentMinX = min(currentMinX, nextBox.left)
                    currentMinY = min(currentMinY, nextBox.top)
                    currentMaxX = max(currentMaxX, nextBox.right)
                    currentMaxY = max(currentMaxY, nextBox.bottom)
                } else {
                    val pad = 0
                    clusterLines.add(
                        Rect(
                            (currentMinX - pad).coerceIn(0, bitmapWidth),
                            (currentMinY - pad).coerceIn(0, bitmapHeight),
                            (currentMaxX + pad).coerceIn(0, bitmapWidth),
                            (currentMaxY + pad).coerceIn(0, bitmapHeight),
                        )
                    )
                    currentMinX = nextBox.left
                    currentMinY = nextBox.top
                    currentMaxX = nextBox.right
                    currentMaxY = nextBox.bottom
                }
            }

            val pad = 0
            clusterLines.add(
                Rect(
                    (currentMinX - pad).coerceIn(0, bitmapWidth),
                    (currentMinY - pad).coerceIn(0, bitmapHeight),
                    (currentMaxX + pad).coerceIn(0, bitmapWidth),
                    (currentMaxY + pad).coerceIn(0, bitmapHeight),
                )
            )
        }

        // 5. Ensure adjacent vertical lines never overlap horizontally along X
        if (clusterLines.size >= 2) {
            val sortedLines = clusterLines.sortedBy { it.centerX() }
            for (i in 0 until sortedLines.size - 1) {
                val leftLine = sortedLines[i]
                val rightLine = sortedLines[i + 1]
                if (leftLine.right > rightLine.left) {
                    val seamX = (leftLine.right + rightLine.left) / 2
                    leftLine.right = seamX
                    rightLine.left = seamX
                }
            }
        }

        AppLogger.info("[extractLinesFromCluster] boxes=${boxes.size}, medianW=$medianCharW, nCols=$nCols, clusterLines=${clusterLines.size}")
        return clusterLines
    }


    data class PanelGutters(
        val horizontalGutters: List<Int>,
        val verticalGutters: List<Int>,
    )

    /**
     * Detects horizontal and vertical comic panel gutters (solid white/black dividers between panels).
     */
    fun detectPanelGutters(bitmap: Bitmap): PanelGutters {
        val hGutters = mutableListOf<Int>()
        val vGutters = mutableListOf<Int>()
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 100 || h <= 100) return PanelGutters(hGutters, vGutters)

        val sampleStep = 8
        var consecutiveGutterRows = 0
        val totalHSamples = w / sampleStep
        for (y in 15 until h - 15 step 3) {
            var uniformCount = 0
            for (x in 0 until w step sampleStep) {
                val p = bitmap.getPixel(x, y)
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                if (luma > 235 || luma < 25) {
                    uniformCount++
                }
            }
            if (uniformCount.toFloat() / totalHSamples.toFloat() > 0.90f) {
                consecutiveGutterRows++
                if (consecutiveGutterRows == 3) {
                    hGutters.add(y)
                }
            } else {
                consecutiveGutterRows = 0
            }
        }

        var consecutiveGutterCols = 0
        val totalVSamples = h / sampleStep
        for (x in 15 until w - 15 step 3) {
            var uniformCount = 0
            for (y in 0 until h step sampleStep) {
                val p = bitmap.getPixel(x, y)
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                if (luma > 235 || luma < 25) {
                    uniformCount++
                }
            }
            if (uniformCount.toFloat() / totalVSamples.toFloat() > 0.90f) {
                consecutiveGutterCols++
                if (consecutiveGutterCols == 3) {
                    vGutters.add(x)
                }
            } else {
                consecutiveGutterCols = 0
            }
        }

        return PanelGutters(hGutters, vGutters)
    }

    /**
     * Checks whether a panel gutter or solid black frame border line exists between two regions.
     */
    fun hasGutterOrBorderBetween(
        bitmap: Bitmap?,
        r1: Rect,
        r2: Rect,
        gutters: PanelGutters? = null,
    ): Boolean {
        if (gutters != null) {
            val yMin = min(r1.centerY(), r2.centerY())
            val yMax = max(r1.centerY(), r2.centerY())
            if (gutters.horizontalGutters.any { gy -> gy in yMin..yMax }) {
                return true
            }

            val xMin = min(r1.centerX(), r2.centerX())
            val xMax = max(r1.centerX(), r2.centerX())
            if (gutters.verticalGutters.any { gx -> gx in xMin..xMax }) {
                return true
            }
        }

        if (bitmap == null) return false

        // Local horizontal panel border check between vertically separated boxes
        val dy = max(0, max(r1.top, r2.top) - min(r1.bottom, r2.bottom))
        if (dy >= 6) {
            val gapTop = min(r1.bottom, r2.bottom)
            val gapBottom = max(r1.top, r2.top)
            val spanLeft = max(0, min(r1.left, r2.left))
            val spanRight = min(bitmap.width - 1, max(r1.right, r2.right))
            val spanWidth = spanRight - spanLeft

            if (spanWidth > 20) {
                val stepX = max(2, spanWidth / 20)
                for (y in gapTop until gapBottom step max(1, (gapBottom - gapTop) / 4)) {
                    if (y !in 0 until bitmap.height) continue
                    var darkCount = 0
                    var total = 0
                    for (x in spanLeft..spanRight step stepX) {
                        val p = bitmap.getPixel(x, y)
                        val luma = (((p ushr 16) and 0xFF) * 299 + ((p ushr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                        if (luma < 45) darkCount++
                        total++
                    }
                    if (total > 0 && darkCount.toFloat() / total.toFloat() > 0.70f) {
                        return true
                    }
                }
            }
        }

        // Local vertical panel border check between horizontally separated boxes
        val dx = max(0, max(r1.left, r2.left) - min(r1.right, r2.right))
        if (dx >= 6) {
            val gapLeft = min(r1.right, r2.right)
            val gapRight = max(r1.left, r2.left)
            val spanTop = max(0, min(r1.top, r2.top))
            val spanBottom = min(bitmap.height - 1, max(r1.bottom, r2.bottom))
            val spanHeight = spanBottom - spanTop

            if (spanHeight > 20) {
                val stepY = max(2, spanHeight / 20)
                for (x in gapLeft until gapRight step max(1, (gapRight - gapLeft) / 4)) {
                    if (x !in 0 until bitmap.width) continue
                    var darkCount = 0
                    var total = 0
                    for (y in spanTop..spanBottom step stepY) {
                        val p = bitmap.getPixel(x, y)
                        val luma = (((p ushr 16) and 0xFF) * 299 + ((p ushr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                        if (luma < 45) darkCount++
                        total++
                    }
                    if (total > 0 && darkCount.toFloat() / total.toFloat() > 0.70f) {
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Groups vertical line strips into coherent dialogue lobes (clusters).
     * Normal bubbles stay 1 cluster.
     * Joined/conjoined bubbles naturally separate into 2+ distinct lobes.
     */
    fun groupIntoLobes(
        verticalLines: List<Rect>,
        bubbleRegions: List<Rect>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        isRtl: Boolean = true,
        bitmap: Bitmap? = null,
        chunkLinesCount: Int = 2,
    ): List<DialogueLobe> {
        if (verticalLines.isEmpty()) return emptyList()

        val gutters = if (bitmap != null) detectPanelGutters(bitmap) else null
        val assigned = BooleanArray(verticalLines.size)
        val lobes = mutableListOf<DialogueLobe>()
        var lobeIdCounter = 1

        // A. Process lines inside each bubble
        for (bubble in bubbleRegions) {
            val insideIndices = verticalLines.indices.filter { idx ->
                !assigned[idx] && bubble.contains(verticalLines[idx].centerX(), verticalLines[idx].centerY())
            }
            if (insideIndices.isEmpty()) continue

            insideIndices.forEach { assigned[it] = true }
            val bubbleLines = insideIndices.map { verticalLines[it] }

            // Apply Intra-Bubble Neighborhood Rule to partition into speech lobes
            val parent = IntArray(bubbleLines.size) { it }
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

            for (i in bubbleLines.indices) {
                for (j in i + 1 until bubbleLines.size) {
                    if (find(i) == find(j)) continue
                    val l1 = bubbleLines[i]
                    val l2 = bubbleLines[j]

                    // Panel border safety inside bubble
                    if (hasGutterOrBorderBetween(bitmap, l1, l2, gutters)) continue

                    val avgW = (l1.width() + l2.width()) / 2f
                    val minH = min(l1.height(), l2.height())
                    val dx = max(0, max(l1.left, l2.left) - min(l1.right, l2.right))
                    val vertOverlap = min(l1.bottom, l2.bottom) - max(l1.top, l2.top)

                    val topOffset = abs(l1.top - l2.top)
                    val bottomOffset = abs(l1.bottom - l2.bottom)
                    // Conjoined/staircase bubble separation: only separate if there is severe stair-stepping
                    // Relaxed to 0.70h so rounded manga bubble curvature is naturally preserved as 1 lobe
                    val isStairStepped = (topOffset > minH * 0.70f && bottomOffset > minH * 0.70f) || vertOverlap < minH * 0.30f

                    val isAdjacentCol = dx <= avgW * 1.40f && vertOverlap >= minH * 0.35f && !isStairStepped

                    if (isAdjacentCol) {
                        union(i, j)
                    }
                }
            }

            val lobeClusters = bubbleLines.groupBy { find(bubbleLines.indexOf(it)) }
            for ((_, clusterLines) in lobeClusters) {
                val minX = clusterLines.minOf { it.left }
                val minY = clusterLines.minOf { it.top }
                val maxX = clusterLines.maxOf { it.right }
                val maxY = clusterLines.maxOf { it.bottom }

                val pad = 8
                val bounds = Rect(
                    (minX - pad).coerceIn(0, bitmapWidth),
                    (minY - pad).coerceIn(0, bitmapHeight),
                    (maxX + pad).coerceIn(0, bitmapWidth),
                    (maxY + pad).coerceIn(0, bitmapHeight),
                )

                val sortedLines = if (isRtl) {
                    clusterLines.sortedByDescending { it.centerX() }
                } else {
                    clusterLines.sortedBy { it.centerX() }
                }

                val requiresLineFallback = sortedLines.size > chunkLinesCount || bounds.height() > 280 || bounds.width() > 220

                lobes.add(
                    DialogueLobe(
                        id = lobeIdCounter++,
                        bounds = bounds,
                        lines = sortedLines,
                        isBubble = true,
                        requiresLineFallback = requiresLineFallback,
                    )
                )
            }
        }

        // B. Process orphan lines outside bubbles (Orphans never look for neighbors!)
        val orphanIndices = verticalLines.indices.filter { !assigned[it] }
        for (idx in orphanIndices) {
            val line = verticalLines[idx]
            val totalH = line.height()
            val totalW = line.width()
            // Retain all valid text lines outside bubbles (min 8x8px)
            val isMeaningfulLine = totalH >= 8 && totalW >= 8
            if (!isMeaningfulLine) continue

            val pad = 0
            val bounds = Rect(
                (line.left - pad).coerceIn(0, bitmapWidth),
                (line.top - pad).coerceIn(0, bitmapHeight),
                (line.right + pad).coerceIn(0, bitmapWidth),
                (line.bottom + pad).coerceIn(0, bitmapHeight),
            )
            lobes.add(
                DialogueLobe(
                    id = lobeIdCounter++,
                    bounds = bounds,
                    lines = listOf(line),
                    isBubble = false,
                    requiresLineFallback = false,
                )
            )
        }

        // C. Zero-Overlap Guarantee on Lobe Bounds (Lobe-level resolution)
        val disjointLobes = resolveAllOverlaps(lobes, bitmapWidth, bitmapHeight, bitmap, gutters)

        return if (isRtl) {
            disjointLobes.sortedWith(
                compareBy<DialogueLobe> { (it.bounds.centerY() / 200) }
                    .thenByDescending { it.bounds.centerX() }
            )
        } else {
            disjointLobes.sortedWith(
                compareBy<DialogueLobe> { (it.bounds.centerY() / 200) }
                    .thenBy { it.bounds.centerX() }
            )
        }
    }

    private fun resolveAllOverlaps(
        rawLobes: List<DialogueLobe>,
        maxWidth: Int,
        maxHeight: Int,
        bitmap: Bitmap? = null,
        gutters: PanelGutters? = null,
    ): List<DialogueLobe> {
        if (rawLobes.size <= 1) return rawLobes
        val current = rawLobes.toMutableList()

        // 1. Merge heavily overlapping or near-totally nested lobes (True IoU > 45% or Containment > 85%)
        var merged = true
        var pass = 0
        while (merged && pass < 20) {
            merged = false
            pass++
            for (i in 0 until current.size) {
                for (j in i + 1 until current.size) {
                    val l1 = current[i]
                    val l2 = current[j]
                    val r1 = l1.bounds
                    val r2 = l2.bounds
                    val interL = max(r1.left, r2.left)
                    val interT = max(r1.top, r2.top)
                    val interR = min(r1.right, r2.right)
                    val interB = min(r1.bottom, r2.bottom)
                    if (interR > interL && interB > interT) {
                        val interArea = (interR - interL) * (interB - interT)
                        val a1 = r1.width() * r1.height()
                        val a2 = r2.width() * r2.height()
                        val minArea = min(a1, a2)
                        val unionArea = a1 + a2 - interArea
                        val iou = interArea.toFloat() / max(1, unionArea).toFloat()
                        val containment = interArea.toFloat() / max(1, minArea).toFloat()

                        // Never merge across panel gutters or borders!
                        val hasBorder = hasGutterOrBorderBetween(bitmap, r1, r2, gutters)

                        // Only merge if genuine high IoU or near-total containment without crossing borders
                        if (!hasBorder && (iou > 0.45f || containment > 0.85f)) {
                            val unionBounds = Rect(
                                min(r1.left, r2.left),
                                min(r1.top, r2.top),
                                max(r1.right, r2.right),
                                max(r1.bottom, r2.bottom),
                            )
                            val unionLines = (l1.lines + l2.lines).distinct()
                            current[i] = l1.copy(
                                bounds = unionBounds,
                                lines = unionLines,
                                requiresLineFallback = unionLines.size > 2 || unionBounds.height() > 280,
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

        // 2. Edge trim touching or slightly overlapping lobe boundaries (Zero-Overlap Guarantee)
        // Seam-Aware: When the overlap band is taller than it is wide (or vertical columns),
        // trim ONLY along the vertical X seam (midX). NEVER slice horizontally across Y through text columns!
        var adjusted = true
        var adjPass = 0
        while (adjusted && adjPass < 15) {
            adjusted = false
            adjPass++
            for (i in 0 until current.size) {
                for (j in i + 1 until current.size) {
                    val r1 = current[i].bounds
                    val r2 = current[j].bounds
                    val interL = max(r1.left, r2.left)
                    val interT = max(r1.top, r2.top)
                    val interR = min(r1.right, r2.right)
                    val interB = min(r1.bottom, r2.bottom)
                    if (interR > interL && interB > interT) {
                        val interW = interR - interL
                        val interH = interB - interT
                        val newR1: Rect
                        val newR2: Rect

                        // If the overlap zone is taller than wide, or boxes are vertical columns (H > W),
                        // the seam separating them is vertical (X-axis). Trim along midX.
                        val isVerticalSeam = interH >= interW || (r1.height() > r1.width() * 1.15f && r2.height() > r2.width() * 1.15f)
                        if (isVerticalSeam) {
                            val midX = (interL + interR) / 2
                            if (r2.centerX() >= r1.centerX()) {
                                newR1 = Rect(r1.left, r1.top, midX, r1.bottom)
                                newR2 = Rect(midX, r2.top, r2.right, r2.bottom)
                            } else {
                                newR2 = Rect(r2.left, r2.top, midX, r2.bottom)
                                newR1 = Rect(midX, r1.top, r1.right, r1.bottom)
                            }
                        } else {
                            val midY = (interT + interB) / 2
                            if (r2.centerY() >= r1.centerY()) {
                                newR1 = Rect(r1.left, r1.top, r1.right, midY)
                                newR2 = Rect(r2.left, midY, r2.right, r2.bottom)
                            } else {
                                newR2 = Rect(r2.left, r2.top, r2.right, midY)
                                newR1 = Rect(r1.left, midY, r1.right, r1.bottom)
                            }
                        }
                        current[i] = current[i].copy(bounds = newR1)
                        current[j] = current[j].copy(bounds = newR2)
                        adjusted = true
                    }
                }
            }
        }

        return current.filter { it.bounds.width() >= 12 && it.bounds.height() >= 12 }
    }
}
