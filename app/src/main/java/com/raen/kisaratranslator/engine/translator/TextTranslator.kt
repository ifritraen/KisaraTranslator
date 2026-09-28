package com.raen.kisaratranslator.engine.translator

import com.raen.kisaratranslator.data.model.PageTranslation
import java.io.Closeable

interface TextTranslator : Closeable {
    val fromLang: String
    val toLang: String

    suspend fun translate(
        page: PageTranslation,
        onProgress: suspend (translatedBlocks: Int, totalBlocks: Int) -> Unit = { _, _ -> },
    )
}
