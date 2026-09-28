package com.raen.crunchlab.engine

import android.graphics.Rect

/**
 * Binary instance mask of a single speech bubble.
 */
data class BubbleMask(
    val rect: Rect,
    val mask: BooleanArray, // local mask of size width * height (true = inside bubble)
    val width: Int,
    val height: Int,
    val fillArea: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BubbleMask) return false
        return rect == other.rect && width == other.width && height == other.height && fillArea == other.fillArea
    }

    override fun hashCode(): Int {
        var result = rect.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + fillArea
        return result
    }
}
