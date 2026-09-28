package com.raen.kisaratranslator.data.service

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.raen.kisaratranslator.core.util.ImageUtils
import com.raen.kisaratranslator.core.wakelock.WakeLockManager
import com.raen.kisaratranslator.data.model.PipelineConfig
import com.raen.kisaratranslator.data.model.TranslationReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BatchProgress(
    val isRunning: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val currentFileName: String = "",
    val statusText: String = "",
    val percent: Float = 0f,
    val completedFiles: List<File> = emptyList(),
)

class BatchTranslationService(
    private val context: Context,
    private val translationService: TranslationService,
    private val wakeLockManager: WakeLockManager,
) {

    private val _progress = MutableStateFlow(BatchProgress())
    val progress: StateFlow<BatchProgress> = _progress.asStateFlow()

    private var cancelRequested = false

    fun cancel() {
        cancelRequested = true
        _progress.value = _progress.value.copy(statusText = "Cancelling batch...")
    }

    suspend fun processFolder(
        treeUri: Uri,
        config: PipelineConfig,
    ) = wakeLockManager.withWakeLock("batchFolder") {
        withContext(Dispatchers.IO) {
            cancelRequested = false
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: run {
                    _progress.value = BatchProgress(statusText = "Cannot access selected directory")
                    return@withContext
                }

            val imageFiles = rootDoc.listFiles()
                .filter { it.isFile && isImageFile(it.name ?: "") }
                .sortedWith { a, b -> ImageUtils.naturalCompare(a.name ?: "", b.name ?: "") }

            if (imageFiles.isEmpty()) {
                _progress.value = BatchProgress(statusText = "No image files found in folder")
                return@withContext
            }

            processDocuments(imageFiles, config)
        }
    }

    suspend fun processUris(
        uris: List<Uri>,
        config: PipelineConfig,
    ) = wakeLockManager.withWakeLock("batchUris") {
        withContext(Dispatchers.IO) {
            cancelRequested = false
            if (uris.isEmpty()) return@withContext

            val total = uris.size
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outDir = File(context.getExternalFilesDir(null), "BatchTranslated_$timeStamp").apply { mkdirs() }
            val completed = mutableListOf<File>()

            _progress.value = BatchProgress(
                isRunning = true,
                current = 0,
                total = total,
                statusText = "Starting batch translation of $total images...",
                percent = 0f,
            )

            try {
                for ((index, uri) in uris.withIndex()) {
                    if (cancelRequested) break
                    ensureActive()

                    val fileName = "page_${index + 1}.jpg"
                    _progress.value = _progress.value.copy(
                        current = index + 1,
                        currentFileName = fileName,
                        statusText = "Translating $fileName (${index + 1}/$total)...",
                        percent = (index.toFloat() / total),
                    )

                    val bitmap = ImageUtils.decodeBitmapFromUri(context, uri) ?: continue
                    val result = translationService.translateBitmap(bitmap, config) { step ->
                        _progress.value = _progress.value.copy(
                            statusText = "Page ${index + 1}/$total ($fileName) • $step",
                        )
                    }

                    val outFile = File(outDir, fileName)
                    FileOutputStream(outFile).use { outStream ->
                        result.translatedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, outStream)
                        outStream.flush()
                    }
                    completed.add(outFile)

                    bitmap.recycle()
                    result.translatedBitmap.recycle()
                }

                _progress.value = BatchProgress(
                    isRunning = false,
                    current = completed.size,
                    total = total,
                    statusText = if (cancelRequested) "Batch cancelled (${completed.size}/$total translated)" else "Completed! Saved ${completed.size} pages",
                    percent = 1f,
                    completedFiles = completed,
                )
            } catch (e: CancellationException) {
                _progress.value = BatchProgress(isRunning = false, statusText = "Batch cancelled")
            } catch (e: Exception) {
                TranslationReport.log("ERROR", "BatchTranslation", "Batch error: ${e.message}", e)
                _progress.value = BatchProgress(isRunning = false, statusText = "Error: ${e.message}")
            }
        }
    }

    private suspend fun processDocuments(
        docs: List<DocumentFile>,
        config: PipelineConfig,
    ) = kotlinx.coroutines.coroutineScope {
        val total = docs.size
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(context.getExternalFilesDir(null), "BatchTranslated_$timeStamp").apply { mkdirs() }
        val completed = mutableListOf<File>()

        _progress.value = BatchProgress(
            isRunning = true,
            current = 0,
            total = total,
            statusText = "Starting batch translation of $total images...",
            percent = 0f,
        )

        try {
            for ((index, doc) in docs.withIndex()) {
                if (cancelRequested) break
                ensureActive()

                val fileName = doc.name ?: "page_${index + 1}.jpg"
                _progress.value = _progress.value.copy(
                    current = index + 1,
                    currentFileName = fileName,
                    statusText = "Translating $fileName (${index + 1}/$total)...",
                    percent = (index.toFloat() / total),
                )

                val bitmap = ImageUtils.decodeBitmapFromUri(context, doc.uri) ?: continue
                val result = translationService.translateBitmap(bitmap, config) { step ->
                    _progress.value = _progress.value.copy(
                        statusText = "Page ${index + 1}/$total ($fileName) • $step",
                    )
                }

                val outFile = File(outDir, fileName)
                FileOutputStream(outFile).use { outStream ->
                    result.translatedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, outStream)
                    outStream.flush()
                }
                completed.add(outFile)

                bitmap.recycle()
                result.translatedBitmap.recycle()
            }

            _progress.value = BatchProgress(
                isRunning = false,
                current = completed.size,
                total = total,
                statusText = if (cancelRequested) "Batch cancelled (${completed.size}/$total translated)" else "Completed! Saved ${completed.size} pages",
                percent = 1f,
                completedFiles = completed,
            )
            com.raen.kisaratranslator.data.logger.AppLogger.success("Batch completed: Saved ${completed.size}/$total pages to ${outDir.name}")
        } catch (e: Exception) {
            _progress.value = BatchProgress(isRunning = false, statusText = "Error: ${e.message}")
            com.raen.kisaratranslator.data.logger.AppLogger.error("Batch failed", e)
        } finally {
            translationService.optimizeMemory()
        }
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".avif")
    }
}
