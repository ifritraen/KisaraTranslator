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
 * Text Categorizer Engine (Module 1.5).
 *
 * Classifies raw character and text boxes detected in Module 1 into 3 distinct categories:
 * 1. BUBBLED: Text contained within detected speech bubble envelopes or pixel-accurate BubbleMasks.
 * 2. ORPHAN: Typeset dialogue, narration, thoughts, or signs positioned outside speech bubbles.
 * 3. SFX: Hand-drawn sound effects, stylized strokes, and onomatopoeia.
 *
 * Uses Supreme Zero-OCR Classifier (combining stroke caliber/weight and geometric aspect ratio regularity).
 */
object TextCategorizer {

    data class CategorizationResult(
        val allBoxes: List<TextLineItem>,
        val bubbledBoxes: List<TextLineItem>,
        val orphanBoxes: List<TextLineItem>,
        val sfxBoxes: List<TextLineItem>,
    )

    fun categorizeBoxes(
        rawBoxes: List<Rect>,
        bubbleRegions: List<Rect>,
        bubbleMasks: List<BubbleMask> = emptyList(),
        bitmap: Bitmap? = null,
        bitmapWidth: Int,
        bitmapHeight: Int,
        isRtl: Boolean = true,
    ): CategorizationResult {
        if (rawBoxes.isEmpty()) {
            return CategorizationResult(emptyList(), emptyList(), emptyList(), emptyList())
        }

        val bubbled = mutableListOf<TextLineItem>()
        val orphan = mutableListOf<TextLineItem>()
        val sfx = mutableListOf<TextLineItem>()
        val assigned = BooleanArray(rawBoxes.size)

        // 1. Combine valid bubble regions (Manga109 masks + CTD bubble envelopes)
        val validBubbleRegions = bubbleRegions.filter { it.width() >= 18 && it.height() >= 22 && it.width() * it.height() >= 400 }
        val validMasks = bubbleMasks.filter { it.width >= 18 && it.height >= 22 && it.width * it.height >= 400 }
        val allBubbleRects = mutableListOf<Rect>()
        allBubbleRects.addAll(validMasks.map { it.rect })
        allBubbleRects.addAll(validBubbleRegions)

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

        // 2. Classify inside speech bubbles (Max-Intersection Association)
        val bubbleAssignments = List(distinctBubbles.size) { mutableListOf<Int>() }

        for (idx in rawBoxes.indices) {
            val box = rawBoxes[idx]
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
                    val inEnvelope = cx in (bubble.left - 4)..(bubble.right + 4) && cy in (bubble.top - 4)..(bubble.bottom + 4)

                    val isInBubbleEnvelope = (bubble.contains(cx, cy) && (ratio >= 0.45f || isTrulyInBubble)) ||
                        (inEnvelope && ratio >= 0.70f)

                    val matchingMask = validMasks.firstOrNull { it.rect.contains(cx, cy) }
                    val isMatch = if (matchingMask != null) {
                        val lx = cx - matchingMask.rect.left
                        val ly = cy - matchingMask.rect.top
                        val inMask = if (lx in 0 until matchingMask.width && ly in 0 until matchingMask.height) {
                            matchingMask.mask[ly * matchingMask.width + lx]
                        } else false
                        (inMask && isTrulyInBubble && inEnvelope) || isInBubbleEnvelope
                    } else {
                        isInBubbleEnvelope
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

        for (indices in bubbleAssignments) {
            for (idx in indices) {
                bubbled.add(
                    TextLineItem(
                        id = 0,
                        rect = rawBoxes[idx],
                        angle = 0f,
                        confidence = 1.0f,
                        category = TextCategory.BUBBLED,
                        orientation = TextOrientation.VERTICAL,
                    )
                )
            }
        }

        // 3. Classify non-bubbled boxes outside speech bubbles
        val orphanIndices = rawBoxes.indices.filter { !assigned[it] }
        val rawNonBubbledBoxes = orphanIndices.map { rawBoxes[it] }

        // Cluster adjacent non-bubbled boxes into local proximity groups for geometric analysis
        val nonBubbledClusters = clusterAdjacentBoxes(rawNonBubbledBoxes, bitmapWidth, bitmapHeight)

        for (cluster in nonBubbledClusters) {
            // Cluster-level coherent classification:
            // Prevents adjacent strokes/radicals of the same glyph/word from being split into conflicting categories
            val sfxVotes = if (bitmap != null) {
                cluster.count { classifyOrphanVsSfx(bitmap, it, cluster) == TextCategory.SFX }
            } else 0
            val clusterCategory = if (bitmap != null && sfxVotes.toFloat() / cluster.size.toFloat() >= 0.35f) {
                TextCategory.SFX
            } else {
                TextCategory.ORPHAN
            }

            for (box in cluster) {
                val item = TextLineItem(
                    id = 0,
                    rect = box,
                    angle = 0f,
                    confidence = 1.0f,
                    category = clusterCategory,
                    orientation = TextOrientation.VERTICAL,
                )

                if (clusterCategory == TextCategory.ORPHAN) {
                    orphan.add(item)
                } else {
                    sfx.add(item)
                }
            }
        }

        // 4. Combine and sort in Japanese reading order (RTL: top horizontal bands, right-to-left)
        val allCombined = bubbled + orphan + sfx
        val sortedAll = if (isRtl) {
            allCombined.sortedWith(
                compareBy<TextLineItem> { (it.rect.centerY() / 200) }
                    .thenByDescending { it.rect.centerX() }
            )
        } else {
            allCombined.sortedWith(
                compareBy<TextLineItem> { (it.rect.centerY() / 200) }
                    .thenBy { it.rect.centerX() }
            )
        }

        val reIndexed = sortedAll.mapIndexed { idx, item -> item.copy(id = idx + 1) }

        return CategorizationResult(
            allBoxes = reIndexed,
            bubbledBoxes = reIndexed.filter { it.category == TextCategory.BUBBLED },
            orphanBoxes = reIndexed.filter { it.category == TextCategory.ORPHAN },
            sfxBoxes = reIndexed.filter { it.category == TextCategory.SFX },
        )
    }

    /**
     * Supreme Zero-OCR Classifier (combining stroke caliber/weight and geometric regularity).
     * Accurately categorizes non-bubbled text into typeset Orphan Dialogue/Narration vs. hand-drawn SFX.
     */
    fun classifyOrphanVsSfx(bitmap: Bitmap, rect: Rect, cluster: List<Rect>): TextCategory {
        val cL = rect.left.coerceIn(0, bitmap.width)
        val cT = rect.top.coerceIn(0, bitmap.height)
        val cR = rect.right.coerceIn(0, bitmap.width)
        val cB = rect.bottom.coerceIn(0, bitmap.height)
        val w = cR - cL
        val h = cB - cT
        if (w < 6 || h < 6) return TextCategory.SFX

        val totalPixels = w * h
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, w, cL, cT, w, h)

        var minL = 255
        var maxL = 0
        var sumL = 0L
        val lums = IntArray(totalPixels)

        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (r * 77 + g * 150 + b * 29) shr 8
            lums[i] = y
            if (y < minL) minL = y
            if (y > maxL) maxL = y
            sumL += y
        }

        // 1. Contrast & Stroke Caliber
        val contrast = maxL - minL
        if (contrast < 28) return TextCategory.SFX

        val meanL = (sumL / totalPixels).toInt()
        val isLightBg = meanL >= 120
        val strokeThreshold = if (isLightBg) min(185, maxL - 45) else max(70, minL + 45)

        var darkPixels = 0
        for (y in lums) {
            if (isLightBg) {
                if (y <= strokeThreshold) darkPixels++
            } else {
                if (y >= strokeThreshold) darkPixels++
            }
        }

        val fillRatio = darkPixels.toFloat() / totalPixels.toFloat()

        var edgePixels = 0
        for (row in 0 until h - 1) {
            val rOff = row * w
            for (col in 0 until w - 1) {
                val idx = rOff + col
                val diffX = abs(lums[idx] - lums[idx + 1])
                val diffY = abs(lums[idx] - lums[idx + w])
                if (diffX + diffY > 50) edgePixels++
            }
        }

        val strokeCaliber = (2f * darkPixels) / max(1, edgePixels)

        val strokeScore = when {
            strokeCaliber in 1.2f..4.0f && fillRatio in 0.08f..0.42f -> 1.0f
            strokeCaliber in 1.0f..5.0f && fillRatio in 0.06f..0.50f -> 0.65f
            strokeCaliber > 6.2f || fillRatio > 0.55f || fillRatio < 0.04f -> 0.1f
            else -> 0.4f
        }

        // 2. Geometric Regularity
        var aspectRegularityScore = 0.5f
        if (cluster.isNotEmpty()) {
            val ratios = cluster.map { it.width().toFloat() / max(1, it.height()).toFloat() }
            val avgRatio = ratios.average()
            aspectRegularityScore = if (avgRatio in 0.65..1.40) 1.0f else 0.3f
        }

        // 3. Combined Supreme Regularity Score
        val supremeScore = 0.55f * strokeScore + 0.45f * aspectRegularityScore
        return if (supremeScore >= 0.52f) TextCategory.ORPHAN else TextCategory.SFX
    }

    /**
     * Proximity clustering for adjacent non-bubbled character boxes.
     */
    private fun clusterAdjacentBoxes(boxes: List<Rect>, bitmapWidth: Int, bitmapHeight: Int): List<List<Rect>> {
        if (boxes.isEmpty()) return emptyList()
        val visited = BooleanArray(boxes.size)
        val clusters = mutableListOf<List<Rect>>()

        for (i in boxes.indices) {
            if (visited[i]) continue
            visited[i] = true
            val currentCluster = mutableListOf(boxes[i])
            val queue = ArrayDeque<Int>()
            queue.add(i)

            while (queue.isNotEmpty()) {
                val currIdx = queue.removeFirst()
                val b1 = boxes[currIdx]
                val span = max(b1.width(), b1.height()) * 1.5f

                for (j in boxes.indices) {
                    if (visited[j]) continue
                    val b2 = boxes[j]
                    val dx = max(0, max(b1.left, b2.left) - min(b1.right, b2.right))
                    val dy = max(0, max(b1.top, b2.top) - min(b1.bottom, b2.bottom))

                    if (dx <= span && dy <= span) {
                        visited[j] = true
                        currentCluster.add(b2)
                        queue.add(j)
                    }
                }
            }
            clusters.add(currentCluster)
        }
        return clusters
    }
}
