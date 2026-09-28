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

class GroqTranslator(
    private val apiKey: String,
    private val model: String = "qwen/qwen3.6-27b",
    override val fromLang: String = "ja",
    override val toLang: String = "en",
) : TextTranslator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
            throw IllegalStateException("Groq API Key is missing. Please enter your Groq Cloud key (from console.groq.com) in Pipeline Settings.")
        }

        val jsonInputArray = JSONArray()
        for (b in blocks) {
            jsonInputArray.put(b.text)
        }

        val systemPrompt = "You are a professional manga localizer and translator. Translate the given JSON array of Japanese manga dialogue lines into natural, colloquial $toLang dialogue suitable for comic speech bubbles. Preserve all character tone, humor, and slang. You must output a valid JSON object with a single key 'translations' containing an array of strings corresponding 1-to-1 with the input lines in the exact same order: {\"translations\": [\"text1\", \"text2\"]}"

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", jsonInputArray.toString())
            })
        }

        val activeModel = when {
            model.isBlank() || model.contains("llama", ignoreCase = true) -> "qwen/qwen3.6-27b"
            else -> model.trim()
        }
        val requestJson = JSONObject().apply {
            put("model", activeModel)
            put("messages", messagesArray)
            put("response_format", JSONObject().put("type", "json_object"))
            put("temperature", 0.3)
        }

        val url = "https://api.groq.com/openai/v1/chat/completions"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("User-Agent", "KisaraTranslator/1.0 (Android)")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw IllegalStateException("Groq API HTTP ${response.code}: $responseBody")
        }

        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices")
            ?: throw IllegalStateException("Groq response missing choices: $responseBody")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
        val contentStr = message?.optString("content", "") ?: ""

        if (contentStr.isBlank()) {
            throw IllegalStateException("Groq returned empty text response.")
        }

        val cleanContent = contentStr.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
        val contentObj = JSONObject(cleanContent)
        val transArr = contentObj.optJSONArray("translations")
            ?: throw IllegalStateException("Groq response JSON missing 'translations' array: $cleanContent")

        for (i in 0 until minOf(transArr.length(), blocks.size)) {
            val transText = transArr.optString(i, "")
            if (transText.isNotBlank()) {
                blocks[i].translation = transText
            }
            onProgress(i + 1, totalBlocks)
        }

        AppLogger.info("Groq Translator: Translated $totalBlocks blocks successfully via Llama-3.3 70B")
    }

    override fun close() {
        // OkHttpClient cleans up automatically
    }
}
