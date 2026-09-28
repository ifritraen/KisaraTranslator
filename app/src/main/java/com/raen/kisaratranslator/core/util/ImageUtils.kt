package com.raen.kisaratranslator.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.raen.kisaratranslator.data.model.PageTranslation
import com.raen.kisaratranslator.data.model.TranslationBlock
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

object ImageUtils {

    fun decodeBitmapFromUri(context: Context, uri: Uri, maxDim: Int = 4096): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            val (w, h) = options.outWidth to options.outHeight
            if (w <= 0 || h <= 0) return null

            var sampleSize = 1
            while (w / sampleSize > maxDim || h / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun decodeBitmapFromFile(file: File, maxDim: Int = 4096): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val (w, h) = options.outWidth to options.outHeight
            if (w <= 0 || h <= 0) return null

            var sampleSize = 1
            while (w / sampleSize > maxDim || h / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        } catch (e: Exception) {
            null
        }
    }

    fun getBubbleBackgroundColor(bitmap: Bitmap, rect: RectF): Int {
        try {
            val w = bitmap.width
            val h = bitmap.height

            val points = listOf(
                Pair((rect.left + 2).toInt(), (rect.top + 2).toInt()),
                Pair((rect.right - 3).toInt(), (rect.top + 2).toInt()),
                Pair((rect.left + 2).toInt(), (rect.bottom - 3).toInt()),
                Pair((rect.right - 3).toInt(), (rect.bottom - 3).toInt()),
                Pair((rect.left + rect.width() / 2).toInt(), (rect.top + 2).toInt()),
                Pair((rect.left + rect.width() / 2).toInt(), (rect.bottom - 3).toInt()),
            )

            val colors = points.mapNotNull { (px, py) ->
                if (px in 0 until w && py in 0 until h) bitmap.getPixel(px, py) else null
            }

            if (colors.isEmpty()) return Color.WHITE

            val picked = colors.minByOrNull { c1 ->
                colors.sumOf { c2 ->
                    val rDiff = Color.red(c1) - Color.red(c2)
                    val gDiff = Color.green(c1) - Color.green(c2)
                    val bDiff = Color.blue(c1) - Color.blue(c2)
                    rDiff * rDiff + gDiff * gDiff + bDiff * bDiff
                }
            } ?: colors[0]

            val lum = 0.299f * Color.red(picked) + 0.587f * Color.green(picked) + 0.114f * Color.blue(picked)
            return if (lum >= 175f) Color.WHITE else if (lum <= 40f) Color.BLACK else picked
        } catch (_: Exception) {
            return Color.WHITE
        }
    }

    fun getBubbleBackgroundColor(bitmap: Bitmap, block: TranslationBlock): Int {
        return getBubbleBackgroundColor(bitmap, RectF(block.x, block.y, block.x + block.width, block.y + block.height))
    }

    fun isLikelySpeechBubble(bitmap: Bitmap, box: Rect, minBrightCount: Int = 9): Boolean {
        try {
            val w = bitmap.width
            val h = bitmap.height
            val left = box.left.coerceIn(0, w - 1)
            val top = box.top.coerceIn(0, h - 1)
            val right = box.right.coerceIn(left + 1, w)
            val bottom = box.bottom.coerceIn(top + 1, h)

            val samplePoints = listOf(
                Pair(left, top),
                Pair((left + right) / 2, top),
                Pair(right - 1, top),
                Pair(left, bottom - 1),
                Pair((left + right) / 2, bottom - 1),
                Pair(right - 1, bottom - 1),
                Pair(left, (top + bottom) / 2),
                Pair(right - 1, (top + bottom) / 2),
                Pair(left, top + (bottom - top) / 4),
                Pair(right - 1, top + (bottom - top) / 4),
                Pair(left, top + 3 * (bottom - top) / 4),
                Pair(right - 1, top + 3 * (bottom - top) / 4),
            )

            var brightCount = 0
            var darkCount = 0
            for ((px, py) in samplePoints) {
                val color = bitmap.getPixel(px, py)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                if (lum > 200f) {
                    brightCount++
                } else if (lum < 100f) {
                    darkCount++
                }
            }
            return brightCount >= minBrightCount && darkCount <= 2
        } catch (_: Exception) {
            return true
        }
    }

    /**
     * Renders a clean inpainting pass over speech bubbles using NON-GROUPED raw detected boxes
     * with 10px dilation to thoroughly eliminate ghost glyph strokes.
     */
    fun renderInpaintedPage(
        original: Bitmap,
        rawBoxes: List<Rect>,
        fillBubbleBackground: Boolean = true,
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        if (!fillBubbleBackground) return result

        val canvas = Canvas(result)
        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val pad = 10f
        for (box in rawBoxes) {
            val rect = RectF(
                (box.left - pad).coerceAtLeast(0f),
                (box.top - pad).coerceAtLeast(0f),
                (box.right + pad).coerceAtMost(original.width.toFloat()),
                (box.bottom + pad).coerceAtMost(original.height.toFloat()),
            )
            bgPaint.color = getBubbleBackgroundColor(original, rect)
            val cornerRadius = min(rect.width(), rect.height()) * 0.15f
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        }
        return result
    }

    fun renderInpaintedPage(
        original: Bitmap,
        translation: PageTranslation,
        fillBubbleBackground: Boolean = true,
    ): Bitmap {
        val boxes = translation.blocks.map {
            Rect(it.x.toInt(), it.y.toInt(), (it.x + it.width).toInt(), (it.y + it.height).toInt())
        }
        return renderInpaintedPage(original, boxes, fillBubbleBackground)
    }

    /**
     * Method 2 (Enhanced) inpainting pass.
     * Only erases Step 1 raw detection boxes whose centroid falls inside at least one
     * Step 3 grouped block's bounding rect. This prevents:
     *   - Leaving Japanese text visible in missed-detection areas (raw boxes cover exactly what was read)
     *   - Over-erasing artwork outside grouped bubbles (we never paint beyond what was actually grouped)
     */
    fun renderInpaintedPageV2(
        original: Bitmap,
        rawBoxes: List<Rect>,
        groupedBlocks: List<TranslationBlock>,
        fillBubbleBackground: Boolean = true,
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        if (!fillBubbleBackground || groupedBlocks.isEmpty()) return result

        // Pre-build grouped block RectFs for centroid containment check
        val groupedRects = groupedBlocks.map { b ->
            RectF(b.x, b.y, b.x + b.width, b.y + b.height)
        }

        val canvas = Canvas(result)
        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val pad = 10f
        for (box in rawBoxes) {
            val cx = (box.left + box.right) / 2f
            val cy = (box.top + box.bottom) / 2f
            // Only erase this raw box if its centroid is inside any grouped block
            val belongsToGroup = groupedRects.any { it.contains(cx, cy) }
            if (!belongsToGroup) continue

            val rect = RectF(
                (box.left - pad).coerceAtLeast(0f),
                (box.top - pad).coerceAtLeast(0f),
                (box.right + pad).coerceAtMost(original.width.toFloat()),
                (box.bottom + pad).coerceAtMost(original.height.toFloat()),
            )
            bgPaint.color = getBubbleBackgroundColor(original, rect)
            val cornerRadius = min(rect.width(), rect.height()) * 0.15f
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        }
        return result
    }

    /**
     * Generates a visualization bitmap of detected bounding boxes with indexed tags.
     */
    fun renderBoxesOverlay(
        original: Bitmap,
        boxes: List<Rect>,
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, original.width / 500f)
            color = Color.parseColor("#00E676")
            isAntiAlias = true
        }

        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#3300E676")
            isAntiAlias = true
        }

        val badgePaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FF00E676")
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = max(14f, original.width / 60f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        for ((idx, box) in boxes.withIndex()) {
            val rectF = RectF(box)
            canvas.drawRect(rectF, fillPaint)
            canvas.drawRect(rectF, strokePaint)

            val badgeRadius = max(12f, original.width / 70f)
            val cx = rectF.left + badgeRadius + 4f
            val cy = rectF.top + badgeRadius + 4f
            canvas.drawCircle(cx, cy, badgeRadius, badgePaint)
            canvas.drawText("${idx + 1}", cx, cy + (badgeRadius * 0.35f), textPaint)
        }
        return result
    }

    /**
     * Renders translated text blocks onto the original manga page,
     * covering original Japanese text with adaptive bubble background and drawing crisp text.
     */
    fun renderTranslatedPage(
        original: Bitmap,
        translation: PageTranslation,
        rawBoxes: List<Rect> = emptyList(),
        fillBubbleBackground: Boolean = true,
        textScaleFactor: Float = 1.0f,
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val strokePaint = TextPaint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
            isFilterBitmap = true
            isSubpixelText = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val fillPaint = TextPaint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = true
            isFilterBitmap = true
            isSubpixelText = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Step 1: Clean/cover original text area using ONLY detected boxes
        if (fillBubbleBackground) {
            val boxesToInpaint = if (rawBoxes.isNotEmpty()) rawBoxes else translation.blocks.map {
                Rect(it.x.toInt(), it.y.toInt(), (it.x + it.width).toInt(), (it.y + it.height).toInt())
            }
            val pad = 6f
            for (box in boxesToInpaint) {
                val rect = RectF(
                    (box.left - pad).coerceAtLeast(0f),
                    (box.top - pad).coerceAtLeast(0f),
                    (box.right + pad).coerceAtMost(original.width.toFloat()),
                    (box.bottom + pad).coerceAtMost(original.height.toFloat()),
                )
                bgPaint.color = getBubbleBackgroundColor(original, rect)
                val cornerRadius = min(rect.width(), rect.height()) * 0.15f
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
            }
        }

        // Step 2: Fit text inside grouped blocks with white outline layer
        for (block in translation.blocks) {
            val textToDraw = block.translation.ifBlank { block.text }
            if (textToDraw.isBlank()) continue

            val rect = RectF(
                block.x,
                block.y,
                block.x + block.width,
                block.y + block.height,
            )
            val blockWidth = max(rect.width().toInt(), 20)
            val blockHeight = max(rect.height().toInt(), 20)

            var fontSize = (blockHeight / (textToDraw.split("\n").size.coerceAtLeast(1) * 1.6f)) * textScaleFactor
            fontSize = fontSize.coerceIn(12f, 72f)

            var strokeLayout: StaticLayout
            var fillLayout: StaticLayout
            do {
                fillPaint.textSize = fontSize
                strokePaint.textSize = fontSize
                strokePaint.strokeWidth = max(3f, fontSize * 0.18f)

                fillLayout = StaticLayout.Builder.obtain(
                    textToDraw,
                    0,
                    textToDraw.length,
                    fillPaint,
                    blockWidth,
                )
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build()

                if (fillLayout.height <= blockHeight || fontSize <= 10f) {
                    break
                }
                fontSize -= 2f
            } while (fontSize > 10f)

            strokeLayout = StaticLayout.Builder.obtain(
                textToDraw,
                0,
                textToDraw.length,
                strokePaint,
                blockWidth,
            )
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()

            // Center vertically within bounding box
            val topOffset = rect.top + max(0f, (blockHeight - fillLayout.height) / 2f)

            canvas.save()
            canvas.translate(rect.left, topOffset)
            // 1. Draw outer white outline layer
            strokeLayout.draw(canvas)
            // 2. Draw sharp black text on top
            fillLayout.draw(canvas)
            canvas.restore()
        }

        return result
    }

    /**
     * Natural string sort comparator for files like page_1, page_2, page_10.
     */
    fun naturalCompare(a: String, b: String): Int {
        val regex = "\\d+".toRegex()
        var aIndex = 0
        var bIndex = 0

        while (aIndex < a.length && bIndex < b.length) {
            val aMatch = regex.find(a, aIndex)
            val bMatch = regex.find(b, bIndex)

            if (aMatch != null && bMatch != null && aMatch.range.first == aIndex && bMatch.range.first == bIndex) {
                val aNum = aMatch.value.toLongOrNull() ?: 0L
                val bNum = bMatch.value.toLongOrNull() ?: 0L
                val diff = aNum.compareTo(bNum)
                if (diff != 0) return diff
                aIndex = aMatch.range.last + 1
                bIndex = bMatch.range.last + 1
            } else {
                val ca = a[aIndex].lowercaseChar()
                val cb = b[bIndex].lowercaseChar()
                if (ca != cb) return ca.compareTo(cb)
                aIndex++
                bIndex++
            }
        }
        return a.length.compareTo(b.length)
    }
}
