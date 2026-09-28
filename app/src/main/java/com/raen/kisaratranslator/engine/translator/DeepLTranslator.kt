package com.raen.kisaratranslator.engine.translator

import com.raen.kisaratranslator.data.logger.AppLogger
import com.raen.kisaratranslator.data.model.PageTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DeepLTranslator(
    private val apiKey: String,
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
    ) = withContext(Dispatchers.IO) {
        val blocks = page.blocks.filter { it.text.isNotBlank() }
        val totalBlocks = blocks.size
        if (totalBlocks == 0) return@withContext

        val key = apiKey.trim()
        if (key.isBlank()) {
            throw IllegalStateException("DeepL API Key is missing. Please enter your DeepL API Key in Pipeline Settings.")
        }

        // Auto-select free vs pro endpoint based on key suffix (:fx)
        val endpoint = if (key.endsWith(":fx", ignoreCase = true)) {
            "https://api-free.deepl.com/v2/translate"
        } else {
            "https://api.deepl.com/v2/translate"
        }

        // DeepL expects JSON payload: { "text": ["..."], "target_lang": "EN", "source_lang": "JA" }
        val textArray = JSONArray()
        for (b in blocks) {
            textArray.put(b.text)
        }

        val jsonPayload = JSONObject().apply {
            put("text", textArray)
            put("target_lang", toLang.uppercase())
            put("source_lang", fromLang.uppercase())
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .header("Authorization", "DeepL-Auth-Key $key")
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw IllegalStateException("DeepL HTTP ${response.code}: $responseBody")
        }

        val root = JSONObject(responseBody)
        val transArr = root.optJSONArray("translations")
            ?: throw IllegalStateException("DeepL response missing 'translations': $responseBody")

        for (i in 0 until minOf(transArr.length(), blocks.size)) {
            val itemObj = transArr.getJSONObject(i)
            val translated = itemObj.optString("text", "")
            if (translated.isNotBlank()) {
                blocks[i].translation = translated
            }
            onProgress(i + 1, totalBlocks)
        }

        AppLogger.info("DeepL Translator: Translated $totalBlocks blocks successfully via $endpoint")
    }

    override fun close() {
        // OkHttpClient cleans up automatically
    }
}
