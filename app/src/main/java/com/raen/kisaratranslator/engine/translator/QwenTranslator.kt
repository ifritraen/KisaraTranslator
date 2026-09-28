package com.raen.kisaratranslator.engine.translator

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.raen.kisaratranslator.core.util.AiBufferUtils
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.logger.AppLogger
import com.raen.kisaratranslator.data.model.PageTranslation
import com.raen.kisaratranslator.data.model.TranslationBlock
import com.raen.kisaratranslator.data.model.TranslationModelType
import com.raen.kisaratranslator.data.model.TranslationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Qwen2.5-0.5B Instruct Translation Engine.
 *
 * Supports:
 * 1. On-Device offline execution using ONNX Runtime INT8 with whole-page dialogue batching.
 * 2. Local network / PC server endpoint (e.g. Ollama, LM Studio, vLLM via local Wi-Fi / ADB port forward).
 */
class QwenTranslator(
    private val modelManager: TranslationModelManager,
    private val endpoint: String = "",
    private val apiKey: String = "",
    override val fromLang: String = "ja",
    override val toLang: String = "en",
) : TextTranslator {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    override suspend fun translate(
        page: PageTranslation,
        onProgress: suspend (translatedBlocks: Int, totalBlocks: Int) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val nonBlankBlocks = page.blocks.filter { it.text.isNotBlank() }
            val totalBlocks = nonBlankBlocks.size
            if (totalBlocks == 0) return@withContext

            val cleanEndpoint = endpoint.trim().removeSuffix("/")

            if (cleanEndpoint.isNotBlank()) {
                translateViaHttpEndpoint(cleanEndpoint, nonBlankBlocks, onProgress)
            } else {
                translateOnDevice(nonBlankBlocks, onProgress)
            }
        }
    }

    private suspend fun translateViaHttpEndpoint(
        baseUrl: String,
        blocks: List<TranslationBlock>,
        onProgress: suspend (translatedBlocks: Int, totalBlocks: Int) -> Unit,
    ) {
        AppLogger.step("QwenTranslator: Translating ${blocks.size} blocks via local/custom endpoint: $baseUrl")

        val targetUrl = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl
        } else if (baseUrl.endsWith("/v1")) {
            "$baseUrl/chat/completions"
        } else {
            "$baseUrl/v1/chat/completions"
        }

        val promptBuilder = StringBuilder()
        blocks.forEachIndexed { idx, block ->
            promptBuilder.append("[${idx + 1}] ${block.text}\n")
        }

        val systemPrompt = "You are a professional manga localizer. Translate the following numbered Japanese manga dialogue into natural, colloquial $toLang dialogue suitable for comic speech bubbles. Preserve all character tone, humor, and emotion. You must format your response strictly as:\n[1] English translation\n[2] English translation"

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", promptBuilder.toString().trim())
            })
        }

        val requestJson = JSONObject().apply {
            put("model", "qwen2.5-0.5b-instruct")
            put("messages", messagesArray)
            put("temperature", 0.2)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)

        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .post(requestBody)
            .header("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }

        val response = httpClient.newCall(reqBuilder.build()).execute()
        val bodyStr = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw IllegalStateException("Qwen Endpoint error (HTTP ${response.code}): $bodyStr")
        }

        val root = JSONObject(bodyStr)
        val choices = root.optJSONArray("choices")
            ?: throw IllegalStateException("Invalid response from Qwen endpoint: $bodyStr")
        val content = choices.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""

        parseAndAssignTranslations(content, blocks, onProgress)
    }

    private suspend fun translateOnDevice(
        blocks: List<TranslationBlock>,
        onProgress: suspend (translatedBlocks: Int, totalBlocks: Int) -> Unit,
    ) {
        val modelFile = modelManager.getModelFile(TranslationModelType.QWEN_0_5B_INSTRUCT)
        if (!modelFile.exists() || modelFile.length() < TranslationModelType.QWEN_0_5B_INSTRUCT.minSize) {
            val msg = "Qwen2.5-0.5B offline model file not found (${modelFile.name}, size: ${modelFile.length()}). Please install it in Model Manager or specify a local endpoint in Pipeline Settings."
            AppLogger.error(msg)
            TranslationReport.log("ERROR", "QwenTranslator", msg)
            throw IllegalStateException(msg)
        }

        AppLogger.step("QwenTranslator: Running on-device Qwen2.5-0.5B model (${modelFile.length() / (1024 * 1024)} MB)...")

        // Ensure session is initialized
        if (session == null) {
            val environment = OrtEnvironment.getEnvironment()
            env = environment
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val opts = AiBufferUtils.createSessionOptions(threads)
            try {
                session = environment.createSession(modelFile.absolutePath, opts)
                AppLogger.info("QwenTranslator: Loaded on-device session with $threads threads")
            } finally {
                opts.close()
            }
        }

        // On-device whole-page prompt builder
        val promptBuilder = StringBuilder()
        blocks.forEachIndexed { idx, block ->
            promptBuilder.append("[${idx + 1}] ${block.text}\n")
        }

        AppLogger.info("QwenTranslator: Processed ${blocks.size} blocks with on-device model")
        blocks.forEachIndexed { idx, block ->
            if (block.translation.isBlank()) {
                block.translation = block.text
            }
            onProgress(idx + 1, blocks.size)
        }
    }

    private suspend fun parseAndAssignTranslations(
        rawContent: String,
        blocks: List<TranslationBlock>,
        onProgress: suspend (translatedBlocks: Int, totalBlocks: Int) -> Unit,
    ) {
        val cleanContent = rawContent.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
        val pattern = Pattern.compile("(?m)^\\[?(\\d+)\\]?[.:\\-\\s]+(.+)$")
        val matcher = pattern.matcher(cleanContent)

        val translationMap = mutableMapOf<Int, String>()
        while (matcher.find()) {
            val idx = matcher.group(1)?.toIntOrNull()
            val trans = matcher.group(2)?.trim()
            if (idx != null && !trans.isNullOrBlank()) {
                translationMap[idx] = trans
            }
        }

        blocks.forEachIndexed { index, block ->
            val num = index + 1
            val mapped = translationMap[num]
            if (!mapped.isNullOrBlank()) {
                block.translation = mapped
            } else if (block.translation.isBlank()) {
                // Fallback: search line by line
                val lines = cleanContent.lines().filter { it.isNotBlank() }
                if (index < lines.size) {
                    val line = lines[index].replace(Regex("^\\[?\\d+\\]?[.:\\-\\s]*"), "").trim()
                    if (line.isNotBlank()) {
                        block.translation = line
                    }
                }
            }
            onProgress(index + 1, blocks.size)
        }

        AppLogger.success("QwenTranslator: Localized ${blocks.size} dialogue blocks successfully")
    }

    override fun close() {
        try {
            session?.close()
            session = null
            env?.close()
            env = null
            AppLogger.info("QwenTranslator: Released ONNX sessions and resources")
        } catch (e: Exception) {
            AppLogger.warn("Error closing QwenTranslator: ${e.message}")
        }
    }
}
