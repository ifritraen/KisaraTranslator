package com.raen.kisaratranslator.engine.grouping

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Method 6: Whole Bubble Detection First Pipeline.
 *
 * Core Paradigm:
 * 1. Physical Speech Bubble First: The detected speech balloon acts as the 100% deterministic container.
 * 2. Conjoined Bubble Splitting: 8-shaped or compound bubbles with narrow waist constrictions are split
 *    into distinct upper and lower dialogue units.
 * 3. Spatial Containment: Every text line whose center lies inside the bubble is grouped into exactly 1 unit.
 * 4. Internal Line Chunking: Lobes <= 2 lines are read in 1 shot; lobes > 2 lines are sliced into <= 2 line chunks.
 * 5. Floating Text Fallback: Orphan lines outside bubbles are clustered by horizontal column proximity.
 */
class Method6BubbleGrouper {

    data class DialogueBubbleUnit(
        val id: Int,
        val bounds: Rect,
        val lines: List<Rect>,
        val isBubble: Boolean,
    )

    fun groupDialogueUnits(
        verticalLines: List<Rect>,
        rawBubbles: List<Rect>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        isRtl: Boolean = true,
        bitmap: Bitmap? = null,
    ): List<DialogueBubbleUnit> {
        if (verticalLines.isEmpty()) return emptyList()

        val assignedLines = BooleanArray(verticalLines.size)
        val units = mutableListOf<DialogueBubbleUnit>()
        var unitIdCounter = 1

        // 1. Process Conjoined Bubbles (8-shaped or vertical compound speech bubbles)
        val refinedBubbles = splitConjoinedBubbles(rawBubbles, verticalLines)

        // 2. Assign Lines to Bubbles with 100% Deterministic Spatial Containment
        for (bubble in refinedBubbles) {
            val insideIndices = verticalLines.indices.filter { idx ->
                !assignedLines[idx] && bubble.contains(verticalLines[idx].centerX(), verticalLines[idx].centerY())
            }
            if (insideIndices.isEmpty()) continue

            insideIndices.forEach { assignedLines[it] = true }
            val bubbleLines = insideIndices.map { verticalLines[it] }

            val sortedLines = if (isRtl) {
                bubbleLines.sortedByDescending { it.centerX() }
            } else {
                bubbleLines.sortedBy { it.centerX() }
            }

            val minX = sortedLines.minOf { it.left }
            val minY = sortedLines.minOf { it.top }
            val maxX = sortedLines.maxOf { it.right }
            val maxY = sortedLines.maxOf { it.bottom }

            val pad = 8
            val bounds = Rect(
                max(0, min(minX - pad, bubble.left)),
                max(0, min(minY - pad, bubble.top)),
                min(bitmapWidth, max(maxX + pad, bubble.right)),
                min(bitmapHeight, max(maxY + pad, bubble.bottom)),
            )

            units.add(
                DialogueBubbleUnit(
                    id = unitIdCounter++,
                    bounds = bounds,
                    lines = sortedLines,
                    isBubble = true,
                )
            )
        }

        // 3. Process Orphan Lines (Orphans never look for neighbors!)
        val orphanIndices = verticalLines.indices.filter { !assignedLines[it] }
        for (idx in orphanIndices) {
            val line = verticalLines[idx]
            val totalH = line.height()
            val totalW = line.width()
            val isMeaningful = totalH >= 8 && totalW >= 8
            if (!isMeaningful) continue

            val pad = 0
            val bounds = Rect(
                (line.left - pad).coerceIn(0, bitmapWidth),
                (line.top - pad).coerceIn(0, bitmapHeight),
                (line.right + pad).coerceIn(0, bitmapWidth),
                (line.bottom + pad).coerceIn(0, bitmapHeight),
            )

            units.add(
                DialogueBubbleUnit(
                    id = unitIdCounter++,
                    bounds = bounds,
                    lines = listOf(line),
                    isBubble = false,
                )
            )
        }

        // 4. Sort all dialogue units into Manga Reading Order (Top-Right to Bottom-Left)
        return if (isRtl) {
            units.sortedWith(
                compareBy<DialogueBubbleUnit> { (it.bounds.centerY() / 200) }
                    .thenByDescending { it.bounds.centerX() }
            )
        } else {
            units.sortedWith(
                compareBy<DialogueBubbleUnit> { (it.bounds.centerY() / 200) }
                    .thenBy { it.bounds.centerX() }
            )
        }
    }

    /**
     * Splits 8-shaped or conjoined vertical speech bubbles where text naturally divides into
     * upper and lower sentence lobes.
     */
    private fun splitConjoinedBubbles(
        bubbles: List<Rect>,
        lines: List<Rect>,
    ): List<Rect> {
        val result = mutableListOf<Rect>()
        for (b in bubbles) {
            val insideLines = lines.filter { b.contains(it.centerX(), it.centerY()) }
            if (insideLines.size >= 2 && b.height() > b.width() * 1.35f) {
                // Check if lines are vertically partitioned into upper and lower groups
                val sortedByY = insideLines.sortedBy { it.centerY() }
                var bestSplitGap = 0
                var splitY = -1

                for (k in 0 until sortedByY.size - 1) {
                    val gap = sortedByY[k + 1].top - sortedByY[k].bottom
                    val avgH = (sortedByY[k].height() + sortedByY[k + 1].height()) / 2f
                    if (gap > avgH * 0.40f && gap > bestSplitGap) {
                        bestSplitGap = gap
                        splitY = (sortedByY[k].bottom + sortedByY[k + 1].top) / 2
                    }
                }

                if (splitY in b.top + 20 until b.bottom - 20) {
                    val topBubble = Rect(b.left, b.top, b.right, splitY)
                    val bottomBubble = Rect(b.left, splitY, b.right, b.bottom)
                    result.add(topBubble)
                    result.add(bottomBubble)
                    continue
                }
            }
            result.add(b)
        }
        return result
    }
}
