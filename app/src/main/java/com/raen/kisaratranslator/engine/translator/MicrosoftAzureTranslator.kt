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

class MicrosoftAzureTranslator(
    private val apiKey: String,
    private val region: String = "global",
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

        if (apiKey.isBlank()) {
            throw IllegalStateException("Microsoft Azure Translator API Key is missing. Please enter your Azure Key in Pipeline Settings.")
        }

        // Batch translate up to 100 blocks in a single HTTP request
        val jsonArray = JSONArray()
        for (b in blocks) {
            val item = JSONObject().apply {
                put("Text", b.text)
            }
            jsonArray.put(item)
        }

        val url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=$fromLang&to=$toLang"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonArray.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Ocp-Apim-Subscription-Key", apiKey.trim())
            .header("Ocp-Apim-Subscription-Region", if (region.isBlank()) "global" else region.trim())
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw IllegalStateException("Azure Translator HTTP ${response.code}: $responseBody")
        }

        val resultArr = JSONArray(responseBody)
        for (i in 0 until minOf(resultArr.length(), blocks.size)) {
            val itemObj = resultArr.getJSONObject(i)
            val transArr = itemObj.optJSONArray("translations")
            if (transArr != null && transArr.length() > 0) {
                val transObj = transArr.getJSONObject(0)
                val text = transObj.optString("text", "")
                if (text.isNotBlank()) {
                    blocks[i].translation = text
                }
            }
            onProgress(i + 1, totalBlocks)
        }

        AppLogger.info("Microsoft Azure Translator: Translated $totalBlocks blocks successfully")
    }

    override fun close() {
        // OkHttpClient daemon threads close on GC
    }
}
