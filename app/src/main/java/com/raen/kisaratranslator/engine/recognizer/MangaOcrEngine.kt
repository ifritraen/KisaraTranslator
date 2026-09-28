package com.raen.kisaratranslator.engine.recognizer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.raen.kisaratranslator.core.util.AiBufferUtils
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.model.TranslationModelType
import com.raen.kisaratranslator.data.model.TranslationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import kotlin.math.max

/**
 * MangaOCR ONNX text recognizer.
 * Executes ViT encoder + autoregressive sequence decoder with tokenizer.
 */
class MangaOcrEngine(
    private val modelManager: TranslationModelManager,
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
        get() = modelManager.isMangaOcrReady()

    private fun ensureSessions(requireInt8: Boolean = false) {
        val encoderFile = modelManager.getModelFile(TranslationModelType.MANGA_OCR_ENCODER)
        if (!encoderFile.exists()) {
            throw IllegalStateException("MangaOCR Encoder (encoder_model.onnx) is missing. Please install it in Model Manager.")
        }
        val decoderFile = if (requireInt8) {
            if (!modelManager.isModelReady(TranslationModelType.MANGA_OCR_DECODER_QUANTIZED)) {
                throw IllegalStateException("MangaOCR INT8 Quantized Decoder is missing. Please install it in Model Manager.")
            }
            modelManager.getModelFile(TranslationModelType.MANGA_OCR_DECODER_QUANTIZED)
        } else if (modelManager.isModelReady(TranslationModelType.MANGA_OCR_DECODER_QUANTIZED)) {
            modelManager.getModelFile(TranslationModelType.MANGA_OCR_DECODER_QUANTIZED)
        } else {
            val fp32File = modelManager.getModelFile(TranslationModelType.MANGA_OCR_DECODER)
            if (!fp32File.exists()) {
                throw IllegalStateException("MangaOCR Decoder is missing. Please install MangaOCR in Model Manager.")
            }
            fp32File
        }
        val tokenizerFile = modelManager.getModelFile(TranslationModelType.MANGA_OCR_TOKENIZER)
        if (!tokenizerFile.exists()) {
            throw IllegalStateException("MangaOCR Tokenizer (tokenizer.json) is missing. Please install it in Model Manager.")
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

    suspend fun recognize(crop: Bitmap, requireInt8: Boolean = false, maxTokens: Int = 36): String = withContext(Dispatchers.Default) {
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
            TranslationReport.log("ERROR", "MangaOCR", "Decode failed: ${e.message}", e)
            throw IllegalStateException("MangaOCR inference failed: ${e.message}", e)
        } finally {
            inputTensor.close()
            processed.recycle()
        }
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
