package com.raen.kisaratranslator.engine.grouping

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Derives a per-pixel binary mask for each speech bubble bounding box by:
 * 1. Cropping the bubble region from the manga bitmap.
 * 2. Luminance-thresholding to find the white interior (luma >= threshold).
 * 3. Flood-filling from the crop center to isolate the connected balloon interior.
 *
 * This gives a real bubble shape (oval, rectangular, irregular) instead of a rectangle,
 * enabling accurate IoA-based text association in Method7CrunchGrouper.
 */
object BubbleMaskExtractor {

    /**
     * Result of mask extraction for one bubble.
     *
     * @param mask     Boolean array [height × width] — true = inside balloon interior.
     * @param rect     The bubble bounding box in original image coordinates.
     * @param width    Width of the mask (= rect.width()).
     * @param height   Height of the mask (= rect.height()).
     * @param fillArea Number of true pixels in the mask (interior pixel count).
     */
    data class BubbleMask(
        val mask: BooleanArray,
        val rect: Rect,
        val width: Int,
        val height: Int,
        val fillArea: Int,
    )

    /**
     * Luma threshold above which a pixel is considered "white interior" of a bubble.
     * Manga balloon interiors are usually luma >= 200. Set conservatively to 180
     * to handle slightly off-white or slightly shaded balloon areas.
     */
    private const val LUMA_THRESHOLD = 180

    /**
     * Extracts a binary interior mask for all given bubble rects.
     *
     * @param bitmap    Full manga page bitmap.
     * @param bubbles   List of bubble bounding boxes from CTD blk output.
     * @param imgWidth  Full image width (for coercion).
     * @param imgHeight Full image height (for coercion).
     * @return List of BubbleMask, one per input bubble (same order, no skips).
     */
    fun extractMasks(
        bitmap: Bitmap,
        bubbles: List<Rect>,
        imgWidth: Int,
        imgHeight: Int,
    ): List<BubbleMask> {
        val result = mutableListOf<BubbleMask>()
        val bitmapPixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(bitmapPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (bubble in bubbles) {
            val left   = bubble.left.coerceIn(0, imgWidth - 1)
            val top    = bubble.top.coerceIn(0, imgHeight - 1)
            val right  = bubble.right.coerceIn(left + 1, imgWidth)
            val bottom = bubble.bottom.coerceIn(top + 1, imgHeight)
            val w = right - left
            val h = bottom - top
            if (w < 4 || h < 4) {
                // Too small — produce an all-true mask (full rect treated as interior)
                result.add(BubbleMask(BooleanArray(w * h) { true }, Rect(left, top, right, bottom), w, h, w * h))
                continue
            }

            // 1. Build luminance mask from full bitmap pixels (no sub-bitmap allocation)
            val lumaMask = BooleanArray(w * h)
            for (dy in 0 until h) {
                val srcY = top + dy
                for (dx in 0 until w) {
                    val srcX = left + dx
                    val pixel = bitmapPixels[srcY * bitmap.width + srcX]
                    val r = (pixel ushr 16) and 0xFF
                    val g = (pixel ushr 8)  and 0xFF
                    val b =  pixel          and 0xFF
                    val luma = (r * 299 + g * 587 + b * 114) / 1000
                    lumaMask[dy * w + dx] = luma >= LUMA_THRESHOLD
                }
            }

            // 2. Flood-fill from the center of the crop to get the connected interior.
            //    Only pixels that are (a) above luma threshold AND (b) connected to center are kept.
            val startX = w / 2
            val startY = h / 2

            // If center is not white (dark artwork center), scan outward in a small spiral to find a white seed.
            val seedIdx = findWhiteSeed(lumaMask, w, h, startX, startY)

            val interior = BooleanArray(w * h)
            var fillArea = 0

            if (seedIdx >= 0) {
                // BFS flood fill from seed — zero-allocation integer queue (packed x|y)
                val queue = IntArray(w * h)
                var qHead = 0
                var qTail = 0
                val seedX = seedIdx % w
                val seedY = seedIdx / w
                queue[qTail++] = (seedY shl 16) or seedX
                interior[seedIdx] = true
                fillArea = 1

                while (qHead < qTail) {
                    val packed = queue[qHead++]
                    val cx = packed and 0xFFFF
                    val cy = packed ushr 16

                    // 4-connected neighbors
                    if (cx > 0)     tryFlood(cx - 1, cy, w, lumaMask, interior, queue, qHead, qTail).also { if (it > qTail) { qTail = it; fillArea++ } }
                    if (cx < w - 1) tryFlood(cx + 1, cy, w, lumaMask, interior, queue, qHead, qTail).also { if (it > qTail) { qTail = it; fillArea++ } }
                    if (cy > 0)     tryFlood(cx, cy - 1, w, lumaMask, interior, queue, qHead, qTail).also { if (it > qTail) { qTail = it; fillArea++ } }
                    if (cy < h - 1) tryFlood(cx, cy + 1, w, lumaMask, interior, queue, qHead, qTail).also { if (it > qTail) { qTail = it; fillArea++ } }
                }
            } else {
                // Fallback: entire luminance mask as interior (manga with dark/inverted bubbles)
                lumaMask.copyInto(interior)
                fillArea = lumaMask.count { it }
            }

            result.add(BubbleMask(interior, Rect(left, top, right, bottom), w, h, fillArea))
        }
        return result
    }

    /** Counts interior pixels of [textBox] that fall inside [mask]. Used for IoA computation. */
    fun countIntersectionPixels(mask: BubbleMask, textBox: Rect): Int {
        // Intersection of textBox with mask rect in mask-local coordinates
        val interLeft   = max(textBox.left,   mask.rect.left)   - mask.rect.left
        val interTop    = max(textBox.top,    mask.rect.top)    - mask.rect.top
        val interRight  = min(textBox.right,  mask.rect.right)  - mask.rect.left
        val interBottom = min(textBox.bottom, mask.rect.bottom) - mask.rect.top

        if (interRight <= interLeft || interBottom <= interTop) return 0

        var count = 0
        for (dy in interTop until interBottom) {
            for (dx in interLeft until interRight) {
                if (dy in 0 until mask.height && dx in 0 until mask.width) {
                    if (mask.mask[dy * mask.width + dx]) count++
                }
            }
        }
        return count
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Finds a white seed pixel starting from (startX, startY) and spiraling outward
     * within a 20% radius of the crop center. Returns flat index or -1 if not found.
     */
    private fun findWhiteSeed(lumaMask: BooleanArray, w: Int, h: Int, startX: Int, startY: Int): Int {
        val maxR = min(w, h) / 5 // search within ~20% of smaller dimension
        for (r in 0..maxR) {
            // Sample a ring of radius r around center
            val x1 = max(0, startX - r); val x2 = min(w - 1, startX + r)
            val y1 = max(0, startY - r); val y2 = min(h - 1, startY + r)
            for (dx in x1..x2) {
                if (dy_check(lumaMask, w, y1, dx)) return y1 * w + dx
                if (y2 != y1 && dy_check(lumaMask, w, y2, dx)) return y2 * w + dx
            }
            for (dy in y1 + 1 until y2) {
                if (dy_check(lumaMask, w, dy, x1)) return dy * w + x1
                if (x2 != x1 && dy_check(lumaMask, w, dy, x2)) return dy * w + x2
            }
        }
        return -1
    }

    private fun dy_check(lumaMask: BooleanArray, w: Int, y: Int, x: Int): Boolean =
        lumaMask[y * w + x]

    /**
     * Attempts to enqueue a neighbor pixel if it is white and not yet visited.
     * Returns the new qTail (incremented if pixel was enqueued, unchanged otherwise).
     */
    private fun tryFlood(
        nx: Int, ny: Int, w: Int,
        lumaMask: BooleanArray,
        interior: BooleanArray,
        queue: IntArray,
        @Suppress("UNUSED_PARAMETER") qHead: Int,
        qTail: Int,
    ): Int {
        val nIdx = ny * w + nx
        return if (lumaMask[nIdx] && !interior[nIdx]) {
            interior[nIdx] = true
            queue[qTail] = (ny shl 16) or nx
            qTail + 1
        } else {
            qTail
        }
    }
}
