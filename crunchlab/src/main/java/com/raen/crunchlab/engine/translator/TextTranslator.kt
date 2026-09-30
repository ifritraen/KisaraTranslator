package com.raen.crunchlab.engine.translator

import java.io.Closeable

/**
 * Text translation interface for CrunchLab Module 5.
 */
interface TextTranslator : Closeable {
    val name: String

    /**
     * Translates a single Japanese string to English.
     */
    suspend fun translate(text: String): String

    /**
     * Translates a list of Japanese strings to English with progress updates.
     */
    suspend fun translateBatch(
        texts: List<String>,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): List<String>
}
