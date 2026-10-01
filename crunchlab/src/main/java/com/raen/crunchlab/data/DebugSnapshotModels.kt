package com.raen.crunchlab.data

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.raen.crunchlab.engine.BubbleMask
import org.json.JSONArray
import org.json.JSONObject

enum class TextCategory {
    BUBBLED,
    ORPHAN,
    SFX
}

enum class TextOrientation {
    VERTICAL,
    HORIZONTAL
}

data class TextLineItem(
    val id: Int,
    val rect: Rect,
    val angle: Float = 0f,
    val confidence: Float = 1.0f,
    val category: TextCategory = TextCategory.BUBBLED,
    val orientation: TextOrientation = TextOrientation.VERTICAL,
    val isFurigana: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("l", rect.left)
        put("t", rect.top)
        put("r", rect.right)
        put("b", rect.bottom)
        put("angle", angle.toDouble())
        put("conf", confidence.toDouble())
        put("cat", category.name)
        put("ori", orientation.name)
        put("furi", isFurigana)
    }

    companion object {
        fun fromJson(obj: JSONObject): TextLineItem = TextLineItem(
            id = obj.optInt("id", 0),
            rect = Rect(obj.getInt("l"), obj.getInt("t"), obj.getInt("r"), obj.getInt("b")),
            angle = obj.optDouble("angle", 0.0).toFloat(),
            confidence = obj.optDouble("conf", 1.0).toFloat(),
            category = try {
                TextCategory.valueOf(obj.optString("cat", TextCategory.BUBBLED.name))
            } catch (_: Exception) { TextCategory.BUBBLED },
            orientation = try {
                TextOrientation.valueOf(obj.optString("ori", TextOrientation.VERTICAL.name))
            } catch (_: Exception) { TextOrientation.VERTICAL },
            isFurigana = obj.optBoolean("furi", false)
        )
    }
}

data class ReadingGroupItem(
    val groupId: Int,
    val readingOrder: Int, // 1, 2, 3...
    val lineIds: List<Int>,
    val boundingBox: Rect
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("groupId", groupId)
        put("readingOrder", readingOrder)
        put("lineIds", JSONArray(lineIds))
        put("l", boundingBox.left)
        put("t", boundingBox.top)
        put("r", boundingBox.right)
        put("b", boundingBox.bottom)
    }

    companion object {
        fun fromJson(obj: JSONObject): ReadingGroupItem {
            val arr = obj.getJSONArray("lineIds")
            val ids = mutableListOf<Int>()
            for (i in 0 until arr.length()) ids.add(arr.getInt(i))
            return ReadingGroupItem(
                groupId = obj.getInt("groupId"),
                readingOrder = obj.optInt("readingOrder", 1),
                lineIds = ids,
                boundingBox = Rect(obj.getInt("l"), obj.getInt("t"), obj.getInt("r"), obj.getInt("b"))
            )
        }
    }
}

data class DialogueLineItem(
    val lineId: Int,
    val rect: Rect,
    val readingOrder: Int, // 1, 2, 3... within group (RTL order)
    val recognizedText: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lineId", lineId)
        put("l", rect.left)
        put("t", rect.top)
        put("r", rect.right)
        put("b", rect.bottom)
        put("order", readingOrder)
        if (recognizedText.isNotBlank()) put("text", recognizedText)
    }

    companion object {
        fun fromJson(obj: JSONObject): DialogueLineItem = DialogueLineItem(
            lineId = obj.getInt("lineId"),
            rect = Rect(obj.getInt("l"), obj.getInt("t"), obj.getInt("r"), obj.getInt("b")),
            readingOrder = obj.optInt("order", 1),
            recognizedText = obj.optString("text", "")
        )
    }
}

data class DialogueGroupItem(
    val groupId: Int,
    val isBubble: Boolean,
    val bubbleIndex: Int?,
    val bounds: Rect,
    val lines: List<DialogueLineItem>,
    val category: TextCategory,
    val contourPoints: List<Point> = emptyList(),
    val recognizedText: String = "",
    val translatedText: String = "",
    val translationEngine: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("groupId", groupId)
        put("isBubble", isBubble)
        if (bubbleIndex != null) put("bubbleIndex", bubbleIndex)
        put("l", bounds.left)
        put("t", bounds.top)
        put("r", bounds.right)
        put("b", bounds.bottom)
        put("category", category.name)
        val lArr = JSONArray()
        lines.forEach { lArr.put(it.toJson()) }
        put("lines", lArr)
        if (contourPoints.isNotEmpty()) {
            val cArr = JSONArray()
            contourPoints.forEach { pt ->
                cArr.put(JSONObject().apply { put("x", pt.x); put("y", pt.y) })
            }
            put("contour", cArr)
        }
        if (recognizedText.isNotEmpty()) put("text", recognizedText)
        if (translatedText.isNotEmpty()) put("translation", translatedText)
        if (translationEngine.isNotEmpty()) put("engine", translationEngine)
    }

    companion object {
        fun fromJson(obj: JSONObject): DialogueGroupItem {
            val lArr = obj.optJSONArray("lines") ?: JSONArray()
            val linesList = mutableListOf<DialogueLineItem>()
            for (i in 0 until lArr.length()) {
                linesList.add(DialogueLineItem.fromJson(lArr.getJSONObject(i)))
            }
            val cArr = obj.optJSONArray("contour")
            val cList = mutableListOf<Point>()
            if (cArr != null) {
                for (i in 0 until cArr.length()) {
                    val o = cArr.optJSONObject(i) ?: continue
                    cList.add(Point(o.getInt("x"), o.getInt("y")))
                }
            }
            return DialogueGroupItem(
                groupId = obj.getInt("groupId"),
                isBubble = obj.optBoolean("isBubble", true),
                bubbleIndex = if (obj.has("bubbleIndex")) obj.getInt("bubbleIndex") else null,
                bounds = Rect(obj.getInt("l"), obj.getInt("t"), obj.getInt("r"), obj.getInt("b")),
                lines = linesList,
                category = try {
                    TextCategory.valueOf(obj.optString("category", TextCategory.BUBBLED.name))
                } catch (_: Exception) { TextCategory.BUBBLED },
                contourPoints = cList,
                recognizedText = obj.optString("text", ""),
                translatedText = obj.optString("translation", ""),
                translationEngine = obj.optString("engine", "")
            )
        }
    }
}

data class OcrCropDebugItem(
    val index: Int,
    val groupId: Int,
    val rect: Rect,
    val cropBitmap: Bitmap?,
    val rawText: String,
    val isBubble: Boolean = true,
    val lineId: Int? = null,
    val readingOrder: Int? = null,
    val durationMs: Long = 0L,
)

data class TypesetLineItem(
    val text: String,
    val y: Float,
    val xCenter: Float,
    val chordWidth: Float,
    val scaleX: Float = 1.0f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("y", y.toDouble())
        put("xc", xCenter.toDouble())
        put("cw", chordWidth.toDouble())
        put("sx", scaleX.toDouble())
    }

    companion object {
        fun fromJson(obj: JSONObject): TypesetLineItem = TypesetLineItem(
            text = obj.getString("text"),
            y = obj.getDouble("y").toFloat(),
            xCenter = obj.getDouble("xc").toFloat(),
            chordWidth = obj.getDouble("cw").toFloat(),
            scaleX = obj.optDouble("sx", 1.0).toFloat(),
        )
    }
}

data class TypesetBlockItem(
    val groupId: Int,
    val isBubble: Boolean,
    val bounds: Rect,
    val fontSize: Float,
    val lines: List<TypesetLineItem>,
    val originalJapanese: String = "",
    val translatedEnglish: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("groupId", groupId)
        put("isBubble", isBubble)
        put("l", bounds.left)
        put("t", bounds.top)
        put("r", bounds.right)
        put("b", bounds.bottom)
        put("fontSize", fontSize.toDouble())
        put("ja", originalJapanese)
        put("en", translatedEnglish)
        val lArr = JSONArray()
        lines.forEach { lArr.put(it.toJson()) }
        put("lines", lArr)
    }

    companion object {
        fun fromJson(obj: JSONObject): TypesetBlockItem {
            val lArr = obj.optJSONArray("lines") ?: JSONArray()
            val lList = mutableListOf<TypesetLineItem>()
            for (i in 0 until lArr.length()) lList.add(TypesetLineItem.fromJson(lArr.getJSONObject(i)))
            return TypesetBlockItem(
                groupId = obj.getInt("groupId"),
                isBubble = obj.optBoolean("isBubble", true),
                bounds = Rect(obj.getInt("l"), obj.getInt("t"), obj.getInt("r"), obj.getInt("b")),
                fontSize = obj.getDouble("fontSize").toFloat(),
                lines = lList,
                originalJapanese = obj.optString("ja", ""),
                translatedEnglish = obj.optString("en", ""),
            )
        }
    }
}

data class CrunchPartitionItem(
    val bubbleIndex: Int,
    val bubbleRect: Rect,
    val isConjoined: Boolean,
    val confConj: Float,
    val p1Raw: Point?,
    val p2Raw: Point?,
    val p1Snapped: Point?,
    val p2Snapped: Point?,
    val candidatePoints: List<Point> = emptyList(),
    val lobeALineIds: List<Int> = emptyList(),
    val lobeBLineIds: List<Int> = emptyList(),
    val isCutRejected: Boolean = false,
    val rejectionReason: String? = null,
    val splitLines: List<TextLineItem> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("bubbleIndex", bubbleIndex)
        put("bl", bubbleRect.left)
        put("bt", bubbleRect.top)
        put("br", bubbleRect.right)
        put("bb", bubbleRect.bottom)
        put("isConjoined", isConjoined)
        put("confConj", confConj.toDouble())
        if (p1Raw != null) { put("p1rx", p1Raw.x); put("p1ry", p1Raw.y) }
        if (p2Raw != null) { put("p2rx", p2Raw.x); put("p2ry", p2Raw.y) }
        if (p1Snapped != null) { put("p1sx", p1Snapped.x); put("p1sy", p1Snapped.y) }
        if (p2Snapped != null) { put("p2sx", p2Snapped.x); put("p2sy", p2Snapped.y) }
        if (candidatePoints.isNotEmpty()) {
            val cpArr = JSONArray()
            candidatePoints.forEach { pt ->
                cpArr.put(JSONObject().apply { put("x", pt.x); put("y", pt.y) })
            }
            put("candidates", cpArr)
        }
        put("lobeA", JSONArray(lobeALineIds))
        put("lobeB", JSONArray(lobeBLineIds))
        put("isCutRejected", isCutRejected)
        if (rejectionReason != null) put("reason", rejectionReason)
        if (splitLines.isNotEmpty()) {
            val slArr = JSONArray()
            splitLines.forEach { slArr.put(it.toJson()) }
            put("splitLines", slArr)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): CrunchPartitionItem {
            fun readPoint(kx: String, ky: String): Point? {
                return if (obj.has(kx) && obj.has(ky)) Point(obj.getInt(kx), obj.getInt(ky)) else null
            }
            fun readList(k: String): List<Int> {
                val arr = obj.optJSONArray(k) ?: return emptyList()
                val list = mutableListOf<Int>()
                for (i in 0 until arr.length()) list.add(arr.getInt(i))
                return list
            }
            val splitLinesList = mutableListOf<TextLineItem>()
            val slArr = obj.optJSONArray("splitLines")
            if (slArr != null) {
                for (i in 0 until slArr.length()) {
                    splitLinesList.add(TextLineItem.fromJson(slArr.getJSONObject(i)))
                }
            }
            val cPoints = mutableListOf<Point>()
            val cpArr = obj.optJSONArray("candidates")
            if (cpArr != null) {
                for (i in 0 until cpArr.length()) {
                    val o = cpArr.optJSONObject(i) ?: continue
                    cPoints.add(Point(o.getInt("x"), o.getInt("y")))
                }
            }
            return CrunchPartitionItem(
                bubbleIndex = obj.getInt("bubbleIndex"),
                bubbleRect = Rect(obj.getInt("bl"), obj.getInt("bt"), obj.getInt("br"), obj.getInt("bb")),
                isConjoined = obj.getBoolean("isConjoined"),
                confConj = obj.optDouble("confConj", 0.0).toFloat(),
                p1Raw = readPoint("p1rx", "p1ry"),
                p2Raw = readPoint("p2rx", "p2ry"),
                p1Snapped = readPoint("p1sx", "p1sy"),
                p2Snapped = readPoint("p2sx", "p2sy"),
                candidatePoints = cPoints,
                lobeALineIds = readList("lobeA"),
                lobeBLineIds = readList("lobeB"),
                isCutRejected = obj.optBoolean("isCutRejected", false),
                rejectionReason = if (obj.has("reason")) obj.getString("reason") else null,
                splitLines = splitLinesList,
            )
        }
    }
}

data class DebugModuleSnapshot(
    val slotName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val module1Lines: List<TextLineItem> = emptyList(),
    val module1Bubbles: List<Rect> = emptyList(),
    val module1Pass2Boxes: List<Rect> = emptyList(),
    val module1_5CategorizedBoxes: List<TextLineItem> = emptyList(),
    val module2VerticalLines: List<TextLineItem> = emptyList(),
    val module2ConjoinedSplitBubbles: List<Rect> = emptyList(),
    val module2Groups: List<ReadingGroupItem> = emptyList(),
    val module3Partitions: List<CrunchPartitionItem> = emptyList(),
    val module4DialogueGroups: List<DialogueGroupItem> = emptyList(),
    val module5DialogueGroups: List<DialogueGroupItem> = emptyList(),
    val module5EngineType: String = "",
) {
    fun toJson(): String {
        val root = JSONObject().apply {
            put("slotName", slotName)
            put("timestamp", timestamp)
            put("m1", JSONArray().apply { module1Lines.forEach { put(it.toJson()) } })
            put("m1Bubbles", JSONArray().apply {
                module1Bubbles.forEach { r ->
                    put(JSONObject().apply { put("l", r.left); put("t", r.top); put("r", r.right); put("b", r.bottom) })
                }
            })
            put("m1Pass2", JSONArray().apply {
                module1Pass2Boxes.forEach { r ->
                    put(JSONObject().apply { put("l", r.left); put("t", r.top); put("r", r.right); put("b", r.bottom) })
                }
            })
            put("m1_5", JSONArray().apply { module1_5CategorizedBoxes.forEach { put(it.toJson()) } })
            put("m2Lines", JSONArray().apply { module2VerticalLines.forEach { put(it.toJson()) } })
            put("m2SplitBubbles", JSONArray().apply {
                module2ConjoinedSplitBubbles.forEach { r ->
                    put(JSONObject().apply { put("l", r.left); put("t", r.top); put("r", r.right); put("b", r.bottom) })
                }
            })
            put("m2", JSONArray().apply { module2Groups.forEach { put(it.toJson()) } })
            put("m3", JSONArray().apply { module3Partitions.forEach { put(it.toJson()) } })
            put("m4Groups", JSONArray().apply { module4DialogueGroups.forEach { put(it.toJson()) } })
            put("m5Groups", JSONArray().apply { module5DialogueGroups.forEach { put(it.toJson()) } })
            if (module5EngineType.isNotEmpty()) put("m5Engine", module5EngineType)
        }
        return root.toString(2)
    }

    companion object {
        private fun readRectList(root: JSONObject, key: String): List<Rect> {
            val arr = root.optJSONArray(key) ?: return emptyList()
            val list = mutableListOf<Rect>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Rect(o.getInt("l"), o.getInt("t"), o.getInt("r"), o.getInt("b")))
            }
            return list
        }

        fun fromJson(jsonStr: String): DebugModuleSnapshot {
            val root = JSONObject(jsonStr)
            val slot = root.optString("slotName", "snapshot")
            val ts = root.optLong("timestamp", System.currentTimeMillis())

            val m1Arr = root.optJSONArray("m1") ?: JSONArray()
            val m1 = mutableListOf<TextLineItem>()
            for (i in 0 until m1Arr.length()) m1.add(TextLineItem.fromJson(m1Arr.getJSONObject(i)))

            val m1Bubbles = readRectList(root, "m1Bubbles")
            val m1Pass2 = readRectList(root, "m1Pass2")

            val m1_5Arr = root.optJSONArray("m1_5") ?: JSONArray()
            val m1_5 = mutableListOf<TextLineItem>()
            for (i in 0 until m1_5Arr.length()) m1_5.add(TextLineItem.fromJson(m1_5Arr.getJSONObject(i)))

            val m2LinesArr = root.optJSONArray("m2Lines") ?: JSONArray()
            val m2Lines = mutableListOf<TextLineItem>()
            for (i in 0 until m2LinesArr.length()) m2Lines.add(TextLineItem.fromJson(m2LinesArr.getJSONObject(i)))

            val m2SplitBubbles = readRectList(root, "m2SplitBubbles")

            val m2Arr = root.optJSONArray("m2") ?: JSONArray()
            val m2 = mutableListOf<ReadingGroupItem>()
            for (i in 0 until m2Arr.length()) m2.add(ReadingGroupItem.fromJson(m2Arr.getJSONObject(i)))

            val m3Arr = root.optJSONArray("m3") ?: JSONArray()
            val m3 = mutableListOf<CrunchPartitionItem>()
            for (i in 0 until m3Arr.length()) m3.add(CrunchPartitionItem.fromJson(m3Arr.getJSONObject(i)))

            val m4Arr = root.optJSONArray("m4Groups") ?: JSONArray()
            val m4 = mutableListOf<DialogueGroupItem>()
            for (i in 0 until m4Arr.length()) m4.add(DialogueGroupItem.fromJson(m4Arr.getJSONObject(i)))

            val m5Arr = root.optJSONArray("m5Groups") ?: JSONArray()
            val m5 = mutableListOf<DialogueGroupItem>()
            for (i in 0 until m5Arr.length()) m5.add(DialogueGroupItem.fromJson(m5Arr.getJSONObject(i)))
            val m5Eng = root.optString("m5Engine", "")

            return DebugModuleSnapshot(
                slotName = slot,
                timestamp = ts,
                module1Lines = m1,
                module1Bubbles = m1Bubbles,
                module1Pass2Boxes = m1Pass2,
                module1_5CategorizedBoxes = m1_5,
                module2VerticalLines = if (m2Lines.isNotEmpty()) m2Lines else m2.mapIndexed { idx, g -> TextLineItem(idx + 1, g.boundingBox, 0f, 1f) },
                module2ConjoinedSplitBubbles = m2SplitBubbles,
                module2Groups = m2,
                module3Partitions = m3,
                module4DialogueGroups = m4,
                module5DialogueGroups = m5,
                module5EngineType = m5Eng,
            )
        }
    }
}

data class ProcessedPage(
    val index: Int,
    val label: String,
    val sourceBitmap: Bitmap,
    val isolatedBitmap: Bitmap? = null,
    val detectedMasks: List<BubbleMask> = emptyList(),
    // Module 1 Data:
    val m1TextProb: FloatArray? = null,
    val m1Lines: List<TextLineItem> = emptyList(),
    val m1Bubbles: List<Rect> = emptyList(),
    var m1HeatmapBitmap: Bitmap? = null,
    val m1Pass2Boxes: List<Rect> = emptyList(),
    var m1Pass2Bitmap: Bitmap? = null,
    val m1Pass2PassedBoxes: List<Rect> = emptyList(),
    val m1Pass2RejectedBoxes: List<Rect> = emptyList(),
    val m1Pass2Texts: Map<Rect, String> = emptyMap(),
    val m1DurationMs: Long = 0L,
    // Module 1.5 Data (Categorization):
    val m1_5CategorizedBoxes: List<TextLineItem> = emptyList(),
    val m1_5BubbledCount: Int = 0,
    val m1_5OrphanCount: Int = 0,
    val m1_5SfxCount: Int = 0,
    val m1_5DurationMs: Long = 0L,
    // Module 2 Data (Single Vertical Lines):
    val m2VerticalLines: List<TextLineItem> = emptyList(),
    val m2ConjoinedSplitBubbles: List<Rect> = emptyList(),
    val m2SuppressedFuriganaCount: Int = 0,
    val m2DurationMs: Long = 0L,
    // Module 3 Data:
    val m3Partitions: List<CrunchPartitionItem> = emptyList(),
    val m3DurationMs: Long = 0L,
    // Module 4 Data (Data Prepare & MangaOCR):
    val m4DialogueGroups: List<DialogueGroupItem> = emptyList(),
    val m4OcrBlocks: List<TranslationBlock> = emptyList(),
    val m4OcrCrops: List<OcrCropDebugItem> = emptyList(),
    val m4PrepDurationMs: Long = 0L,
    val m4OcrDurationMs: Long = 0L,
    // Module 5 Data (Translation):
    val m5TranslatedGroups: List<DialogueGroupItem> = emptyList(),
    val m5EngineType: String = "",
    val m5DurationMs: Long = 0L,
    // Module 6 Data (Inpainting & Typesetting):
    var m6CleanBitmap: Bitmap? = null,
    val m6TypesetBlocks: List<TypesetBlockItem> = emptyList(),
    var m6FinalBitmap: Bitmap? = null,
    val m6InpaintDurationMs: Long = 0L,
    val m6TypesetDurationMs: Long = 0L,
) {
    val totalDurationMs: Long
        get() = m1DurationMs + m1_5DurationMs + m2DurationMs + m3DurationMs +
                m4PrepDurationMs + m4OcrDurationMs + m5DurationMs +
                m6InpaintDurationMs + m6TypesetDurationMs
}
