package com.raen.kisaratranslator.data.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.raen.kisaratranslator.data.logger.AppLogger
import com.raen.kisaratranslator.data.model.TranslationModelType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TranslationModelManager(private val context: Context) {

    data class DownloadState(
        val isDownloading: Boolean = false,
        val progress: Float = 0f,
        val status: String = "",
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadJobs = ConcurrentHashMap<TranslationModelType, Job>()
    private val downloadStates = ConcurrentHashMap<TranslationModelType, MutableStateFlow<DownloadState>>()

    fun getDownloadState(type: TranslationModelType): StateFlow<DownloadState> {
        return downloadStates.getOrPut(type) { MutableStateFlow(DownloadState()) }.asStateFlow()
    }

    fun isDownloading(type: TranslationModelType): Boolean {
        return downloadJobs[type]?.isActive == true
    }

    fun getModelDir(type: TranslationModelType): File {
        return File(context.filesDir, type.subDir).apply { mkdirs() }
    }

    fun getModelFile(type: TranslationModelType): File {
        val internalFile = File(getModelDir(type), type.fileName)
        if (internalFile.exists() && internalFile.length() >= type.minSize) {
            return internalFile
        }

        try {
            val extAppDir = context.getExternalFilesDir(type.subDir)
            if (extAppDir != null) {
                val candidateApp = File(extAppDir, type.fileName)
                if (candidateApp.exists() && candidateApp.length() >= type.minSize && candidateApp.canRead()) {
                    return candidateApp
                }
            }

            val extRoot = Environment.getExternalStorageDirectory()
            val candidateRoot = File(extRoot, type.fileName)
            if (candidateRoot.exists() && candidateRoot.length() >= type.minSize && candidateRoot.canRead()) {
                try {
                    candidateRoot.inputStream().use { input ->
                        internalFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (internalFile.exists() && internalFile.length() >= type.minSize) {
                        return internalFile
                    }
                } catch (_: Exception) {}
            }

            val candidateDownload = File(File(extRoot, "Download"), type.fileName)
            if (candidateDownload.exists() && candidateDownload.length() >= type.minSize && candidateDownload.canRead()) {
                try {
                    candidateDownload.inputStream().use { input ->
                        internalFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (internalFile.exists() && internalFile.length() >= type.minSize) {
                        return internalFile
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }

        return internalFile
    }

    fun isModelReady(type: TranslationModelType): Boolean {
        val file = getModelFile(type)
        return file.exists() && file.length() >= type.minSize
    }

    fun isMangaOcrReady(): Boolean {
        val hasDecoder = isModelReady(TranslationModelType.MANGA_OCR_DECODER_QUANTIZED) ||
            isModelReady(TranslationModelType.MANGA_OCR_DECODER)
        return isModelReady(TranslationModelType.MANGA_OCR_ENCODER) &&
            hasDecoder &&
            isModelReady(TranslationModelType.MANGA_OCR_TOKENIZER)
    }

    fun isMangaOcrInt8Ready(): Boolean {
        return isModelReady(TranslationModelType.MANGA_OCR_ENCODER) &&
            isModelReady(TranslationModelType.MANGA_OCR_DECODER_QUANTIZED) &&
            isModelReady(TranslationModelType.MANGA_OCR_TOKENIZER)
    }

    fun is48pxCtcReady(): Boolean {
        return isModelReady(TranslationModelType.OCR_48PX_CTC) &&
            isModelReady(TranslationModelType.OCR_48PX_ALPHABET)
    }

    fun isPaddleOcrReady(): Boolean {
        return isModelReady(TranslationModelType.PADDLE_OCR_REC) &&
            isModelReady(TranslationModelType.PADDLE_OCR_DICT)
    }

    fun deleteModel(type: TranslationModelType): Boolean {
        val file = getModelFile(type)
        return file.delete()
    }

    fun startDownload(type: TranslationModelType, onComplete: ((Boolean) -> Unit)? = null) {
        if (isDownloading(type)) return
        val stateFlow = downloadStates.getOrPut(type) { MutableStateFlow(DownloadState()) }
        val job = downloadScope.launch {
            stateFlow.value = DownloadState(isDownloading = true, status = "Connecting...")
            var success = false
            AppLogger.info("Starting download for ${type.displayName}...")
            try {
                success = downloadModelInternal(type)
                if (success) {
                    AppLogger.success("Downloaded ${type.displayName} successfully!")
                } else {
                    AppLogger.error("Failed to download ${type.displayName} (mirrors exhausted)")
                }
            } catch (e: CancellationException) {
                stateFlow.value = DownloadState(isDownloading = false, status = "Cancelled")
                AppLogger.warn("Download cancelled for ${type.displayName}")
            } catch (e: Exception) {
                AppLogger.error("Download error for ${type.displayName}", e)
                stateFlow.value = DownloadState(isDownloading = false, status = "Error: ${e.message}")
            } finally {
                downloadJobs.remove(type)
                stateFlow.value = stateFlow.value.copy(
                    isDownloading = false,
                    progress = if (success) 1f else 0f,
                    status = if (success) "Completed" else stateFlow.value.status,
                )
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(success)
                }
            }
        }
        downloadJobs[type] = job
    }

    fun cancelDownload(type: TranslationModelType) {
        downloadJobs.remove(type)?.cancel()
        downloadStates[type]?.value = DownloadState(isDownloading = false, status = "Cancelled")
    }

    private suspend fun downloadModelInternal(type: TranslationModelType): Boolean = withContext(Dispatchers.IO) {
        val targetFile = File(getModelDir(type), type.fileName)
        val tempFile = File(getModelDir(type), "${type.fileName}.tmp")

        if (targetFile.exists() && targetFile.length() >= type.minSize) {
            return@withContext true
        }

        // Promote existing completed temp file if valid
        if (tempFile.exists() && tempFile.length() >= type.minSize) {
            if (targetFile.exists()) targetFile.delete()
            if (tempFile.renameTo(targetFile)) {
                return@withContext true
            }
        }

        val stateFlow = downloadStates.getOrPut(type) { MutableStateFlow(DownloadState()) }
        var downloaded = false

        for ((index, url) in type.mirrors.withIndex()) {
            try {
                stateFlow.value = stateFlow.value.copy(status = "Connecting (Mirror ${index + 1}/${type.mirrors.size})...")
                var startByte = 0L
                if (tempFile.exists()) {
                    startByte = tempFile.length()
                }

                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")

                if (startByte > 0) {
                    reqBuilder.header("Range", "bytes=$startByte-")
                }

                var response = client.newCall(reqBuilder.build()).execute()

                // If 416 Range Not Satisfiable (e.g. range past EOF or fully downloaded)
                if (response.code == 416) {
                    response.close()
                    if (tempFile.exists() && tempFile.length() >= type.minSize) {
                        if (targetFile.exists()) targetFile.delete()
                        if (tempFile.renameTo(targetFile)) {
                            downloaded = true
                            break
                        }
                    }
                    // Reset and fetch from scratch
                    tempFile.delete()
                    startByte = 0L
                    val freshReq = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                        .build()
                    response = client.newCall(freshReq).execute()
                }

                val isRangeSuccess = response.code == 206
                val isNormalSuccess = response.code == 200

                if (!isRangeSuccess && !isNormalSuccess) {
                    AppLogger.warn("Mirror ${index + 1} ($url) returned HTTP ${response.code}")
                    response.close()
                    continue
                }

                val body = response.body ?: run {
                    response.close()
                    continue
                }
                val contentLength = body.contentLength()
                val totalLength = if (isRangeSuccess) contentLength + startByte else contentLength
                val append = isRangeSuccess && startByte > 0

                val outputStream = FileOutputStream(tempFile, append)
                val inputStream = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var currentBytes = if (append) startByte else 0L

                outputStream.use { out ->
                    inputStream.use { inStream ->
                        while (inStream.read(buffer).also { bytesRead = it } != -1) {
                            ensureActive()
                            out.write(buffer, 0, bytesRead)
                            currentBytes += bytesRead

                            val progress = if (totalLength > 0) currentBytes.toFloat() / totalLength else 0f
                            stateFlow.value = stateFlow.value.copy(
                                isDownloading = true,
                                progress = progress,
                                downloadedBytes = currentBytes,
                                totalBytes = totalLength,
                                status = "${currentBytes / (1024 * 1024)} MB / ${totalLength / (1024 * 1024)} MB",
                            )
                        }
                        out.flush()
                    }
                }
                response.close()

                if (tempFile.exists() && tempFile.length() >= type.minSize) {
                    if (targetFile.exists()) targetFile.delete()
                    if (tempFile.renameTo(targetFile)) {
                        downloaded = true
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn("Mirror $url failed: ${e.message}")
                Log.w("TranslationModelManager", "Mirror $url failed: ${e.message}", e)
            }
        }

        downloaded
    }

    /**
     * Imports an external model file from local storage into the app's models directory.
     */
    suspend fun importExternalModel(type: TranslationModelType, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val destFile = File(getModelDir(type), type.fileName)
        val tempFile = File(getModelDir(type), "${type.fileName}.importing")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        ensureActive()
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }

            if (tempFile.exists() && tempFile.length() >= type.minSize) {
                if (destFile.exists()) destFile.delete()
                tempFile.renameTo(destFile)
            } else {
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e("TranslationModelManager", "Failed to import model $uri", e)
            tempFile.delete()
            false
        }
    }
}
