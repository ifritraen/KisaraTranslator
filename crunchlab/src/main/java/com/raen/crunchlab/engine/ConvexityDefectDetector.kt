package com.raen.crunchlab.engine

import android.graphics.Bitmap
import android.graphics.Point
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Sub-pixel concavity notch & convexity defect candidate detector for speech bubbles.
 *
 * Recreates the exact candidate notch extraction algorithm from Crunch Annotator auto_suggest
 * (method3/server/server.py):
 * 1. Adaptive interior bubble brightness thresholding on raw crop bitmap:
 *    t = min(225, max(140, p95 * 0.90)).
 * 2. Outer external contour tracing (Moore-Neighbor 8-connected boundary tracing) without
 *    morphological closing to avoid obliterating narrow ink waist notches.
 * 3. Bubble contour selection by maximum enclosed area (Shoelace formula >= 1500 px^2).
 * 4. Cyclic polygon approximation (Ramer-Douglas-Peucker with epsilon = 2.0).
 * 5. Convex hull computation (Andrew's Monotone Chain preserving cyclic contour traversal).
 * 6. Convexity defects calculation (maximum perpendicular chord distance >= min_depth).
 * 7. Candidate notch clustering & deduplication sorted by depth descending.
 */
object ConvexityDefectDetector {

    data class DefectPoint(
        val point: Point,
        val depth: Float
    )

    /**
     * Extracts concavity defect candidate points from a speech bubble crop.
     * Operates directly on the raw high-resolution crop bitmap pixels to preserve ink boundary detail.
     */
    fun detectCandidates(
        cropBmp: Bitmap,
        @Suppress("UNUSED_PARAMETER") bubbleMask: BooleanArray? = null
    ): List<Point> {
        val w = cropBmp.width
        val h = cropBmp.height
        if (w < 12 || h < 12) return emptyList()

        // 1. Adaptive thresholding: determine bubble interior based on p95 brightness
        val mask = computeAdaptiveThresholdMask(cropBmp, w, h)

        // 2. Extract external outer contour using Moore-Neighbor tracing & Shoelace area
        val contour = extractOuterContour(mask, w, h) ?: return emptyList()

        // 3. Approximate polygon with epsilon = 2.0 (closed RDP)
        val approx = rdpClosed(contour, eps = 2.0f)
        if (approx.size < 5) return emptyList()

        // 4. Compute convex hull vertices preserving cyclic traversal order
        val hullIndices = computeHullIndices(approx)
        if (hullIndices.size <= 3) return emptyList()

        // 5. Compute convexity defects
        val minDepth = max(3.0f, min(w, h) * 0.018f)
        val defects = computeConvexityDefects(approx, hullIndices, minDepth)
        if (defects.isEmpty()) return emptyList()

        // 6. Sort by depth descending (deepest notches first)
        val sortedDefects = defects.sortedByDescending { it.depth }

        // 7. Cluster nearby candidates (within 8px radius)
        val candidates = mutableListOf<Point>()
        for (defect in sortedDefects) {
            val pt = defect.point
            val isDuplicate = candidates.any { c ->
                val dx = c.x - pt.x
                val dy = c.y - pt.y
                (dx * dx + dy * dy) < 64 // 8px radius
            }
            if (!isDuplicate) {
                candidates.add(pt)
            }
        }

        return candidates
    }

    private fun computeAdaptiveThresholdMask(cropBmp: Bitmap, w: Int, h: Int): BooleanArray {
        val gray = IntArray(w * h)
        val hist = IntArray(256)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pix = cropBmp.getPixel(x, y)
                val r = (pix ushr 16) and 0xFF
                val g = (pix ushr 8) and 0xFF
                val b = pix and 0xFF
                val gVal = ((299 * r + 587 * g + 114 * b + 500) / 1000).coerceIn(0, 255)
                gray[y * w + x] = gVal
                hist[gVal]++
            }
        }

        var count = 0
        val targetCount = (0.95f * (w * h)).toInt()
        var p95 = 200
        for (v in 0..255) {
            count += hist[v]
            if (count >= targetCount) {
                p95 = v
                break
            }
        }
        val t = (p95 * 0.90f).toInt().coerceIn(140, 225)
        return BooleanArray(w * h) { gray[it] >= t }
    }

    private fun extractOuterContour(mask: BooleanArray, w: Int, h: Int): List<Point>? {
        val visited = BooleanArray(w * h)
        var bestContour: List<Point>? = null
        var maxArea = 0.0

        val dirsX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dirsY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

        for (y in 0 until h) {
            val rOff = y * w
            for (x in 0 until w) {
                val idx = rOff + x
                if (!mask[idx] || visited[idx]) continue

                // Check if this pixel is on the outer boundary (left neighbor is non-bubble or canvas border)
                val isOuterStart = (x == 0 || !mask[idx - 1])
                if (!isOuterStart) continue

                val contour = traceOuterContour(mask, w, h, x, y, visited, dirsX, dirsY)
                if (contour.size >= 10) {
                    val area = shoelaceArea(contour)
                    if (area > maxArea) {
                        maxArea = area
                        bestContour = contour
                    }
                }
            }
        }

        return if (maxArea >= 1500.0) bestContour else null
    }

    private fun traceOuterContour(
        mask: BooleanArray,
        w: Int,
        h: Int,
        startX: Int,
        startY: Int,
        visited: BooleanArray,
        dirsX: IntArray,
        dirsY: IntArray
    ): List<Point> {
        val contour = mutableListOf<Point>()
        var currX = startX
        var currY = startY
        var backtrackDir = 4 // Entered from West (x - 1)

        contour.add(Point(currX, currY))
        visited[currY * w + currX] = true
        val maxSteps = w * h

        for (step in 0 until maxSteps) {
            var foundNext = false
            for (i in 0 until 8) {
                val d = (backtrackDir + 2 + i) % 8
                val nx = currX + dirsX[d]
                val ny = currY + dirsY[d]

                if (nx in 0 until w && ny in 0 until h && mask[ny * w + nx]) {
                    currX = nx
                    currY = ny
                    backtrackDir = (d + 4) % 8
                    contour.add(Point(currX, currY))
                    visited[currY * w + currX] = true
                    foundNext = true
                    break
                }
            }

            if (!foundNext) break
            if (currX == startX && currY == startY) break
        }

        return contour
    }

    private fun shoelaceArea(pts: List<Point>): Double {
        val n = pts.size
        if (n < 3) return 0.0
        var a = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += pts[i].x.toDouble() * pts[j].y.toDouble() - pts[j].x.toDouble() * pts[i].y.toDouble()
        }
        return abs(a) * 0.5
    }

    private fun rdpClosed(pts: List<Point>, eps: Float = 2.0f): List<Point> {
        val n = pts.size
        if (n < 5) return pts

        // 1. Find two farthest points across the closed polygon to split into two arcs
        var maxD2 = 0L
        val p0 = pts[0]
        var far1 = 0
        for (i in 1 until n) {
            val dx = pts[i].x.toLong() - p0.x
            val dy = pts[i].y.toLong() - p0.y
            val d2 = dx * dx + dy * dy
            if (d2 > maxD2) {
                maxD2 = d2
                far1 = i
            }
        }

        val p1 = pts[far1]
        maxD2 = 0L
        var far2 = 0
        for (i in 0 until n) {
            val dx = pts[i].x.toLong() - p1.x
            val dy = pts[i].y.toLong() - p1.y
            val d2 = dx * dx + dy * dy
            if (d2 > maxD2) {
                maxD2 = d2
                far2 = i
            }
        }

        if (far1 > far2) {
            val tmp = far1
            far1 = far2
            far2 = tmp
        }

        val keep = BooleanArray(n)
        keep[far1] = true
        keep[far2] = true

        // Arc 1: far1 .. far2
        rdpOpen(pts, far1, far2, eps, keep)

        // Arc 2: far2 wrapping around to far1
        val wrappedLen = (n - far2) + (far1 + 1)
        val ptsWrapped = ArrayList<Point>(wrappedLen)
        for (i in far2 until n) ptsWrapped.add(pts[i])
        for (i in 0..far1) ptsWrapped.add(pts[i])

        val keepWrapped = BooleanArray(wrappedLen)
        keepWrapped[0] = true
        keepWrapped[wrappedLen - 1] = true
        rdpOpen(ptsWrapped, 0, wrappedLen - 1, eps, keepWrapped)

        for (k in 0 until wrappedLen) {
            if (keepWrapped[k]) {
                val origIdx = (far2 + k) % n
                keep[origIdx] = true
            }
        }

        val approx = mutableListOf<Point>()
        for (i in 0 until n) {
            if (keep[i]) approx.add(pts[i])
        }
        return approx
    }

    private fun rdpOpen(
        pts: List<Point>,
        start: Int,
        end: Int,
        eps: Float,
        keep: BooleanArray
    ) {
        if (end - start <= 1) return

        val s = pts[start]
        val e = pts[end]
        val dx = (e.x - s.x).toFloat()
        val dy = (e.y - s.y).toFloat()
        val norm = sqrt(dx * dx + dy * dy)

        var maxD = 0f
        var maxI = -1

        for (i in (start + 1) until end) {
            val p = pts[i]
            val d = if (norm > 1e-4f) {
                abs(dy * p.x - dx * p.y + e.x * s.y - e.y * s.x) / norm
            } else {
                val dpx = (p.x - s.x).toFloat()
                val dpy = (p.y - s.y).toFloat()
                sqrt(dpx * dpx + dpy * dpy)
            }
            if (d > maxD) {
                maxD = d
                maxI = i
            }
        }

        if (maxD > eps && maxI != -1) {
            keep[maxI] = true
            rdpOpen(pts, start, maxI, eps, keep)
            rdpOpen(pts, maxI, end, eps, keep)
        }
    }

    private fun computeHullIndices(points: List<Point>): List<Int> {
        val n = points.size
        if (n <= 3) return points.indices.toList()

        val sorted = points.indices.sortedWith { i1, i2 ->
            val p1 = points[i1]; val p2 = points[i2]
            if (p1.x != p2.x) p1.x.compareTo(p2.x) else p1.y.compareTo(p2.y)
        }

        fun cross(o: Point, a: Point, b: Point): Long {
            return (a.x.toLong() - o.x.toLong()) * (b.y.toLong() - o.y.toLong()) -
                   (a.y.toLong() - o.y.toLong()) * (b.x.toLong() - o.x.toLong())
        }

        val lower = mutableListOf<Int>()
        for (idx in sorted) {
            val p = points[idx]
            while (lower.size >= 2 && cross(points[lower[lower.size - 2]], points[lower[lower.size - 1]], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(idx)
        }

        val upper = mutableListOf<Int>()
        for (i in sorted.indices.reversed()) {
            val idx = sorted[i]
            val p = points[idx]
            while (upper.size >= 2 && cross(points[upper[upper.size - 2]], points[upper[upper.size - 1]], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(idx)
        }

        val hullSet = HashSet<Int>()
        for (i in 0 until (lower.size - 1)) hullSet.add(lower[i])
        for (i in 0 until (upper.size - 1)) hullSet.add(upper[i])

        return points.indices.filter { it in hullSet }
    }

    private fun computeConvexityDefects(
        approx: List<Point>,
        hullIndices: List<Int>,
        minDepth: Float
    ): List<DefectPoint> {
        val defects = mutableListOf<DefectPoint>()
        val m = hullIndices.size
        val n = approx.size
        if (m <= 3 || n < 5) return emptyList()

        for (k in 0 until m) {
            val sIdx = hullIndices[k]
            val eIdx = hullIndices[(k + 1) % m]
            val S = approx[sIdx]
            val E = approx[eIdx]

            val dx = (E.x - S.x).toFloat()
            val dy = (E.y - S.y).toFloat()
            val chordLen = sqrt(dx * dx + dy * dy)
            if (chordLen < 1.0f) continue

            var maxDepth = 0f
            var farPoint: Point? = null

            var curr = (sIdx + 1) % n
            while (curr != eIdx) {
                val P = approx[curr]
                val dist = abs(dy * P.x - dx * P.y + E.x * S.y - E.y * S.x) / chordLen
                if (dist > maxDepth) {
                    maxDepth = dist
                    farPoint = P
                }
                curr = (curr + 1) % n
            }

            if (maxDepth >= minDepth && farPoint != null) {
                defects.add(DefectPoint(farPoint, maxDepth))
            }
        }

        return defects
    }
}
