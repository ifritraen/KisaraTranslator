package com.raen.crunchlab.data

/**
 * Text and dialogue block representation matching Kisara Translator's TranslationBlock.
 */
data class TranslationBlock(
    var text: String = "",
    var translation: String = "",
    var width: Float = 0f,
    var height: Float = 0f,
    var x: Float = 0f,
    var y: Float = 0f,
    var symHeight: Float = 0f,
    var symWidth: Float = 0f,
    val angle: Float = 0f,
    var isBubble: Boolean = false,
)
