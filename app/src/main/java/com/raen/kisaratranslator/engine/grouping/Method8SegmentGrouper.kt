package com.raen.kisaratranslator.engine.grouping

import android.graphics.Bitmap
import android.graphics.Rect
import com.raen.kisaratranslator.data.logger.AppLogger
import kotlin.math.max
import kotlin.math.min

/**
 * Method 8: YOLO11n-seg Pretrained Bubble Segmentation Grouper.
 *
 * Identical association + split pipeline as Method7CrunchGrouper, but the
 * bubble masks come from BubbleSegmentationEngine (YOLO pixel-accurate masks)
 * instead of BubbleMaskExtractor (luminance flood-fill).
 *
 * Fallback: if [segEngine] is not ready, falls back to Method 7's luminance
 * flood-fill so the pipeline always produces output.
 *
 * Internalized options:
 *   C — GrabCut post-refinement hook (stubbed, can be added per-mask below).
 *   H — Fine-tune YOLO: offline workflow only, output is a better .onnx fed here.
 */
class Method8SegmentGrouper {

    companion object {
        private const val IOA_STRONG   = 0.70f
        private const val IOA_ORPHAN   = 0.35f
        private const val DILATION_PAD = 8
    }

    data class DialogueUnit(
        val id: Int,
        var bounds: Rect,
        val lines: List<Rect>,
        val isBubble: Boolean,
    )

    /**
     * @param textLines   Vertical line strips from Method5LobeGrouper.
     * @param segEngine   The loaded BubbleSegmentationEngine (may be not ready).
     * @param rawBubbles  CTD blk bubble rects — used as fallback if segEngine not ready.
     * @param bitmap      Full manga page bitmap.
     */
    var lastCrunchSplits: List<CrunchSplitter.SplitResult> = emptyList()
        private set

    enum class CrunchMode {
        WATERSHED,
        LASER_HYBRID,
        PURE_BORDER_ANGLE,
    }

    /**
     * Groups vertical text lines into coherent dialogue units using YOLO-seg instance masks
     * and CrunchSplitter contour division.
     */
    fun groupDialogueUnits(
        textLines: List<Rect>,
        segEngine: BubbleSegmentationEngine,
        rawBubbles: List<Rect>,
        bitmap: Bitmap,
        bitmapWidth: Int,
        bitmapHeight: Int,
        isRtl: Boolean = true,
        useLaserCut: Boolean = true,
        crunchMode: CrunchMode = if (useLaserCut) CrunchMode.LASER_HYBRID else CrunchMode.WATERSHED,
    ): List<DialogueUnit> {
        if (textLines.isEmpty()) return emptyList()

        // ── Stage 1: Get pixel masks ──────────────────────────────────────────
        val rawMasks: List<BubbleMaskExtractor.BubbleMask> = if (segEngine.isReady) {
            val segs = segEngine.detectMasks(bitmap)
            AppLogger.info("[M8] Using YOLO-seg masks: ${segs.size}")
            segs.ifEmpty {
                // YOLO found nothing — fall back to luminance flood-fill
                AppLogger.info("[M8] YOLO returned 0 masks — falling back to luminance flood-fill")
                BubbleMaskExtractor.extractMasks(bitmap, rawBubbles, bitmapWidth, bitmapHeight)
            }
        } else {
            AppLogger.info("[M8] SegEngine not ready — falling back to Method 7 flood-fill masks")
            BubbleMaskExtractor.extractMasks(bitmap, rawBubbles, bitmapWidth, bitmapHeight)
        }

        // ── Stage 2: Crunch split ─────────────────────────────────────────────
        val refinedMasks = mutableListOf<BubbleMaskExtractor.BubbleMask>()
        val currentSplits = mutableListOf<CrunchSplitter.SplitResult>()
        for (mask in rawMasks) {
            val touchingLines = textLines.filter { line ->
                val ix = max(0, min(mask.rect.right, line.right) - max(mask.rect.left, line.left))
                val iy = max(0, min(mask.rect.bottom, line.bottom) - max(mask.rect.top, line.top))
                ix > 0 && iy > 0
            }
            val split = when (crunchMode) {
                CrunchMode.PURE_BORDER_ANGLE -> CrunchSplitter.trySplitBorderAngleOnly(mask, touchingLines)
                CrunchMode.LASER_HYBRID -> CrunchSplitter.trySplitLaser(mask, touchingLines)
                CrunchMode.WATERSHED -> CrunchSplitter.trySplit(mask, touchingLines)
            }
            refinedMasks.addAll(split.masks)
            if (split.wasSplit) {
                currentSplits.add(split)
                AppLogger.info("[M8] Crunch split ($crunchMode): ${mask.rect} → ${split.masks.size} lobes")
            }
        }
        lastCrunchSplits = currentSplits
        AppLogger.info("[M8] After crunch split ($crunchMode): ${refinedMasks.size} refined masks (${currentSplits.size} compound bubbles split)")

        // ── Stage 3: IoA text association ─────────────────────────────────────
        val assigned   = BooleanArray(textLines.size)
        val assignment = IntArray(textLines.size) { -1 }
        val bestIoa    = FloatArray(textLines.size)
        val textAreas  = textLines.map { it.width() * it.height() }

        for (lineIdx in textLines.indices) {
            val line = textLines[lineIdx]
            val area = textAreas[lineIdx]
            if (area <= 0) { assigned[lineIdx] = true; continue }

            var topIoa = 0f; var topMask = -1
            for (maskIdx in refinedMasks.indices) {
                val inter = BubbleMaskExtractor.countIntersectionPixels(refinedMasks[maskIdx], line)
                if (inter <= 0) continue
                val ioa = inter.toFloat() / area
                if (ioa > topIoa) { topIoa = ioa; topMask = maskIdx }
            }
            bestIoa[lineIdx] = topIoa
            assignment[lineIdx] = topMask
        }

        // Strong pass
        for (i in textLines.indices) {
            if (bestIoa[i] >= IOA_STRONG && assignment[i] >= 0) assigned[i] = true
        }

        // Ambiguous pass + Voronoi tiebreaker
        for (i in textLines.indices) {
            if (assigned[i]) continue
            val ioa = bestIoa[i]; val mIdx = assignment[i]
            if (ioa >= IOA_ORPHAN && mIdx >= 0) {
                val m = refinedMasks[mIdx]
                val dil = Rect(
                    max(0, m.rect.left - DILATION_PAD), max(0, m.rect.top - DILATION_PAD),
                    min(bitmapWidth, m.rect.right + DILATION_PAD), min(bitmapHeight, m.rect.bottom + DILATION_PAD),
                )
                val dilInter = rectIntersect(dil, textLines[i])
                if (dilInter.toFloat() / textAreas[i] >= 0.60f) {
                    assigned[i] = true
                } else {
                    val nearest = nearestCentroid(textLines[i], refinedMasks)
                    if (nearest >= 0) { assignment[i] = nearest; assigned[i] = true }
                }
            }
        }

        // ── Stage 4: Build DialogueUnits ──────────────────────────────────────
        var uid = 1

        val maskToLines = HashMap<Int, MutableList<Rect>>(refinedMasks.size)
        for (i in textLines.indices) {
            if (!assigned[i] || assignment[i] < 0) continue
            maskToLines.getOrPut(assignment[i]) { mutableListOf() }.add(textLines[i])
        }

        val rawBubbleUnits = mutableListOf<DialogueUnit>()
        val maskIdxToUnit = HashMap<Int, DialogueUnit>()

        for ((mIdx, lines) in maskToLines) {
            if (lines.isEmpty()) continue
            val mask = refinedMasks[mIdx]
            val sortedLines = if (isRtl) lines.sortedByDescending { it.centerX() }
                              else       lines.sortedBy { it.centerX() }

            val textL = lines.minOf { it.left }
            val textT = lines.minOf { it.top }
            val textR = lines.maxOf { it.right }
            val textB = lines.maxOf { it.bottom }

            // Idea 1: Text-Centric Bounds with Typography Padding (never bleeds into scenery)
            val pad = 12
            val bL = max(0, max(mask.rect.left, textL - pad))
            val bT = max(0, max(mask.rect.top, textT - pad))
            val bR = min(bitmapWidth, min(mask.rect.right, textR + pad))
            val bB = min(bitmapHeight, min(mask.rect.bottom, textB + pad))

            val bounds = Rect(
                min(bL, textL),
                min(bT, textT),
                max(bR, textR),
                max(bB, textB),
            )
            val unit = DialogueUnit(uid++, bounds, sortedLines, isBubble = true)
            rawBubbleUnits.add(unit)
            maskIdxToUnit[mIdx] = unit
        }

        // Half-Plane Seam Guard for Conjoined Split Lobes (applies to both v1 Watershed and v2 Laser Cut)
        for (split in currentSplits) {
            if (split.masks.size == 2) {
                val idx0 = refinedMasks.indexOf(split.masks[0])
                val idx1 = refinedMasks.indexOf(split.masks[1])
                val u0 = if (idx0 >= 0) maskIdxToUnit[idx0] else null
                val u1 = if (idx1 >= 0) maskIdxToUnit[idx1] else null
                if (u0 != null && u1 != null) {
                    val r0 = u0.bounds
                    val r1 = u1.bounds
                    val interL = max(r0.left, r1.left); val interT = max(r0.top, r1.top)
                    val interR = min(r0.right, r1.right); val interB = min(r0.bottom, r1.bottom)
                    if (interR > interL && interB > interT) {
                        val interW = interR - interL
                        val interH = interB - interT

                        if (interH >= interW) {
                            // Side-by-side lobes -> separate along vertical X seam
                            val (leftU, rightU) = if (r0.centerX() <= r1.centerX()) Pair(u0, u1) else Pair(u1, u0)
                            val leftMaxX = leftU.lines.maxOfOrNull { it.right } ?: leftU.bounds.left
                            val rightMinX = rightU.lines.minOfOrNull { it.left } ?: rightU.bounds.right
                            val seamX = if (rightMinX > leftMaxX) {
                                ((leftMaxX + rightMinX) / 2).coerceIn(interL, interR)
                            } else {
                                (interL + interR) / 2
                            }
                            leftU.bounds = Rect(leftU.bounds.left, leftU.bounds.top, seamX, leftU.bounds.bottom)
                            rightU.bounds = Rect(seamX, rightU.bounds.top, rightU.bounds.right, rightU.bounds.bottom)
                        } else {
                            // Vertically stacked lobes -> separate along horizontal Y seam
                            val (topU, bottomU) = if (r0.centerY() <= r1.centerY()) Pair(u0, u1) else Pair(u1, u0)
                            val topMaxY = topU.lines.maxOfOrNull { it.bottom } ?: topU.bounds.top
                            val bottomMinY = bottomU.lines.minOfOrNull { it.top } ?: bottomU.bounds.bottom
                            val seamY = if (bottomMinY > topMaxY) {
                                ((topMaxY + bottomMinY) / 2).coerceIn(interT, interB)
                            } else {
                                (interT + interB) / 2
                            }
                            topU.bounds = Rect(topU.bounds.left, topU.bounds.top, topU.bounds.right, seamY)
                            bottomU.bounds = Rect(bottomU.bounds.left, seamY, bottomU.bounds.right, bottomU.bounds.bottom)
                        }
                    }
                }
            }
        }

        val allUnits = rawBubbleUnits.toMutableList()

        // Orphan queue
        for (i in textLines.indices) {
            if (assigned[i]) continue
            val l = textLines[i]
            if (l.width() < 8 || l.height() < 8) continue
            allUnits.add(DialogueUnit(uid++, Rect(l), listOf(l), isBubble = false))
        }

        // Enforce mathematical ZERO-OVERLAP across ALL dialogue units on the page
        val disjointUnits = resolveAllOverlaps(allUnits, bitmapWidth, bitmapHeight)

        // ── Stage 5: Reading order sort ───────────────────────────────────────
        return if (isRtl)
            disjointUnits.sortedWith(compareBy<DialogueUnit> { it.bounds.centerY() / 200 }.thenByDescending { it.bounds.centerX() })
        else
            disjointUnits.sortedWith(compareBy<DialogueUnit> { it.bounds.centerY() / 200 }.thenBy { it.bounds.centerX() })
    }

    /**
     * Seam-aware zero-overlap resolution across all dialogue units.
     * Guarantees 0-pixel bounding box overlap while protecting all interior text lines.
     */
    private fun resolveAllOverlaps(
        rawUnits: List<DialogueUnit>,
        maxWidth: Int,
        maxHeight: Int,
    ): List<DialogueUnit> {
        if (rawUnits.size <= 1) return rawUnits
        val current = rawUnits.toMutableList()

        // Pass 1: Union-merge heavy overlaps or near-total containment (IoU > 0.45 or containment > 0.85)
        var merged = true
        var pass = 0
        while (merged && pass < 15) {
            merged = false
            pass++
            for (i in 0 until current.size) {
                for (j in i + 1 until current.size) {
                    val u1 = current[i]; val u2 = current[j]
                    val r1 = u1.bounds; val r2 = u2.bounds
                    val interL = max(r1.left, r2.left); val interT = max(r1.top, r2.top)
                    val interR = min(r1.right, r2.right); val interB = min(r1.bottom, r2.bottom)
                    if (interR > interL && interB > interT) {
                        val interArea = (interR - interL) * (interB - interT)
                        val a1 = r1.width() * r1.height()
                        val a2 = r2.width() * r2.height()
                        val minArea = min(a1, a2)
                        val unionArea = a1 + a2 - interArea
                        val iou = interArea.toFloat() / max(1, unionArea).toFloat()
                        val containment = interArea.toFloat() / max(1, minArea).toFloat()

                        if (iou > 0.45f || containment > 0.85f) {
                            val unionBounds = Rect(
                                min(r1.left, r2.left), min(r1.top, r2.top),
                                max(r1.right, r2.right), max(r1.bottom, r2.bottom),
                            )
                            val unionLines = (u1.lines + u2.lines).distinct()
                            current[i] = u1.copy(bounds = unionBounds, lines = unionLines)
                            current.removeAt(j)
                            merged = true
                            break
                        }
                    }
                }
                if (merged) break
            }
        }

        // Pass 2: Edge-trim touching/overlapping boundaries (Zero-Overlap Guarantee)
        var adjusted = true
        var adjPass = 0
        while (adjusted && adjPass < 15) {
            adjusted = false
            adjPass++
            for (i in 0 until current.size) {
                for (j in i + 1 until current.size) {
                    val u1 = current[i]; val u2 = current[j]
                    val r1 = u1.bounds; val r2 = u2.bounds
                    val interL = max(r1.left, r2.left); val interT = max(r1.top, r2.top)
                    val interR = min(r1.right, r2.right); val interB = min(r1.bottom, r2.bottom)
                    if (interR > interL && interB > interT) {
                        val interW = interR - interL
                        val interH = interB - interT

                        val isVerticalSeam = interH >= interW
                        if (isVerticalSeam) {
                            val (leftU, rightU, leftIdx, rightIdx) = if (r1.centerX() <= r2.centerX()) {
                                listOf(u1, u2, i, j)
                            } else {
                                listOf(u2, u1, j, i)
                            }
                            val leftUnit = leftU as DialogueUnit
                            val rightUnit = rightU as DialogueUnit

                            val leftMaxX = leftUnit.lines.maxOfOrNull { it.right } ?: leftUnit.bounds.left
                            val rightMinX = rightUnit.lines.minOfOrNull { it.left } ?: rightUnit.bounds.right

                            val midX = if (rightMinX > leftMaxX) {
                                ((leftMaxX + rightMinX) / 2).coerceIn(interL, interR)
                            } else {
                                (interL + interR) / 2
                            }

                            current[leftIdx as Int] = leftUnit.copy(
                                bounds = Rect(leftUnit.bounds.left, leftUnit.bounds.top, midX, leftUnit.bounds.bottom)
                            )
                            current[rightIdx as Int] = rightUnit.copy(
                                bounds = Rect(midX, rightUnit.bounds.top, rightUnit.bounds.right, rightUnit.bounds.bottom)
                            )
                            adjusted = true
                        } else {
                            val (topU, bottomU, topIdx, bottomIdx) = if (r1.centerY() <= r2.centerY()) {
                                listOf(u1, u2, i, j)
                            } else {
                                listOf(u2, u1, j, i)
                            }
                            val topUnit = topU as DialogueUnit
                            val bottomUnit = bottomU as DialogueUnit

                            val topMaxY = topUnit.lines.maxOfOrNull { it.bottom } ?: topUnit.bounds.top
                            val bottomMinY = bottomUnit.lines.minOfOrNull { it.top } ?: bottomUnit.bounds.bottom

                            val midY = if (bottomMinY > topMaxY) {
                                ((topMaxY + bottomMinY) / 2).coerceIn(interT, interB)
                            } else {
                                (interT + interB) / 2
                            }

                            current[topIdx as Int] = topUnit.copy(
                                bounds = Rect(topUnit.bounds.left, topUnit.bounds.top, topUnit.bounds.right, midY)
                            )
                            current[bottomIdx as Int] = bottomUnit.copy(
                                bounds = Rect(bottomUnit.bounds.left, midY, bottomUnit.bounds.right, bottomUnit.bounds.bottom)
                            )
                            adjusted = true
                        }
                    }
                }
            }
        }

        return current
    }

    private fun rectIntersect(a: Rect, b: Rect): Int {
        val iL = max(a.left, b.left); val iT = max(a.top, b.top)
        val iR = min(a.right, b.right); val iB = min(a.bottom, b.bottom)
        return if (iR > iL && iB > iT) (iR - iL) * (iB - iT) else 0
    }

    private fun nearestCentroid(line: Rect, masks: List<BubbleMaskExtractor.BubbleMask>): Int {
        val lx = line.centerX(); val ly = line.centerY()
        var best = -1; var bestD = Long.MAX_VALUE
        for ((i, m) in masks.withIndex()) {
            val dx = (m.rect.centerX() - lx).toLong(); val dy = (m.rect.centerY() - ly).toLong()
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
