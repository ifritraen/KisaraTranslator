package com.raen.kisaratranslator.engine.translator

import com.raen.kisaratranslator.data.model.PageTranslation
import com.raen.kisaratranslator.data.model.TranslationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GoogleTranslator(
    override val fromLang: String = "ja",
    override val toLang: String = "en",
) : TextTranslator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(
        page: PageTranslation,
        onProgress: suspend (translatedBlocks: Int, totalBlocks: Int) -> Unit,
    ) = coroutineScope {
        val totalBlocks = page.blocks.size
        if (totalBlocks == 0) return@coroutineScope

        val completed = AtomicInteger(0)
        val jobs = page.blocks.map { block ->
            async(Dispatchers.IO) {
                if (block.text.isNotBlank()) {
                    try {
                        val result = translateText(toLang, block.text)
                        if (result.isNotBlank()) {
                            block.translation = result
                        }
                    } catch (e: Exception) {
                        TranslationReport.log("WARN", "GoogleTranslator", "Failed block: '${block.text}' - ${e.message}")
                    } finally {
                        val done = completed.incrementAndGet()
                        onProgress(done, totalBlocks)
                    }
                } else {
                    val done = completed.incrementAndGet()
                    onProgress(done, totalBlocks)
                }
            }
        }
        jobs.awaitAll()
        TranslationReport.log("INFO", "GoogleTranslator", "Completed translation of $totalBlocks blocks")
    }

    private suspend fun translateText(targetLang: String, text: String): String = withContext(Dispatchers.IO) {
        // Attempt 1: GTX JSON API
        try {
            val encodedText = URLEncoder.encode(text, "utf-8")
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
        } catch (_: Exception) {
        }

        // Attempt 2: Mobile Web Endpoint fallback
        try {
            val encoded = URLEncoder.encode(text, "utf-8")
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
        } catch (_: Exception) {
        }

        ""
    }

    override fun close() {
        // OkHttpClient cleans up automatically
    }
}
