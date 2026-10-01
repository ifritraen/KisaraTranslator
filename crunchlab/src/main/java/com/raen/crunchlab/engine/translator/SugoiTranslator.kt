package com.raen.crunchlab.engine.translator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.raen.crunchlab.data.ModelDownloader
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
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Sugoi Manga Translator (Fairseq INT8 ONNX V4) for CrunchLab Module 5.
 * Executes offline on-device neural translation using ONNX Runtime.
 */
class SugoiTranslator(
    private val modelDownloader: ModelDownloader,
    var beamWidth: Int = 1,
    var speedLevel: Int = 3,
) : TextTranslator {

    override val name: String = "Sugoi Manga Translator (ONNX)"

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: SugoiTokenizer? = null

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

    @Synchronized
    fun ensureSessions(): Boolean {
        if (encoderSession != null && decoderSession != null && tokenizer != null) return true

        val encFile = modelDownloader.getSugoiEncoderFile()
        val decFile = modelDownloader.getSugoiDecoderFile()
        val vocabFile = modelDownloader.getSugoiVocabFile()

        if (!encFile.exists() || !decFile.exists() || !vocabFile.exists()) {
            val msg = "Sugoi ONNX files missing (enc: ${encFile.exists()} [${encFile.length()}], dec: ${decFile.exists()} [${decFile.length()}], vocab: ${vocabFile.exists()} [${vocabFile.length()}])"
            Log.e("SugoiTranslator", msg)
            throw IllegalStateException("Sugoi ONNX files not found. Place them in /sdcard/Download/sugoi/ or app storage.")
        }

        return try {
            val environment = OrtEnvironment.getEnvironment()
            val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val threads = configuredThreads.coerceIn(1, numCores)
            val opts = AiBufferUtils.createSessionOptions(threads, hardwareDelegate)
            try {
                encoderSession = environment.createSession(encFile.absolutePath, opts)
                decoderSession = environment.createSession(decFile.absolutePath, opts)
            } finally {
                opts.close()
            }
            tokenizer = SugoiTokenizer.load(vocabFile)
            env = environment

            Log.i("SugoiTranslator", "Sugoi V4 INT8 ONNX Engine initialized ($threads-threads, ${hardwareDelegate.shortLabel})")
            true
        } catch (e: Exception) {
            Log.e("SugoiTranslator", "Failed to initialize Sugoi ONNX sessions", e)
            throw IllegalStateException("Sugoi ONNX initialization failed: ${e.message}", e)
        }
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.Default) {
        val cleanText = preprocessJapanese(text)
        if (cleanText.isBlank()) return@withContext ""

        if (!ensureSessions()) {
            return@withContext cleanText
        }

        val tok = tokenizer ?: return@withContext cleanText
        val environment = env ?: return@withContext cleanText
        val enc = encoderSession ?: return@withContext cleanText
        val dec = decoderSession ?: return@withContext cleanText

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

            val maxTokens = min(50, max(6, fullInputIds.size * 2 + 2))

            if (beamWidth <= 1) {
                // High-Speed Greedy Decoding
                val decodedIds = ArrayList<Int>(48).apply { add(2) } // Fairseq BOS = 2
                val eosId = 2

                val decBuf = LongBuffer.allocate(maxTokens + 1)
                var lastVocabBuf: FloatArray? = null

                for (step in 0 until maxTokens) {
                    coroutineContext.ensureActive()
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

                        // Suppress token if repeating 2+ times consecutively
                        if (decodedIds.size >= 3 && decodedIds[decodedIds.size - 1] == decodedIds[decodedIds.size - 2]) {
                            val repeating = decodedIds.last()
                            if (repeating in 0 until vocabSize) {
                                lastVocabBuf[repeating] = Float.NEGATIVE_INFINITY
                            }
                        }

                        // 3-gram repetition blocking: prevent (tokA, tokB, tokC) from recurring
                        if (decodedIds.size >= 3) {
                            val tokA = decodedIds[decodedIds.size - 2]
                            val tokB = decodedIds[decodedIds.size - 1]
                            for (i in 1 until decodedIds.size - 2) {
                                if (decodedIds[i] == tokA && decodedIds[i + 1] == tokB) {
                                    val banned = decodedIds[i + 2]
                                    if (banned in 0 until vocabSize) {
                                        lastVocabBuf[banned] = Float.NEGATIVE_INFINITY
                                    }
                                }
                            }
                        }

                        // Fast argmax on logits
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

                        decodedIds.add(bestToken)
                    } finally {
                        decInputTensor.close()
                        decOut.close()
                    }
                }

                val finalTokens = decodedIds.drop(1).filter { it !in listOf(0, 1, 2) }
                val decoded = tok.decode(finalTokens)
                val rawResult = if (decoded.isBlank()) cleanText else decoded
                postProcessEnglish(rawResult)
            } else {
                // Quality Beam Search Decoding (Width 2)
                val effectiveBeamWidth = 2
                data class BeamCandidate(
                    val tokens: MutableList<Int>,
                    var score: Float,
                    var finished: Boolean = false,
                )

                var beams = mutableListOf(BeamCandidate(mutableListOf(2), 0f))
                var lastVocabBuf: FloatArray? = null
                val repPenalty = 1.15f
                val lenPenaltyExp = 0.6

                for (step in 0 until maxTokens) {
                    coroutineContext.ensureActive()
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

                            // Repetition penalty on already emitted tokens (Keskar et al. 2019)
                            if (cand.tokens.size > 1) {
                                for (t in cand.tokens.drop(1)) {
                                    if (t in 0 until vocabSize) {
                                        if (lastVocabBuf[t] > 0) {
                                            lastVocabBuf[t] /= repPenalty
                                        } else {
                                            lastVocabBuf[t] *= repPenalty
                                        }
                                    }
                                }
                            }

                            // Suppress token if repeating 2+ times consecutively
                            if (cand.tokens.size >= 3 && cand.tokens[cand.tokens.size - 1] == cand.tokens[cand.tokens.size - 2]) {
                                val repeating = cand.tokens.last()
                                if (repeating in 0 until vocabSize) {
                                    lastVocabBuf[repeating] = Float.NEGATIVE_INFINITY
                                }
                            }

                            // 3-gram repetition blocking
                            if (cand.tokens.size >= 3) {
                                val tokA = cand.tokens[cand.tokens.size - 2]
                                val tokB = cand.tokens[cand.tokens.size - 1]
                                for (i in 1 until cand.tokens.size - 2) {
                                    if (cand.tokens[i] == tokA && cand.tokens[i + 1] == tokB) {
                                        val banned = cand.tokens[i + 2]
                                        if (banned in 0 until vocabSize) {
                                            lastVocabBuf[banned] = Float.NEGATIVE_INFINITY
                                        }
                                    }
                                }
                            }

                            var maxLogit = Float.NEGATIVE_INFINITY
                            for (v in 0 until vocabSize) {
                                if (lastVocabBuf[v] > maxLogit) maxLogit = lastVocabBuf[v]
                            }
                            var sumExp = 0.0
                            for (v in 0 until vocabSize) {
                                sumExp += kotlin.math.exp((lastVocabBuf[v] - maxLogit).toDouble())
                            }
                            val logSumExp = (maxLogit + kotlin.math.ln(sumExp)).toFloat()

                            val indexedLogits = Array(effectiveBeamWidth) { Pair(0, Float.NEGATIVE_INFINITY) }
                            for (v in 0 until vocabSize) {
                                val logProb = lastVocabBuf[v] - logSumExp
                                if (logProb > indexedLogits[effectiveBeamWidth - 1].second) {
                                    indexedLogits[effectiveBeamWidth - 1] = Pair(v, logProb)
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

                    nextCandidates.sortByDescending { it.score / Math.pow(it.tokens.size.toDouble(), lenPenaltyExp).toFloat() }
                    beams = nextCandidates.take(effectiveBeamWidth).toMutableList()

                    // Early stopping: if candidate 0 finishes and is clearly superior or all finished
                    if (beams.isNotEmpty() && beams[0].finished) {
                        if (beams.size < 2 || beams[1].finished) break
                        val cand0Norm = beams[0].score / Math.pow(beams[0].tokens.size.toDouble(), lenPenaltyExp).toFloat()
                        val cand1Norm = beams[1].score / Math.pow(beams[1].tokens.size.toDouble(), lenPenaltyExp).toFloat()
                        if (cand0Norm - cand1Norm > 2.0f) break
                    }
                }

                val bestBeam = beams.maxByOrNull {
                    it.score / Math.pow(max(1, it.tokens.size - 1).toDouble(), lenPenaltyExp).toFloat()
                } ?: beams.firstOrNull()

                val finalTokens = bestBeam?.tokens?.drop(1)?.filter { it !in listOf(0, 1, 2) } ?: emptyList()
                val decoded = tok.decode(finalTokens)
                val rawResult = if (decoded.isBlank()) cleanText else decoded
                postProcessEnglish(rawResult)
            }
        } catch (e: Exception) {
            Log.e("SugoiTranslator", "Sugoi inference error for '$cleanText'", e)
            cleanText
        } finally {
            inputIdsTensor.close()
            hiddenStates?.close()
            encOut?.close()
        }
    }

    override suspend fun translateBatch(
        texts: List<String>,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
    ): List<String> = coroutineScope {
        val total = texts.size
        if (total == 0) return@coroutineScope emptyList()

        ensureSessions()

        val completed = AtomicInteger(0)
        val concurrency = when (speedLevel.coerceIn(1, 5)) {
            1 -> 1
            2 -> 1
            3 -> 2
            4 -> 3
            else -> 4
        }
        val semaphore = Semaphore(concurrency)

        val deferredList = texts.mapIndexed { idx, text ->
            async(Dispatchers.Default) {
                coroutineContext.ensureActive()
                val res = if (text.isNotBlank()) {
                    try {
                        semaphore.withPermit { translate(text) }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e("SugoiTranslator", "Failed batch item [$idx]: '$text'", e)
                        text
                    }
                } else {
                    ""
                }
                val done = completed.incrementAndGet()
                onProgress(done, total)
                res
            }
        }
        deferredList.awaitAll()
    }

    override fun close() {
        encoderSession?.close()
        encoderSession = null
        decoderSession?.close()
        decoderSession = null
        tokenizer = null
        env = null
    }

    class SugoiTokenizer(
        private val jaVocab: Map<String, Pair<Int, Float>>,
        private val enVocab: Array<String>,
    ) {
        fun encode(text: String): List<Int> {
            val normalized = text
                .replace('！', '!')
                .replace('？', '?')
                .replace("…", "...")
                .replace("‥", "..")
                .replace('～', '〜')
                .replace('　', ' ')
            val spText = "\u2581${normalized.replace(" ", "\u2581")}"
            val n = spText.length
            val dp = FloatArray(n + 1) { -1e9f }
            dp[0] = 0f
            val prev = IntArray(n + 1) { -1 }
            val tokMap = IntArray(n + 1) { -1 }

            for (i in 0 until n) {
                if (dp[i] <= -1e8f) continue

                // Single-character <unk> fallback transition (token ID 3, penalty -15f)
                // Ensures unknown characters (emojis, unmapped symbols) never sever the Viterbi chain
                val unkScore = dp[i] - 15f
                if (unkScore > dp[i + 1]) {
                    dp[i + 1] = unkScore
                    prev[i + 1] = i
                    tokMap[i + 1] = 3
                }

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

    companion object {
        fun preprocessJapanese(text: String): String {
            var s = text.replace("\n", " ").trim()
            if (s.isEmpty()) return ""

            // 1. Collapse 3+ repeating kana/letters to 2 (e.g. あああああ -> ああ, いぃぃぃ -> いい)
            s = s.replace(Regex("([\\p{IsHiragana}\\p{IsKatakana}a-zA-Zー])\\1{2,}"), "$1$1")

            // 2. Insert space before attached stylized symbols so the tokenizer separates them cleanly
            s = s.replace(Regex("([^\\s])([♡♥〜～♪])"), "$1 $2")

            // 3. Fullwidth & Zenkaku punctuation normalization
            s = s.replace('！', '!')
                .replace('？', '?')
                .replace("…", "...")
                .replace("‥", "..")
                .replace('～', '〜')
                .replace('　', ' ')

            return s.trim()
        }

        fun postProcessEnglish(text: String): String {
            if (text.isBlank()) return text
            val capitalized = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            return capitalized
                .replace(Regex("\\s+([!?,.:;])"), "$1")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}

