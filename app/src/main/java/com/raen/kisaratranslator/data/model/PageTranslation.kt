package com.raen.kisaratranslator.data.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
data class PageTranslation(
    var blocks: MutableList<TranslationBlock> = mutableListOf(),
    var imgWidth: Float = 0f,
    var imgHeight: Float = 0f,
) {
    companion object {
        val EMPTY = PageTranslation()
    }
}

@Serializable
data class TranslationBlock(
    var text: String,
    var translation: String = "",
    var width: Float,
    var height: Float,
    var x: Float,
    var y: Float,
    var symHeight: Float = 0f,
    var symWidth: Float = 0f,
    val angle: Float = 0f,
    var isBubble: Boolean = false,
)

fun PageTranslation.deepCopy(): PageTranslation {
    return PageTranslation(
        blocks = blocks.map { it.copy() }.toMutableList(),
        imgWidth = imgWidth,
        imgHeight = imgHeight,
    )
}

data class OcrCropDebug(
    val index: Int,
    val rect: android.graphics.Rect,
    val cropBitmap: android.graphics.Bitmap,
    val rawText: String,
)

data class LogEntry(
    val level: String,
    val tag: String,
    val message: String,
    val timestamp: String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()),
    val throwable: Throwable? = null,
)

object TranslationReport {
    private val _logs = CopyOnWriteArrayList<LogEntry>()
    val logs: List<LogEntry> get() = _logs

    private val _logFlow = MutableSharedFlow<LogEntry>(replay = 50, extraBufferCapacity = 100)
    val logFlow = _logFlow.asSharedFlow()

    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(level, tag, message, throwable = throwable)
        _logs.add(entry)
        if (_logs.size > 1000) {
            _logs.removeAt(0)
        }
        _logFlow.tryEmit(entry)
    }

    fun clear() {
        _logs.clear()
    }
}
