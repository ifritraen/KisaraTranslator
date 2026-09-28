package com.raen.kisaratranslator.engine.translator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.raen.kisaratranslator.core.util.AiBufferUtils
import com.raen.kisaratranslator.data.logger.AppLogger
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.model.PageTranslation
import com.raen.kisaratranslator.data.model.TranslationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class SugoiTranslator(
    private val modelManager: TranslationModelManager,
    override val fromLang: String = "ja",
    override val toLang: String = "en",
    private val beamWidth: Int = 1,
    private val speedLevel: Int = 3,
) : TextTranslator {

    private val opusMtDelegate = OpusMtTranslator(modelManager, fromLang, toLang)
    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: SugoiTokenizer? = null

    @Synchronized
    private fun ensureSessions(): Boolean {
        if (encoderSession != null && decoderSession != null && tokenizer != null) return true

        val sugoiDir = File(modelManager.getModelFile(com.raen.kisaratranslator.data.model.TranslationModelType.SUGOI_TRANSLATOR).parentFile ?: return false, "")
        val encFile = File(sugoiDir, "encoder_model_quantized.onnx")
        val decFile = File(sugoiDir, "decoder_model_quantized.onnx")
        val vocabFile = File(sugoiDir, "sugoi_vocab.json")

        if (!encFile.exists() || !decFile.exists() || !vocabFile.exists()) {
            val msg = "Sugoi ONNX files missing/inaccessible (enc: ${encFile.exists()}, dec: ${decFile.exists()}, vocab: ${vocabFile.exists()})"
            AppLogger.error(msg)
            TranslationReport.log("ERROR", "SugoiTranslator", msg)
            throw IllegalStateException("Sugoi V4 ONNX Engine error: $msg. Please install Sugoi in Model Manager.")
        }

        return try {
            val environment = OrtEnvironment.getEnvironment()
            val numCores = Runtime.getRuntime().availableProcessors()
            val threads = when (speedLevel.coerceIn(1, 5)) {
                1 -> 1
                2 -> 2
                3 -> numCores.coerceIn(2, 4)
                4 -> numCores.coerceIn(4, 6)
                else -> numCores
            }
            val opts = AiBufferUtils.createSessionOptions(threads)
            try {
                encoderSession = environment.createSession(encFile.absolutePath, opts)
                decoderSession = environment.createSession(decFile.absolutePath, opts)
            } finally {
                opts.close()
            }
            tokenizer = SugoiTokenizer.load(vocabFile)
            env = environment

            val msg = "Sugoi V4 INT8 ONNX Manga Engine initialized successfully (Active: SUGOI)"
            AppLogger.info(msg)
            TranslationReport.log("INFO", "SugoiTranslator", msg)
            true
        } catch (e: Exception) {
            val msg = "Sugoi init failed: ${e.message}"
            Log.e("SugoiTranslator", "Failed to initialize Sugoi ONNX sessions", e)
            AppLogger.error(msg, e)
            TranslationReport.log("ERROR", "SugoiTranslator", msg, e)
            throw IllegalStateException("Sugoi V4 ONNX Engine initialization failed: ${e.message}", e)
        }
    }

    override suspend fun translate(
        page: PageTranslation,
        onProgress: suspend (translatedBlocks: Int, totalBlocks: Int) -> Unit,
    ) {
        withContext(Dispatchers.Default) {
            val totalBlocks = page.blocks.size
            if (totalBlocks == 0) return@withContext

        val hasSugoi = ensureSessions()
        if (!hasSugoi) {
            throw IllegalStateException("Sugoi V4 ONNX Engine could not be initialized.")
        }

        AppLogger.info("Translating $totalBlocks blocks with REAL Sugoi V4 ONNX Engine")
        TranslationReport.log("INFO", "SugoiTranslator", "Translating $totalBlocks blocks via Sugoi V4 ONNX")

        val validBlocks = page.blocks.filter { it.text.isNotBlank() }
        val completed = AtomicInteger(totalBlocks - validBlocks.size)
        if (completed.get() > 0) {
            onProgress(completed.get(), totalBlocks)
        }

        val concurrency = when (speedLevel.coerceIn(1, 5)) {
            1 -> 1
            2 -> 1
            3 -> 2
            4 -> 3
            else -> 4
        }
        val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency)
        kotlinx.coroutines.coroutineScope {
            validBlocks.map { block ->
                async(Dispatchers.Default) {
                    try {
                        val translated = semaphore.withPermit { translateText(block.text) }
                        if (translated.isNotBlank()) {
                            block.translation = translated
                        }
                    } catch (e: Exception) {
                        TranslationReport.log("WARN", "SugoiTranslator", "Failed to translate: '${block.text}' - ${e.message}")
                    } finally {
                        val done = completed.incrementAndGet()
                        onProgress(done, totalBlocks)
                    }
                }
            }.awaitAll()
        }
    }
}

    suspend fun translateText(text: String): String = withContext(Dispatchers.Default) {
        if (!ensureSessions()) {
            return@withContext text
        }

        val tok = tokenizer ?: return@withContext text
        val environment = env ?: return@withContext text
        val enc = encoderSession ?: return@withContext text
        val dec = decoderSession ?: return@withContext text

        val cleanText = text.replace("\n", " ").trim()
        if (cleanText.isBlank()) return@withContext ""

        val tokenIds = tok.encode(cleanText)
        if (tokenIds.isEmpty()) return@withContext cleanText

        // Append Fairseq EOS (2)
        val fullInputIds = tokenIds + listOf(2)
        val seqLen = fullInputIds.size.toLong()

        val inputIdsBuf = LongBuffer.allocate(fullInputIds.size)
        fullInputIds.forEach { inputIdsBuf.put(it.toLong()) }
        inputIdsBuf.rewind()

        val inputIdsTensor = OnnxTensor.createTensor(environment, inputIdsBuf, longArrayOf(1L, seqLen))
        var encOut: OrtSession.Result? = null
        var hiddenStates: OnnxTensor? = null

        try {
            encOut = enc.run(mapOf("input_ids" to inputIdsTensor))
            hiddenStates = (encOut["last_hidden_state"]?.get() ?: encOut[0].value) as OnnxTensor

            val maxTokens = min(45, max(12, fullInputIds.size * 3 + 4))

            if (beamWidth <= 1) {
                // High-Speed Greedy Decoding Path (1 ONNX session run per token step)
                val decodedIds = ArrayList<Int>(48).apply { add(2) } // Fairseq BOS = 2
                val eosId = 2

                val decBuf = LongBuffer.allocate(maxTokens + 1)
                var lastVocabBuf: FloatArray? = null

                for (step in 0 until maxTokens) {
                    decBuf.clear()
                    for (id in decodedIds) {
                        decBuf.put(id.toLong())
                    }
                    decBuf.flip()

                    val decInputTensor = OnnxTensor.createTensor(
                        environment,
                        decBuf,
                        longArrayOf(1L, decodedIds.size.toLong()),
                    )
                    val decOut = dec.run(
                        mapOf(
                            "input_ids" to decInputTensor,
                            "encoder_hidden_states" to hiddenStates,
                        ),
                    )
                    try {
                        val logits = (decOut["logits"]?.get() ?: decOut[0].value) as OnnxTensor
                        val shape = logits.info.shape
                        val curSeq = shape[1].toInt()
                        val vocabSize = shape[2].toInt()
                        val logitsBuf = logits.floatBuffer

                        if (lastVocabBuf == null || lastVocabBuf.size != vocabSize) {
                            lastVocabBuf = FloatArray(vocabSize)
                        }

                        val lastPos = (curSeq - 1) * vocabSize
                        logitsBuf.position(lastPos)
                        logitsBuf.get(lastVocabBuf, 0, vocabSize)

                        logits.close()

                        // Fast argmax on logits (monotonic with softmax, zero exponential math)
                        var maxLogit = Float.NEGATIVE_INFINITY
                        var bestToken = eosId
                        for (v in 0 until vocabSize) {
                            val logit = lastVocabBuf[v]
                            if (logit > maxLogit) {
                                maxLogit = logit
                                bestToken = v
                            }
                        }

                        if (bestToken == eosId) break

                        // Repetition loop guard: stop early if the same token repeats 3 times
                        if (decodedIds.size >= 3 &&
                            decodedIds[decodedIds.size - 1] == bestToken &&
                            decodedIds[decodedIds.size - 2] == bestToken
                        ) {
                            break
                        }

                        decodedIds.add(bestToken)
                    } finally {
                        decInputTensor.close()
                        decOut.close()
                    }
                }

                val finalTokens = decodedIds.drop(1).filter { it !in listOf(0, 1, 2) }
                val decoded = tok.decode(finalTokens)
                if (decoded.isBlank()) cleanText else decoded
            } else {
                // Optimized Beam Search Decoding Path
                data class BeamCandidate(
                    val tokens: MutableList<Int>,
                    var score: Float,
                    var finished: Boolean = false,
                )

                var beams = mutableListOf(BeamCandidate(mutableListOf(2), 0f))
                var lastVocabBuf: FloatArray? = null

                for (step in 0 until maxTokens) {
                    val nextCandidates = mutableListOf<BeamCandidate>()

                    for (cand in beams) {
                        if (cand.finished) {
                            nextCandidates.add(cand)
                            continue
                        }

                        val decSeqLen = cand.tokens.size.toLong()
                        val decBuf = LongBuffer.allocate(cand.tokens.size)
                        cand.tokens.forEach { decBuf.put(it.toLong()) }
                        decBuf.rewind()

                        val decInputTensor = OnnxTensor.createTensor(environment, decBuf, longArrayOf(1L, decSeqLen))
                        val decOut = dec.run(
                            mapOf(
                                "input_ids" to decInputTensor,
                                "encoder_hidden_states" to hiddenStates,
                            ),
                        )
                        try {
                            val logits = (decOut["logits"]?.get() ?: decOut[0].value) as OnnxTensor
                            val shape = logits.info.shape
                            val curSeq = shape[1].toInt()
                            val vocabSize = shape[2].toInt()
                            val logitsBuf = logits.floatBuffer

                            if (lastVocabBuf == null || lastVocabBuf.size != vocabSize) {
                                lastVocabBuf = FloatArray(vocabSize)
                            }

                            val lastPos = (curSeq - 1) * vocabSize
                            logitsBuf.position(lastPos)
                            logitsBuf.get(lastVocabBuf, 0, vocabSize)

                            logits.close()

                            // Find max for numerical stability in log-softmax
                            var maxLogit = Float.NEGATIVE_INFINITY
                            for (v in 0 until vocabSize) {
                                if (lastVocabBuf[v] > maxLogit) maxLogit = lastVocabBuf[v]
                            }
                            var sumExp = 0.0
                            for (v in 0 until vocabSize) {
                                sumExp += kotlin.math.exp((lastVocabBuf[v] - maxLogit).toDouble())
                            }
                            val logSumExp = (maxLogit + kotlin.math.ln(sumExp)).toFloat()

                            // Extract top beamWidth tokens for this beam
                            val indexedLogits = Array(beamWidth) { Pair(0, Float.NEGATIVE_INFINITY) }
                            for (v in 0 until vocabSize) {
                                val logProb = lastVocabBuf[v] - logSumExp
                                if (logProb > indexedLogits[beamWidth - 1].second) {
                                    indexedLogits[beamWidth - 1] = Pair(v, logProb)
                                    indexedLogits.sortByDescending { it.second }
                                }
                            }

                            for (top in indexedLogits) {
                                val tokId = top.first
                                val tokLogProb = top.second
                                val newSeq = ArrayList(cand.tokens).apply { add(tokId) }
                                val isDone = (tokId == 2)
                                nextCandidates.add(BeamCandidate(newSeq, cand.score + tokLogProb, isDone))
                            }
                        } finally {
                            decInputTensor.close()
                            decOut.close()
                        }
                    }

                    // Sort and retain top beamWidth candidates normalized by length
                    nextCandidates.sortByDescending { it.score / Math.pow(it.tokens.size.toDouble(), 0.6).toFloat() }
                    beams = nextCandidates.take(beamWidth).toMutableList()
                    if (beams.all { it.finished }) break
                }

                val bestBeam = beams.maxByOrNull {
                    it.score / Math.pow(max(1, it.tokens.size - 1).toDouble(), 0.6).toFloat()
                } ?: beams.firstOrNull()

                val finalTokens = bestBeam?.tokens?.drop(1)?.filter { it !in listOf(0, 1, 2) } ?: emptyList()
                val decoded = tok.decode(finalTokens)
                if (decoded.isBlank()) cleanText else decoded
            }
        } catch (e: Exception) {
            Log.e("SugoiTranslator", "Sugoi inference error", e)
            text
        } finally {
            inputIdsTensor.close()
            hiddenStates?.close()
            encOut?.close()
        }
    }

    override fun close() {
        encoderSession?.close()
        encoderSession = null
        decoderSession?.close()
        decoderSession = null
        tokenizer = null
        env = null
        opusMtDelegate.close()
    }

    class SugoiTokenizer(
        private val jaVocab: Map<String, Pair<Int, Float>>,
        private val enVocab: Array<String>,
    ) {
        fun encode(text: String): List<Int> {
            val spText = "\u2581${text.replace(" ", "\u2581")}"
            val n = spText.length
            val dp = FloatArray(n + 1) { -1e9f }
            dp[0] = 0f
            val prev = IntArray(n + 1) { -1 }
            val tokMap = IntArray(n + 1) { -1 }

            for (i in 0 until n) {
                if (dp[i] <= -1e8f) continue
                val maxLen = min(n + 1, i + 32)
                for (j in i + 1 until maxLen) {
                    val sub = spText.substring(i, j)
                    val entry = jaVocab[sub]
                    if (entry != null) {
                        val (tokId, score) = entry
                        val newScore = dp[i] + score
                        if (newScore > dp[j]) {
                            dp[j] = newScore
                            prev[j] = i
                            tokMap[j] = tokId
                        }
                    }
                }
            }

            var curr = n
            val tokens = mutableListOf<Int>()
            while (curr > 0) {
                val p = prev[curr]
                if (p == -1) {
                    curr--
                    continue
                }
                tokens.add(tokMap[curr])
                curr = p
            }
            tokens.reverse()
            return tokens
        }

        fun decode(ids: List<Int>): String {
            val sb = StringBuilder()
            for (id in ids) {
                if (id !in enVocab.indices) continue
                val piece = enVocab[id]
                if (piece in listOf("<s>", "<pad>", "</s>", "<unk>")) continue
                sb.append(piece)
            }
            return sb.toString().replace("\u2581", " ").trim()
        }

        companion object {
            fun load(file: File): SugoiTokenizer {
                val json = JSONObject(file.readText())
                val jaObj = json.getJSONObject("ja_vocab")
                val jaMap = HashMap<String, Pair<Int, Float>>(jaObj.length())
                jaObj.keys().forEach { k ->
                    val arr = jaObj.getJSONArray(k)
                    jaMap[k] = Pair(arr.getInt(0), arr.getDouble(1).toFloat())
                }

                val enArr = json.getJSONArray("en_vocab")
                val enVocab = Array(enArr.length()) { enArr.getString(it) }

                return SugoiTokenizer(jaMap, enVocab)
            }
        }
    }
}
