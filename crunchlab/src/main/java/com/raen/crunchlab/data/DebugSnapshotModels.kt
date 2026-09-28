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
    val module3Partitions: List<CrunchPartitionItem> = emptyList()
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
                module3Partitions = m3
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
    // Module 1.5 Data (Categorization):
    val m1_5CategorizedBoxes: List<TextLineItem> = emptyList(),
    val m1_5BubbledCount: Int = 0,
    val m1_5OrphanCount: Int = 0,
    val m1_5SfxCount: Int = 0,
    // Module 2 Data (Single Vertical Lines):
    val m2VerticalLines: List<TextLineItem> = emptyList(),
    val m2ConjoinedSplitBubbles: List<Rect> = emptyList(),
    val m2SuppressedFuriganaCount: Int = 0,
    // Module 3 Data:
    val m3Partitions: List<CrunchPartitionItem> = emptyList()
)
