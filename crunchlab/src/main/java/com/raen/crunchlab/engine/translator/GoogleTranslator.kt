package com.raen.crunchlab.engine.translator

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Translator (GTX API + Mobile Web fallback) for CrunchLab Module 5.
 */
class GoogleTranslator(
    private val targetLang: String = "en",
) : TextTranslator {

    override val name: String = "Google Translate"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return@withContext ""

        // Attempt 1: GTX JSON API
        try {
            val encodedText = URLEncoder.encode(cleanText, "utf-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encodedText"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                .build()

            val response = client.newCall(req).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val root = JSONArray(body)
                    val segments = root.optJSONArray(0)
                    if (segments != null) {
                        val sb = StringBuilder()
                        for (i in 0 until segments.length()) {
                            val seg = segments.optJSONArray(i)
                            if (seg != null && !seg.isNull(0)) {
                                sb.append(seg.getString(0))
                            }
                        }
                        val res = sb.toString().trim()
                        if (res.isNotBlank()) return@withContext res
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("GoogleTranslator", "GTX API attempt failed: ${e.message}")
        }

        // Attempt 2: Mobile Web Endpoint fallback
        try {
            val encoded = URLEncoder.encode(cleanText, "utf-8")
            val webUrl = "https://translate.google.com/m?sl=auto&tl=$targetLang&q=$encoded"
            val webReq = Request.Builder()
                .url(webUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                .build()

            val webResp = client.newCall(webReq).execute()
            if (webResp.isSuccessful) {
                val html = webResp.body?.string() ?: ""
                val regex = """<div[^>]*class=["']result-container["'][^>]*>(.*?)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val match = regex.find(html)
                if (match != null) {
                    val raw = match.groupValues[1]
                        .replace("&amp;", "&")
                        .replace("&quot;", "\"")
                        .replace("&#39;", "'")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .trim()
                    if (raw.isNotBlank()) return@withContext raw
                }
            }
        } catch (e: Exception) {
            Log.w("GoogleTranslator", "Mobile web fallback failed: ${e.message}")
        }

        cleanText
    }

    override suspend fun translateBatch(
        texts: List<String>,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
    ): List<String> = coroutineScope {
        val total = texts.size
        if (total == 0) return@coroutineScope emptyList()

        val completed = AtomicInteger(0)
        val deferredList = texts.mapIndexed { idx, text ->
            async(Dispatchers.IO) {
                coroutineContext.ensureActive()
                val res = if (text.isNotBlank()) {
                    try {
                        translate(text)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e("GoogleTranslator", "Error translating [$idx]: '$text'", e)
                        text
                    }
                } else {
                    ""
                }
                val done = completed.incrementAndGet()
                onProgress(done, total)
                res
            }
        }
        deferredList.awaitAll()
    }

    override fun close() {
        // OkHttpClient cleans up automatically
    }
}
