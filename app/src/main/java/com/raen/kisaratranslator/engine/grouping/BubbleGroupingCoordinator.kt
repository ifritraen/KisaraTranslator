package com.raen.kisaratranslator.engine.grouping

import android.graphics.Rect
import android.graphics.RectF
import com.raen.kisaratranslator.data.model.TranslationBlock
import kotlin.math.max
import kotlin.math.min

/**
 * Disjoint-Set Union-Find (DSU) Speech Bubble & Text Grouping Coordinator.
 * Resolves fragmented multi-line OCR detections into coherent sentences with
 * Point-in-Polygon centroid enclosure and RTL/LTR reading order sorting.
 */
class BubbleGroupingCoordinator(
    private val horizontalProximityThresholdDp: Float = 24f,
    private val verticalProximityThresholdDp: Float = 16f,
) {

    data class TextFragment(
        val block: TranslationBlock,
        val rect: RectF,
        val index: Int,
    )

    fun groupBlocks(
        blocks: List<TranslationBlock>,
        bubbleRegions: List<Rect> = emptyList(),
        isRtl: Boolean = true,
        assignmentMode: Int = 0, // 0 = Point-in-Polygon Centroid, 1 = Spatial Proximity DSU
        sortingOrder: Int = 0, // 0 = Auto/RTL, 1 = LTR, 2 = Disabled
        hardBubbleFence: Boolean = false, // Method 2: orphan fragments cannot cross into assigned bubbles
    ): List<TranslationBlock> {
        if (blocks.size <= 1) return blocks

        val fragments = blocks.mapIndexed { idx, b ->
            TextFragment(
                block = b,
                rect = RectF(b.x, b.y, b.x + b.width, b.y + b.height),
                index = idx,
            )
        }

        // Initialize DSU
        val parent = IntArray(fragments.size) { it }

        fun find(i: Int): Int {
            var curr = i
            while (parent[curr] != curr) {
                parent[curr] = parent[parent[curr]]
                curr = parent[curr]
            }
            return curr
        }

        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) {
                parent[rootI] = rootJ
            }
        }

        val assignedBubble = IntArray(fragments.size) { -1 }

        // 1. Point-in-Polygon Enclosure Assignment
        if (assignmentMode == 0 && bubbleRegions.isNotEmpty()) {
            for ((bIdx, bubble) in bubbleRegions.withIndex()) {
                val bubbleRectF = RectF(bubble)
                val insideIndices = fragments.filter { f ->
                    val cx = f.rect.centerX()
                    val cy = f.rect.centerY()
                    val isCentroidInside = bubbleRectF.contains(cx, cy)
                    val overlap = RectF()
                    val hasAreaOverlap = overlap.setIntersect(bubbleRectF, f.rect) &&
                        (overlap.width() * overlap.height()) / (f.rect.width() * f.rect.height()) > 0.35f
                    isCentroidInside || hasAreaOverlap
                }.map { it.index }

                for (idx in insideIndices) {
                    assignedBubble[idx] = bIdx
                }

                for (i in insideIndices.indices) {
                    for (j in i + 1 until insideIndices.size) {
                        val idxA = insideIndices[i]
                        val idxB = insideIndices[j]
                        if (areFragmentsInSameBubble(fragments[idxA].rect, fragments[idxB].rect, isRtl)) {
                            union(idxA, idxB)
                        }
                    }
                }
            }
        }

        // 2. Spatial proximity clustering for fragments
        for (i in fragments.indices) {
            for (j in i + 1 until fragments.size) {
                if (find(i) == find(j)) continue
                // If both fragments are assigned to two DIFFERENT explicit bubbles, do not cross-merge
                if (assignedBubble[i] != -1 && assignedBubble[j] != -1 && assignedBubble[i] != assignedBubble[j]) {
                    continue
                }
                // Method 2 (hardBubbleFence): also block orphan fragments from merging INTO an assigned bubble
                if (hardBubbleFence) {
                    val aAssigned = assignedBubble[i] != -1
                    val bAssigned = assignedBubble[j] != -1
                    if (aAssigned != bAssigned) continue // one assigned, one orphan → fence
                }

                val r1 = fragments[i].rect
                val r2 = fragments[j].rect

                if (areFragmentsInSameBubble(r1, r2, isRtl)) {
                    union(i, j)
                }
            }
        }

        // 3. Group by DSU root and sort internally
        val groups = fragments.groupBy { find(it.index) }
        val consolidatedBlocks = mutableListOf<TranslationBlock>()

        val effectiveIsRtl = when (sortingOrder) {
            1 -> false
            2 -> false
            else -> isRtl
        }

        for ((_, group) in groups) {
            if (group.isEmpty()) continue

            val sortedGroup = when (sortingOrder) {
                0 -> {
                    if (effectiveIsRtl) {
                        group.sortedWith(
                            compareByDescending<TextFragment> { it.rect.centerX() }
                                .thenBy { it.rect.top },
                        )
                    } else {
                        group.sortedWith(
                            compareBy<TextFragment> { it.rect.top }
                                .thenBy { it.rect.left },
                        )
                    }
                }
                1 -> {
                    group.sortedWith(
                        compareBy<TextFragment> { it.rect.top }
                            .thenBy { it.rect.left },
                    )
                }
                else -> group
            }

            val composedText = composeText(sortedGroup.map { it.block.text })
            val minX = sortedGroup.minOf { it.rect.left }
            val minY = sortedGroup.minOf { it.rect.top }
            val maxX = sortedGroup.maxOf { it.rect.right }
            val maxY = sortedGroup.maxOf { it.rect.bottom }

            val width = maxX - minX
            val height = maxY - minY

            val isBubble = sortedGroup.any { it.block.isBubble } || (bubbleRegions.any { b ->
                val br = RectF(b)
                val inter = RectF()
                inter.setIntersect(br, RectF(minX, minY, maxX, maxY))
            })

            consolidatedBlocks.add(
                TranslationBlock(
                    text = composedText,
                    width = width,
                    height = height,
                    x = minX,
                    y = minY,
                    symWidth = width / max(composedText.length, 1),
                    symHeight = height / max(composedText.length, 1),
                    angle = if (height > width * 1.3f) 90f else 0f,
                    isBubble = isBubble,
                ),
            )
        }

        // 4. Sort page-level consolidated blocks
        return if (effectiveIsRtl) {
            consolidatedBlocks.sortedWith(
                compareBy<TranslationBlock> { (it.y / 180f).toInt() }
                    .thenByDescending { it.x },
            )
        } else {
            consolidatedBlocks.sortedWith(
                compareBy<TranslationBlock> { (it.y / 180f).toInt() }
                    .thenBy { it.x },
            )
        }
    }

    private fun areFragmentsInSameBubble(r1: RectF, r2: RectF, isRtl: Boolean): Boolean {
        val dx = max(0f, max(r1.left, r2.left) - min(r1.right, r2.right))
        val dy = max(0f, max(r1.top, r2.top) - min(r1.bottom, r2.bottom))

        val avgWidth = (r1.width() + r2.width()) / 2f
        val avgHeight = (r1.height() + r2.height()) / 2f
        val isVerticalText = (r1.height() > r1.width() * 1.15f) || (r2.height() > r2.width() * 1.15f)

        if (isVerticalText || isRtl) {
            // Vertical Japanese/CJK text: lines are parallel columns side-by-side
            val verticalOverlap = min(r1.bottom, r2.bottom) - max(r1.top, r2.top)
            val minHeight = min(r1.height(), r2.height())
            val hasVerticalOverlap = verticalOverlap > minHeight * 0.35f

            // Columns in the same bubble have aligned vertical centers
            val centerDy = kotlin.math.abs(r1.centerY() - r2.centerY())
            val isVerticallyAligned = centerDy < minHeight * 0.55f

            val maxColGap = avgWidth * 1.50f
            val isAdjacentColumn = dx < maxColGap && hasVerticalOverlap && isVerticallyAligned

            // Vertically stacked fragments in the same column (e.g. split line / trailing punctuation)
            val horizOverlap = min(r1.right, r2.right) - max(r1.left, r2.left)
            val minWidth = min(r1.width(), r2.width())
            val isSameColumn = horizOverlap > minWidth * 0.35f || dx < avgWidth * 0.20f
            val isStackedInCol = isSameColumn && dy < avgHeight * 0.55f

            if (isAdjacentColumn || isStackedInCol) return true
        } else {
            // Horizontal text: lines are rows stacked vertically
            val horizontalOverlap = min(r1.right, r2.right) - max(r1.left, r2.left)
            val minWidth = min(r1.width(), r2.width())
            val hasHorizontalOverlap = horizontalOverlap > minWidth * 0.40f

            val centerDx = kotlin.math.abs(r1.centerX() - r2.centerX())
            val isHorizontallyAligned = centerDx < minWidth * 0.45f

            val maxRowGap = avgHeight * 1.35f
            val isAdjacentRow = dy < maxRowGap && hasHorizontalOverlap && isHorizontallyAligned

            val vertOverlap = min(r1.bottom, r2.bottom) - max(r1.top, r2.top)
            val minHeight = min(r1.height(), r2.height())
            val isSameRow = vertOverlap > minHeight * 0.40f || dy < avgHeight * 0.20f
            val isSideBySideInRow = isSameRow && dx < avgWidth * 0.50f

            if (isAdjacentRow || isSideBySideInRow) return true
        }

        // Only merge if boxes actually overlap with substantial area (prevents false proximity bridging)
        val interLeft = max(r1.left, r2.left)
        val interTop = max(r1.top, r2.top)
        val interRight = min(r1.right, r2.right)
        val interBottom = min(r1.bottom, r2.bottom)
        if (interRight > interLeft && interBottom > interTop) {
            val interArea = (interRight - interLeft) * (interBottom - interTop)
            val minArea = min(r1.width() * r1.height(), r2.width() * r2.height())
            if (interArea > minArea * 0.20f) return true
        }
        return false
    }

    private fun composeText(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (sb.isNotEmpty()) {
                val lastChar = sb.last()
                val firstChar = trimmed.first()

                val isLastCjk = isCjkCharacter(lastChar)
                val isFirstCjk = isCjkCharacter(firstChar)

                if (isLastCjk && isFirstCjk) {
                    // No space between CJK
                } else if (lastChar == '-' || lastChar == '—' || lastChar == '・') {
                    // Direct continuation
                } else {
                    sb.append(" ")
                }
            }
            sb.append(trimmed)
        }
        return sb.toString()
    }

    private fun isCjkCharacter(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HANGUL_JAMO ||
            block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }
}
