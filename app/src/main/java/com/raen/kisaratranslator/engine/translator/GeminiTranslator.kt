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

class GeminiTranslator(
    private val apiKey: String,
    private val model: String = "gemini-1.5-flash",
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
            throw IllegalStateException("Google Gemini API Key is missing. Please enter your Google AI Studio key in Pipeline Settings.")
        }

        val jsonInputArray = JSONArray()
        for (b in blocks) {
            jsonInputArray.put(b.text)
        }

        val systemPrompt = "You are a professional manga localizer and translator. Translate the given JSON array of Japanese manga dialogue lines into natural, colloquial $toLang dialogue suitable for comic speech bubbles. Preserve all character tone, humor, and slang. Output ONLY a valid JSON array of strings corresponding 1-to-1 with the input lines in the exact same order."

        val safetyArr = JSONArray().apply {
            for (cat in listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT",
                "HARM_CATEGORY_CIVIC_INTEGRITY",
            )) {
                put(JSONObject().apply {
                    put("category", cat)
                    put("threshold", "BLOCK_NONE")
                })
            }
        }

        val requestJson = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", jsonInputArray.toString())))
            }))
            put("generationConfig", JSONObject().apply {
                put("response_mime_type", "application/json")
                put("temperature", 0.3)
                put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            })
            put("safetySettings", safetyArr)
        }

        val activeModel = if (model.isNotBlank()) model.trim() else "gemini-1.5-flash"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$activeModel:generateContent?key=$key"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("User-Agent", "KisaraTranslator/1.0 (Android)")
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw IllegalStateException("Gemini API HTTP ${response.code}: $responseBody")
        }

        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException("Gemini response missing candidates: $responseBody")
        val candidate = candidates.optJSONObject(0)
            ?: throw IllegalStateException("Gemini returned 0 candidates: $responseBody")
        val content = candidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        val rawText = parts?.optJSONObject(0)?.optString("text", "") ?: ""

        if (rawText.isBlank()) {
            throw IllegalStateException("Gemini returned empty text response.")
        }

        val parsedArray = JSONArray(rawText)
        for (i in 0 until minOf(parsedArray.length(), blocks.size)) {
            val transText = parsedArray.optString(i, "")
            if (transText.isNotBlank()) {
                blocks[i].translation = transText
            }
            onProgress(i + 1, totalBlocks)
        }

        AppLogger.info("Gemini Translator: Translated $totalBlocks blocks successfully")
    }

    override fun close() {
        // OkHttpClient cleans up automatically
    }
}
