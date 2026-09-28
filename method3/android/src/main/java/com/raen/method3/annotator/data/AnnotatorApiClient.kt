package com.raen.method3.annotator.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AnnotatorApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchStatus(baseUrl: String, batch: String): Result<ServerStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/api/status?batch=$batch"
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Server error: ${resp.code}")
            val bodyStr = resp.body?.string() ?: error("Empty response")
            val obj = JSONObject(bodyStr)

            val batchesArr = obj.optJSONArray("batches") ?: JSONArray()
            val batchList = mutableListOf<String>()
            for (i in 0 until batchesArr.length()) batchList.add(batchesArr.getString(i))

            ServerStatus(
                status = obj.optString("status", "ok"),
                localIp = obj.optString("local_ip", ""),
                batches = batchList,
                currentBatch = obj.optString("current_batch", batch),
                total = obj.optInt("total", 0),
                unlabeled = obj.optInt("unlabeled", obj.optInt("batch_left", 0)),
                annotated = obj.optInt("annotated", 0),
                verified = obj.optInt("verified", 0),
                conjoinedQueued = obj.optInt("conjoined_queued", 0),
                unverified = obj.optInt("unverified", 0),
                recheck = obj.optInt("recheck", 0),
                conjoined = obj.optInt("conjoined", 0),
                single = obj.optInt("single", 0),
                discarded = obj.optInt("discarded", 0),
                percentComplete = obj.optDouble("percent_complete", 0.0).toFloat(),
                batchDone = obj.optInt("batch_done", obj.optInt("verified", 0) + obj.optInt("discarded", 0)),
                batchLeft = obj.optInt("batch_left", obj.optInt("unlabeled", 0)),
                totalAll = obj.optInt("total_all", obj.optInt("total", 0)),
                doneAll = obj.optInt("done_all", obj.optInt("verified", 0) + obj.optInt("discarded", 0)),
                leftAll = obj.optInt("left_all", obj.optInt("unlabeled", 0))
            )
        }
    }

    suspend fun fetchNextBubble(baseUrl: String, batch: String): Result<BubbleItem?> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/api/bubble/next?batch=$batch"
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Server error: ${resp.code}")
            val bodyStr = resp.body?.string() ?: error("Empty response")
            val obj = JSONObject(bodyStr)

            val bubbleObj = obj.optJSONObject("bubble") ?: return@runCatching null
            val cropDims = bubbleObj.optJSONObject("crop_dimensions") ?: JSONObject()

            BubbleItem(
                bubbleId = bubbleObj.getString("bubble_id"),
                filename = bubbleObj.getString("filename"),
                imageUrl = "$baseUrl${obj.getString("image_url")}",
                width = cropDims.optInt("width", 0),
                height = cropDims.optInt("height", 0),
                pageName = bubbleObj.optString("page_name", ""),
                confidence = bubbleObj.optDouble("confidence", 0.0).toFloat(),
                index = obj.optInt("index", 1),
                total = obj.optInt("total", 1)
            )
        }
    }

    suspend fun fetchBitmap(imageUrl: String): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(imageUrl).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Image load error: ${resp.code}")
            val bytes = resp.body?.bytes() ?: error("Empty image data")
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Failed to decode bitmap")
        }
    }

    suspend fun autoSuggest(
        baseUrl: String,
        batch: String,
        bubbleId: String
    ): Result<AutoSuggestResult> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject().apply {
                put("batch", batch)
                put("bubble_id", bubbleId)
            }
            val req = Request.Builder()
                .url("$baseUrl/api/bubble/auto_suggest")
                .post(root.toString().toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Auto-suggest failed: ${resp.code}")
            val bodyStr = resp.body?.string() ?: error("Empty response")
            val obj = JSONObject(bodyStr)
            val success = obj.optBoolean("success", false)

            val ptsList = mutableListOf<CrunchPointAnnotation>()
            val linesList = mutableListOf<DividingLineAnnotation>()

            if (success) {
                val ptsArr = obj.optJSONArray("crunch_points") ?: JSONArray()
                for (i in 0 until ptsArr.length()) {
                    val pObj = ptsArr.getJSONObject(i)
                    val bboxArr = pObj.getJSONArray("bbox")
                    val centerArr = pObj.getJSONArray("center")
                    ptsList.add(
                        CrunchPointAnnotation(
                            id = pObj.optInt("id", i + 1),
                            label = pObj.optString("label", "crunch_${i + 1}"),
                            rect = Rect(bboxArr.getInt(0), bboxArr.getInt(1), bboxArr.getInt(2), bboxArr.getInt(3)),
                            center = Point(centerArr.getInt(0), centerArr.getInt(1))
                        )
                    )
                }

                val linesArr = obj.optJSONArray("dividing_lines") ?: JSONArray()
                for (i in 0 until linesArr.length()) {
                    val lObj = linesArr.getJSONObject(i)
                    val pts = lObj.getJSONArray("points")
                    val ptList = mutableListOf<Point>()
                    for (j in 0 until pts.length()) {
                        val pt = pts.getJSONArray(j)
                        ptList.add(Point(pt.getInt(0), pt.getInt(1)))
                    }
                    linesList.add(DividingLineAnnotation(lObj.optInt("line_id", i + 1), ptList))
                }
            }

            val candArr = obj.optJSONArray("candidate_points") ?: JSONArray()
            val candList = mutableListOf<Point>()
            for (i in 0 until candArr.length()) {
                val cpt = candArr.getJSONArray(i)
                candList.add(Point(cpt.getInt(0), cpt.getInt(1)))
            }

            AutoSuggestResult(success, ptsList, linesList, candList)
        }
    }

    suspend fun flagConjoined(
        baseUrl: String,
        batch: String,
        bubbleId: String,
        reason: String = "auto_suggest_rejected"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject().apply {
                put("batch", batch)
                put("bubble_id", bubbleId)
                put("reason", reason)
            }
            val req = Request.Builder()
                .url("$baseUrl/api/bubble/flag_conjoined")
                .post(root.toString().toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Flag conjoined failed: ${resp.code}")
        }
    }

    suspend fun submitAnnotation(
        baseUrl: String,
        batch: String,
        bubbleId: String,
        isConjoined: Boolean,
        crunchPoints: List<CrunchPointAnnotation>,
        dividingLines: List<DividingLineAnnotation>,
        notes: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject().apply {
                put("batch", batch)
                put("bubble_id", bubbleId)
                put("is_conjoined", isConjoined)
                put("notes", notes)

                val pointsArr = JSONArray()
                for (p in crunchPoints) {
                    pointsArr.put(JSONObject().apply {
                        put("id", p.id)
                        put("label", p.label)
                        put("bbox", JSONArray().apply {
                            put(p.rect.left)
                            put(p.rect.top)
                            put(p.rect.right)
                            put(p.rect.bottom)
                        })
                        put("center", JSONArray().apply {
                            put(p.center.x)
                            put(p.center.y)
                        })
                    })
                }
                put("crunch_points", pointsArr)

                val linesArr = JSONArray()
                for (l in dividingLines) {
                    linesArr.put(JSONObject().apply {
                        put("line_id", l.lineId)
                        val pts = JSONArray()
                        for (pt in l.points) {
                            pts.put(JSONArray().apply {
                                put(pt.x)
                                put(pt.y)
                            })
                        }
                        put("points", pts)
                    })
                }
                put("dividing_lines", linesArr)
            }

            val req = Request.Builder()
                .url("$baseUrl/api/annotate")
                .post(root.toString().toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Annotate failed: ${resp.code}")
        }
    }

    suspend fun fetchNextUnverified(baseUrl: String, batch: String): Result<UnverifiedBubbleItem?> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/api/unverified/next?batch=$batch"
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Fetch unverified failed: ${resp.code}")
            val bodyStr = resp.body?.string() ?: error("Empty response")
            val obj = JSONObject(bodyStr)

            val itemObj = obj.optJSONObject("item") ?: return@runCatching null
            val annotObj = itemObj.optJSONObject("annotation") ?: JSONObject()

            val ptsList = mutableListOf<CrunchPointAnnotation>()
            val ptsArr = annotObj.optJSONArray("crunch_points") ?: JSONArray()
            for (i in 0 until ptsArr.length()) {
                val pObj = ptsArr.getJSONObject(i)
                val bboxArr = pObj.getJSONArray("bbox")
                val centerArr = pObj.getJSONArray("center")
                ptsList.add(
                    CrunchPointAnnotation(
                        id = pObj.optInt("id", i + 1),
                        label = pObj.optString("label", "crunch_${i + 1}"),
                        rect = Rect(bboxArr.getInt(0), bboxArr.getInt(1), bboxArr.getInt(2), bboxArr.getInt(3)),
                        center = Point(centerArr.getInt(0), centerArr.getInt(1))
                    )
                )
            }

            val linesList = mutableListOf<DividingLineAnnotation>()
            val linesArr = annotObj.optJSONArray("dividing_lines") ?: JSONArray()
            for (i in 0 until linesArr.length()) {
                val lObj = linesArr.getJSONObject(i)
                val pts = lObj.getJSONArray("points")
                val ptList = mutableListOf<Point>()
                for (j in 0 until pts.length()) {
                    val pt = pts.getJSONArray(j)
                    ptList.add(Point(pt.getInt(0), pt.getInt(1)))
                }
                linesList.add(DividingLineAnnotation(lObj.optInt("line_id", i + 1), ptList))
            }

            UnverifiedBubbleItem(
                bubbleId = itemObj.getString("bubble_id"),
                batch = batch,
                filename = itemObj.getString("filename"),
                imageUrl = "$baseUrl${itemObj.getString("image_url")}",
                crunchPoints = ptsList,
                dividingLines = linesList,
                pendingCount = itemObj.optInt("pending_count", 1)
            )
        }
    }

    suspend fun verifyAnnotation(
        baseUrl: String,
        batch: String,
        bubbleId: String,
        verified: Boolean,
        crunchPoints: List<CrunchPointAnnotation>,
        dividingLines: List<DividingLineAnnotation>,
        notes: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject().apply {
                put("batch", batch)
                put("bubble_id", bubbleId)
                put("verified", verified)
                put("notes", notes)

                val pointsArr = JSONArray()
                for (p in crunchPoints) {
                    pointsArr.put(JSONObject().apply {
                        put("id", p.id)
                        put("label", p.label)
                        put("bbox", JSONArray().apply {
                            put(p.rect.left)
                            put(p.rect.top)
                            put(p.rect.right)
                            put(p.rect.bottom)
                        })
                        put("center", JSONArray().apply {
                            put(p.center.x)
                            put(p.center.y)
                        })
                    })
                }
                put("crunch_points", pointsArr)

                val linesArr = JSONArray()
                for (l in dividingLines) {
                    linesArr.put(JSONObject().apply {
                        put("line_id", l.lineId)
                        val pts = JSONArray()
                        for (pt in l.points) {
                            pts.put(JSONArray().apply {
                                put(pt.x)
                                put(pt.y)
                            })
                        }
                        put("points", pts)
                    })
                }
                put("dividing_lines", linesArr)
            }

            val req = Request.Builder()
                .url("$baseUrl/api/unverified/verify")
                .post(root.toString().toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Verify call failed: ${resp.code}")
        }
    }

    suspend fun discardBubble(
        baseUrl: String,
        batch: String,
        bubbleId: String,
        reason: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject().apply {
                put("batch", batch)
                put("bubble_id", bubbleId)
                put("reason", reason)
            }
            val req = Request.Builder()
                .url("$baseUrl/api/discard")
                .post(root.toString().toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Discard failed: ${resp.code}")
        }
    }

    suspend fun undoLast(baseUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/undo")
                .post("{}".toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Undo failed: ${resp.code}")
        }
    }

    suspend fun fetchVerifiedList(
        baseUrl: String,
        batch: String,
        conjoinedOnly: Boolean = false
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/api/bubble/verified/list?batch=$batch&conjoined_only=$conjoinedOnly"
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Fetch verified list error: ${resp.code}")
            val bodyStr = resp.body?.string() ?: error("Empty response")
            val obj = JSONObject(bodyStr)
            val itemsArr = obj.optJSONArray("items") ?: JSONArray()
            val list = mutableListOf<String>()
            for (i in 0 until itemsArr.length()) {
                val item = itemsArr.getJSONObject(i)
                list.add(item.getString("bubble_id"))
            }
            list
        }
    }

    suspend fun fetchVerifiedBubble(
        baseUrl: String,
        batch: String,
        bubbleId: String
    ): Result<VerifiedBubbleItem> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/api/bubble/verified/$batch/$bubbleId"
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Fetch verified bubble error: ${resp.code}")
            val bodyStr = resp.body?.string() ?: error("Empty response")
            val obj = JSONObject(bodyStr)

            val bubbleObj = obj.getJSONObject("bubble")
            val isConjoined = obj.optBoolean("is_conjoined", false)
            val imageUrl = "$baseUrl${obj.getString("image_url")}"
            val index = obj.optInt("index", 1)
            val total = obj.optInt("total", 1)

            val ptsList = mutableListOf<CrunchPointAnnotation>()
            val ptsArr = obj.optJSONArray("crunch_points") ?: JSONArray()
            for (i in 0 until ptsArr.length()) {
                val pObj = ptsArr.getJSONObject(i)
                val bboxArr = pObj.getJSONArray("bbox")
                val centerArr = pObj.getJSONArray("center")
                ptsList.add(
                    CrunchPointAnnotation(
                        id = pObj.optInt("id", i + 1),
                        label = pObj.optString("label", "crunch_${i + 1}"),
                        rect = Rect(bboxArr.getInt(0), bboxArr.getInt(1), bboxArr.getInt(2), bboxArr.getInt(3)),
                        center = Point(centerArr.getInt(0), centerArr.getInt(1))
                    )
                )
            }

            val linesList = mutableListOf<DividingLineAnnotation>()
            val linesArr = obj.optJSONArray("dividing_lines") ?: JSONArray()
            for (i in 0 until linesArr.length()) {
                val lObj = linesArr.getJSONObject(i)
                val pts = lObj.getJSONArray("points")
                val ptList = mutableListOf<Point>()
                for (j in 0 until pts.length()) {
                    val pt = pts.getJSONArray(j)
                    ptList.add(Point(pt.getInt(0), pt.getInt(1)))
                }
                linesList.add(DividingLineAnnotation(lObj.optInt("line_id", i + 1), ptList))
            }

            VerifiedBubbleItem(
                bubbleId = bubbleId,
                filename = bubbleObj.getString("filename"),
                imageUrl = imageUrl,
                isConjoined = isConjoined,
                crunchPoints = ptsList,
                dividingLines = linesList,
                index = index,
                total = total
            )
        }
    }

    suspend fun fetchSinglesList(
        baseUrl: String,
        batch: String
    ): Result<List<BubbleThumbItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/api/bubble/verified/list?batch=$batch&singles_only=true"
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Fetch singles list error: ${resp.code}")
            val bodyStr = resp.body?.string() ?: error("Empty response")
            val obj = JSONObject(bodyStr)
            val itemsArr = obj.optJSONArray("items") ?: JSONArray()
            val list = mutableListOf<BubbleThumbItem>()
            for (i in 0 until itemsArr.length()) {
                val item = itemsArr.getJSONObject(i)
                val fname = item.getString("filename")
                list.add(
                    BubbleThumbItem(
                        bubbleId = item.getString("bubble_id"),
                        filename = fname,
                        imageUrl = "$baseUrl/api/image/$batch/$fname",
                        isConjoined = item.optBoolean("is_conjoined", false),
                        manifestIndex = item.optInt("manifest_index", i + 1)
                    )
                )
            }
            list
        }
    }

    suspend fun fetchThumbnail(imageUrl: String, maxDim: Int = 300): Result<Bitmap> = withContext(Dispatchers.IO) {
        val cached = ThumbnailCache.lru.get(imageUrl)
        if (cached != null) return@withContext Result.success(cached)
        runCatching {
            val req = Request.Builder().url(imageUrl).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) error("Image load error: ${resp.code}")
            val bytes = resp.body?.bytes() ?: error("Empty image data")
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
            var sampleSize = 1
            val maxSide = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
            while (maxSide / (sampleSize * 2) >= maxDim) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: error("Failed to decode bitmap")
            ThumbnailCache.lru.put(imageUrl, bmp)
            bmp
        }
    }
}

object ThumbnailCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxOf(maxMemory / 8, 16 * 1024)
    val lru = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }
}

