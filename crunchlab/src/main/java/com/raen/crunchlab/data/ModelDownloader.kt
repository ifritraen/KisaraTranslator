package com.raen.crunchlab.data

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class DownloadProgress(
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L
)

class ModelDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var activeJob: Job? = null
    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    companion object {
        const val MODEL_BUBBLE_SEG = "manga109_segmentation_bubble_1024.onnx"
        const val MODEL_COMIC_TEXT = "comic_text_detector.onnx"
        const val MODEL_WAIST = "manga_waist_model.onnx"

        const val MIN_SIZE_BUBBLE = 8 * 1024 * 1024L // ~8MB minimum
        const val MIN_SIZE = MIN_SIZE_BUBBLE
        const val MIN_SIZE_CTD = 40 * 1024 * 1024L   // ~40MB minimum
        const val MIN_SIZE_WAIST = 10 * 1024 * 1024L // ~12MB minimum

        // Exact mirrors from KisaraTranslator
        val MIRRORS_BUBBLE = listOf(
            "https://huggingface.co/mednasserallah/manga109-segmentation-bubble-onnx/resolve/main/manga109_segmentation_bubble_1024.onnx",
            "https://hf-mirror.com/mednasserallah/manga109-segmentation-bubble-onnx/resolve/main/manga109_segmentation_bubble_1024.onnx"
        )

        // Exact working HuggingFace mirrors from KisaraTranslator TranslationModelType.COMIC_TEXT_DETECTOR
        val MIRRORS_CTD = listOf(
            "https://huggingface.co/mayocream/comic-text-detector-onnx/resolve/main/comic-text-detector.onnx",
            "https://hf-mirror.com/mayocream/comic-text-detector-onnx/resolve/main/comic-text-detector.onnx",
            "https://huggingface.co/mayocream/comic-text-detector-onnx/raw/main/comic-text-detector.onnx",
        )

        // MangaOCR Models & Mirrors from KisaraTranslator TranslationModelType
        const val MODEL_MANGA_OCR_ENCODER = "encoder_model.onnx"
        const val MODEL_MANGA_OCR_DECODER = "decoder_model.onnx"
        const val MODEL_MANGA_OCR_DECODER_QUANTIZED = "decoder_model_quantized.onnx"
        const val MODEL_MANGA_OCR_TOKENIZER = "tokenizer.json"

        const val MIN_SIZE_OCR_ENCODER = 15 * 1024 * 1024L  // ~21 MB
        const val MIN_SIZE_OCR_DECODER = 50 * 1024 * 1024L  // ~113 MB (FP32 Original)
        const val MIN_SIZE_OCR_TOKENIZER = 50 * 1024L       // ~115 KB

        val MIRRORS_OCR_ENCODER = listOf(
            "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/encoder_model.onnx",
            "https://hf-mirror.com/l0wgear/manga-ocr-2025-onnx/resolve/main/encoder_model.onnx",
            "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/raw/main/encoder_model.onnx",
        )

        val MIRRORS_OCR_DECODER = listOf(
            "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/decoder_model.onnx",
            "https://hf-mirror.com/l0wgear/manga-ocr-2025-onnx/resolve/main/decoder_model.onnx",
            "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/raw/main/decoder_model.onnx",
        )

        val MIRRORS_OCR_TOKENIZER = listOf(
            "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/tokenizer.json",
            "https://hf-mirror.com/l0wgear/manga-ocr-2025-onnx/resolve/main/tokenizer.json",
            "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/raw/main/tokenizer.json",
        )
    }

    fun getModelFile(): File = getBubbleModelFile()

    fun getBubbleModelFile(): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val internalFile = File(dir, MODEL_BUBBLE_SEG)
        if (internalFile.exists() && internalFile.length() >= MIN_SIZE_BUBBLE) {
            return internalFile
        }

        try {
            val extRoot = Environment.getExternalStorageDirectory()
            val candidatePaths = listOf(
                File(context.getExternalFilesDir(null), "models/$MODEL_BUBBLE_SEG"),
                File(extRoot, "Android/data/com.raen.kisaratranslator/files/models/bubbleSeg/$MODEL_BUBBLE_SEG"),
                File(extRoot, "Android/data/com.raen.kisaratranslator.debug/files/models/bubbleSeg/$MODEL_BUBBLE_SEG"),
                File(extRoot, "Android/data/com.raen.kisara/files/models/bubbleSeg/$MODEL_BUBBLE_SEG"),
                File(extRoot, "Download/$MODEL_BUBBLE_SEG"),
                File(extRoot, MODEL_BUBBLE_SEG),
            )
            for (cand in candidatePaths) {
                if (cand.exists() && cand.length() >= MIN_SIZE_BUBBLE && cand.canRead()) {
                    cand.inputStream().use { input ->
                        internalFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (internalFile.exists() && internalFile.length() >= MIN_SIZE_BUBBLE) {
                        return internalFile
                    }
                }
            }
        } catch (_: Exception) {}

        return internalFile
    }

    fun getComicTextModelFile(): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val internalFile = File(dir, MODEL_COMIC_TEXT)
        if (internalFile.exists() && internalFile.length() >= MIN_SIZE_CTD) {
            return internalFile
        }

        try {
            val extRoot = Environment.getExternalStorageDirectory()
            val candidatePaths = listOf(
                File(context.getExternalFilesDir(null), "models/$MODEL_COMIC_TEXT"),
                File(context.getExternalFilesDir(null), "models/comic-text-detector.onnx"),
                File(extRoot, "comic_text_detector.onnx"),
                File(extRoot, "comic-text-detector.onnx"),
                File(extRoot, "Download/comic_text_detector.onnx"),
                File(extRoot, "Download/comic-text-detector.onnx"),
                File(extRoot, "Android/data/com.raen.kisaratranslator/files/models/ai/comic_text_detector.onnx"),
                File(extRoot, "Android/data/com.raen.kisaratranslator/files/models/ai/comic-text-detector.onnx"),
                File(extRoot, "Android/data/com.raen.kisaratranslator.debug/files/models/ai/comic_text_detector.onnx"),
                File(extRoot, "Android/data/com.raen.kisaratranslator.debug/files/models/ai/comic-text-detector.onnx"),
                File(extRoot, "Android/data/com.raen.kisara/files/models/ai/comic_text_detector.onnx"),
            )
            for (cand in candidatePaths) {
                if (cand.exists() && cand.length() >= MIN_SIZE_CTD && cand.canRead()) {
                    cand.inputStream().use { input ->
                        internalFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (internalFile.exists() && internalFile.length() >= MIN_SIZE_CTD) {
                        return internalFile
                    }
                }
            }
        } catch (_: Exception) {}

        return internalFile
    }

    fun getWaistModelFile(): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val internalFile = File(dir, MODEL_WAIST)
        if (internalFile.exists() && internalFile.length() >= MIN_SIZE_WAIST) {
            return internalFile
        }

        try {
            val extRoot = Environment.getExternalStorageDirectory()
            val candidatePaths = listOf(
                File(context.getExternalFilesDir(null), "models/$MODEL_WAIST"),
                File(extRoot, "Download/$MODEL_WAIST"),
                File(extRoot, MODEL_WAIST),
                File(extRoot, "Download/manga_waist_model.onnx"),
                File(extRoot, "Download/best.onnx"),
            )
            for (cand in candidatePaths) {
                if (cand.exists() && cand.length() >= MIN_SIZE_WAIST && cand.canRead()) {
                    cand.inputStream().use { input ->
                        internalFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (internalFile.exists() && internalFile.length() >= MIN_SIZE_WAIST) {
                        return internalFile
                    }
                }
            }
        } catch (_: Exception) {}

        return internalFile
    }

    fun getMangaOcrDir(): File {
        return File(context.filesDir, "models/mangaocr").apply { mkdirs() }
    }

    fun getMangaOcrEncoderFile(): File {
        val dir = getMangaOcrDir()
        val internalFile = File(dir, MODEL_MANGA_OCR_ENCODER)
        if (internalFile.exists() && internalFile.length() >= MIN_SIZE_OCR_ENCODER) {
            return internalFile
        }

        try {
            val extRoot = Environment.getExternalStorageDirectory()
            val candidatePaths = listOf(
                File(context.getExternalFilesDir(null), "models/mangaocr/$MODEL_MANGA_OCR_ENCODER"),
                File(extRoot, "Android/data/com.raen.kisaratranslator/files/models/mangaocr/$MODEL_MANGA_OCR_ENCODER"),
                File(extRoot, "Android/data/com.raen.kisaratranslator.debug/files/models/mangaocr/$MODEL_MANGA_OCR_ENCODER"),
                File(extRoot, "Download/KisaraTranslator/mangaocr/$MODEL_MANGA_OCR_ENCODER"),
                File(extRoot, "Download/$MODEL_MANGA_OCR_ENCODER"),
                File(extRoot, MODEL_MANGA_OCR_ENCODER),
            )
            for (cand in candidatePaths) {
                if (cand.exists() && cand.length() >= MIN_SIZE_OCR_ENCODER && cand.canRead()) {
                    cand.inputStream().use { input ->
                        internalFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (internalFile.exists() && internalFile.length() >= MIN_SIZE_OCR_ENCODER) {
                        return internalFile
                    }
                }
            }
        } catch (_: Exception) {}

        return internalFile
    }

    fun getMangaOcrDecoderFile(): File {
        val dir = getMangaOcrDir()
        val internalFile = File(dir, MODEL_MANGA_OCR_DECODER)
        if (internalFile.exists() && internalFile.length() >= MIN_SIZE_OCR_DECODER) {
            return internalFile
        }

        try {
            val extRoot = Environment.getExternalStorageDirectory()
            val candidatePaths = listOf(
                File(context.getExternalFilesDir(null), "models/mangaocr/$MODEL_MANGA_OCR_DECODER"),
                File(extRoot, "Android/data/com.raen.kisaratranslator/files/models/mangaocr/$MODEL_MANGA_OCR_DECODER"),
                File(extRoot, "Android/data/com.raen.kisaratranslator.debug/files/models/mangaocr/$MODEL_MANGA_OCR_DECODER"),
                File(extRoot, "Download/KisaraTranslator/mangaocr/$MODEL_MANGA_OCR_DECODER"),
                File(extRoot, "Download/$MODEL_MANGA_OCR_DECODER"),
                File(extRoot, MODEL_MANGA_OCR_DECODER),
                // Quantized fallback if large FP32 not yet present
                File(context.getExternalFilesDir(null), "models/mangaocr/$MODEL_MANGA_OCR_DECODER_QUANTIZED"),
                File(extRoot, "Download/KisaraTranslator/mangaocr/$MODEL_MANGA_OCR_DECODER_QUANTIZED"),
                File(extRoot, "Download/$MODEL_MANGA_OCR_DECODER_QUANTIZED"),
            )
            for (cand in candidatePaths) {
                if (cand.exists() && cand.length() >= 20 * 1024 * 1024L && cand.canRead()) {
                    cand.inputStream().use { input ->
                        internalFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (internalFile.exists() && internalFile.length() >= 20 * 1024 * 1024L) {
                        return internalFile
                    }
                }
            }
        } catch (_: Exception) {}

        return internalFile
    }

    fun getMangaOcrTokenizerFile(): File {
        val dir = getMangaOcrDir()
        val internalFile = File(dir, MODEL_MANGA_OCR_TOKENIZER)
        if (internalFile.exists() && internalFile.length() >= MIN_SIZE_OCR_TOKENIZER) {
            return internalFile
        }

        try {
            val extRoot = Environment.getExternalStorageDirectory()
            val candidatePaths = listOf(
                File(context.getExternalFilesDir(null), "models/mangaocr/$MODEL_MANGA_OCR_TOKENIZER"),
                File(extRoot, "Android/data/com.raen.kisaratranslator/files/models/mangaocr/$MODEL_MANGA_OCR_TOKENIZER"),
                File(extRoot, "Android/data/com.raen.kisaratranslator.debug/files/models/mangaocr/$MODEL_MANGA_OCR_TOKENIZER"),
                File(extRoot, "Download/KisaraTranslator/mangaocr/$MODEL_MANGA_OCR_TOKENIZER"),
                File(extRoot, "Download/$MODEL_MANGA_OCR_TOKENIZER"),
                File(extRoot, MODEL_MANGA_OCR_TOKENIZER),
            )
            for (cand in candidatePaths) {
                if (cand.exists() && cand.length() >= MIN_SIZE_OCR_TOKENIZER && cand.canRead()) {
                    cand.inputStream().use { input ->
                        internalFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (internalFile.exists() && internalFile.length() >= MIN_SIZE_OCR_TOKENIZER) {
                        return internalFile
                    }
                }
            }
        } catch (_: Exception) {}

        return internalFile
    }

    fun isMangaOcrReady(): Boolean {
        val enc = getMangaOcrEncoderFile()
        val dec = getMangaOcrDecoderFile()
        val tok = getMangaOcrTokenizerFile()
        return enc.exists() && enc.length() >= MIN_SIZE_OCR_ENCODER &&
               dec.exists() && dec.length() >= MIN_SIZE_OCR_DECODER &&
               tok.exists() && tok.length() >= MIN_SIZE_OCR_TOKENIZER
    }

    fun startDownloadMangaOcr(onComplete: () -> Unit = {}) {
        if (isMangaOcrReady() || activeJob?.isActive == true) {
            if (isMangaOcrReady()) onComplete()
            return
        }

        activeJob = scope.launch {
            // 1. Encoder
            if (getMangaOcrEncoderFile().length() < MIN_SIZE_OCR_ENCODER) {
                val ok = downloadSingleModel(
                    targetFile = getMangaOcrEncoderFile(),
                    minSize = MIN_SIZE_OCR_ENCODER,
                    mirrors = MIRRORS_OCR_ENCODER,
                    modelLabel = "MangaOCR ViT Encoder"
                )
                if (!ok) {
                    _progress.value = DownloadProgress(isDownloading = false, status = "Failed to download MangaOCR Encoder")
                    return@launch
                }
            }

            // 2. Decoder Quantized
            if (getMangaOcrDecoderFile().length() < MIN_SIZE_OCR_DECODER) {
                val ok = downloadSingleModel(
                    targetFile = getMangaOcrDecoderFile(),
                    minSize = MIN_SIZE_OCR_DECODER,
                    mirrors = MIRRORS_OCR_DECODER,
                    modelLabel = "MangaOCR INT8 Decoder"
                )
                if (!ok) {
                    _progress.value = DownloadProgress(isDownloading = false, status = "Failed to download MangaOCR Decoder")
                    return@launch
                }
            }

            // 3. Tokenizer
            if (getMangaOcrTokenizerFile().length() < MIN_SIZE_OCR_TOKENIZER) {
                val ok = downloadSingleModel(
                    targetFile = getMangaOcrTokenizerFile(),
                    minSize = MIN_SIZE_OCR_TOKENIZER,
                    mirrors = MIRRORS_OCR_TOKENIZER,
                    modelLabel = "MangaOCR Tokenizer"
                )
                if (!ok) {
                    _progress.value = DownloadProgress(isDownloading = false, status = "Failed to download MangaOCR Tokenizer")
                    return@launch
                }
            }

            _progress.value = DownloadProgress(isDownloading = false, progress = 1f, status = "MangaOCR Ready!")
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun isModelReady(): Boolean = isBubbleModelReady()

    fun isBubbleModelReady(): Boolean {
        val file = getBubbleModelFile()
        return file.exists() && file.length() >= MIN_SIZE_BUBBLE
    }

    fun isComicTextModelReady(): Boolean {
        val file = getComicTextModelFile()
        return file.exists() && file.length() >= MIN_SIZE_CTD
    }

    fun isWaistModelReady(): Boolean {
        val file = getWaistModelFile()
        return file.exists() && file.length() >= MIN_SIZE_WAIST
    }

    fun isAllModelsReady(): Boolean = isBubbleModelReady() && isComicTextModelReady()

    fun startDownload(onComplete: () -> Unit = {}) {
        startDownloadAll(onComplete)
    }

    fun startDownloadAll(onComplete: () -> Unit = {}) {
        if (isAllModelsReady() || activeJob?.isActive == true) {
            if (isAllModelsReady()) onComplete()
            return
        }

        activeJob = scope.launch {
            // 1. Download Bubble Seg if missing
            if (!isBubbleModelReady()) {
                val ok = downloadSingleModel(
                    targetFile = getBubbleModelFile(),
                    minSize = MIN_SIZE_BUBBLE,
                    mirrors = MIRRORS_BUBBLE,
                    modelLabel = "Manga109 Bubble Seg"
                )
                if (!ok) {
                    _progress.value = DownloadProgress(isDownloading = false, status = "Failed to download Bubble Seg model")
                    return@launch
                }
            }

            // 2. Download Comic Text Detector if missing
            if (!isComicTextModelReady()) {
                val ok = downloadSingleModel(
                    targetFile = getComicTextModelFile(),
                    minSize = MIN_SIZE_CTD,
                    mirrors = MIRRORS_CTD,
                    modelLabel = "Comic Text Detector"
                )
                if (!ok) {
                    _progress.value = DownloadProgress(isDownloading = false, status = "Failed to download Comic Text model")
                    return@launch
                }
            }

            _progress.value = DownloadProgress(isDownloading = false, progress = 1f, status = "All models ready!")
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    private suspend fun downloadSingleModel(
        targetFile: File,
        minSize: Long,
        mirrors: List<String>,
        modelLabel: String
    ): Boolean {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        _progress.value = DownloadProgress(isDownloading = true, status = "Connecting to $modelLabel...")

        for (url in mirrors) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w("CrunchLab", "Mirror $url returned HTTP ${response.code}")
                    response.close()
                    continue
                }

                val body = response.body ?: continue
                val total = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var downloaded = 0L

                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read

                            val frac = if (total > 0) downloaded.toFloat() / total else 0f
                            _progress.value = DownloadProgress(
                                isDownloading = true,
                                progress = frac,
                                status = "$modelLabel: ${(downloaded / (1024 * 1024f)).toInt()}MB / ${(total / (1024 * 1024f)).toInt()}MB",
                                downloadedBytes = downloaded,
                                totalBytes = total,
                            )
                        }
                    }
                }

                if (tempFile.length() >= minSize) {
                    if (targetFile.exists()) targetFile.delete()
                    val renamed = tempFile.renameTo(targetFile)
                    if (!renamed) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                    Log.i("CrunchLab", "Successfully saved $modelLabel (${targetFile.length() / 1024}KB)")
                    return true
                }
            } catch (e: Exception) {
                Log.e("CrunchLab", "Download error from $url: ${e.message}")
            }
        }
        return false
    }
}
