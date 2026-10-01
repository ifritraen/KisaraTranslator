package com.raen.crunchlab.engine.recognizer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.raen.crunchlab.data.DialogueGroupItem
import com.raen.crunchlab.data.DialogueLineItem
import com.raen.crunchlab.data.ModelDownloader
import com.raen.crunchlab.data.OcrCropDebugItem
import com.raen.crunchlab.data.TranslationBlock
import com.raen.crunchlab.util.AiBufferUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

data class DialogueOcrResult(
    val blocks: List<TranslationBlock>,
    val crops: List<OcrCropDebugItem>,
    val updatedGroups: List<DialogueGroupItem>,
    val durationMs: Long
)

data class ProbedBoxResult(
    val isValid: Boolean,
    val text: String,
    val crop: Bitmap? = null,
)

/**
 * MangaOCR ONNX text recognizer for CrunchLab.
 * Executes ViT encoder + autoregressive sequence decoder with tokenizer.
 * Includes probeBoxWithOcr for Step 1.3 mini-test validation of neighbor candidate boxes.
 */
class MangaOcrEngine(
    private val modelDownloader: ModelDownloader,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Closeable {

    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: SimpleTokenizer? = null

    var configuredThreads: Int = 4
    var hardwareDelegate: com.raen.crunchlab.engine.profile.HardwareDelegate = com.raen.crunchlab.engine.profile.HardwareDelegate.XNNPACK

    fun setResourceConfig(threads: Int, delegate: com.raen.crunchlab.engine.profile.HardwareDelegate = hardwareDelegate) {
        val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val safeThreads = threads.coerceIn(1, numCores)
        if (safeThreads != configuredThreads || delegate != hardwareDelegate) {
            configuredThreads = safeThreads
            hardwareDelegate = delegate
            close()
        }
    }

    fun setSpeedLevel(level: Int) {
        val numCores = Runtime.getRuntime().availableProcessors()
        val targetThreads = when (level.coerceIn(1, 5)) {
            1 -> 1
            2 -> 2
            3 -> 3.coerceAtMost(numCores)
            4 -> 4.coerceAtMost(numCores)
            else -> 4.coerceAtMost(numCores)
        }
        setResourceConfig(targetThreads, hardwareDelegate)
    }

    val isReady: Boolean
        get() = modelDownloader.isMangaOcrReady()

    private fun ensureSessions(requireInt8: Boolean = false) {
        val encoderFile = modelDownloader.getMangaOcrEncoderFile()
        if (!encoderFile.exists() || encoderFile.length() < ModelDownloader.MIN_SIZE_OCR_ENCODER) {
            throw IllegalStateException("MangaOCR Encoder (encoder_model.onnx) is missing. Please download it first.")
        }
        val decoderFile = modelDownloader.getMangaOcrDecoderFile()
        if (!decoderFile.exists() || decoderFile.length() < 20 * 1024 * 1024L) {
            throw IllegalStateException("MangaOCR Decoder (decoder_model.onnx) is missing. Please download it first.")
        }
        val tokenizerFile = modelDownloader.getMangaOcrTokenizerFile()
        if (!tokenizerFile.exists() || tokenizerFile.length() < ModelDownloader.MIN_SIZE_OCR_TOKENIZER) {
            throw IllegalStateException("MangaOCR Tokenizer (tokenizer.json) is missing. Please download it first.")
        }

        val numCores = Runtime.getRuntime().availableProcessors()
        val threads = configuredThreads.coerceIn(1, numCores)

        if (encoderSession == null) {
            val opts = AiBufferUtils.createSessionOptions(threads, hardwareDelegate)
            try {
                encoderSession = env.createSession(encoderFile.absolutePath, opts)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to initialize MangaOCR Encoder ONNX session: ${e.message}", e)
            } finally {
                opts.close()
            }
        }
        if (decoderSession == null) {
            val opts = AiBufferUtils.createSessionOptions(threads, hardwareDelegate)
            try {
                decoderSession = env.createSession(decoderFile.absolutePath, opts)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to initialize MangaOCR Decoder ONNX session: ${e.message}", e)
            } finally {
                opts.close()
            }
        }
        if (tokenizer == null) {
            try {
                tokenizer = SimpleTokenizer.load(tokenizerFile)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to load MangaOCR Tokenizer: ${e.message}", e)
            }
        }
    }

    suspend fun recognize(crop: Bitmap, requireInt8: Boolean = true, maxTokens: Int = 16): String = withContext(Dispatchers.Default) {
        if (isBlankOrSolid(crop)) return@withContext ""

        ensureSessions(requireInt8)
        val enc = encoderSession ?: throw IllegalStateException("MangaOCR Encoder session not ready")
        val dec = decoderSession ?: throw IllegalStateException("MangaOCR Decoder session not ready")
        val tok = tokenizer ?: throw IllegalStateException("MangaOCR Tokenizer not ready")

        val processed = preprocessBitmap(crop)
        val inputTensor = bitmapToTensor(processed)

        return@withContext try {
            // Encode
            val encoderOutput = enc.run(mapOf("pixel_values" to inputTensor))
            val hiddenStates = encoderOutput["last_hidden_state"].get() as OnnxTensor

            try {
                // Autoregressive decode with zero-allocation buffers
                val maxLen = maxTokens
                val decodedIds = ArrayList<Int>(maxLen + 4).apply { add(tok.bosTokenId) }
                val eosId = tok.eosTokenId

                val inputIdsBuf = LongBuffer.allocate(maxLen + 1)
                var lastTokenVocabBuf: FloatArray? = null

                for (step in 0 until maxLen) {
                    coroutineContext.ensureActive()
                    inputIdsBuf.clear()
                    for (id in decodedIds) {
                        inputIdsBuf.put(id.toLong())
                    }
                    inputIdsBuf.flip()

                    val inputIdsTensor = OnnxTensor.createTensor(
                        env,
                        inputIdsBuf,
                        longArrayOf(1L, decodedIds.size.toLong()),
                    )
                    val decOut = dec.run(
                        mapOf(
                            "input_ids" to inputIdsTensor,
                            "encoder_hidden_states" to hiddenStates,
                        ),
                    )
                    try {
                        val logits = decOut["logits"].get() as OnnxTensor
                        val shape = logits.info.shape
                        val seqLen = shape[1].toInt()
                        val vocabSize = shape[2].toInt()
                        val logitsBuffer = logits.floatBuffer

                        if (lastTokenVocabBuf == null || lastTokenVocabBuf.size != vocabSize) {
                            lastTokenVocabBuf = FloatArray(vocabSize)
                        }

                        // Seek directly to the last token's slice without reading past tokens
                        val lastPos = (seqLen - 1) * vocabSize
                        logitsBuffer.position(lastPos)
                        logitsBuffer.get(lastTokenVocabBuf, 0, vocabSize)

                        var maxVal = Float.NEGATIVE_INFINITY
                        var maxIdx = 0
                        for (i in 0 until vocabSize) {
                            val score = lastTokenVocabBuf[i]
                            if (score > maxVal) {
                                maxVal = score
                                maxIdx = i
                            }
                        }

                        logits.close()

                        if (maxIdx == eosId) break

                        // Repetition loop guard: if last 3 tokens are identical, terminate early
                        if (decodedIds.size >= 3 &&
                            decodedIds[decodedIds.size - 1] == maxIdx &&
                            decodedIds[decodedIds.size - 2] == maxIdx
                        ) {
                            break
                        }

                        decodedIds.add(maxIdx)
                    } finally {
                        inputIdsTensor.close()
                        decOut.close()
                    }
                }

                tok.decode(decodedIds.drop(1)) // drop BOS
            } finally {
                hiddenStates.close()
                encoderOutput.close()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("MangaOcrEngine", "OCR recognition error", e)
            throw IllegalStateException("MangaOCR inference failed: ${e.message}", e)
        } finally {
            inputTensor.close()
            processed.recycle()
        }
    }

    /**
     * Executes Mini-Test probe on a candidate box:
     * 1. Crops region with 20% margin clamped to [2, 12] px.
     * 2. Upscales 1.8x with bilinear filtering (min 48 px).
     * 3. Runs INT8 decoder with up to [maxTokens] tokens.
     * 4. Assesses whether recognized string contains valid Japanese CJK or alphanumeric text.
     */
    suspend fun probeBoxWithOcr(bitmap: Bitmap, box: Rect, maxTokens: Int = 12): ProbedBoxResult {
        if (box.width() < 6 || box.height() < 6) return ProbedBoxResult(false, "", null)
        val padX = (box.width() * 0.20f).toInt().coerceIn(2, 12)
        val padY = (box.height() * 0.20f).toInt().coerceIn(2, 12)
        val safeLeft = (box.left - padX).coerceIn(0, bitmap.width - 1)
        val safeTop = (box.top - padY).coerceIn(0, bitmap.height - 1)
        val safeRight = (box.right + padX).coerceIn(safeLeft + 1, bitmap.width)
        val safeBottom = (box.bottom + padY).coerceIn(safeTop + 1, bitmap.height)
        val rawCrop = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

        // Smart Probing with 1.8x contrast-enhanced zoom for small/faint text
        val targetW = (rawCrop.width * 1.8f).toInt().coerceAtLeast(48)
        val targetH = (rawCrop.height * 1.8f).toInt().coerceAtLeast(48)
        val zoomedCrop = Bitmap.createScaledBitmap(rawCrop, targetW, targetH, true)

        return try {
            val text = recognize(zoomedCrop, requireInt8 = false, maxTokens = maxTokens).trim()
            val isValid = text.isNotBlank() && text.any { c ->
                (c in '\u3040'..'\u309F') || // Hiragana
                (c in '\u30A0'..'\u30FF') || // Katakana
                (c in '\u4E00'..'\u9FFF') || // Kanji
                (c in '\uFF65'..'\uFF9F') || // Half-width Katakana
                (c in '\u3000'..'\u303F') || // CJK symbols & punctuation (『』【】、。)
                (c in '\uFF01'..'\uFF5E') || // Full-width ASCII & symbols (！, ？, etc.)
                c.isLetterOrDigit()          // Alphanumeric / Latin text
            }
            val debugCrop = rawCrop.copy(rawCrop.config ?: Bitmap.Config.ARGB_8888, false)
            ProbedBoxResult(isValid, text, debugCrop)
        } catch (e: Exception) {
            ProbedBoxResult(false, "", null)
        } finally {
            rawCrop.recycle()
            zoomedCrop.recycle()
        }
    }

    /**
     * Executes MangaOCR text recognition over individual vertical lines (Screen 4.2).
     *
     * Core Architectural Invariant:
     * - MangaOCR ViT model is trained strictly on isolated single lines.
     * - Multi-line crops cause attention scrambling, skipped columns, and hallucinated order.
     * - Therefore, EVERY vertical line in each dialogue group is cropped individually
     *   with typography safety padding (padX = 4, padY = 8).
     * - Each single-line crop is recognized independently via MangaOCR (concurrency-throttled).
     * - Recognized single-line texts are sorted by RTL reading order (#G[id].1, #G[id].2...)
     *   and joined into group.recognizedText without spaces.
     * - The joined group.recognizedText represents the full coherent dialogue utterance,
     *   ready to be fed downstream into Module 5 translation (Google / ML Kit / Sugoi).
     */
    suspend fun executeDialogueOcr(
        bitmap: Bitmap,
        groups: List<DialogueGroupItem>,
        chunkLinesCount: Int = 1,
        onProgress: (done: Int, total: Int, currentText: String) -> Unit = { _, _, _ -> },
    ): DialogueOcrResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        if (groups.isEmpty()) {
            return@withContext DialogueOcrResult(emptyList(), emptyList(), emptyList(), 0L)
        }

        ensureSessions(requireInt8 = true)
        val semaphore = Semaphore(2)

        data class LineTask(
            val groupIndex: Int,
            val group: DialogueGroupItem,
            val line: DialogueLineItem,
            val globalIndex: Int
        )

        val tasks = mutableListOf<LineTask>()
        var taskCounter = 0
        groups.forEachIndexed { gIdx, group ->
            val sortedLines = group.lines.sortedBy { it.readingOrder }
            sortedLines.forEach { line ->
                tasks.add(LineTask(gIdx, group, line, taskCounter++))
            }
        }

        val totalLines = tasks.size
        val completedLinesCounter = AtomicInteger(0)

        // Recognize each single vertical line independently
        val lineResults = coroutineScope {
            tasks.map { task ->
                async(Dispatchers.Default) {
                    coroutineContext.ensureActive()
                    val line = task.line
                    val group = task.group

                    // Single-line crop with safe typography padding
                    val padX = 4
                    val padY = 8
                    val safeLeft = (line.rect.left - padX).coerceIn(0, bitmap.width - 1)
                    val safeTop = (line.rect.top - padY).coerceIn(0, bitmap.height - 1)
                    val safeRight = (line.rect.right + padX).coerceIn(safeLeft + 1, bitmap.width)
                    val safeBottom = (line.rect.bottom + padY).coerceIn(safeTop + 1, bitmap.height)
                    val cropRect = Rect(safeLeft, safeTop, safeRight, safeBottom)

                    val crop = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

                    val text = if (isBlankOrSolid(crop)) {
                        ""
                    } else {
                        try {
                            semaphore.withPermit {
                                recognize(crop, requireInt8 = true, maxTokens = 48).trim()
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.w("MangaOcrEngine", "Error recognizing line #${line.lineId} in group #${group.groupId}: ${e.message}")
                            ""
                        }
                    }

                    val done = completedLinesCounter.incrementAndGet()
                    onProgress(done, totalLines, text)

                    val cropDebug = OcrCropDebugItem(
                        index = task.globalIndex,
                        groupId = group.groupId,
                        rect = cropRect,
                        cropBitmap = crop.copy(crop.config ?: Bitmap.Config.ARGB_8888, false),
                        rawText = text,
                        isBubble = group.isBubble,
                        lineId = line.lineId,
                        readingOrder = line.readingOrder,
                    )
                    crop.recycle()

                    val updatedLine = line.copy(recognizedText = text)
                    Triple(task.groupIndex, updatedLine, cropDebug)
                }
            }.awaitAll()
        }

        // Group line results back into their dialogue units
        val linesByGroupIndex = lineResults.groupBy { it.first }
        val allCrops = lineResults.map { it.third }

        val updatedGroups = groups.mapIndexed { gIdx, group ->
            val groupLineResults = linesByGroupIndex[gIdx] ?: emptyList()
            if (groupLineResults.isEmpty()) {
                group
            } else {
                val updatedLines = groupLineResults.map { it.second }.sortedBy { it.readingOrder }
                // Join lines in RTL order without spaces (Japanese manga text convention)
                val fullGroupText = updatedLines.map { it.recognizedText.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("")
                group.copy(
                    lines = updatedLines,
                    recognizedText = fullGroupText
                )
            }
        }

        // Create TranslationBlocks at group level for downstream translation rendering
        val allBlocks = updatedGroups.mapNotNull { group ->
            if (group.recognizedText.isNotBlank()) {
                TranslationBlock(
                    text = group.recognizedText,
                    width = group.bounds.width().toFloat(),
                    height = group.bounds.height().toFloat(),
                    x = group.bounds.left.toFloat(),
                    y = group.bounds.top.toFloat(),
                    symWidth = group.bounds.width().toFloat() / max(group.recognizedText.length, 1),
                    symHeight = group.bounds.height().toFloat() / max(group.recognizedText.length, 1),
                    angle = if (group.bounds.height() > group.bounds.width() * 1.3f) 90f else 0f,
                    isBubble = group.isBubble,
                )
            } else null
        }

        val durationMs = System.currentTimeMillis() - startTime

        DialogueOcrResult(
            blocks = allBlocks,
            crops = allCrops,
            updatedGroups = updatedGroups,
            durationMs = durationMs
        )
    }

    private fun isBlankOrSolid(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 4 || h < 4) return true

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        val stepY = max(1, h / 10)
        val stepX = max(1, w / 10)

        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val p = bitmap.getPixel(x, y)
                val lum = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
                sum += lum
                sumSq += lum * lum
                count++
            }
        }

        if (count <= 1) return true
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return kotlin.math.sqrt(max(0.0, variance)) < 6.0
    }

    private fun preprocessBitmap(src: Bitmap): Bitmap {
        val size = 224
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, Paint())
        }
        scaled.recycle()
        return result
    }

    private fun bitmapToTensor(bitmap: Bitmap): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val numPixels = w * h
        val pixels = IntArray(numPixels)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Vectorized 1-pass NCHW planar RGB normalized to [-1.0..1.0] with bulk buffer write
        val rawFloats = FloatArray(3 * numPixels)
        val planeR = 0
        val planeG = numPixels
        val planeB = numPixels * 2
        val scale = 2f / 255f

        for (i in 0 until numPixels) {
            val p = pixels[i]
            rawFloats[planeR + i] = (((p ushr 16) and 0xFF) * scale) - 1.0f
            rawFloats[planeG + i] = (((p ushr 8) and 0xFF) * scale) - 1.0f
            rawFloats[planeB + i] = ((p and 0xFF) * scale) - 1.0f
        }

        val buf = AiBufferUtils.allocateDirectFloatBuffer(3 * numPixels)
        buf.put(rawFloats)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, longArrayOf(1L, 3L, h.toLong(), w.toLong()))
    }

    override fun close() {
        encoderSession?.close()
        encoderSession = null
        decoderSession?.close()
        decoderSession = null
        tokenizer = null
    }

    class SimpleTokenizer(
        private val idToToken: Array<String>,
        val bosTokenId: Int,
        val eosTokenId: Int,
    ) {
        fun decode(ids: List<Int>): String {
            val sb = StringBuilder()
            for (id in ids) {
                if (id !in idToToken.indices) continue
                val tok = idToToken[id]
                if (tok == "[CLS]" || tok == "[SEP]" || tok == "[PAD]" || tok == "[UNK]" ||
                    tok == "<s>" || tok == "</s>" || tok == "<pad>" || tok == "<unk>") {
                    continue
                }
                if (tok.startsWith("##")) sb.append(tok.substring(2)) else sb.append(tok)
            }
            return sb.toString().trim()
        }

        companion object {
            fun load(file: File): SimpleTokenizer {
                val json = JSONObject(file.readText())
                val vocab = json.getJSONObject("model").getJSONObject("vocab")
                val arr = Array(vocab.length()) { "" }
                vocab.keys().forEach { k -> arr[vocab.getInt(k)] = k }
                val added = json.optJSONArray("added_tokens")
                var bos = if (vocab.has("[CLS]")) vocab.getInt("[CLS]") else 2
                var eos = if (vocab.has("[SEP]")) vocab.getInt("[SEP]") else 3
                if (added != null) {
                    for (i in 0 until added.length()) {
                        val tok = added.getJSONObject(i)
                        val content = tok.optString("content")
                        if (content == "[CLS]" || content == "<s>") bos = tok.getInt("id")
                        if (content == "[SEP]" || content == "</s>") eos = tok.getInt("id")
                    }
                }
                return SimpleTokenizer(arr, bos, eos)
            }
        }
    }
}
