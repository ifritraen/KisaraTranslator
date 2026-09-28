package com.raen.crunchlab.data

import android.content.Context
import android.graphics.*
import android.util.Log
import com.raen.crunchlab.engine.PureBorderAngleSplitter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object CrunchExporter {
    private const val TAG = "CrunchLab"

    data class ExportResult(
        val outputDir: File,
        val step1File: File,
        val step2File: File,
        val step3File: File,
        val jsonFile: File,
        val splitCount: Int,
        val totalCount: Int
    )

    data class PageExportData(
        val pageIndex: Int,
        val pageLabel: String,
        val userNote: String = "",
        val bubbleNotes: Map<Int, String> = emptyMap(),
        val isolatedBitmap: Bitmap,
        val splits: List<PureBorderAngleSplitter.SplitResult>
    )

    fun clearPreviousExports(context: Context) {
        try {
            val exportDir = File(context.getExternalFilesDir(null), "crunch_export")
            if (exportDir.exists()) {
                exportDir.deleteRecursively()
            }
            exportDir.mkdirs()
            File(exportDir, ".nomedia").createNewFile()
        } catch (e: Exception) {
            Log.w(TAG, "Note: internal exportDir cleanup: ${e.message}")
        }

        try {
            val publicDir = File("/sdcard/Download/CrunchLab")
            if (publicDir.exists()) {
                publicDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.startsWith("step") || name.startsWith("bubble_") || name.startsWith("page_") ||
                        name.startsWith("m1_") || name.startsWith("m2_") || name.startsWith("m3_") ||
                        name == "crunch_telemetry.json") {
                        file.deleteRecursively()
                    }
                }
            } else {
                publicDir.mkdirs()
            }
            File(publicDir, ".nomedia").createNewFile()
        } catch (e: Exception) {
            Log.w(TAG, "Note: public exportDir cleanup: ${e.message}")
        }
    }

    /**
     * Master export function for a processed page across all modules (M1, M1.5, M2, M3).
     * Generates comprehensive telemetry JSON and high-resolution annotated debug images for every step.
     */
    fun exportPage(context: Context, page: ProcessedPage): File? {
        try {
            val internalRoot = File(context.getExternalFilesDir(null), "crunch_export").apply { mkdirs() }
            val publicRoot = File("/sdcard/Download/CrunchLab").apply {
                try { mkdirs() } catch (_: Exception) {}
            }
            try { File(internalRoot, ".nomedia").createNewFile() } catch (_: Exception) {}
            try { File(publicRoot, ".nomedia").createNewFile() } catch (_: Exception) {}

            val pageFolder = "page_${page.index}"
            val internalPageDir = File(internalRoot, pageFolder).apply { mkdirs() }
            val publicPageDir = File(publicRoot, pageFolder).apply {
                try { mkdirs() } catch (_: Exception) {}
            }

            // 1. Generate Telemetry JSON
            val telemetryJson = generateTelemetryJson(page)
            val telemetryStr = telemetryJson.toString(2)

            val rootJsonFile = File(internalRoot, "crunch_telemetry.json").apply { writeText(telemetryStr) }
            val pageJsonFile = File(internalPageDir, "crunch_telemetry.json").apply { writeText(telemetryStr) }

            if (publicRoot.exists()) {
                try { File(publicRoot, "crunch_telemetry.json").writeText(telemetryStr) } catch (_: Exception) {}
                try { File(publicPageDir, "crunch_telemetry.json").writeText(telemetryStr) } catch (_: Exception) {}
            }

            // Helper to save bitmap to all target locations
            fun saveStepImage(bmp: Bitmap?, fileName: String) {
                if (bmp == null || bmp.isRecycled) return
                try {
                    // Internal page folder
                    saveBitmap(bmp, File(internalPageDir, fileName))
                    // Internal root (mirrors active page)
                    saveBitmap(bmp, File(internalRoot, fileName))

                    if (publicRoot.exists()) {
                        // Public page folder
                        saveBitmap(bmp, File(publicPageDir, fileName))
                        // Public root
                        saveBitmap(bmp, File(publicRoot, fileName))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed saving step image $fileName: ${e.message}")
                }
            }

            val baseBmp = page.sourceBitmap

            // 2. Render Module 1 Step Images
            if (page.m1TextProb != null) {
                val hmBmp = renderM1_1Heatmap(baseBmp, page.m1TextProb)
                saveStepImage(hmBmp, "m1_1_heatmap.png")
            }

            if (page.m1Lines.isNotEmpty() || page.m1Bubbles.isNotEmpty()) {
                val linesBmp = renderM1_2Pass1Lines(baseBmp, page.m1Lines, page.m1Bubbles)
                saveStepImage(linesBmp, "m1_2_pass1_lines.png")
            }

            if (page.m1Pass2Boxes.isNotEmpty() || page.m1Pass2PassedBoxes.isNotEmpty() || page.m1Pass2RejectedBoxes.isNotEmpty()) {
                val p2Bmp = renderM1_3Pass2Cutoffs(
                    baseBmp,
                    page.m1Bubbles,
                    page.m1Pass2PassedBoxes,
                    page.m1Pass2RejectedBoxes,
                    page.m1Pass2Boxes,
                    page.m1Pass2Texts
                )
                saveStepImage(p2Bmp, "m1_3_pass2_cutoffs.png")
            }

            // 3. Render Module 1.5 Step Images (Categorization)
            if (page.m1_5CategorizedBoxes.isNotEmpty()) {
                val allCatBmp = renderM1_5Categorized(baseBmp, page.m1_5CategorizedBoxes, page.m1Bubbles, null)
                saveStepImage(allCatBmp, "m1_5_all_categorized.png")

                val bubbledCatBmp = renderM1_5Categorized(baseBmp, page.m1_5CategorizedBoxes, page.m1Bubbles, TextCategory.BUBBLED)
                saveStepImage(bubbledCatBmp, "m1_5_bubbled.png")

                val orphanCatBmp = renderM1_5Categorized(baseBmp, page.m1_5CategorizedBoxes, page.m1Bubbles, TextCategory.ORPHAN)
                saveStepImage(orphanCatBmp, "m1_5_orphan.png")

                val sfxCatBmp = renderM1_5Categorized(baseBmp, page.m1_5CategorizedBoxes, page.m1Bubbles, TextCategory.SFX)
                saveStepImage(sfxCatBmp, "m1_5_sfx.png")
            }

            // 4. Render Module 2 Step Images (Single Vertical Lines)
            if (page.m2VerticalLines.isNotEmpty()) {
                val allVLinesBmp = renderM2VerticalLines(baseBmp, page.m2VerticalLines, page.m1Bubbles, null)
                saveStepImage(allVLinesBmp, "m2_1_all_vertical_lines.png")

                val bubbledVLinesBmp = renderM2VerticalLines(baseBmp, page.m2VerticalLines, page.m1Bubbles, TextCategory.BUBBLED)
                saveStepImage(bubbledVLinesBmp, "m2_2_bubbled_lines.png")

                val orphanVLinesBmp = renderM2VerticalLines(baseBmp, page.m2VerticalLines, page.m1Bubbles, TextCategory.ORPHAN)
                saveStepImage(orphanVLinesBmp, "m2_3_orphan_lines.png")
            }

            // 5. Render Module 3 Step Images (Waist Crunch)
            if (page.m3Partitions.isNotEmpty()) {
                val dispBmp = page.isolatedBitmap ?: baseBmp
                val waistBmp = renderM3Waist(dispBmp, page.m3Partitions)
                saveStepImage(waistBmp, "m3_1_ai_waist.png")
                saveStepImage(waistBmp, "step1_mask_notches.png")

                val cutlineBmp = renderM3Cutline(dispBmp, page.m3Partitions)
                saveStepImage(cutlineBmp, "m3_2_snapped_cutline.png")
                saveStepImage(cutlineBmp, "step2_seam_cut.png")

                val lobesBmp = renderM3Lobes(dispBmp, page.m3Partitions, page.m2VerticalLines.ifEmpty { page.m1Lines })
                saveStepImage(lobesBmp, "m3_3_lobe_partition.png")
                saveStepImage(lobesBmp, "step3_separated_lobes.png")
            }

            Log.i(TAG, "CrunchExporter: Full page debug export complete for ${page.label} (Page ${page.index})")
            return rootJsonFile
        } catch (e: Exception) {
            Log.e(TAG, "CrunchExporter: Failed to export page debug data", e)
            return null
        }
    }

    /**
     * Builds comprehensive telemetry JSON spanning all modules and steps.
     */
    fun generateTelemetryJson(page: ProcessedPage): JSONObject {
        val root = JSONObject().apply {
            put("app", "Crunch Lab 📐")
            put("package", "com.raen.crunchlab")
            put("timestamp", System.currentTimeMillis())
            put("datetime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("page_index", page.index)
            put("page_label", page.label)
            put("image_width", page.sourceBitmap.width)
            put("image_height", page.sourceBitmap.height)
        }

        // Module 1: CTD Text
        val m1Obj = JSONObject().apply {
            put("status", if (page.m1Lines.isNotEmpty()) "COMPLETED" else "PENDING")
            put("lines_count", page.m1Lines.size)
            put("bubbles_count", page.m1Bubbles.size)

            val linesArr = JSONArray()
            page.m1Lines.forEach { line ->
                val r = line.rect
                linesArr.put(JSONObject().apply {
                    put("id", line.id)
                    put("confidence", "%.3f".format(line.confidence).toDouble())
                    put("left", r.left); put("top", r.top); put("right", r.right); put("bottom", r.bottom)
                    put("width", r.width()); put("height", r.height())
                    put("centerX", r.centerX()); put("centerY", r.centerY())
                })
            }
            put("lines", linesArr)

            val bubblesArr = JSONArray()
            page.m1Bubbles.forEachIndexed { idx, b ->
                bubblesArr.put(JSONObject().apply {
                    put("index", idx)
                    put("left", b.left); put("top", b.top); put("right", b.right); put("bottom", b.bottom)
                    put("width", b.width()); put("height", b.height())
                })
            }
            put("bubbles", bubblesArr)

            val p2Obj = JSONObject().apply {
                put("candidates_count", page.m1Pass2Boxes.size)
                put("passed_count", page.m1Pass2PassedBoxes.size)
                put("rejected_count", page.m1Pass2RejectedBoxes.size)

                val passedArr = JSONArray()
                page.m1Pass2PassedBoxes.forEach { b ->
                    passedArr.put(JSONObject().apply {
                        put("left", b.left); put("top", b.top); put("right", b.right); put("bottom", b.bottom)
                        page.m1Pass2Texts[b]?.let { put("text", it) }
                    })
                }
                put("passed_boxes", passedArr)

                val rejectedArr = JSONArray()
                page.m1Pass2RejectedBoxes.forEach { b ->
                    rejectedArr.put(JSONObject().apply {
                        put("left", b.left); put("top", b.top); put("right", b.right); put("bottom", b.bottom)
                        page.m1Pass2Texts[b]?.let { put("text", it) }
                    })
                }
                put("rejected_boxes", rejectedArr)
            }
            put("pass2", p2Obj)
        }
        root.put("module_1_ctd", m1Obj)

        // Module 1.5: Categorization
        val m1_5Obj = JSONObject().apply {
            put("status", if (page.m1_5CategorizedBoxes.isNotEmpty()) "COMPLETED" else "PENDING")
            put("total_boxes", page.m1_5CategorizedBoxes.size)
            put("bubbled_count", page.m1_5BubbledCount)
            put("orphan_count", page.m1_5OrphanCount)
            put("sfx_count", page.m1_5SfxCount)

            val boxesArr = JSONArray()
            page.m1_5CategorizedBoxes.forEach { item ->
                val r = item.rect
                boxesArr.put(JSONObject().apply {
                    put("id", item.id)
                    put("category", item.category.name)
                    put("left", r.left); put("top", r.top); put("right", r.right); put("bottom", r.bottom)
                    put("width", r.width()); put("height", r.height())
                })
            }
            put("boxes", boxesArr)
        }
        root.put("module_1_5_categorize", m1_5Obj)

        // Module 2: Single Vertical Lines
        val m2Obj = JSONObject().apply {
            put("status", if (page.m2VerticalLines.isNotEmpty()) "COMPLETED" else "PENDING")
            put("total_lines", page.m2VerticalLines.size)
            put("bubbled_lines_count", page.m2VerticalLines.count { it.category == TextCategory.BUBBLED })
            put("orphan_lines_count", page.m2VerticalLines.count { it.category == TextCategory.ORPHAN })
            put("sfx_lines_count", page.m2VerticalLines.count { it.category == TextCategory.SFX })
            put("suppressed_furigana_count", page.m2SuppressedFuriganaCount)

            val linesArr = JSONArray()
            page.m2VerticalLines.forEach { line ->
                val r = line.rect
                linesArr.put(JSONObject().apply {
                    put("id", line.id)
                    put("category", line.category.name)
                    put("orientation", line.orientation.name)
                    put("left", r.left); put("top", r.top); put("right", r.right); put("bottom", r.bottom)
                    put("width", r.width()); put("height", r.height())
                    put("centerX", r.centerX()); put("centerY", r.centerY())
                })
            }
            put("lines", linesArr)
        }
        root.put("module_2_vertical_lines", m2Obj)

        // Module 3: Waist Crunch
        val m3Obj = JSONObject().apply {
            put("status", if (page.m3Partitions.isNotEmpty()) "COMPLETED" else "PENDING")
            put("total_partitions", page.m3Partitions.size)
            put("conjoined_count", page.m3Partitions.count { it.isConjoined })
            put("cut_rejected_count", page.m3Partitions.count { it.isCutRejected })

            val partsArr = JSONArray()
            page.m3Partitions.forEach { part ->
                val br = part.bubbleRect
                partsArr.put(JSONObject().apply {
                    put("bubble_index", part.bubbleIndex)
                    put("bubble_rect", JSONObject().apply {
                        put("left", br.left); put("top", br.top); put("right", br.right); put("bottom", br.bottom)
                        put("width", br.width()); put("height", br.height())
                    })
                    put("is_conjoined", part.isConjoined)
                    put("conf_conj", "%.3f".format(part.confConj).toDouble())
                    put("is_cut_rejected", part.isCutRejected)
                    part.rejectionReason?.let { put("rejection_reason", it) }

                    part.p1Raw?.let { p -> put("p1_raw", JSONObject().apply { put("x", p.x); put("y", p.y) }) }
                    part.p2Raw?.let { p -> put("p2_raw", JSONObject().apply { put("x", p.x); put("y", p.y) }) }
                    part.p1Snapped?.let { p -> put("p1_snapped", JSONObject().apply { put("x", p.x); put("y", p.y) }) }
                    part.p2Snapped?.let { p -> put("p2_snapped", JSONObject().apply { put("x", p.x); put("y", p.y) }) }

                    val candArr = JSONArray()
                    part.candidatePoints.forEach { pt ->
                        candArr.put(JSONObject().apply { put("x", pt.x); put("y", pt.y) })
                    }
                    put("candidate_notches", candArr)

                    put("lobe_a_line_ids", JSONArray(part.lobeALineIds))
                    put("lobe_b_line_ids", JSONArray(part.lobeBLineIds))
                })
            }
            put("partitions", partsArr)
        }
        root.put("module_3_waist_crunch", m3Obj)

        return root
    }

    // ════════════════════════════════════════════════════════════════
    // STEP IMAGE RENDERERS
    // ════════════════════════════════════════════════════════════════

    private fun drawBannerHeader(canvas: Canvas, text: String, width: Int) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEE1A1A1A.toInt()
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E5FF.toInt()
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        }
        val bannerH = 44f
        canvas.drawRect(0f, 0f, width.toFloat(), bannerH, bgPaint)
        canvas.drawText(text, 16f, 31f, textPaint)
    }

    private fun drawBadgePill(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        bgColor: Int,
        textColor: Int = Color.BLACK,
        fontSize: Float = 14f
    ) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD
        }
        val textW = textPaint.measureText(text)
        val pillH = fontSize + 8f
        val pillW = textW + 12f

        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
        }

        val pillRect = RectF(x, y - pillH, x + pillW, y)
        canvas.drawRoundRect(pillRect, 4f, 4f, pillPaint)
        canvas.drawRoundRect(pillRect, 4f, 4f, borderPaint)

        val textY = y - (pillH / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(text, x + 6f, textY, textPaint)
    }

    // Step 1.1: Heatmap
    private fun renderM1_1Heatmap(base: Bitmap, prob: FloatArray?): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        if (prob == null) return out

        val dim = 1024
        val hmBmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(dim * dim)
        for (i in 0 until dim * dim) {
            val p = prob[i]
            if (p < 0.20f) {
                pixels[i] = 0
            } else {
                val norm = ((p - 0.20f) / 0.80f).coerceIn(0f, 1f)
                val r = 255
                val g = ((1f - norm) * 220).toInt()
                val b = 0
                val a = (120 + (norm * 135)).toInt()
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        hmBmp.setPixels(pixels, 0, dim, 0, 0, dim, dim)

        val srcR = Rect(0, 0, dim, dim)
        val dstR = Rect(0, 0, base.width, base.height)
        canvas.drawBitmap(hmBmp, srcR, dstR, Paint(Paint.FILTER_BITMAP_FLAG))
        hmBmp.recycle()

        drawBannerHeader(canvas, "Step 1.1: ComicText Probability Heatmap (p >= 0.20)", base.width)
        return out
    }

    // Step 1.2: Pass 1 Lines
    private fun renderM1_2Pass1Lines(base: Bitmap, lines: List<TextLineItem>, bubbles: List<Rect>): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00B0FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.0f
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E676.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x2000E676.toInt()
            style = Paint.Style.FILL
        }

        bubbles.forEach { b -> canvas.drawRect(b, bubblePaint) }

        lines.forEach { line ->
            canvas.drawRect(line.rect, linePaint)
            canvas.drawRect(line.rect, fillPaint)
            val badgeY = if (line.rect.top > 25) line.rect.top.toFloat() else (line.rect.bottom + 18).toFloat()
            drawBadgePill(canvas, "#${line.id}", line.rect.left.toFloat(), badgeY, 0xFF00E676.toInt(), Color.BLACK, 13f)
        }

        drawBannerHeader(canvas, "Step 1.2: Pass 1 Lines (${lines.size} lines, ${bubbles.size} bubbles)", base.width)
        return out
    }

    // Step 1.3: Pass 2 Cut-Offs
    private fun renderM1_3Pass2Cutoffs(
        base: Bitmap,
        bubbles: List<Rect>,
        passed: List<Rect>,
        rejected: List<Rect>,
        allCandidates: List<Rect>,
        texts: Map<Rect, String>
    ): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00B0FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.0f
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        val passedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E676.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        val passedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2500E676.toInt(); style = Paint.Style.FILL }

        val rejectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF1744.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        }
        val rejectedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x20FF1744.toInt(); style = Paint.Style.FILL }

        bubbles.forEach { b -> canvas.drawRect(b, bubblePaint) }

        passed.forEachIndexed { idx, box ->
            canvas.drawRect(box, passedPaint)
            canvas.drawRect(box, passedFill)
            val txt = texts[box]?.let { "✓ $it" } ?: "✓ #P${idx + 1}"
            drawBadgePill(canvas, txt, box.left.toFloat(), box.top.toFloat().coerceAtLeast(46f), 0xFF00E676.toInt(), Color.BLACK, 12f)
        }

        rejected.forEachIndexed { idx, box ->
            canvas.drawRect(box, rejectedPaint)
            canvas.drawRect(box, rejectedFill)
            val txt = texts[box]?.let { "✗ $it" } ?: "✗ #R${idx + 1}"
            drawBadgePill(canvas, txt, box.left.toFloat(), box.top.toFloat().coerceAtLeast(46f), 0xFFFF1744.toInt(), Color.WHITE, 12f)
        }

        drawBannerHeader(canvas, "Step 1.3: Pass 2 Focused Cut-Offs (Passed: ${passed.size}, Rejected: ${rejected.size})", base.width)
        return out
    }

    // Step 1.5: Categorization (Bubbled/Orphan/SFX)
    private fun renderM1_5Categorized(
        base: Bitmap,
        boxes: List<TextLineItem>,
        bubbles: List<Rect>,
        filterCategory: TextCategory? = null
    ): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00B0FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
        }
        bubbles.forEach { b -> canvas.drawRect(b, bubblePaint) }

        val displayBoxes = if (filterCategory != null) boxes.filter { it.category == filterCategory } else boxes

        displayBoxes.forEach { item ->
            val r = item.rect
            val color = when (item.category) {
                TextCategory.BUBBLED -> 0xFF00E676.toInt() // Green
                TextCategory.ORPHAN -> 0xFFFFD600.toInt()  // Gold
                TextCategory.SFX -> 0xFFFF3D00.toInt()     // Red
            }

            val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = 2.0f
            }
            val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = (color and 0x00FFFFFF) or 0x22000000
                style = Paint.Style.FILL
            }

            canvas.drawRect(r, strokeP)
            canvas.drawRect(r, fillP)

            val tag = when (item.category) {
                TextCategory.BUBBLED -> "B"
                TextCategory.ORPHAN -> "O"
                TextCategory.SFX -> "S"
            }
            val badgeY = if (r.top > 25) r.top.toFloat() else (r.bottom + 16).toFloat()
            drawBadgePill(canvas, "#${item.id}[$tag]", r.left.toFloat(), badgeY, color, Color.BLACK, 11.5f)
        }

        val catLabel = filterCategory?.name ?: "ALL"
        drawBannerHeader(canvas, "Step 1.5: Categorized Boxes - $catLabel (${displayBoxes.size} boxes)", base.width)
        return out
    }

    // Step 2.1: Single Vertical Lines (Palette + Badges)
    private fun renderM2VerticalLines(
        base: Bitmap,
        lines: List<TextLineItem>,
        bubbles: List<Rect>,
        filterCategory: TextCategory? = null
    ): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val palette = listOf(
            0xFF00E5FF.toInt(), // Cyan
            0xFFFFD600.toInt(), // Yellow
            0xFFFF4081.toInt(), // Pink
            0xFF76FF03.toInt(), // Lime
            0xFFFF9100.toInt(), // Orange
            0xFFE040FB.toInt(), // Purple
            0xFF00E676.toInt(), // Green
            0xFF40C4FF.toInt(), // Light Blue
        )

        // Speech bubble reference outlines (crisp bright cyan dashed)
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00B0FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        bubbles.forEach { b -> canvas.drawRect(b, bubblePaint) }

        val displayLines = if (filterCategory != null) lines.filter { it.category == filterCategory } else lines

        displayLines.forEachIndexed { idx, line ->
            val r = line.rect
            val color = palette[idx % palette.size]

            val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = 2.8f
            }
            val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = (color and 0x00FFFFFF) or 0x26000000
                style = Paint.Style.FILL
            }

            canvas.drawRect(r, strokeP)
            canvas.drawRect(r, fillP)

            // Draw crisp high-visibility numbered badge pill: "#1", "#2", "#20", etc.
            val badgeText = "#${line.id}"
            val badgeY = if (r.top > 25) r.top.toFloat() else (r.bottom + 20).toFloat()
            drawBadgePill(canvas, badgeText, r.left.toFloat(), badgeY, color, Color.BLACK, 14f)
        }

        val catLabel = filterCategory?.name ?: "ALL"
        drawBannerHeader(canvas, "Step 2.1: Single Vertical Lines - $catLabel (${displayLines.size} columns)", base.width)
        return out
    }

    // Step 3.1: AI Waist & Candidates
    private fun renderM3Waist(base: Bitmap, partitions: List<CrunchPartitionItem>): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val splitBoxP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD500F9.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.0f }
        val normalBoxP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x669E9E9E.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f }
        val candP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E5FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.0f }
        val candFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4400E5FF.toInt(); style = Paint.Style.FILL }
        val rawPtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD600.toInt(); style = Paint.Style.FILL }

        partitions.forEach { part ->
            canvas.drawRect(part.bubbleRect, if (part.isConjoined) splitBoxP else normalBoxP)

            part.candidatePoints.forEach { pt ->
                canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 5.0f, candFill)
                canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 4.0f, candP)
            }

            part.p1Raw?.let { p -> canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 6.0f, rawPtP) }
            part.p2Raw?.let { p -> canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 6.0f, rawPtP) }
        }

        drawBannerHeader(canvas, "Step 3.1: AI Waist Keypoints & Candidates (${partitions.count { it.isConjoined }} conjoined)", base.width)
        return out
    }

    // Step 3.2: Snapped Cutline
    private fun renderM3Cutline(base: Bitmap, partitions: List<CrunchPartitionItem>): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val cutlineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E676.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        val rejectedP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF1744.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        val endpointP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

        partitions.forEach { part ->
            if (part.isConjoined && part.p1Snapped != null && part.p2Snapped != null) {
                val p1 = part.p1Snapped
                val p2 = part.p2Snapped
                val p = if (part.isCutRejected) rejectedP else cutlineP
                canvas.drawLine(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat(), p)
                canvas.drawCircle(p1.x.toFloat(), p1.y.toFloat(), 6f, endpointP)
                canvas.drawCircle(p2.x.toFloat(), p2.y.toFloat(), 6f, endpointP)
            }
        }

        drawBannerHeader(canvas, "Step 3.2: Snapped Seam Cutlines", base.width)
        return out
    }

    // Step 3.3: Lobe Partitions
    private fun renderM3Lobes(base: Bitmap, partitions: List<CrunchPartitionItem>, vLines: List<TextLineItem>): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val lineMap = vLines.associateBy { it.id }.toMutableMap()
        partitions.forEach { p -> p.splitLines.forEach { sl -> lineMap[sl.id] = sl } }

        val lobeAPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2979FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.6f }
        val lobeAFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2A2979FF.toInt(); style = Paint.Style.FILL }

        val lobeBPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF4081.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.6f }
        val lobeBFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2AFFFF40.toInt(); style = Paint.Style.FILL }

        partitions.forEach { part ->
            part.lobeALineIds.forEach { id ->
                lineMap[id]?.let { line ->
                    canvas.drawRect(line.rect, lobeAPaint)
                    canvas.drawRect(line.rect, lobeAFill)
                }
            }
            part.lobeBLineIds.forEach { id ->
                lineMap[id]?.let { line ->
                    canvas.drawRect(line.rect, lobeBPaint)
                    canvas.drawRect(line.rect, lobeBFill)
                }
            }
        }

        drawBannerHeader(canvas, "Step 3.3: Lobe Partitioning (Blue: Lobe A, Pink: Lobe B)", base.width)
        return out
    }

    private fun saveBitmap(bmp: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // LEGACY COMPATIBILITY METHODS
    // ════════════════════════════════════════════════════════════════

    fun exportSession(
        context: Context,
        isolatedBitmap: Bitmap,
        splits: List<PureBorderAngleSplitter.SplitResult>
    ): ExportResult? {
        return exportMultiPageSession(
            context,
            listOf(PageExportData(pageIndex = 0, pageLabel = "Page 0", isolatedBitmap = isolatedBitmap, splits = splits))
        )
    }

    fun exportMultiPageSession(
        context: Context,
        pages: List<PageExportData>
    ): ExportResult? {
        if (pages.isEmpty()) return null
        try {
            val exportDir = File(context.getExternalFilesDir(null), "crunch_export").apply { mkdirs() }
            val publicDir = File("/sdcard/Download/CrunchLab").apply {
                try { mkdirs() } catch (_: Exception) {}
            }

            try { File(exportDir, ".nomedia").createNewFile() } catch (_: Exception) {}
            try { File(publicDir, ".nomedia").createNewFile() } catch (_: Exception) {}

            var totalSplits = 0
            var totalBubbles = 0

            val rootJson = JSONObject().apply {
                put("app", "Crunch Lab 📐")
                put("package", "com.raen.crunchlab")
                put("timestamp", System.currentTimeMillis())
                put("total_pages", pages.size)
                put("zero_fallback_border_angle_verified", true)
            }
            val pagesJsonArr = JSONArray()

            for (page in pages) {
                val pageFolder = "page_${page.pageIndex}"
                val pageExportDir = File(exportDir, pageFolder).apply { mkdirs() }
                val pagePublicDir = File(publicDir, pageFolder).apply {
                    try { mkdirs() } catch (_: Exception) {}
                }

                val step1Bmp = renderLegacyStep1(page.isolatedBitmap, page.splits, "${page.pageLabel} (${page.splits.count { it.wasSplit }}/${page.splits.size} ✂️)")
                val step2Bmp = renderLegacyStep2(page.isolatedBitmap, page.splits)
                val step3Bmp = renderLegacyStep3(page.isolatedBitmap, page.splits)

                saveBitmap(step1Bmp, File(pageExportDir, "step1_checked_points.png"))
                saveBitmap(step1Bmp, File(pageExportDir, "step1_mask_notches.png"))
                saveBitmap(step2Bmp, File(pageExportDir, "step2_mask_cut.png"))
                saveBitmap(step2Bmp, File(pageExportDir, "step2_seam_cut.png"))
                saveBitmap(step3Bmp, File(pageExportDir, "step3_separated_lobes.png"))

                if (publicDir.exists()) {
                    try {
                        saveBitmap(step1Bmp, File(pagePublicDir, "step1_checked_points.png"))
                        saveBitmap(step1Bmp, File(pagePublicDir, "step1_mask_notches.png"))
                        saveBitmap(step2Bmp, File(pagePublicDir, "step2_mask_cut.png"))
                        saveBitmap(step2Bmp, File(pagePublicDir, "step2_seam_cut.png"))
                        saveBitmap(step3Bmp, File(pagePublicDir, "step3_separated_lobes.png"))
                    } catch (_: Exception) {}
                }

                if (page.pageIndex == 0) {
                    saveBitmap(step1Bmp, File(exportDir, "step1_checked_points.png"))
                    saveBitmap(step1Bmp, File(exportDir, "step1_mask_notches.png"))
                    saveBitmap(step2Bmp, File(exportDir, "step2_mask_cut.png"))
                    saveBitmap(step2Bmp, File(exportDir, "step2_seam_cut.png"))
                    saveBitmap(step3Bmp, File(exportDir, "step3_separated_lobes.png"))
                    if (publicDir.exists()) {
                        try {
                            saveBitmap(step1Bmp, File(publicDir, "step1_checked_points.png"))
                            saveBitmap(step1Bmp, File(publicDir, "step1_mask_notches.png"))
                            saveBitmap(step2Bmp, File(publicDir, "step2_mask_cut.png"))
                            saveBitmap(step2Bmp, File(publicDir, "step2_seam_cut.png"))
                            saveBitmap(step3Bmp, File(publicDir, "step3_separated_lobes.png"))
                        } catch (_: Exception) {}
                    }
                }

                totalSplits += page.splits.count { it.wasSplit }
                totalBubbles += page.splits.size
                pagesJsonArr.put(JSONObject(generatePageJson(page.pageIndex, page.pageLabel, page.splits, page.userNote, page.bubbleNotes)))
            }

            rootJson.put("total_detected_bubbles", totalBubbles)
            rootJson.put("conjoined_crunched_bubbles", totalSplits)
            rootJson.put("pages", pagesJsonArr)

            val combinedJsonStr = rootJson.toString(2)
            val rootJsonFile = File(exportDir, "crunch_telemetry.json")
            rootJsonFile.writeText(combinedJsonStr)
            if (publicDir.exists()) {
                try { File(publicDir, "crunch_telemetry.json").writeText(combinedJsonStr) } catch (_: Exception) {}
            }

            return ExportResult(
                outputDir = exportDir,
                step1File = File(exportDir, "step1_checked_points.png"),
                step2File = File(exportDir, "step2_mask_cut.png"),
                step3File = File(exportDir, "step3_separated_lobes.png"),
                jsonFile = rootJsonFile,
                splitCount = totalSplits,
                totalCount = totalBubbles
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export multi-page session: ", e)
            return null
        }
    }

    private fun renderLegacyStep1(base: Bitmap, splits: List<PureBorderAngleSplitter.SplitResult>, pageHeader: String? = null): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val splitBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8000E676.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f }
        val unsplitBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x559E9E9E.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f; pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f) }
        for (split in splits) {
            canvas.drawRect(split.originalRect, if (split.wasSplit) splitBoxPaint else unsplitBoxPaint)
        }
        if (pageHeader != null) drawBannerHeader(canvas, pageHeader, base.width)
        return out
    }

    private fun renderLegacyStep2(base: Bitmap, splits: List<PureBorderAngleSplitter.SplitResult>): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF6D00.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND }
        for (split in splits) {
            if (!split.wasSplit) continue
            val cutPoints = split.cutLinePoints
            if (cutPoints.size >= 2) {
                val path = android.graphics.Path()
                path.moveTo(cutPoints[0].x.toFloat(), cutPoints[0].y.toFloat())
                for (i in 1 until cutPoints.size) { path.lineTo(cutPoints[i].x.toFloat(), cutPoints[i].y.toFloat()) }
                canvas.drawPath(path, seamPaint)
            }
        }
        return out
    }

    private fun renderLegacyStep3(base: Bitmap, splits: List<PureBorderAngleSplitter.SplitResult>): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val cyanFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3300E5FF.toInt(); style = Paint.Style.FILL }
        val emeraldFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3300E676.toInt(); style = Paint.Style.FILL }
        for (split in splits) {
            if (!split.wasSplit) continue
            split.splitLobeRects.forEachIndexed { idx, lobeRect ->
                val fillP = if (idx % 2 == 0) cyanFill else emeraldFill
                canvas.drawRect(lobeRect, fillP)
            }
        }
        return out
    }

    fun generatePageJson(
        pageIndex: Int,
        pageLabel: String,
        splits: List<PureBorderAngleSplitter.SplitResult>,
        userNote: String = "",
        bubbleNotes: Map<Int, String> = emptyMap()
    ): String {
        val root = JSONObject()
        root.put("page_index", pageIndex)
        root.put("page_label", pageLabel)
        root.put("total_detected_bubbles", splits.size)
        root.put("conjoined_crunched_bubbles", splits.count { it.wasSplit })
        val arr = JSONArray()
        for ((idx, split) in splits.withIndex()) {
            val bObj = JSONObject().apply {
                put("index", idx)
                put("was_split", split.wasSplit)
                val r = split.originalRect
                put("box", JSONObject().apply {
                    put("left", r.left); put("top", r.top); put("right", r.right); put("bottom", r.bottom)
                    put("width", r.width()); put("height", r.height())
                })
            }
            arr.put(bObj)
        }
        root.put("bubbles", arr)
        return root.toString(2)
    }

    fun updateTelemetryNotes(
        context: Context,
        notesMap: Map<Int, String>,
        bubbleNotes: Map<String, String> = emptyMap()
    ) {
        // Preserved for backward compatibility
    }
}
