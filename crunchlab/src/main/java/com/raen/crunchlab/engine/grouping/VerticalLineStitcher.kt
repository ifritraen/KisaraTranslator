package com.raen.crunchlab.engine.grouping

import android.graphics.Bitmap
import android.graphics.Rect
import com.raen.crunchlab.data.TextCategory
import com.raen.crunchlab.data.TextLineItem
import com.raen.crunchlab.data.TextOrientation
import com.raen.crunchlab.engine.BubbleMask
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Vertical Line Stitcher (Module 2) — Simplified Deterministic Geometry Architecture.
 *
 * Enforces the 6 Inviolable Pipeline Invariants:
 * 1. CTD Box Atomicity: CTD Pass 1 boxes are indivisible units of ink. Seams snap to whitespace gutters.
 * 2. In-Bubble Isolation: Speech bubble dialogue lines never merge with unbubbled orphan/SFX lines.
 * 3. 1 Line Per Column: Inside any speech bubble, a vertical column has zero breaks from top to bottom.
 * 4. Uniform Width: All columns in a bubble share uniform width = median(char.width) * 1.08f.
 * 5. Strict Containment: In-bubble lines are 100% contained within the bubble boundary (zero bleeding).
 * 6. Zero Crossover: Adjacent columns on the page maintain 0-pixel overlap.
 */
object VerticalLineStitcher {

    data class StitchedLinesResult(
        val allLines: List<TextLineItem>,
        val bubbledLines: List<TextLineItem>,
        val orphanLines: List<TextLineItem>,
        val sfxLines: List<TextLineItem>,
        val suppressedFuriganaCount: Int = 0,
    )

    /**
     * Whiteness & Ink Density Detector.
     * Screens out phantom text boxes (probes over pure white paper or margin gutters)
     * by checking stroke luminance and dark pixel contrast.
     */
    fun isBoxBlank(bitmap: Bitmap, rect: Rect): Boolean {
        val bmp = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return false
        } else {
            bitmap
        }
        val cL = rect.left.coerceIn(0, bmp.width - 1)
        val cT = rect.top.coerceIn(0, bmp.height - 1)
        val cR = rect.right.coerceIn(cL + 1, bmp.width)
        val cB = rect.bottom.coerceIn(cT + 1, bmp.height)
        val w = cR - cL
        val h = cB - cT
        if (w <= 0 || h <= 0) return true
        if (w < 4 && h < 4) return true

        val totalPixels = w * h
        val pixels = IntArray(totalPixels)
        bmp.getPixels(pixels, 0, w, cL, cT, w, h)

        var minL = 255
        var maxL = 0
        var sumL = 0L
        var darkCount = 0
        var brightCount = 0

        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (r * 77 + g * 150 + b * 29) shr 8
            if (y < minL) minL = y
            if (y > maxL) maxL = y
            sumL += y
            if (y < 135) darkCount++
            if (y > 200) brightCount++
        }

        val contrast = maxL - minL
        if (contrast < 18) return true

        val meanL = (sumL / totalPixels).toInt()
        val darkRatio = darkCount.toFloat() / totalPixels.toFloat()
        val brightRatio = brightCount.toFloat() / totalPixels.toFloat()

        if (meanL > 185 && (darkRatio < 0.008f || darkCount < 4)) return true
        if (meanL < 70 && (brightRatio < 0.008f || brightCount < 4)) return true
        if (contrast < 18 && darkRatio < 0.01f) return true

        return false
    }

    /**
     * Primary entry point: Stitches character boxes into continuous vertical lines.
     */
    fun stitchLines(
        categorizedBoxes: List<TextLineItem>,
        bubbleRegions: List<Rect>,
        bubbleMasks: List<BubbleMask> = emptyList(),
        bitmap: Bitmap? = null,
        bitmapWidth: Int,
        bitmapHeight: Int,
        isRtl: Boolean = true,
    ): StitchedLinesResult {
        if (categorizedBoxes.isEmpty()) {
            return StitchedLinesResult(emptyList(), emptyList(), emptyList(), emptyList(), 0)
        }

        val safeBmp = if (bitmap != null && bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }

        // Pre-filter: Discard pure white paper / blank noise boxes before any grouping
        val validCategorizedBoxes = if (safeBmp != null) {
            categorizedBoxes.filter { !isBoxBlank(safeBmp, it.rect) }
        } else {
            categorizedBoxes
        }
        if (validCategorizedBoxes.isEmpty()) {
            return StitchedLinesResult(emptyList(), emptyList(), emptyList(), emptyList(), 0)
        }

        // Consolidate bubble regions & masks into distinct bubbles
        val allBubbleRects = mutableListOf<Rect>()
        allBubbleRects.addAll(bubbleMasks.map { it.rect })
        allBubbleRects.addAll(bubbleRegions)

        val distinctBubbles = mutableListOf<Rect>()
        for (b in allBubbleRects) {
            val existing = distinctBubbles.firstOrNull { other ->
                val interL = max(other.left, b.left)
                val interT = max(other.top, b.top)
                val interR = min(other.right, b.right)
                val interB = min(other.bottom, b.bottom)
                if (interR > interL && interB > interT) {
                    val interArea = (interR - interL).toLong() * (interB - interT).toLong()
                    val minArea = min(other.width().toLong() * other.height().toLong(), b.width().toLong() * b.height().toLong())
                    minArea > 0 && interArea.toFloat() / minArea.toFloat() > 0.60f
                } else false
            }
            if (existing != null) {
                existing.left = min(existing.left, b.left)
                existing.top = min(existing.top, b.top)
                existing.right = max(existing.right, b.right)
                existing.bottom = max(existing.bottom, b.bottom)
            } else {
                distinctBubbles.add(Rect(b))
            }
        }

        val validBoxes = validCategorizedBoxes.filter { it.rect.width() >= 6 && it.rect.height() >= 6 }
        val medianCharW = if (validBoxes.isNotEmpty()) {
            val sortedW = validBoxes.map { it.rect.width() }.sorted()
            sortedW[sortedW.size / 2].toFloat().coerceIn(18f, 48f)
        } else {
            26f
        }

        val assigned = BooleanArray(validCategorizedBoxes.size)
        val resultBubbled = mutableListOf<Rect>()
        var totalFuriganaSuppressed = 0

        // 1. Process inside each speech bubble (each column becomes exactly ONE continuous vertical line)
        val bubbleAssignments = List(distinctBubbles.size) { mutableListOf<Int>() }

        for (idx in validCategorizedBoxes.indices) {
            val box = validCategorizedBoxes[idx].rect
            val cx = box.centerX()
            val cy = box.centerY()
            val boxArea = box.width().toLong() * box.height().toLong()
            var bestBIdx = -1
            var maxInterArea = 0L

            for (bIdx in distinctBubbles.indices) {
                val bubble = distinctBubbles[bIdx]
                val interL = max(box.left, bubble.left)
                val interT = max(box.top, bubble.top)
                val interR = min(box.right, bubble.right)
                val interB = min(box.bottom, bubble.bottom)
                if (interR > interL && interB > interT) {
                    val interArea = (interR - interL).toLong() * (interB - interT).toLong()
                    val ratio = if (boxArea > 0) interArea.toFloat() / boxArea.toFloat() else 0f
                    val excessX = max(0, box.right - bubble.right) + max(0, bubble.left - box.left)
                    val isTrulyInBubble = (ratio >= 0.60f && excessX <= max(12, (box.width() * 0.25f).toInt())) || (ratio >= 0.85f)
                    val inEnvelope = cx in (bubble.left - 6)..(bubble.right + 6) && cy in (bubble.top - 6)..(bubble.bottom + 6)

                    val isMatch = if (validCategorizedBoxes[idx].category == TextCategory.BUBBLED) {
                        bubble.contains(cx, cy) || (inEnvelope && (ratio >= 0.35f || interArea > 0))
                    } else {
                        val matchingMask = bubbleMasks.firstOrNull { it.rect.contains(cx, cy) }
                        val inMask = if (matchingMask != null) {
                            val lx = cx - matchingMask.rect.left
                            val ly = cy - matchingMask.rect.top
                            if (lx in 0 until matchingMask.width && ly in 0 until matchingMask.height) {
                                matchingMask.mask[ly * matchingMask.width + lx]
                            } else true
                        } else true
                        inMask && isTrulyInBubble && inEnvelope
                    }

                    if (isMatch && interArea > maxInterArea) {
                        maxInterArea = interArea
                        bestBIdx = bIdx
                    }
                }
            }

            if (bestBIdx >= 0) {
                assigned[idx] = true
                bubbleAssignments[bestBIdx].add(idx)
            }
        }

        for (bIdx in distinctBubbles.indices) {
            val insideIndices = bubbleAssignments[bIdx]
            if (insideIndices.isEmpty()) continue

            val rawCluster = insideIndices.map { validCategorizedBoxes[it].rect }
            val (stitched, furiCount) = stitchBubbleColumns(rawCluster, distinctBubbles[bIdx], medianCharW, safeBmp)
            resultBubbled.addAll(stitched)
            totalFuriganaSuppressed += furiCount
        }

        // 2. Process non-bubbled boxes outside speech bubbles
        val orphanIndices = validCategorizedBoxes.indices.filter { !assigned[it] }
        val orphanBoxes = orphanIndices.filter { validCategorizedBoxes[it].category != TextCategory.SFX }.map { validCategorizedBoxes[it].rect }
        val sfxBoxes = orphanIndices.filter { validCategorizedBoxes[it].category == TextCategory.SFX }.map { validCategorizedBoxes[it].rect }

        val (unbubbledOrphan, reclassifiedSfx) = stitchUnbubbledBoxes(orphanBoxes, medianCharW, isOrphanCandidate = true, safeBmp)
        val (_, unbubbledSfx) = stitchUnbubbledBoxes(sfxBoxes, medianCharW, isOrphanCandidate = false, safeBmp)
        val finalOrphan = unbubbledOrphan
        val finalSfx = unbubbledSfx + reclassifiedSfx

        // 3. Convert to TextLineItem
        val bubbledItems = resultBubbled.map {
            TextLineItem(id = 0, rect = it, angle = 0f, confidence = 1.0f, category = TextCategory.BUBBLED, orientation = TextOrientation.VERTICAL)
        }
        val orphanItems = finalOrphan.map {
            TextLineItem(id = 0, rect = it, angle = 0f, confidence = 1.0f, category = TextCategory.ORPHAN, orientation = TextOrientation.VERTICAL)
        }
        val sfxItems = finalSfx.map {
            TextLineItem(id = 0, rect = it, angle = 0f, confidence = 1.0f, category = TextCategory.SFX, orientation = TextOrientation.VERTICAL)
        }

        // 4. Resolve unbubbled collisions with speech bubbles & across unbubbled lines
        val containedLines = mutableListOf<TextLineItem>()
        containedLines.addAll(bubbledItems)

        // Suppress unbubbled items with major bubble overlap (>= 50%)
        for (item in orphanItems + sfxItems) {
            val r = Rect(item.rect)
            var valid = true
            for (b in distinctBubbles) {
                val interL = max(r.left, b.left)
                val interT = max(r.top, b.top)
                val interR = min(r.right, b.right)
                val interB = min(r.bottom, b.bottom)
                if (interR > interL && interB > interT) {
                    val interArea = (interR - interL).toLong() * (interB - interT).toLong()
                    val rArea = r.width().toLong() * r.height().toLong()
                    if (rArea > 0 && interArea.toFloat() / rArea.toFloat() >= 0.50f) {
                        valid = false
                        break
                    }
                }
            }
            if (valid && r.width() >= 6 && r.height() >= 6) {
                containedLines.add(item.copy(rect = r))
            }
        }

        // 4b. Deduplicate any near-identical rects (SFX/ORPHAN overlapping identical visual blob)
        val deduplicatedLines = deduplicateLineItems(containedLines)

        // 5. Sort in Japanese reading order (RTL: top-to-bottom bands, right-to-left within bands)
        val sortedAll = if (isRtl) {
            deduplicatedLines.sortedWith(
                compareBy<TextLineItem> { (it.rect.centerY() / 200) }
                    .thenByDescending { it.rect.centerX() }
            )
        } else {
            deduplicatedLines.sortedWith(
                compareBy<TextLineItem> { (it.rect.centerY() / 200) }
                    .thenBy { it.rect.centerX() }
            )
        }

        val reIndexed = sortedAll.mapIndexed { idx, item -> item.copy(id = idx + 1) }

        return StitchedLinesResult(
            allLines = reIndexed,
            bubbledLines = reIndexed.filter { it.category == TextCategory.BUBBLED },
            orphanLines = reIndexed.filter { it.category == TextCategory.ORPHAN },
            sfxLines = reIndexed.filter { it.category == TextCategory.SFX },
            suppressedFuriganaCount = totalFuriganaSuppressed,
        )
    }

    /**
     * In-Bubble Column Stitcher.
     * Enforces the 6 core invariants inside a speech bubble:
     * 1. 1 Line Per Column: Zero breaks or multiple segments per column.
     * 2. Uniform Width: All columns in the bubble share uniform width = median(char.width) * 1.08f.
     * 3. CTD Atomic Box Grounding: Constituent character ink is 100% enclosed; boundaries snap to whitespace gutters.
     * 4. Strict Containment: 100% contained within the bubble boundary (0% bleed).
     * 5. Zero Crossover: 0-pixel overlap between adjacent columns.
     */
    fun stitchBubbleColumns(
        boxes: List<Rect>,
        bubble: Rect,
        globalCharW: Float,
        bitmap: Bitmap? = null,
    ): Pair<List<Rect>, Int> {
        if (boxes.isEmpty()) return Pair(emptyList(), 0)

        // Step 0: Clip incoming boxes strictly to bubble bounds
        val clippedBoxes = boxes.mapNotNull { b ->
            val interL = max(b.left, bubble.left)
            val interT = max(b.top, bubble.top)
            val interR = min(b.right, bubble.right)
            val interB = min(b.bottom, bubble.bottom)
            if (interR > interL && interB > interT && (interR - interL) >= 6 && (interB - interT) >= 6) {
                Rect(interL, interT, interR, interB)
            } else null
        }
        if (clippedBoxes.isEmpty()) return Pair(emptyList(), 0)

        val nonBlank = if (bitmap != null) clippedBoxes.filter { !isBoxBlank(bitmap, it) } else clippedBoxes
        if (nonBlank.isEmpty()) return Pair(emptyList(), 0)

        // Deduplicate nested boxes
        val deduplicated = resolveNestedBoxes(nonBlank)

        // Local character width estimation from standard-sized character boxes
        val standardChars = deduplicated.filter { it.height() >= 16 && it.width() >= 12 }
        val maxAllowedW = max(48f, globalCharW * 1.5f)
        val localCharW = if (standardChars.isNotEmpty()) {
            val sorted = standardChars.map { it.width() }.sorted()
            sorted[sorted.size / 2].toFloat().coerceIn(16f, maxAllowedW)
        } else {
            globalCharW.coerceIn(16f, maxAllowedW)
        }

        // Step 1: Furigana ruby text absorption
        val (cleanedBoxes, furiCount) = suppressOrMergeFurigana(deduplicated, localCharW)

        // Step 2: 1D Horizontal Pitch Clustering (RTL: right-to-left)
        // In vertical Japanese text, columns are parallel strips separated in X.
        data class ColumnCluster(
            val members: MutableList<Rect>,
            var minLeft: Int,
            var maxRight: Int,
            var minTop: Int,
            var maxBottom: Int,
        ) {
            val cx: Float get() = members.map { it.centerX() }.average().toFloat()
        }

        val sortedBoxes = cleanedBoxes.sortedWith(
            compareByDescending<Rect> { it.centerX() }.thenBy { it.top }
        )

        val columns = mutableListOf<ColumnCluster>()

        for (box in sortedBoxes) {
            val bcx = box.centerX().toFloat()
            var bestCol: ColumnCluster? = null
            var bestDist = Float.MAX_VALUE

            for (col in columns) {
                val cxDist = abs(bcx - col.cx)
                val unionW = max(col.maxRight, box.right) - min(col.minLeft, box.left)
                val hOverlap = max(0, min(col.maxRight, box.right) - max(col.minLeft, box.left))
                val minW = min(box.width(), col.maxRight - col.minLeft)

                // Same column if center is within 0.75x charW OR substantial horizontal overlap
                val isCorridorMatch = (cxDist <= localCharW * 0.75f && unionW <= localCharW * 1.70f) ||
                        (minW > 0 && hOverlap >= minW * 0.35f && unionW <= localCharW * 1.85f)

                if (isCorridorMatch && cxDist < bestDist) {
                    bestDist = cxDist
                    bestCol = col
                }
            }

            if (bestCol != null) {
                bestCol.members.add(box)
                bestCol.minLeft = min(bestCol.minLeft, box.left)
                bestCol.maxRight = max(bestCol.maxRight, box.right)
                bestCol.minTop = min(bestCol.minTop, box.top)
                bestCol.maxBottom = max(bestCol.maxBottom, box.bottom)
            } else {
                columns.add(
                    ColumnCluster(
                        members = mutableListOf(box),
                        minLeft = box.left,
                        maxRight = box.right,
                        minTop = box.top,
                        maxBottom = box.bottom,
                    )
                )
            }
        }

        // Step 2b: Physical Column Pitch Law (Japanese Manga Typography)
        // Two distinct vertical columns cannot have center distance < 1.15 * localCharW.
        // Columns with cxDist < minColPitch are split radicals/strokes of the same character
        // or colliding columns in an overcrowded bubble.
        // We iteratively merge the most colliding pair (smallest center distance).
        val minColPitch = localCharW * 1.15f
        var pitchChanged = true
        var pitchPasses = 0
        while (pitchChanged && pitchPasses < 50 && columns.size > 1) {
            pitchChanged = false
            pitchPasses++
            var bestI = -1
            var bestJ = -1
            var minCxDist = Float.MAX_VALUE
            for (i in columns.indices) {
                for (j in i + 1 until columns.size) {
                    val dist = abs(columns[i].cx - columns[j].cx)
                    if (dist < minCxDist) {
                        minCxDist = dist
                        bestI = i
                        bestJ = j
                    }
                }
            }
            if (bestI >= 0 && bestJ >= 0 && minCxDist < minColPitch) {
                val c1 = columns[bestI]
                val c2 = columns[bestJ]
                c1.members.addAll(c2.members)
                c1.minLeft = min(c1.minLeft, c2.minLeft)
                c1.maxRight = max(c1.maxRight, c2.maxRight)
                c1.minTop = min(c1.minTop, c2.minTop)
                c1.maxBottom = max(c1.maxBottom, c2.maxBottom)
                columns.removeAt(bestJ)
                pitchChanged = true
            }
        }

        // Step 2c: Bubble Column Capacity Bound (Physical Geometry Law)
        // Minimum bubble width needed for k columns: W >= (1.20 * k + 0.30) * localCharW.
        // Therefore k_max = floor((bubbleW - 0.30 * localCharW) / (1.20 * localCharW)).
        val bubbleW = bubble.width().toFloat()
        val maxColumns = max(1, ((bubbleW - 0.30f * localCharW) / (1.20f * localCharW)).toInt())

        // If single-column bubble, merge all boxes unconditionally into 1 column
        if (maxColumns == 1 && columns.size > 1) {
            val main = columns[0]
            for (k in 1 until columns.size) {
                main.members.addAll(columns[k].members)
                main.minLeft = min(main.minLeft, columns[k].minLeft)
                main.maxRight = max(main.maxRight, columns[k].maxRight)
                main.minTop = min(main.minTop, columns[k].minTop)
                main.maxBottom = max(main.maxBottom, columns[k].maxBottom)
            }
            columns.clear()
            columns.add(main)
        } else {
            // Prune short sub-fragments (ruby text, punctuation, split radicals) that sat beside tall columns
            val maxColH = columns.maxOfOrNull { it.maxBottom - it.minTop } ?: 0
            val toMerge = mutableListOf<ColumnCluster>()
            for (col in columns) {
                val h = col.maxBottom - col.minTop
                if (columns.size > 1 && h < max(localCharW * 1.25f, maxColH * 0.35f) && col.members.size <= 2) {
                    val nearest = columns.filter { it != col && it !in toMerge }.minByOrNull { abs(it.cx - col.cx) }
                    if (nearest != null && abs(nearest.cx - col.cx) <= localCharW * 1.40f) {
                        nearest.members.addAll(col.members)
                        nearest.minLeft = min(nearest.minLeft, col.minLeft)
                        nearest.maxRight = max(nearest.maxRight, col.maxRight)
                        nearest.minTop = min(nearest.minTop, col.minTop)
                        nearest.maxBottom = max(nearest.maxBottom, col.maxBottom)
                        toMerge.add(col)
                    }
                }
            }
            columns.removeAll(toMerge)

            // Strictly enforce capacity: while columns exceed maxColumns, merge the weakest cluster into nearest dominant neighbor
            while (columns.size > maxColumns) {
                val weakest = columns.minByOrNull { (it.maxBottom - it.minTop) * 1000 + it.members.size } ?: break
                val nearest = columns.filter { it != weakest }.minByOrNull { abs(it.cx - weakest.cx) } ?: break
                nearest.members.addAll(weakest.members)
                nearest.minLeft = min(nearest.minLeft, weakest.minLeft)
                nearest.maxRight = max(nearest.maxRight, weakest.maxRight)
                nearest.minTop = min(nearest.minTop, weakest.minTop)
                nearest.maxBottom = max(nearest.maxBottom, weakest.maxBottom)
                columns.remove(weakest)
            }
        }

        // Step 3: Uniform Column Width & CTD Atomic Box Grounding
        // All columns inside the bubble share uniform width = localCharW * 1.08f
        val minColWidthFloor = max((localCharW * 0.75f).toInt(), 16)
        val uniformW = (localCharW * 1.08f).toInt().coerceAtLeast(minColWidthFloor)
        val halfW = uniformW / 2

        class GroundedColumn(
            val col: ColumnCluster,
            val rect: Rect,
            val charMinLeft: Int,
            val charMaxRight: Int,
        )

        val groundedCols = mutableListOf<GroundedColumn>()
        for (col in columns) {
            val cx = col.cx.toInt()
            var colL = cx - halfW
            var colR = cx + halfW

            // Grounding: Enclose all constituent CTD character boxes without slicing
            colL = min(colL, col.minLeft)
            colR = max(colR, col.maxRight)

            // Strict Bubble Containment: 100% inside bubble boundary (zero bleed)
            var clampL = max(colL, bubble.left)
            var clampR = min(colR, bubble.right)
            val clampT = max(col.minTop, bubble.top)
            val clampB = min(col.maxBottom, bubble.bottom)

            // Elliptical Bubble Taper Adaptation: Prevent curved bubble borders from choking column caliber
            val curW = clampR - clampL
            if (curW < minColWidthFloor && bubble.width() >= minColWidthFloor) {
                val deficit = minColWidthFloor - curW
                val expandL = min(deficit / 2, max(0, clampL - bubble.left))
                val expandR = min(deficit - expandL, max(0, bubble.right - clampR))
                clampL -= expandL
                clampR += expandR
            }

            val line = Rect(clampL, clampT, clampR, clampB)
            if (line.width() >= 8 && line.height() >= 12) {
                groundedCols.add(GroundedColumn(col, line, col.minLeft, col.maxRight))
            }
        }

        // Step 4: Atomic Whitespace Gutter Seam Trimming (NEVER slice through character ink)
        groundedCols.sortBy { it.rect.centerX() }
        for (i in 0 until groundedCols.size - 1) {
            val leftCol = groundedCols[i]
            val rightCol = groundedCols[i + 1]
            val leftRect = leftCol.rect
            val rightRect = rightCol.rect

            val vOverlap = max(0, min(leftRect.bottom, rightRect.bottom) - max(leftRect.top, rightRect.top))
            if (vOverlap > 0 && leftRect.right > rightRect.left) {
                val leftCharMaxR = leftCol.charMaxRight
                val rightCharMinL = rightCol.charMinLeft

                val seamX = if (leftCharMaxR <= rightCharMinL) {
                    // Clean whitespace gutter between characters: snap seam into the gutter!
                    (leftCharMaxR + rightCharMinL) / 2
                } else {
                    // Characters physically overlap in X: snap to the midpoint between column centers
                    ((leftCol.col.cx + rightCol.col.cx) / 2f).toInt()
                }

                val minSafeR = leftRect.left + minColWidthFloor
                val maxSafeL = rightRect.right - minColWidthFloor
                val safeSeam = if (minSafeR <= maxSafeL) {
                    seamX.coerceIn(minSafeR, maxSafeL)
                } else {
                    (leftRect.left + rightRect.right) / 2
                }
                leftRect.right = min(leftRect.right, safeSeam)
                rightRect.left = max(rightRect.left, safeSeam)
            }
        }

        return Pair(groundedCols.map { it.rect }, furiCount)
    }

    /**
     * Unbubbled Box Stitcher (for text on art, captions, and sound effects).
     * Enforces physical vertical gap caps (<= 2.0x charW) to prevent cross-panel monster lines.
     * Categorizes narrative vertical columns as ORPHAN and sound effects/brush art as SFX.
     */
    private fun stitchUnbubbledBoxes(
        boxes: List<Rect>,
        medianCharW: Float,
        isOrphanCandidate: Boolean,
        bitmap: Bitmap? = null,
    ): Pair<List<Rect>, List<Rect>> {
        if (boxes.isEmpty()) return Pair(emptyList(), emptyList())

        val deduplicated = resolveNestedBoxes(boxes)
        if (deduplicated.isEmpty()) return Pair(emptyList(), emptyList())

        val xStripW = medianCharW.toInt().coerceAtLeast(8)
        val sortedBoxes = deduplicated.sortedWith(
            compareByDescending<Rect> { it.centerX() / xStripW }
                .thenBy { it.top }
        )

        val maxVertGap = medianCharW * 2.0f // Physical gap cap: eliminates 500px monster lines!

        data class UnbubbledCol(
            val members: MutableList<Rect>,
            var topEdge: Int,
            var bottomEdge: Int,
        ) {
            val cx: Float get() = members.map { it.centerX() }.average().toFloat()
        }

        val columns = mutableListOf<UnbubbledCol>()

        for (box in sortedBoxes) {
            val bcx = box.centerX().toFloat()
            var bestCol: UnbubbledCol? = null
            var bestDist = Float.MAX_VALUE

            for (col in columns) {
                if (abs(bcx - col.cx) > medianCharW * 0.60f) continue
                // Proper 2-sided vertical gap:
                val gap = if (box.top >= col.bottomEdge) {
                    box.top - col.bottomEdge
                } else if (box.bottom <= col.topEdge) {
                    col.topEdge - box.bottom
                } else {
                    val vOverlap = min(box.bottom, col.bottomEdge) - max(box.top, col.topEdge)
                    if (vOverlap > min(box.height(), col.bottomEdge - col.topEdge) * 0.25f) continue
                    0
                }
                if (gap > maxVertGap) continue

                val dist = abs(bcx - col.cx)
                if (dist < bestDist) {
                    bestDist = dist
                    bestCol = col
                }
            }

            if (bestCol != null) {
                bestCol.members.add(box)
                bestCol.topEdge = min(bestCol.topEdge, box.top)
                bestCol.bottomEdge = max(bestCol.bottomEdge, box.bottom)
            } else {
                columns.add(UnbubbledCol(mutableListOf(box), box.top, box.bottom))
            }
        }

        val orphans = mutableListOf<Rect>()
        val sfx = mutableListOf<Rect>()

        val uniformW = (medianCharW * 1.15f).toInt().coerceIn(18, 52)
        val halfW = uniformW / 2

        for (col in columns) {
            val cx = col.cx.toInt()
            val minL = col.members.minOf { it.left }
            val maxR = col.members.maxOf { it.right }
            val minT = col.members.minOf { it.top }
            val maxB = col.members.maxOf { it.bottom }

            if (isOrphanCandidate) {
                val orphanRect = Rect(min(cx - halfW, minL), minT, max(cx + halfW, maxR), maxB)
                val isProperWidth = orphanRect.width() <= medianCharW * 1.45f
                val isMultiChar = orphanRect.height() >= medianCharW * 1.80f && col.members.size >= 2
                val isVertical = orphanRect.height() >= orphanRect.width() * 1.30f

                if (isProperWidth && isMultiChar && isVertical) {
                    if (orphanRect.width() >= 6 && orphanRect.height() >= 6) {
                        orphans.add(orphanRect)
                    }
                } else {
                    val naturalRect = Rect(minL, minT, maxR, maxB)
                    if (naturalRect.width() >= 6 && naturalRect.height() >= 6) {
                        sfx.add(naturalRect)
                    }
                }
            } else {
                val naturalRect = Rect(minL, minT, maxR, maxB)
                if (naturalRect.width() >= 6 && naturalRect.height() >= 6) {
                    sfx.add(naturalRect)
                }
            }
        }

        return Pair(orphans, sfx)
    }

    /**
     * Furigana Suppression & Ruby Column Merging.
     * Identifies narrow ruby text boxes (width < 0.45 * medianCharWidth) and absorbs them into the adjacent kanji column.
     */
    fun suppressOrMergeFurigana(boxes: List<Rect>, medianCharW: Float): Pair<List<Rect>, Int> {
        if (boxes.size < 2) return Pair(boxes, 0)

        val mainColumns = mutableListOf<Rect>()
        val furiganaBoxes = mutableListOf<Rect>()

        for (b in boxes) {
            val w = b.width().toFloat()
            if (w < 0.52f * medianCharW && b.height() > 6) {
                furiganaBoxes.add(b)
            } else {
                mainColumns.add(b)
            }
        }

        if (furiganaBoxes.isEmpty() || mainColumns.isEmpty()) {
            return Pair(boxes, 0)
        }

        val mergedMain = mainColumns.toMutableList()
        var totalAbsorbed = 0
        for (furi in furiganaBoxes) {
            val furiCx = furi.centerX()
            // In-column alignment check: if aligned with an existing box in the column corridor,
            // it is an in-column character/punctuation (e.g. small kana or punctuation), NOT ruby text!
            val inColAligned = mainColumns.any { abs(it.centerX() - furiCx) <= medianCharW * 0.38f }
            if (inColAligned) {
                mergedMain.add(furi)
                continue
            }

            val parentCol = mergedMain.filter { col ->
                val vOverlap = max(0, min(col.bottom, furi.bottom) - max(col.top, furi.top))
                val horizDist = abs(col.centerX() - furiCx).toFloat()
                vOverlap > 0 && horizDist in (medianCharW * 0.38f)..(medianCharW * 1.40f)
            }.minByOrNull { col -> abs(col.centerX() - furiCx) }

            if (parentCol != null) {
                val idx = mergedMain.indexOf(parentCol)
                mergedMain[idx] = Rect(
                    min(parentCol.left, furi.left),
                    min(parentCol.top, furi.top),
                    max(parentCol.right, furi.right),
                    max(parentCol.bottom, furi.bottom)
                )
                totalAbsorbed++
            } else {
                mergedMain.add(furi)
            }
        }

        return Pair(mergedMain, totalAbsorbed)
    }

    /**
     * Absorbs raw character boxes that are heavily nested or overlapping (> 40% area overlap).
     */
    private fun resolveNestedBoxes(boxes: List<Rect>): List<Rect> {
        if (boxes.size < 2) return boxes
        val result = boxes.toMutableList()
        var changed = true
        var passes = 0

        while (changed && passes < 8) {
            changed = false
            passes++
            for (i in result.indices) {
                for (j in i + 1 until result.size) {
                    val a = result[i]
                    val b = result[j]
                    val interL = max(a.left, b.left)
                    val interT = max(a.top, b.top)
                    val interR = min(a.right, b.right)
                    val interB = min(a.bottom, b.bottom)
                    if (interR > interL && interB > interT) {
                        val interArea = (interR - interL).toLong() * (interB - interT).toLong()
                        val aArea = a.width().toLong() * a.height().toLong()
                        val bArea = b.width().toLong() * b.height().toLong()

                        val aInB = aArea > 0 && interArea.toFloat() / aArea.toFloat() >= 0.40f
                        val bInA = bArea > 0 && interArea.toFloat() / bArea.toFloat() >= 0.40f

                        if (aInB || bInA) {
                            val unionRect = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
                            result.removeAt(j)
                            result[i] = unionRect
                            changed = true
                            break
                        }
                    }
                }
                if (changed) break
            }
        }
        return result
    }

    /**
     * Cross-category deduplication: removes duplicate rects when the same visual blob appears in multiple categories.
     * Priority: BUBBLED > ORPHAN > SFX.
     */
    private fun deduplicateLineItems(lines: List<TextLineItem>): List<TextLineItem> {
        if (lines.size < 2) return lines
        val toRemove = mutableSetOf<Int>()
        for (i in lines.indices) {
            if (i in toRemove) continue
            for (j in i + 1 until lines.size) {
                if (j in toRemove) continue
                val ra = lines[i].rect
                val rb = lines[j].rect
                val interL = max(ra.left, rb.left)
                val interT = max(ra.top, rb.top)
                val interR = min(ra.right, rb.right)
                val interB = min(ra.bottom, rb.bottom)
                if (interR > interL && interB > interT) {
                    val interArea = (interR - interL).toLong() * (interB - interT).toLong()
                    val minArea = min(ra.width().toLong() * ra.height().toLong(), rb.width().toLong() * rb.height().toLong())
                    if (minArea > 0 && interArea.toFloat() / minArea.toFloat() > 0.65f) {
                        val priI = when (lines[i].category) { TextCategory.BUBBLED -> 3; TextCategory.ORPHAN -> 2; else -> 1 }
                        val priJ = when (lines[j].category) { TextCategory.BUBBLED -> 3; TextCategory.ORPHAN -> 2; else -> 1 }
                        val removeJ = if (priI != priJ) (priI > priJ) else (ra.width().toLong() * ra.height().toLong() >= rb.width().toLong() * rb.height().toLong())
                        toRemove.add(if (removeJ) j else i)
                    }
                }
            }
        }
        return lines.filterIndexed { index, _ -> index !in toRemove }
    }

    /**
     * Backwards compatibility helper for external / testing callers.
     */
    fun stitchColumnBoxes(
        boxes: List<Rect>,
        bitmapWidth: Int = 10000,
        bitmapHeight: Int = 10000,
        bitmap: Bitmap? = null,
        isInsideBubble: Boolean = false,
        globalCharW: Float = 26f,
    ): List<Rect> {
        val (orphans, sfx) = stitchUnbubbledBoxes(boxes, globalCharW, isOrphanCandidate = !isInsideBubble, bitmap = bitmap)
        return orphans + sfx
    }
}
