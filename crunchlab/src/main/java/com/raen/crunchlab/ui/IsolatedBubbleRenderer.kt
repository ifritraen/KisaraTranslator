package com.raen.crunchlab.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.raen.crunchlab.engine.BubbleMask

/**
 * Creates an isolated pure white canvas containing ONLY the detected speech bubbles.
 * Everything outside the bubbles is painted pure white.
 * Applies a fast 3px dilation so the artist's drawn ink border contour is fully preserved.
 */
object IsolatedBubbleRenderer {

    fun renderIsolatedBubbles(
        sourceBitmap: Bitmap,
        masks: List<BubbleMask>,
        safetyMarginPx: Int = 6,
        dilationRadiusPx: Int = 4,
        inkLuminanceThreshold: Int = 165
    ): Bitmap? {
        if (sourceBitmap.isRecycled) return null
        val w = sourceBitmap.width
        val h = sourceBitmap.height
        if (w <= 0 || h <= 0) return null

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.eraseColor(Color.WHITE)
        if (masks.isEmpty()) return outBmp

        val dxs = intArrayOf(1, -1, 0, 0, 1, -1, 1, -1)
        val dys = intArrayOf(0, 0, 1, -1, 1, 1, -1, -1)

        // Global or per-bubble keep-mask across the padded region
        for (mask in masks) {
            val mw = mask.width
            val mh = mask.height
            if (mw <= 0 || mh <= 0) continue

            // Expand bounding box by safetyMarginPx in all directions
            val expLeft = (mask.rect.left - safetyMarginPx).coerceIn(0, w - 1)
            val expTop = (mask.rect.top - safetyMarginPx).coerceIn(0, h - 1)
            val expRight = (mask.rect.right + safetyMarginPx).coerceIn(expLeft + 1, w)
            val expBottom = (mask.rect.bottom + safetyMarginPx).coerceIn(expTop + 1, h)
            val expW = expRight - expLeft
            val expH = expBottom - expTop
            if (expW <= 0 || expH <= 0) continue

            val area = expW * expH
            val expandedKeep = BooleanArray(area)
            val dist = IntArray(area) { -1 }
            val queueX = IntArray(area)
            val queueY = IntArray(area)
            var head = 0
            var tail = 0

            // 1. Seed BFS with all pixels from original mask
            for (my in 0 until mh) {
                val origRow = my * mw
                val ey = (mask.rect.top + my) - expTop
                if (ey !in 0 until expH) continue

                for (mx in 0 until mw) {
                    if (mask.mask[origRow + mx]) {
                        val ex = (mask.rect.left + mx) - expLeft
                        if (ex in 0 until expW) {
                            val idx = ey * expW + ex
                            expandedKeep[idx] = true
                            dist[idx] = 0
                            queueX[tail] = ex
                            queueY[tail] = ey
                            tail++
                        }
                    }
                }
            }

            val pixels = IntArray(area)
            sourceBitmap.getPixels(pixels, 0, expW, expLeft, expTop, expW, expH)

            // 2. Adaptive BFS expansion: only expand into dark border ink
            while (head < tail) {
                val cx = queueX[head]
                val cy = queueY[head]
                val cIdx = cy * expW + cx
                val d = dist[cIdx]
                head++

                if (d >= dilationRadiusPx) continue

                for (k in 0 until 8) {
                    val nx = cx + dxs[k]
                    val ny = cy + dys[k]
                    if (nx in 0 until expW && ny in 0 until expH) {
                        val nIdx = ny * expW + nx
                        if (dist[nIdx] == -1) {
                            val c = pixels[nIdx]
                            val r = (c shr 16) and 0xFF
                            val g = (c shr 8) and 0xFF
                            val b = c and 0xFF
                            val lum = (r * 299 + g * 587 + b * 114) / 1000

                            // Only keep pixels that are dark ink (artist's outline stroke)
                            if (lum < inkLuminanceThreshold) {
                                dist[nIdx] = d + 1
                                expandedKeep[nIdx] = true
                                queueX[tail] = nx
                                queueY[tail] = ny
                                tail++
                            }
                        }
                    }
                }
            }

            // 3. Mask non-kept pixels to pure white
            for (i in 0 until area) {
                if (!expandedKeep[i]) {
                    pixels[i] = Color.WHITE
                }
            }

            // 4. Blend / copy into outBmp
            val existingPixels = IntArray(area)
            outBmp.getPixels(existingPixels, 0, expW, expLeft, expTop, expW, expH)

            for (i in 0 until area) {
                if (expandedKeep[i]) {
                    existingPixels[i] = pixels[i]
                }
            }
            outBmp.setPixels(existingPixels, 0, expW, expLeft, expTop, expW, expH)
        }

        return outBmp
    }
}
