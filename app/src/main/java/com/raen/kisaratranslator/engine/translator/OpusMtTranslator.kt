package com.raen.kisaratranslator.engine.translator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.raen.kisaratranslator.core.util.AiBufferUtils
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.model.PageTranslation
import com.raen.kisaratranslator.data.model.TranslationModelType
import com.raen.kisaratranslator.data.model.TranslationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class OpusMtTranslator(
    private val modelManager: TranslationModelManager,
    override val fromLang: String = "ja",
    override val toLang: String = "en",
) : TextTranslator {

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: UnigramTokenizer? = null

    private var fallbackTranslator: GoogleTranslator? = null

    @Synchronized
    private fun ensureSessions(): Boolean {
        if (encoderSession != null && decoderSession != null && tokenizer != null) return true

        val encFile = modelManager.getModelFile(TranslationModelType.OPUS_MT_ENCODER)
        val decFile = modelManager.getModelFile(TranslationModelType.OPUS_MT_DECODER)
        val tokFile = modelManager.getModelFile(TranslationModelType.OPUS_MT_TOKENIZER)

        if (!encFile.exists() || !decFile.exists() || !tokFile.exists()) {
            TranslationReport.log(
                "WARN",
                "OpusMtTranslator",
                "Opus-MT models not downloaded yet (enc: ${encFile.exists()}, dec: ${decFile.exists()}, tok: ${tokFile.exists()}). Using Google Translate fallback.",
            )
            return false
        }

        return try {
            val environment = OrtEnvironment.getEnvironment()
            val numCores = Runtime.getRuntime().availableProcessors()
            val threads = numCores.coerceIn(2, 4)
            val opts = AiBufferUtils.createSessionOptions(threads)
            try {
                encoderSession = environment.createSession(encFile.absolutePath, opts)
                decoderSession = environment.createSession(decFile.absolutePath, opts)
            } finally {
                opts.close()
            }
            tokenizer = UnigramTokenizer.load(tokFile)
            env = environment

            TranslationReport.log("INFO", "OpusMtTranslator", "Opus-MT Marian engine initialized successfully")
            true
        } catch (e: Exception) {
            Log.e("OpusMtTranslator", "Failed to initialize Opus-MT ONNX sessions", e)
            TranslationReport.log("ERROR", "OpusMtTranslator", "Session init failed: ${e.message}", e)
            false
        }
    }

    override suspend fun translate(
        page: PageTranslation,
        onProgress: suspend (translatedBlocks: Int, totalBlocks: Int) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val totalBlocks = page.blocks.size
        if (totalBlocks == 0) return@withContext

        val hasOnnx = ensureSessions()
        if (!hasOnnx) {
            throw IllegalStateException("Opus-MT Marian ONNX models are missing or failed to initialize. Please install them in Model Manager.")
        }

        val completed = AtomicInteger(0)
        for (block in page.blocks) {
            if (block.text.isNotBlank()) {
                val translated = translateText(block.text)
                if (translated.isNotBlank()) {
                    block.translation = translated
                }
            }
            val done = completed.incrementAndGet()
            onProgress(done, totalBlocks)
        }
    }

    suspend fun translateText(text: String): String = withContext(Dispatchers.Default) {
        if (!ensureSessions()) {
            throw IllegalStateException("Opus-MT Marian ONNX models are missing or failed to initialize.")
        }

        val tok = tokenizer ?: return@withContext text
        val environment = env ?: return@withContext text
        val enc = encoderSession ?: return@withContext text
        val dec = decoderSession ?: return@withContext text

        val cleanText = text.replace("\n", " ").trim()
        if (cleanText.isBlank()) return@withContext ""

        val tokenIds = tok.encode(cleanText)
        if (tokenIds.isEmpty()) return@withContext cleanText

        // Append EOS (0) to encoder input
        val fullInputIds = tokenIds + listOf(tok.eosTokenId)
        val seqLen = fullInputIds.size.toLong()

        val inputIdsBuf = LongBuffer.allocate(fullInputIds.size)
        fullInputIds.forEach { inputIdsBuf.put(it.toLong()) }
        inputIdsBuf.rewind()

        val maskBuf = LongBuffer.allocate(fullInputIds.size)
        repeat(fullInputIds.size) { maskBuf.put(1L) }
        maskBuf.rewind()

        val inputIdsTensor = OnnxTensor.createTensor(environment, inputIdsBuf, longArrayOf(1L, seqLen))
        val maskTensor = OnnxTensor.createTensor(environment, maskBuf, longArrayOf(1L, seqLen))

        var hiddenStates: OnnxTensor? = null
        try {
            val encInputs = mutableMapOf<String, OnnxTensor>(
                "input_ids" to inputIdsTensor,
            )
            if (enc.inputNames.contains("attention_mask")) {
                encInputs["attention_mask"] = maskTensor
            }

            val encOut = enc.run(encInputs)
            hiddenStates = (encOut["last_hidden_state"]?.get() ?: encOut[0].value) as OnnxTensor

            // Autoregressive decoding
            val decodedIds = mutableListOf(tok.decoderStartTokenId)
            val maxTokens = 64

            for (step in 0 until maxTokens) {
                val decSeqLen = decodedIds.size.toLong()
                val decBuf = LongBuffer.allocate(decodedIds.size)
                decodedIds.forEach { decBuf.put(it.toLong()) }
                decBuf.rewind()

                val decInputTensor = OnnxTensor.createTensor(environment, decBuf, longArrayOf(1L, decSeqLen))
                val decInputs = mutableMapOf<String, OnnxTensor>(
                    "input_ids" to decInputTensor,
                    "encoder_hidden_states" to hiddenStates,
                )
                if (dec.inputNames.contains("encoder_attention_mask")) {
                    decInputs["encoder_attention_mask"] = maskTensor
                }

                val decOut = dec.run(decInputs)
                val logits = (decOut["logits"]?.get() ?: decOut[0].value) as OnnxTensor
                val shape = logits.info.shape
                val curSeq = shape[1].toInt()
                val vocabSize = shape[2].toInt()

                val logitsBuf = logits.floatBuffer
                val logitsArr = FloatArray(logitsBuf.remaining())
                logitsBuf.get(logitsArr)

                val lastPos = (curSeq - 1) * vocabSize
                var maxVal = Float.NEGATIVE_INFINITY
                var maxIdx = 0
                for (v in 0 until vocabSize) {
                    if (logitsArr[lastPos + v] > maxVal) {
                        maxVal = logitsArr[lastPos + v]
                        maxIdx = v
                    }
                }

                decInputTensor.close()
                logits.close()

                if (maxIdx == tok.eosTokenId) break
                decodedIds.add(maxIdx)
            }

            // Drop decoder start token
            tok.decode(decodedIds.drop(1))
        } catch (e: Exception) {
            Log.e("OpusMtTranslator", "Inference error", e)
            text
        } finally {
            inputIdsTensor.close()
            maskTensor.close()
            hiddenStates?.close()
        }
    }

    override fun close() {
        encoderSession?.close()
        encoderSession = null
        decoderSession?.close()
        decoderSession = null
        fallbackTranslator?.close()
        fallbackTranslator = null
    }

    class UnigramTokenizer(
        private val vocab: Map<String, Pair<Int, Float>>,
        private val idToToken: Array<String>,
        val decoderStartTokenId: Int,
        val eosTokenId: Int,
    ) {
        fun encode(text: String): List<Int> {
            val result = mutableListOf<Int>()
            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            for (word in words) {
                val spWord = "\u2581$word"
                val n = spWord.length
                val dp = FloatArray(n + 1) { -1e9f }
                dp[0] = 0f
                val prev = IntArray(n + 1) { -1 }
                val tokenMap = IntArray(n + 1) { -1 }

                for (i in 0 until n) {
                    if (dp[i] <= -1e8f) continue
                    val maxLen = min(n + 1, i + 32)
                    for (j in i + 1 until maxLen) {
                        val sub = spWord.substring(i, j)
                        val entry = vocab[sub]
                        if (entry != null) {
                            val (tokId, score) = entry
                            val newScore = dp[i] + score
                            if (newScore > dp[j]) {
                                dp[j] = newScore
                                prev[j] = i
                                tokenMap[j] = tokId
                            }
                        }
                    }
                }

                var curr = n
                val wordTokens = mutableListOf<Int>()
                while (curr > 0) {
                    val p = prev[curr]
                    if (p == -1) {
                        curr--
                        continue
                    }
                    wordTokens.add(tokenMap[curr])
                    curr = p
                }
                wordTokens.reverse()
                result.addAll(wordTokens)
            }
            return result
        }

        fun decode(ids: List<Int>): String {
            val sb = StringBuilder()
            for (id in ids) {
                if (id !in idToToken.indices) continue
                val token = idToToken[id]
                if (token == "</s>" || token == "<unk>" || token == "<pad>") continue
                if (token.startsWith("\u2581")) {
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append(token.substring(1))
                } else {
                    sb.append(token)
                }
            }
            return sb.toString().trim()
        }

        companion object {
            fun load(file: File): UnigramTokenizer {
                val json = JSONObject(file.readText())
                val modelObj = json.getJSONObject("model")
                val vocabArray = modelObj.getJSONArray("vocab")
                val vocabMap = HashMap<String, Pair<Int, Float>>(vocabArray.length())
                val idArr = Array(vocabArray.length()) { "" }

                for (i in 0 until vocabArray.length()) {
                    val entry = vocabArray.getJSONArray(i)
                    val tok = entry.getString(0)
                    val score = entry.getDouble(1).toFloat()
                    vocabMap[tok] = Pair(i, score)
                    idArr[i] = tok
                }

                val decStart = 60715
                val eos = 0
                return UnigramTokenizer(vocabMap, idArr, decStart, eos)
            }
        }
    }
}
