package com.raen.crunchlab.engine.translator

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Google ML Kit on-device translation engine for CrunchLab Module 5.
 */
class MLKitTranslator : TextTranslator {

    override val name: String = "Google ML Kit (On-Device)"

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    private val conditions = DownloadConditions.Builder().build()
    private var isModelDownloaded = false

    private suspend fun ensureModelDownloaded() = withContext(Dispatchers.IO) {
        if (isModelDownloaded) return@withContext
        try {
            Log.i("MLKitTranslator", "Checking/downloading on-device translation model pack (JA->EN)...")
            Tasks.await(translator.downloadModelIfNeeded(conditions))
            isModelDownloaded = true
            Log.i("MLKitTranslator", "On-device ML Kit translation pack ready.")
        } catch (e: Exception) {
            Log.e("MLKitTranslator", "Failed to download/verify ML Kit model pack: ${e.message}", e)
        }
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.Default) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return@withContext ""

        ensureModelDownloaded()

        try {
            val lines = cleanText.split("\n").filter { it.isNotBlank() }
            if (lines.size > 1) {
                val translatedLines = lines.mapNotNull { line ->
                    try {
                        Tasks.await(translator.translate(line)).takeIf { it.isNotBlank() }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (translatedLines.isNotEmpty()) {
                    return@withContext translatedLines.joinToString("\n")
                }
            }

            Tasks.await(translator.translate(cleanText))
        } catch (e: Exception) {
            Log.e("MLKitTranslator", "Translation failed for '$cleanText': ${e.message}", e)
            cleanText
        }
    }

    override suspend fun translateBatch(
        texts: List<String>,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
    ): List<String> = withContext(Dispatchers.Default) {
        val total = texts.size
        if (total == 0) return@withContext emptyList()

        ensureModelDownloaded()

        val results = mutableListOf<String>()
        var completed = 0

        for (text in texts) {
            coroutineContext.ensureActive()
            val res = if (text.isNotBlank()) {
                try {
                    translate(text)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("MLKitTranslator", "Error translating: '$text'", e)
                    text
                }
            } else {
                ""
            }
            results.add(res)
            completed++
            onProgress(completed, total)
        }

        results
    }

    override fun close() {
        try {
            translator.close()
        } catch (e: Exception) {
            Log.w("MLKitTranslator", "Error closing ML Kit translator: ${e.message}")
        }
    }
}
