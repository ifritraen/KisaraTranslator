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
import com.raen.crunchlab.data.ModelDownloader
import com.raen.crunchlab.data.OcrCropDebugItem
import com.raen.crunchlab.data.TranslationBlock
import com.raen.crunchlab.util.AiBufferUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    fun setSpeedLevel(level: Int) {
        val numCores = Runtime.getRuntime().availableProcessors()
        val targetThreads = when (level.coerceIn(1, 5)) {
            1 -> 1
            2 -> 2
            3 -> 3.coerceAtMost(numCores)
            4 -> 4.coerceAtMost(numCores)
            else -> 4.coerceAtMost(numCores)
        }
        if (targetThreads != configuredThreads) {
            configuredThreads = targetThreads
            close()
        }
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
            val opts = AiBufferUtils.createSessionOptions(threads)
            try {
                encoderSession = env.createSession(encoderFile.absolutePath, opts)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to initialize MangaOCR Encoder ONNX session: ${e.message}", e)
            } finally {
                opts.close()
            }
        }
        if (decoderSession == null) {
            val opts = AiBufferUtils.createSessionOptions(threads)
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
     * Executes MangaOCR text recognition over dialogue groups (Screen 4.2).
     * Adapted directly from KisaraTranslator's production executeOcrMethod8 pipeline:
     * - Multi-line sweet-spot: units with <= chunkLinesCount lines fed directly in 1 pass.
     * - Larger units: sliced into <= chunkLinesCount chunks in RTL order and joined.
     * - Semaphore-throttled concurrency (2 parallel workers) to prevent CPU/RAM thermal spikes.
     */
    suspend fun executeDialogueOcr(
        bitmap: Bitmap,
        groups: List<DialogueGroupItem>,
        chunkLinesCount: Int = 2,
        onProgress: (done: Int, total: Int, currentText: String) -> Unit = { _, _, _ -> },
    ): DialogueOcrResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        if (groups.isEmpty()) {
            return@withContext DialogueOcrResult(emptyList(), emptyList(), emptyList(), 0L)
        }

        ensureSessions(requireInt8 = true)
        val semaphore = Semaphore(2)
        val completedCounter = AtomicInteger(0)
        val total = groups.size

        val intermediateResults = coroutineScope {
            groups.mapIndexed { unitIdx, group ->
                async(Dispatchers.Default) {
                    if (group.lines.isEmpty()) {
                        val done = completedCounter.incrementAndGet()
                        onProgress(done, total, "")
                        return@async Triple(null, emptyList<OcrCropDebugItem>(), group)
                    }

                    if (group.lines.size <= chunkLinesCount) {
                        // Sweet-spot: direct multi-line OCR in 1 pass
                        val pad = 8
                        val safeLeft = (group.bounds.left - pad).coerceIn(0, bitmap.width - 1)
                        val safeTop = (group.bounds.top - pad).coerceIn(0, bitmap.height - 1)
                        val safeRight = (group.bounds.right + pad).coerceIn(safeLeft + 1, bitmap.width)
                        val safeBottom = (group.bounds.bottom + pad).coerceIn(safeTop + 1, bitmap.height)
                        val crop = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

                        val text = try {
                            semaphore.withPermit {
                                recognize(crop, requireInt8 = true, maxTokens = 48).trim()
                            }
                        } catch (e: Exception) {
                            Log.w("MangaOcrEngine", "Error recognizing group #${group.groupId}: ${e.message}")
                            ""
                        }

                        val done = completedCounter.incrementAndGet()
                        onProgress(done, total, text)

                        val cropDebug = OcrCropDebugItem(
                            index = unitIdx,
                            groupId = group.groupId,
                            rect = group.bounds,
                            cropBitmap = crop.copy(crop.config ?: Bitmap.Config.ARGB_8888, false),
                            rawText = text,
                            isBubble = group.isBubble,
                        )
                        crop.recycle()

                        val updatedGroup = group.copy(recognizedText = text)
                        val block = if (text.isNotBlank()) {
                            TranslationBlock(
                                text = text,
                                width = group.bounds.width().toFloat(),
                                height = group.bounds.height().toFloat(),
                                x = group.bounds.left.toFloat(),
                                y = group.bounds.top.toFloat(),
                                symWidth = group.bounds.width().toFloat() / max(text.length, 1),
                                symHeight = group.bounds.height().toFloat() / max(text.length, 1),
                                angle = if (group.bounds.height() > group.bounds.width() * 1.3f) 90f else 0f,
                                isBubble = group.isBubble,
                            )
                        } else null

                        Triple(block, listOf(cropDebug), updatedGroup)
                    } else {
                        // Large group: chunk into slices of <= chunkLinesCount lines in RTL reading order
                        val chunks = group.lines.chunked(chunkLinesCount)
                        val chunkTexts = mutableListOf<String>()
                        val chunkCrops = mutableListOf<OcrCropDebugItem>()

                        for ((chunkIdx, chunkLines) in chunks.withIndex()) {
                            val minX = chunkLines.minOf { it.rect.left }
                            val minY = chunkLines.minOf { it.rect.top }
                            val maxX = chunkLines.maxOf { it.rect.right }
                            val maxY = chunkLines.maxOf { it.rect.bottom }

                            val pad = 8
                            val safeLeft = (minX - pad).coerceIn(0, bitmap.width - 1)
                            val safeTop = (minY - pad).coerceIn(0, bitmap.height - 1)
                            val safeRight = (maxX + pad).coerceIn(safeLeft + 1, bitmap.width)
                            val safeBottom = (maxY + pad).coerceIn(safeTop + 1, bitmap.height)
                            val chunkCrop = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

                            val cText = try {
                                semaphore.withPermit {
                                    recognize(chunkCrop, requireInt8 = true, maxTokens = 36).trim()
                                }
                            } catch (e: Exception) {
                                Log.w("MangaOcrEngine", "Error recognizing chunk $chunkIdx of group #${group.groupId}: ${e.message}")
                                ""
                            }
                            chunkTexts.add(cText)

                            chunkCrops.add(
                                OcrCropDebugItem(
                                    index = unitIdx * 100 + chunkIdx,
                                    groupId = group.groupId,
                                    rect = Rect(safeLeft, safeTop, safeRight, safeBottom),
                                    cropBitmap = chunkCrop.copy(chunkCrop.config ?: Bitmap.Config.ARGB_8888, false),
                                    rawText = cText,
                                    isBubble = group.isBubble,
                                )
                            )
                            chunkCrop.recycle()
                        }

                        val done = completedCounter.incrementAndGet()
                        val fullText = chunkTexts.filter { it.isNotBlank() }.joinToString("")
                        onProgress(done, total, fullText)

                        val updatedGroup = group.copy(recognizedText = fullText)
                        val block = if (fullText.isNotBlank()) {
                            TranslationBlock(
                                text = fullText,
                                width = group.bounds.width().toFloat(),
                                height = group.bounds.height().toFloat(),
                                x = group.bounds.left.toFloat(),
                                y = group.bounds.top.toFloat(),
                                symWidth = group.bounds.width().toFloat() / max(fullText.length, 1),
                                symHeight = group.bounds.height().toFloat() / max(fullText.length, 1),
                                angle = if (group.bounds.height() > group.bounds.width() * 1.3f) 90f else 0f,
                                isBubble = group.isBubble,
                            )
                        } else null

                        Triple(block, chunkCrops, updatedGroup)
                    }
                }
            }.awaitAll()
        }

        val allBlocks = intermediateResults.mapNotNull { it.first }
        val allCrops = intermediateResults.flatMap { it.second }
        val updatedGroups = intermediateResults.map { it.third }
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
