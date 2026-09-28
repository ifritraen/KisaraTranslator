package com.raen.kisaratranslator.engine.grouping

import android.graphics.Bitmap
import android.graphics.Rect
import com.raen.kisaratranslator.data.logger.AppLogger
import kotlin.math.max
import kotlin.math.min

/**
 * Method 7: Geometric Crunch Grouper.
 *
 * Paradigm shift from Methods 5/6:
 *   - Derives actual bubble SHAPE (not rectangle) via luminance flood-fill on the manga bitmap.
 *   - Detects conjoined ("crunched") bubbles via Distance Transform peak counting.
 *   - Splits crunches via morphological erosion (fast) or watershed (fallback).
 *   - Associates text lines to bubbles via pixel-accurate IoA (not centroid containment).
 *   - Routes unassigned text (narration, SFX, floating dialogue) to UNASSIGNED queue — never discarded.
 *
 * Internalized options:
 *   A — Contour polygon (bubble shape = flood-fill interior, not bounding rect).
 *   B — Flood-fill seed (Option B absorbed into BubbleMaskExtractor).
 *   E — Morphological erosion first pass in CrunchSplitter.
 *   F — IoA-based greedy best-match (replaces centroid containment).
 *   I — Distance-to-centroid Voronoi tiebreaker for ambiguous assignments (0.35–0.70 IoA band).
 */
class Method7CrunchGrouper {

    companion object {
        /** Text is confidently inside a bubble at this IoA or above. */
        private const val IOA_STRONG = 0.70f
        /** Below this IoA across all bubbles → UNASSIGNED (narration, SFX, floating). */
        private const val IOA_ORPHAN = 0.35f
        /** Mask dilation padding (px) for the ambiguous 0.35–0.70 re-check. */
        private const val DILATION_PAD = 8
    }

    data class DialogueUnit(
        val id: Int,
        val bounds: Rect,
        val lines: List<Rect>,
        val isBubble: Boolean,
    )

    /**
     * Main entry point.
     *
     * @param textLines     Vertical line strips from Method5LobeGrouper.extractVerticalLinesDetailed().allLines
     * @param rawBubbles    CTD blk output — lastDetectedBubbles from ComicTextDetector.
     * @param bitmap        Full manga page bitmap.
     * @param bitmapWidth   Page width in pixels.
     * @param bitmapHeight  Page height in pixels.
     * @param isRtl         true for Japanese right-to-left manga.
     * @return Ordered list of DialogueUnit, one per bubble + unassigned orphans.
     */
    var lastCrunchSplits: List<CrunchSplitter.SplitResult> = emptyList()
        private set

    fun groupDialogueUnits(
        textLines: List<Rect>,
        rawBubbles: List<Rect>,
        bitmap: Bitmap,
        bitmapWidth: Int,
        bitmapHeight: Int,
        isRtl: Boolean = true,
    ): List<DialogueUnit> {
        if (textLines.isEmpty()) return emptyList()

        // ── Stage 1: Extract pixel masks for each bubble ──────────────────────
        val rawMasks = BubbleMaskExtractor.extractMasks(bitmap, rawBubbles, bitmapWidth, bitmapHeight)
        AppLogger.info("[M7] Extracted ${rawMasks.size} bubble masks from ${rawBubbles.size} CTD bubbles")

        // ── Stage 2: Crunch detection + splitting ─────────────────────────────
        val refinedMasks = mutableListOf<BubbleMaskExtractor.BubbleMask>()
        val currentSplits = mutableListOf<CrunchSplitter.SplitResult>()
        for (mask in rawMasks) {
            val touchingLines = textLines.filter { line ->
                val ix = max(0, min(mask.rect.right, line.right) - max(mask.rect.left, line.left))
                val iy = max(0, min(mask.rect.bottom, line.bottom) - max(mask.rect.top, line.top))
                ix > 0 && iy > 0
            }
            val splitResult = CrunchSplitter.trySplit(mask, touchingLines)
            refinedMasks.addAll(splitResult.masks)
            if (splitResult.wasSplit) {
                currentSplits.add(splitResult)
                AppLogger.info("[M7] Crunch split: ${mask.rect} → ${splitResult.masks.size} lobes")
            }
        }
        lastCrunchSplits = currentSplits
        AppLogger.info("[M7] After crunch split: ${refinedMasks.size} refined bubble masks (${currentSplits.size} compound bubbles split)")

        // ── Stage 3: IoA text association ─────────────────────────────────────
        val assignedLines    = BooleanArray(textLines.size)
        val bubbleAssignment = IntArray(textLines.size) { -1 }   // index into refinedMasks
        val lineIoa          = FloatArray(textLines.size) { 0f } // best IoA achieved

        // Pre-compute text box areas
        val textAreas = textLines.map { it.width() * it.height() }

        // For each text line, find best matching bubble by IoA
        for (lineIdx in textLines.indices) {
            val line = textLines[lineIdx]
            val textArea = textAreas[lineIdx]
            if (textArea <= 0) { assignedLines[lineIdx] = true; continue }

            var bestIoa    = 0f
            var bestMaskIdx = -1

            for (maskIdx in refinedMasks.indices) {
                val mask = refinedMasks[maskIdx]
                val intersect = BubbleMaskExtractor.countIntersectionPixels(mask, line)
                if (intersect <= 0) continue
                val ioa = intersect.toFloat() / textArea
                if (ioa > bestIoa) { bestIoa = ioa; bestMaskIdx = maskIdx }
            }

            lineIoa[lineIdx]         = bestIoa
            bubbleAssignment[lineIdx] = bestMaskIdx
        }

        // Strong assignment pass (IoA >= IOA_STRONG)
        for (lineIdx in textLines.indices) {
            if (lineIoa[lineIdx] >= IOA_STRONG && bubbleAssignment[lineIdx] >= 0) {
                assignedLines[lineIdx] = true
            }
        }

        // Ambiguous pass (IOA_ORPHAN <= IoA < IOA_STRONG): dilate mask, re-check
        for (lineIdx in textLines.indices) {
            if (assignedLines[lineIdx]) continue
            val ioa = lineIoa[lineIdx]
            val maskIdx = bubbleAssignment[lineIdx]
            if (ioa >= IOA_ORPHAN && maskIdx >= 0) {
                // Dilate: expand the bubble rect by DILATION_PAD and recount
                val mask = refinedMasks[maskIdx]
                val dilatedRect = Rect(
                    max(0, mask.rect.left   - DILATION_PAD),
                    max(0, mask.rect.top    - DILATION_PAD),
                    min(bitmapWidth,  mask.rect.right  + DILATION_PAD),
                    min(bitmapHeight, mask.rect.bottom + DILATION_PAD),
                )
                val dilatedIntersect = rectIntersectArea(dilatedRect, textLines[lineIdx])
                val dilatedIoa = dilatedIntersect.toFloat() / textAreas[lineIdx]
                if (dilatedIoa >= 0.60f) {
                    assignedLines[lineIdx] = true
                    // Keep the original bestMaskIdx assignment
                } else {
                    // Voronoi tiebreaker (Option I): assign to nearest bubble centroid
                    val nearest = nearestBubbleCentroid(textLines[lineIdx], refinedMasks)
                    if (nearest >= 0) {
                        bubbleAssignment[lineIdx] = nearest
                        assignedLines[lineIdx] = true
                        AppLogger.info("[M7] Voronoi tiebreaker: line ${lineIdx} → bubble $nearest")
                    }
                }
            }
        }

        // ── Stage 4: Build DialogueUnits from assigned lines ──────────────────
        val units = mutableListOf<DialogueUnit>()
        var unitId = 1

        // Group lines by their assigned bubble mask
        val maskToLines = HashMap<Int, MutableList<Rect>>(refinedMasks.size)
        for (lineIdx in textLines.indices) {
            if (!assignedLines[lineIdx]) continue
            val mIdx = bubbleAssignment[lineIdx]
            if (mIdx < 0) continue
            maskToLines.getOrPut(mIdx) { mutableListOf() }.add(textLines[lineIdx])
        }

        for ((maskIdx, lines) in maskToLines) {
            if (lines.isEmpty()) continue
            val mask = refinedMasks[maskIdx]

            // RTL sort: right-to-left column order, top-to-bottom within column
            val sortedLines = if (isRtl) lines.sortedByDescending { it.centerX() }
                              else       lines.sortedBy { it.centerX() }

            val minX = lines.minOf { it.left }
            val minY = lines.minOf { it.top }
            val maxX = lines.maxOf { it.right }
            val maxY = lines.maxOf { it.bottom }

            val bounds = Rect(
                max(0,           min(minX, mask.rect.left)),
                max(0,           min(minY, mask.rect.top)),
                min(bitmapWidth, max(maxX, mask.rect.right)),
                min(bitmapHeight,max(maxY, mask.rect.bottom)),
            )

            units.add(DialogueUnit(id = unitId++, bounds = bounds, lines = sortedLines, isBubble = true))
        }

        // ── Stage 5: Unassigned queue (never discarded) ───────────────────────
        for (lineIdx in textLines.indices) {
            if (assignedLines[lineIdx]) continue
            val line = textLines[lineIdx]
            if (line.width() < 8 || line.height() < 8) continue
            units.add(DialogueUnit(id = unitId++, bounds = Rect(line), lines = listOf(line), isBubble = false))
        }

        // ── Stage 6: Page-level manga reading order sort ──────────────────────
        return if (isRtl) {
            units.sortedWith(
                compareBy<DialogueUnit> { it.bounds.centerY() / 200 }
                    .thenByDescending { it.bounds.centerX() }
            )
        } else {
            units.sortedWith(
                compareBy<DialogueUnit> { it.bounds.centerY() / 200 }
                    .thenBy { it.bounds.centerX() }
            )
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Area of intersection between two rects (pure geometry, no mask). */
    private fun rectIntersectArea(a: Rect, b: Rect): Int {
        val iL = max(a.left, b.left); val iT = max(a.top, b.top)
        val iR = min(a.right, b.right); val iB = min(a.bottom, b.bottom)
        return if (iR > iL && iB > iT) (iR - iL) * (iB - iT) else 0
    }

    /** Returns the index of the bubble mask whose centroid is closest to the text line center. */
    private fun nearestBubbleCentroid(line: Rect, masks: List<BubbleMaskExtractor.BubbleMask>): Int {
        val lx = line.centerX(); val ly = line.centerY()
        var bestIdx = -1; var bestDist = Long.MAX_VALUE
        for ((i, m) in masks.withIndex()) {
            val dx = (m.rect.centerX() - lx).toLong()
            val dy = (m.rect.centerY() - ly).toLong()
            val dist = dx * dx + dy * dy
            if (dist < bestDist) { bestDist = dist; bestIdx = i }
        }
        return bestIdx
    }
}
