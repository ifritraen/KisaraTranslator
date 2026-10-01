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
        ctdBubbles: List<Rect> = emptyList(),
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
        val distinctBubbles = mutableListOf<Rect>()
        if (validMasks.isNotEmpty()) {
            for (bm in validMasks) {
                if (bm.isLobe) {
                    distinctBubbles.add(Rect(bm.rect))
                    continue
                }
                var dup = false
                for (existing in distinctBubbles) {
                    val il = max(existing.left, bm.rect.left)
                    val it = max(existing.top, bm.rect.top)
                    val ir = min(existing.right, bm.rect.right)
                    val ib = min(existing.bottom, bm.rect.bottom)
                    if (ir > il && ib > it) {
                        val ia = (ir - il).toLong() * (ib - it).toLong()
                        val unionA = existing.width().toLong() * existing.height().toLong() +
                                     bm.rect.width().toLong() * bm.rect.height().toLong() - ia
                        if (unionA > 0 && ia.toFloat() / unionA.toFloat() > 0.70f) {
                            dup = true
                            break
                        }
                    }
                }
                if (!dup) {
                    distinctBubbles.add(Rect(bm.rect))
                }
            }
        } else {
            for (b in validBubbleRegions) {
                // Verify candidate region has physical speech bubble properties (high background whiteness)
                if (bitmap != null && !isCandidateRealBubble(bitmap, b)) continue

                val existing = distinctBubbles.firstOrNull { other ->
                    val interL = max(other.left, b.left)
                    val interT = max(other.top, b.top)
                    val interR = min(other.right, b.right)
                    val interB = min(other.bottom, b.bottom)
                    if (interR > interL && interB > interT) {
                        val interArea = (interR - interL).toLong() * (interB - interT).toLong()
                        val minArea = min(other.width().toLong() * other.height().toLong(), b.width().toLong() * b.height().toLong())
                        minArea > 0 && interArea.toFloat() / minArea.toFloat() > 0.65f
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

                    val matchingMask = findMatchingMask(bubble, validMasks)
                    val isMatch = if (matchingMask != null) {
                        val mil = max(matchingMask.rect.left, box.left)
                        val mit = max(matchingMask.rect.top, box.top)
                        val mir = min(matchingMask.rect.right, box.right)
                        val mib = min(matchingMask.rect.bottom, box.bottom)
                        var maskPixels = 0
                        if (mir > mil && mib > mit) {
                            for (my in mit until mib) {
                                val rowOff = (my - matchingMask.rect.top) * matchingMask.width
                                val startX = mil - matchingMask.rect.left
                                val endX = mir - matchingMask.rect.left
                                for (mx in startX until endX) {
                                    if (matchingMask.mask[rowOff + mx]) maskPixels++
                                }
                            }
                        }
                        val maskRatio = if (boxArea > 0) maskPixels.toFloat() / boxArea.toFloat() else 0f
                        val lx = cx - matchingMask.rect.left
                        val ly = cy - matchingMask.rect.top
                        val inMask = if (lx in 0 until matchingMask.width && ly in 0 until matchingMask.height) {
                            matchingMask.mask[ly * matchingMask.width + lx]
                        } else false
                        inMask && inEnvelope && maskRatio >= 0.45f
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

        // Cluster adjacent non-bubbled boxes into local typography proximity groups
        val nonBubbledClusters = clusterAdjacentBoxes(rawNonBubbledBoxes, bitmapWidth, bitmapHeight)

        val validCtd = ctdBubbles.filter { it.width() >= 14 && it.height() >= 14 }

        for (cluster in nonBubbledClusters) {
            val clusterCategory = if (validCtd.isNotEmpty()) {
                // User Law: Declare ORPHAN to those detected by CTD as bubble/block, but failed Manga109 test.
                // Others are simply SFX.
                val matchesCtd = cluster.any { box ->
                    val cx = box.centerX()
                    val cy = box.centerY()
                    val bArea = box.width().toLong() * box.height().toLong()
                    validCtd.any { ctd ->
                        val il = max(box.left, ctd.left)
                        val it = max(box.top, ctd.top)
                        val ir = min(box.right, ctd.right)
                        val ib = min(box.bottom, ctd.bottom)
                        val interArea = if (ir > il && ib > it) (ir - il).toLong() * (ib - it).toLong() else 0L
                        val ratio = if (bArea > 0) interArea.toFloat() / bArea.toFloat() else 0f
                        val inEnv = cx in (ctd.left - 6)..(ctd.right + 6) && cy in (ctd.top - 6)..(ctd.bottom + 6)
                        (ctd.contains(cx, cy) && ratio >= 0.35f) || (inEnv && ratio >= 0.40f) || ratio >= 0.55f
                    }
                }
                if (matchesCtd) TextCategory.ORPHAN else TextCategory.SFX
            } else {
                // Fallback: Stroke caliber and aspect regularity scoring when CTD bubbles unavailable
                val sfxVotes = if (bitmap != null) {
                    cluster.count { classifyOrphanVsSfx(bitmap, it, cluster) == TextCategory.SFX }
                } else 0
                if (bitmap != null && sfxVotes.toFloat() / cluster.size.toFloat() >= 0.65f) {
                    TextCategory.SFX
                } else {
                    TextCategory.ORPHAN
                }
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
            strokeCaliber in 1.0f..4.8f && fillRatio in 0.05f..0.75f -> 1.0f
            strokeCaliber in 0.8f..5.8f && fillRatio in 0.04f..0.85f -> 0.70f
            strokeCaliber > 8.0f || fillRatio < 0.02f -> 0.1f
            else -> 0.45f
        }

        // 2. Geometric Regularity
        // For Japanese text, single glyphs have aspect ~ 1.0, and vertical line strips have aspect <= 0.55
        var aspectRegularityScore = 0.6f
        if (cluster.isNotEmpty()) {
            val ratios = cluster.map { it.width().toFloat() / max(1, it.height()).toFloat() }
            val avgRatio = ratios.average()
            aspectRegularityScore = when {
                avgRatio in 0.10..0.55 -> 1.0f // Vertical column / text line
                avgRatio in 0.65..1.40 -> 1.0f // Standard character aspect
                else -> 0.45f
            }
        }

        // 3. Combined Supreme Regularity Score
        val supremeScore = 0.55f * strokeScore + 0.45f * aspectRegularityScore
        return if (supremeScore >= 0.50f) TextCategory.ORPHAN else TextCategory.SFX
    }

    /**
     * Verifies whether an unsegmented candidate box has genuine speech bubble physical properties
     * (predominantly bright/white interior background >= 60%).
     */
    fun isCandidateRealBubble(bitmap: Bitmap, rect: Rect): Boolean {
        val cL = rect.left.coerceIn(0, bitmap.width)
        val cT = rect.top.coerceIn(0, bitmap.height)
        val cR = rect.right.coerceIn(0, bitmap.width)
        val cB = rect.bottom.coerceIn(0, bitmap.height)
        val w = cR - cL
        val h = cB - cT
        if (w < 18 || h < 22) return false
        val total = w * h
        val pixels = IntArray(total)
        bitmap.getPixels(pixels, 0, w, cL, cT, w, h)
        var whiteCount = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            if (lum >= 195) whiteCount++
        }
        val whiteRatio = whiteCount.toFloat() / total.toFloat()
        return whiteRatio >= 0.60f
    }

    /**
     * Typography-aware proximity clustering for adjacent non-bubbled character boxes.
     * Clusters boxes into localized columnar dialogue groups without cross-panel domino chaining.
     */
    private fun clusterAdjacentBoxes(boxes: List<Rect>, bitmapWidth: Int, bitmapHeight: Int): List<List<Rect>> {
        if (boxes.isEmpty()) return emptyList()
        val visited = BooleanArray(boxes.size)
        val clusters = mutableListOf<List<Rect>>()

        val charWidths = boxes.map { min(it.width(), it.height()) }.sorted()
        val medianW = charWidths[charWidths.size / 2].toFloat().coerceIn(16f, 50f)

        for (i in boxes.indices) {
            if (visited[i]) continue
            visited[i] = true
            val currentCluster = mutableListOf(boxes[i])
            val queue = ArrayDeque<Int>()
            queue.add(i)

            while (queue.isNotEmpty()) {
                val currIdx = queue.removeFirst()
                val b1 = boxes[currIdx]

                for (j in boxes.indices) {
                    if (visited[j]) continue
                    val b2 = boxes[j]
                    val dx = max(0, max(b1.left, b2.left) - min(b1.right, b2.right))
                    val dy = max(0, max(b1.top, b2.top) - min(b1.bottom, b2.bottom))
                    val cxDist = abs(b1.centerX() - b2.centerX())
                    val vOverlap = max(0, min(b1.bottom, b2.bottom) - max(b1.top, b2.top))
                    val minH = min(b1.height(), b2.height())

                    // Japanese typography proximity:
                    // 1. Column alignment (vertical text line corridor)
                    val isCol = (cxDist <= 1.25f * medianW) && (dy <= 2.8f * medianW)
                    // 2. Parallel columns side-by-side
                    val isParallel = (dx <= 2.2f * medianW) && (vOverlap >= 0.15f * minH || dy <= 1.0f * medianW)

                    if (isCol || isParallel) {
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

    fun findMatchingMask(bubble: Rect, masks: List<BubbleMask>): BubbleMask? {
        if (masks.isEmpty()) return null
        masks.firstOrNull { it.rect == bubble }?.let { return it }
        var bestMask: BubbleMask? = null
        var maxInter = 0L
        for (bm in masks) {
            val il = max(bm.rect.left, bubble.left)
            val it = max(bm.rect.top, bubble.top)
            val ir = min(bm.rect.right, bubble.right)
            val ib = min(bm.rect.bottom, bubble.bottom)
            if (ir > il && ib > it) {
                val inter = (ir - il).toLong() * (ib - it).toLong()
                if (inter > maxInter) {
                    maxInter = inter
                    bestMask = bm
                }
            }
        }
        return bestMask
    }
}
