package com.raen.kisaratranslator.engine.translator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.raen.kisaratranslator.data.model.PageTranslation
import com.raen.kisaratranslator.data.model.TranslationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MLKitTranslator(
    override val fromLang: String = "ja",
    override val toLang: String = "en",
) : TextTranslator {

    private val sourceTag = TranslateLanguage.fromLanguageTag(fromLang) ?: TranslateLanguage.JAPANESE
    private val targetTag = TranslateLanguage.fromLanguageTag(toLang) ?: TranslateLanguage.ENGLISH

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(sourceTag)
            .setTargetLanguage(targetTag)
            .build(),
    )

    private val conditions = DownloadConditions.Builder().build()

    override suspend fun translate(
        page: PageTranslation,
        onProgress: suspend (translatedBlocks: Int, totalBlocks: Int) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val totalBlocks = page.blocks.size
        if (totalBlocks == 0) return@withContext

        try {
            TranslationReport.log("INFO", "MLKitTranslator", "Checking on-device translation model pack...")
            Tasks.await(translator.downloadModelIfNeeded(conditions))
        } catch (e: Exception) {
            TranslationReport.log("ERROR", "MLKitTranslator", "Failed to download MLKit model: ${e.message}", e)
        }

        var completed = 0
        for (b in page.blocks) {
            if (b.text.isNotBlank()) {
                val lines = b.text.split("\n").filter { it.isNotBlank() }
                val translatedLines = lines.mapNotNull { line ->
                    try {
                        Tasks.await(translator.translate(line)).takeIf { it.isNotBlank() }
                    } catch (e: Exception) {
                        null
                    }
                }
                b.translation = if (translatedLines.isNotEmpty()) {
                    translatedLines.joinToString("\n")
                } else {
                    try {
                        Tasks.await(translator.translate(b.text))
                    } catch (e: Exception) {
                        ""
                    }
                }
            }
            completed++
            onProgress(completed, totalBlocks)
        }
        TranslationReport.log("INFO", "MLKitTranslator", "Completed translation of $totalBlocks blocks")
    }

    override fun close() {
        translator.close()
    }
}
