package com.raen.crunchlab.engine.typeset

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import com.raen.crunchlab.data.DialogueGroupItem
import com.raen.crunchlab.data.TextCategory
import com.raen.crunchlab.data.TypesetBlockItem
import com.raen.crunchlab.data.TypesetLineItem
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class TypesetResult(
    val outputBitmap: Bitmap,
    val blocks: List<TypesetBlockItem>,
    val durationMs: Long
)

/**
 * Module 6.2: Dynamic Shape-Aware Chord Typesetter.
 *
 * Core Typographic & Geometric Invariants:
 * 1. Non-rectangular line-by-line horizontal layout:
 *    Calculates horizontal chord width W(y) at each vertical line position y based on true
 *    bubble area (Moore-Neighbor polygon contour ray intersections or inscribed elliptical chords).
 *    Naturally accommodates oval, circular, or irregular manga speech bubbles (short lines at top/bottom,
 *    long lines in the middle).
 * 2. Dynamic Font Sizing & Line Numbers:
 *    Iteratively optimizes font size (26sp down to 10sp) so translated text completely and
 *    comfortably fills the bubble without vertical overflow.
 * 3. Whole-Word Preservation & textScaleX Condensation:
 *    English words are never cut mid-word across lines. If a word exceeds the current chord,
 *    it flows to the next line. If it exceeds an entire line chord, horizontal compression
 *    (paint.textScaleX down to 0.72) thins the word to fit cleanly.
 * 4. High-Contrast Legibility:
 *    Bubbled text renders crisp solid black. Orphan text renders bold white text with a solid
 *    black outline stroke for flawless readability over manga artwork.
 */
object DynamicChordTypesetter {

    fun typesetDialogue(
        cleanBitmap: Bitmap,
        groups: List<DialogueGroupItem>,
    ): TypesetResult {
        val startTime = System.currentTimeMillis()
        val resultBmp = cleanBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBmp)

        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
            isFilterBitmap = true
        }

        val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
            isFilterBitmap = true
        }

        val typesetBlocks = mutableListOf<TypesetBlockItem>()

        for (group in groups) {
            val rawText = group.translatedText.ifBlank { group.recognizedText }
            if (rawText.isBlank() || group.bounds.width() <= 0 || group.bounds.height() <= 0) continue

            val block = computeBlockLayout(group, rawText, fillPaint) ?: continue
            typesetBlocks.add(block)

            // Render text onto clean canvas
            val isBubble = block.isBubble
            val fontSize = block.fontSize
            fillPaint.textSize = fontSize

            for (line in block.lines) {
                fillPaint.textScaleX = line.scaleX

                if (isBubble) {
                    // Standard Speech Bubble: Sharp black text
                    fillPaint.color = Color.BLACK
                    canvas.drawText(line.text, line.xCenter, line.y, fillPaint)
                } else {
                    // Orphan Text over Artwork: White fill + solid black outline stroke
                    strokePaint.textSize = fontSize
                    strokePaint.textScaleX = line.scaleX
                    strokePaint.strokeWidth = max(2.5f, fontSize * 0.18f)

                    // 1. Draw black outline
                    strokePaint.color = Color.BLACK
                    canvas.drawText(line.text, line.xCenter, line.y, strokePaint)

                    // 2. Draw white core text
                    fillPaint.color = Color.WHITE
                    canvas.drawText(line.text, line.xCenter, line.y, fillPaint)
                }
            }
            fillPaint.textScaleX = 1.0f
        }

        val duration = System.currentTimeMillis() - startTime
        return TypesetResult(
            outputBitmap = resultBmp,
            blocks = typesetBlocks,
            durationMs = duration
        )
    }

    /**
     * Computes the optimal dynamic font size and line-by-line chord positions for a dialogue group.
     */
    fun computeBlockLayout(
        group: DialogueGroupItem,
        text: String,
        measuringPaint: TextPaint,
    ): TypesetBlockItem? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val margin = if (group.isBubble) 8f else 4f
        val candidateFontSizes = listOf(26f, 24f, 22f, 20f, 18f, 16f, 15f, 14f, 13f, 12f, 11f, 10f, 9f)

        var bestLayout: List<TypesetLineItem>? = null
        var bestFontSize = 10f

        for (fontSize in candidateFontSizes) {
            measuringPaint.textSize = fontSize
            val lineHeight = fontSize * 1.28f
            val availH = (group.bounds.height() - 2 * margin).coerceAtLeast(lineHeight)
            val maxPossibleLines = (availH / lineHeight).toInt().coerceAtLeast(1)

            // Try line counts from 1 up to maxPossibleLines
            for (lineCount in 1..maxPossibleLines) {
                val totalH = lineCount * lineHeight
                val startY = group.bounds.centerY() - (totalH / 2f) + (fontSize * 0.9f)
                val candidateYPositions = (0 until lineCount).map { startY + it * lineHeight }

                // Check chords for all lines
                val chords = candidateYPositions.mapNotNull { y ->
                    calculateChord(group, y, margin)
                }
                if (chords.size < lineCount) continue // Chord clipped outside bubble

                // Attempt whole-word packing into these chords
                val packed = packWordsIntoChords(words, candidateYPositions, chords, measuringPaint)
                if (packed != null) {
                    bestLayout = packed
                    bestFontSize = fontSize
                    break
                }
            }

            if (bestLayout != null) {
                // Found largest font size that cleanly accommodates all words
                break
            }
        }

        // Fallback: If text is unusually long, force-pack at minimum font size (9f) with scaleX compression
        if (bestLayout == null) {
            measuringPaint.textSize = 9f
            val lineHeight = 9f * 1.25f
            val availH = (group.bounds.height() - 2 * margin).coerceAtLeast(lineHeight)
            val lineCount = (availH / lineHeight).toInt().coerceAtLeast(1)
            val totalH = lineCount * lineHeight
            val startY = group.bounds.centerY() - (totalH / 2f) + (9f * 0.9f)
            val candidateYPositions = (0 until lineCount).map { startY + it * lineHeight }
            val chords = candidateYPositions.map { y ->
                calculateChord(group, y, margin) ?: Pair(group.bounds.left.toFloat(), group.bounds.right.toFloat())
            }
            bestLayout = packWordsIntoChordsFallback(words, candidateYPositions, chords, measuringPaint)
            bestFontSize = 9f
        }

        return TypesetBlockItem(
            groupId = group.groupId,
            isBubble = group.isBubble,
            bounds = group.bounds,
            fontSize = bestFontSize,
            lines = bestLayout ?: emptyList(),
            originalJapanese = group.recognizedText,
            translatedEnglish = text
        )
    }

    /**
     * Greedily packs whole words into horizontal line chords without breaking words across lines.
     * Applies horizontal font compression (scaleX down to 0.72) only if an individual word exceeds a line chord.
     */
    private fun packWordsIntoChords(
        words: List<String>,
        yPositions: List<Float>,
        chords: List<Pair<Float, Float>>,
        paint: TextPaint,
    ): List<TypesetLineItem>? {
        val lineCount = chords.size
        val linesResult = mutableListOf<TypesetLineItem>()
        var wordIdx = 0
        val spaceWidth = paint.measureText(" ")

        for (k in 0 until lineCount) {
            if (wordIdx >= words.size) break

            val (xL, xR) = chords[k]
            val chordW = xR - xL
            if (chordW < 12f) return null

            val currentWords = mutableListOf<String>()
            var currentLineWidth = 0f
            var lineScaleX = 1.0f

            while (wordIdx < words.size) {
                val nextWord = words[wordIdx]
                val wordW = paint.measureText(nextWord)
                val testWidth = if (currentWords.isEmpty()) wordW else currentLineWidth + spaceWidth + wordW

                if (testWidth <= chordW) {
                    currentWords.add(nextWord)
                    currentLineWidth = testWidth
                    wordIdx++
                } else {
                    if (currentWords.isEmpty()) {
                        // Word exceeds chord even when starting the line alone:
                        // Attempt scaleX horizontal condensation to make it thinner to fit
                        val reqScale = (chordW / wordW).coerceAtLeast(0.70f)
                        if (reqScale >= 0.72f) {
                            currentWords.add(nextWord)
                            lineScaleX = reqScale
                            wordIdx++
                        } else {
                            // Word cannot fit in this chord
                            return null
                        }
                    }
                    // Current line is full, move to next chord
                    break
                }
            }

            if (currentWords.isNotEmpty()) {
                val lineText = currentWords.joinToString(" ")
                val xCenter = (xL + xR) / 2f
                linesResult.add(
                    TypesetLineItem(
                        text = lineText,
                        y = yPositions[k],
                        xCenter = xCenter,
                        chordWidth = chordW,
                        scaleX = lineScaleX
                    )
                )
            }
        }

        // Return non-null only if all words were successfully placed
        return if (wordIdx >= words.size) linesResult else null
    }

    /**
     * Fallback word packer when text is very long: packs greedily across available chords,
     * applying textScaleX compression as necessary.
     */
    private fun packWordsIntoChordsFallback(
        words: List<String>,
        yPositions: List<Float>,
        chords: List<Pair<Float, Float>>,
        paint: TextPaint,
    ): List<TypesetLineItem> {
        val linesResult = mutableListOf<TypesetLineItem>()
        val lineCount = chords.size
        val wordsPerLine = max(1, (words.size + lineCount - 1) / lineCount)
        val spaceWidth = paint.measureText(" ")

        var wordIdx = 0
        for (k in 0 until lineCount) {
            if (wordIdx >= words.size) break
            val (xL, xR) = chords[k]
            val chordW = (xR - xL).coerceAtLeast(20f)

            val currentWords = mutableListOf<String>()
            var currentLineWidth = 0f

            while (wordIdx < words.size && (currentWords.size < wordsPerLine || k == lineCount - 1)) {
                val nextWord = words[wordIdx]
                val wordW = paint.measureText(nextWord)
                val testWidth = if (currentWords.isEmpty()) wordW else currentLineWidth + spaceWidth + wordW

                if (testWidth <= chordW || currentWords.isEmpty()) {
                    currentWords.add(nextWord)
                    currentLineWidth = testWidth
                    wordIdx++
                } else {
                    break
                }
            }

            if (currentWords.isNotEmpty()) {
                val scaleX = if (currentLineWidth > chordW) (chordW / currentLineWidth).coerceIn(0.65f, 1.0f) else 1.0f
                val lineText = currentWords.joinToString(" ")
                linesResult.add(
                    TypesetLineItem(
                        text = lineText,
                        y = yPositions[k],
                        xCenter = (xL + xR) / 2f,
                        chordWidth = chordW,
                        scaleX = scaleX
                    )
                )
            }
        }
        return linesResult
    }

    /**
     * Calculates the horizontal chord [xLeft, xRight] at vertical scanline y.
     * Uses polygon ray-casting for masked bubbles, inscribed elliptical chords for unmasked bubbles,
     * and bounding rect insets for orphan artwork text.
     */
    fun calculateChord(
        group: DialogueGroupItem,
        y: Float,
        margin: Float = 8f
    ): Pair<Float, Float>? {
        val bounds = group.bounds
        if (y < bounds.top + margin || y > bounds.bottom - margin) return null

        // Case A: Moore-Neighbor Polygon Boundary Contour (from YOLO-seg mask)
        if (group.contourPoints.size >= 3) {
            val intersections = mutableListOf<Float>()
            val pts = group.contourPoints
            val n = pts.size

            for (i in 0 until n) {
                val p1 = pts[i]
                val p2 = pts[(i + 1) % n]

                val y1 = p1.y.toFloat()
                val y2 = p2.y.toFloat()

                if ((y1 <= y && y2 > y) || (y2 <= y && y1 > y)) {
                    val t = (y - y1) / (y2 - y1)
                    val x = p1.x.toFloat() + t * (p2.x.toFloat() - p1.x.toFloat())
                    intersections.add(x)
                }
            }

            if (intersections.size >= 2) {
                intersections.sort()
                val xL = intersections.first() + margin
                val xR = intersections.last() - margin
                if (xR > xL + 12f) {
                    return Pair(xL, xR)
                }
            }
        }

        // Case B: Inscribed Elliptical Chord (Standard Speech Bubble Geometry)
        if (group.isBubble || group.category == TextCategory.BUBBLED) {
            val a = (bounds.width() / 2f) - margin
            val b = (bounds.height() / 2f) - margin
            val xc = bounds.exactCenterX()
            val yc = bounds.exactCenterY()

            if (a > 8f && b > 8f) {
                val dy = y - yc
                val ratio = (dy * dy) / (b * b)
                if (ratio < 1.0f) {
                    val dx = a * sqrt(1.0f - ratio)
                    val xL = xc - dx
                    val xR = xc + dx
                    if (xR > xL + 12f) {
                        return Pair(xL, xR)
                    }
                }
            }
        }

        // Case C: Rectangular Bounding Box Inset (Orphan text or rectangular bubble)
        val xL = bounds.left + margin
        val xR = bounds.right - margin
        return if (xR > xL + 10f) Pair(xL, xR) else null
    }
}
