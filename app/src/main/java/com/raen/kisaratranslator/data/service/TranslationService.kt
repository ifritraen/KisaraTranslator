package com.raen.kisaratranslator.data.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import com.raen.kisaratranslator.core.util.ImageUtils
import com.raen.kisaratranslator.core.wakelock.WakeLockManager
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.logger.AppLogger
import com.raen.kisaratranslator.data.model.DetectorType
import com.raen.kisaratranslator.data.model.OcrType
import com.raen.kisaratranslator.data.model.PageTranslation
import com.raen.kisaratranslator.data.model.PipelineConfig
import com.raen.kisaratranslator.data.model.TranslationBlock
import com.raen.kisaratranslator.data.model.TranslationModelType
import com.raen.kisaratranslator.data.model.TranslationReport
import com.raen.kisaratranslator.data.model.TranslatorType
import com.raen.kisaratranslator.data.model.ViewMode
import com.raen.kisaratranslator.data.monitor.ResourceMonitor
import com.raen.kisaratranslator.engine.detector.BubbleDetector
import com.raen.kisaratranslator.engine.detector.ComicTextDetector
import com.raen.kisaratranslator.engine.detector.PaddleOcrDetector
import com.raen.kisaratranslator.engine.grouping.BubbleGroupingCoordinator
import com.raen.kisaratranslator.engine.grouping.Method8SegmentGrouper
import com.raen.kisaratranslator.engine.recognizer.Ctc48pxOcrEngine
import com.raen.kisaratranslator.engine.recognizer.MangaOcrEngine
import com.raen.kisaratranslator.engine.recognizer.MlKitOcrRecognizer
import com.raen.kisaratranslator.engine.recognizer.PaddleOcrRecognizer
import com.raen.kisaratranslator.engine.translator.GoogleTranslator
import com.raen.kisaratranslator.engine.translator.MLKitTranslator
import com.raen.kisaratranslator.engine.translator.OpusMtTranslator
import com.raen.kisaratranslator.engine.translator.SugoiTranslator
import com.raen.kisaratranslator.engine.translator.TextTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min

data class TranslationStepDiagnostics(
    val detectionTimeMs: Long = 0,
    val ocrTimeMs: Long = 0,
    val groupingTimeMs: Long = 0,
    val translationTimeMs: Long = 0,
    val renderingTimeMs: Long = 0,
    val totalTimeMs: Long = 0,
    val detectedBoxesCount: Int = 0,
    val finalBlocksCount: Int = 0,
)

data class TranslationPipelineResult(
    val originalBitmap: Bitmap,
    val translatedBitmap: Bitmap,
    val pageTranslation: PageTranslation,
    val diagnostics: TranslationStepDiagnostics,
    val detectedBoxes: List<Rect> = emptyList(),
    val pass1Boxes: List<Rect> = emptyList(),
    val pass2Boxes: List<Rect> = emptyList(),
    val pass2BoxesPassed: List<Rect> = emptyList(),
    val pass2BoxesRejected: List<Rect> = emptyList(),
    val pass2Crops: List<com.raen.kisaratranslator.data.model.OcrCropDebug> = emptyList(),
    val lineBoxes: List<Rect> = emptyList(),
    val groupedBoxes: List<Rect> = emptyList(),
    val bubbleBoxes: List<Rect> = emptyList(),
    val m6ChunkBoxes: List<Rect> = emptyList(),
    val m6ChunkCrops: List<com.raen.kisaratranslator.data.model.OcrCropDebug> = emptyList(),
    val nonBubbledCrops: List<com.raen.kisaratranslator.data.model.OcrCropDebug> = emptyList(),
    val ocrCrops: List<com.raen.kisaratranslator.data.model.OcrCropDebug> = emptyList(),
    val crunchSplitBoxes: List<Rect> = emptyList(),
    val crunchOriginalBoxes: List<Rect> = emptyList(),
    val crunchPointsA: List<Point> = emptyList(),
    val crunchPointsB: List<Point> = emptyList(),
    val crunchCutLines: List<List<Point>> = emptyList(),
    val crunchSplits: List<com.raen.kisaratranslator.engine.grouping.CrunchSplitter.SplitResult> = emptyList(),
    val inpaintedBitmap: Bitmap? = null,
    val pass2Bitmap: Bitmap? = null,
)

class TranslationService(
    val context: Context,
    val modelManager: TranslationModelManager,
    val wakeLockManager: WakeLockManager,
) : Closeable {

    private val comicTextDetector = ComicTextDetector(modelManager)
    private val bubbleDetector = BubbleDetector(modelManager)
    private val mangaTextDetector2024 = com.raen.kisaratranslator.engine.detector.MangaTextDetector2024(modelManager)
    private val paddleOcrDetector = PaddleOcrDetector(modelManager)
    private val mangaOcrEngine = MangaOcrEngine(modelManager)
    private val ctc48pxOcrEngine = Ctc48pxOcrEngine(modelManager)
    private val paddleOcrRecognizer = PaddleOcrRecognizer(modelManager)
    private val bubbleGroupingCoordinator = BubbleGroupingCoordinator()
    private val smartBubbleGrouper = com.raen.kisaratranslator.engine.grouping.SmartBubbleGrouper()
    private val method5LobeGrouper = com.raen.kisaratranslator.engine.grouping.Method5LobeGrouper()
    private val method6BubbleGrouper = com.raen.kisaratranslator.engine.grouping.Method6BubbleGrouper()
    private val method7CrunchGrouper = com.raen.kisaratranslator.engine.grouping.Method7CrunchGrouper()
    private val bubbleSegmentationEngine = com.raen.kisaratranslator.engine.grouping.BubbleSegmentationEngine(modelManager)
    private val method8SegmentGrouper = com.raen.kisaratranslator.engine.grouping.Method8SegmentGrouper()

    // Persistent Service Scope across Tab Changes!
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    // Single Page Persistent States
    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    private val _translationResult = MutableStateFlow<TranslationPipelineResult?>(null)
    val translationResult: StateFlow<TranslationPipelineResult?> = _translationResult.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _currentStep = MutableStateFlow("")
    val currentStep: StateFlow<String> = _currentStep.asStateFlow()

    private val _currentViewMode = MutableStateFlow(ViewMode.TRANSLATED)
    val currentViewMode: StateFlow<ViewMode> = _currentViewMode.asStateFlow()

    private val _highlightedBox = MutableStateFlow<Rect?>(null)
    val highlightedBox: StateFlow<Rect?> = _highlightedBox.asStateFlow()

    private val _highlightedBoxLabel = MutableStateFlow("")
    val highlightedBoxLabel: StateFlow<String> = _highlightedBoxLabel.asStateFlow()

    // User Excluded (Ignore) Zones — areas drawn by user that bypass all pipeline stages
    private val _userExcludedBoxes = MutableStateFlow<List<Rect>>(emptyList())
    val userExcludedBoxes: StateFlow<List<Rect>> = _userExcludedBoxes.asStateFlow()

    fun addUserExcludedBox(rect: Rect) {
        _userExcludedBoxes.value = _userExcludedBoxes.value + rect
        AppLogger.info("Added user excluded ignore zone: $rect (total: ${_userExcludedBoxes.value.size})")
    }

    fun removeUserExcludedBox(rect: Rect) {
        _userExcludedBoxes.value = _userExcludedBoxes.value.filter { it != rect }
        AppLogger.info("Removed user excluded ignore zone: $rect (total: ${_userExcludedBoxes.value.size})")
    }

    fun replaceUserExcludedBox(oldRect: Rect, newRect: Rect) {
        _userExcludedBoxes.value = _userExcludedBoxes.value.map { if (it == oldRect) newRect else it }
    }

    fun removeLastUserExcludedBox() {
        val current = _userExcludedBoxes.value
        if (current.isNotEmpty()) {
            _userExcludedBoxes.value = current.dropLast(1)
            AppLogger.info("Undid last user excluded ignore zone (remaining: ${_userExcludedBoxes.value.size})")
        }
    }

    fun clearUserExcludedBoxes() {
        _userExcludedBoxes.value = emptyList()
        AppLogger.info("Cleared all user excluded ignore zones")
    }

    private fun touchesExcluded(box: Rect, excludedList: List<Rect>): Boolean {
        if (excludedList.isEmpty()) return false
        return excludedList.any { ex ->
            Rect.intersects(box, ex) || ex.contains(box) || box.contains(ex)
        }
    }

    fun setViewMode(mode: ViewMode) {
        _currentViewMode.value = mode
    }

    fun setHighlightedBox(rect: Rect?, label: String = "") {
        _highlightedBox.value = rect
        _highlightedBoxLabel.value = label
    }

    private val _isQuickInspectExpanded = MutableStateFlow(false)
    val isQuickInspectExpanded: StateFlow<Boolean> = _isQuickInspectExpanded.asStateFlow()

    fun setQuickInspectExpanded(expanded: Boolean) {
        _isQuickInspectExpanded.value = expanded
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("kisara_pipeline_prefs", Context.MODE_PRIVATE)

    private var lastDetDuration: Long
        get() = prefs.getLong("last_det_duration", 0L)
        set(value) = prefs.edit().putLong("last_det_duration", value).apply()

    private var lastOcrDuration: Long
        get() = prefs.getLong("last_ocr_duration", 0L)
        set(value) = prefs.edit().putLong("last_ocr_duration", value).apply()

    private var lastGroupDuration: Long
        get() = prefs.getLong("last_group_duration", 0L)
        set(value) = prefs.edit().putLong("last_group_duration", value).apply()

    private var lastTransDuration: Long
        get() = prefs.getLong("last_trans_duration", 0L)
        set(value) = prefs.edit().putLong("last_trans_duration", value).apply()

    private var lastRenderDuration: Long
        get() = prefs.getLong("last_render_duration", 0L)
        set(value) = prefs.edit().putLong("last_render_duration", value).apply()

    private fun loadInitialDiagnostics(): TranslationStepDiagnostics {
        var det = lastDetDuration
        var ocr = lastOcrDuration
        var grp = lastGroupDuration
        var trn = lastTransDuration
        var rnd = lastRenderDuration

        if (det == 0L && ocr == 0L) {
            try {
                val latestJsonFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KisaraTranslator/translation_latest.json")
                if (latestJsonFile.exists()) {
                    val json = JSONObject(latestJsonFile.readText())
                    det = json.optLong("detectionTimeMs", 0L)
                    ocr = json.optLong("ocrTimeMs", 0L)
                    grp = json.optLong("groupingTimeMs", 0L)
                    trn = json.optLong("translationTimeMs", 0L)
                    rnd = json.optLong("renderingTimeMs", 0L)
                }
            } catch (_: Exception) {}
        }

        return TranslationStepDiagnostics(
            detectionTimeMs = det,
            ocrTimeMs = ocr,
            groupingTimeMs = grp,
            translationTimeMs = trn,
            renderingTimeMs = rnd,
        )
    }

    private val _stepDiagnostics = MutableStateFlow(loadInitialDiagnostics())
    val stepDiagnostics: StateFlow<TranslationStepDiagnostics> = _stepDiagnostics.asStateFlow()

    private fun buildDiagnostics(
        detDuration: Long = 0L,
        ocrDuration: Long = 0L,
        groupDuration: Long = 0L,
        transDuration: Long = 0L,
        renderDuration: Long = 0L,
        totalDuration: Long = 0L,
        detectedBoxesCount: Int = 0,
        finalBlocksCount: Int = 0,
    ): TranslationStepDiagnostics {
        if (detDuration > 0) lastDetDuration = detDuration
        if (ocrDuration > 0) lastOcrDuration = ocrDuration
        if (groupDuration > 0) lastGroupDuration = groupDuration
        if (transDuration > 0) lastTransDuration = transDuration
        if (renderDuration > 0) lastRenderDuration = renderDuration

        val diag = TranslationStepDiagnostics(
            detectionTimeMs = if (detDuration > 0) detDuration else lastDetDuration,
            ocrTimeMs = if (ocrDuration > 0) ocrDuration else lastOcrDuration,
            groupingTimeMs = if (groupDuration > 0) groupDuration else lastGroupDuration,
            translationTimeMs = if (transDuration > 0) transDuration else lastTransDuration,
            renderingTimeMs = if (renderDuration > 0) renderDuration else lastRenderDuration,
            totalTimeMs = totalDuration,
            detectedBoxesCount = detectedBoxesCount,
            finalBlocksCount = finalBlocksCount,
        )
        _stepDiagnostics.value = diag
        return diag
    }

    private fun loadSavedConfig(): PipelineConfig {
        val jsonStr = prefs.getString("pipeline_config_json", null) ?: return PipelineConfig()
        return try {
            val obj = JSONObject(jsonStr)
            PipelineConfig(
                detector = DetectorType.valueOf(obj.optString("detector", DetectorType.COMIC_TEXT_DETECTOR.name)),
                ocr = OcrType.valueOf(obj.optString("ocr", OcrType.MANGA_OCR.name)),
                translator = TranslatorType.valueOf(obj.optString("translator", TranslatorType.SUGOI_ONNX.name)),
                sourceLang = obj.optString("sourceLang", "ja"),
                targetLang = obj.optString("targetLang", "en"),
                bubbleGroupingEnabled = obj.optBoolean("bubbleGroupingEnabled", true),
                assignmentMode = obj.optInt("assignmentMode", 1),
                lineSortingOrder = obj.optInt("lineSortingOrder", 0),
                fillBubbleBackground = obj.optBoolean("fillBubbleBackground", true),
                textScaleFactor = obj.optDouble("textScaleFactor", 1.0).toFloat(),
                keepScreenOn = obj.optBoolean("keepScreenOn", true),
                azureApiKey = obj.optString("azureApiKey", ""),
                azureRegion = obj.optString("azureRegion", "global"),
                deeplApiKey = obj.optString("deeplApiKey", ""),
                geminiApiKey = obj.optString("geminiApiKey", ""),
                geminiModel = obj.optString("geminiModel", "gemini-2.5-flash"),
                groqApiKey = obj.optString("groqApiKey", ""),
                groqModel = obj.optString("groqModel", "qwen/qwen3.6-27b"),
                sugoiBeamWidth = obj.optInt("sugoiBeamWidth", 1),
                qwenEndpoint = obj.optString("qwenEndpoint", ""),
                qwenApiKey = obj.optString("qwenApiKey", ""),
                enhancedPipeline = obj.optBoolean("enhancedPipeline", false),
                pipelineMethod = obj.optInt("pipelineMethod", if (obj.optBoolean("enhancedPipeline", false)) 1 else 0),
                chunkLinesCount = obj.optInt("chunkLinesCount", 2).coerceIn(1, 3),
                probeDet2Neighbors = obj.optBoolean("probeDet2Neighbors", true),
                probeStep3Orphans = obj.optBoolean("probeStep3Orphans", true),
                speedDet1 = obj.optInt("speedDet1", 3).coerceIn(1, 5),
                speedDet2 = obj.optInt("speedDet2", 3).coerceIn(1, 5),
                speedProbeNeighbors = obj.optInt("speedProbeNeighbors", 3).coerceIn(1, 5),
                speedProbeOrphans = obj.optInt("speedProbeOrphans", 3).coerceIn(1, 5),
                speedOcr = obj.optInt("speedOcr", 3).coerceIn(1, 5),
                speedTranslate = obj.optInt("speedTranslate", 3).coerceIn(1, 5),
            )
        } catch (e: Exception) {
            AppLogger.warn("Failed to parse saved config: ${e.message}")
            PipelineConfig()
        }
    }

    private fun saveConfigToDisk(cfg: PipelineConfig) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val obj = JSONObject().apply {
                    put("detector", cfg.detector.name)
                    put("ocr", cfg.ocr.name)
                    put("translator", cfg.translator.name)
                    put("sourceLang", cfg.sourceLang)
                    put("targetLang", cfg.targetLang)
                    put("bubbleGroupingEnabled", cfg.bubbleGroupingEnabled)
                    put("assignmentMode", cfg.assignmentMode)
                    put("lineSortingOrder", cfg.lineSortingOrder)
                    put("fillBubbleBackground", cfg.fillBubbleBackground)
                    put("textScaleFactor", cfg.textScaleFactor.toDouble())
                    put("keepScreenOn", cfg.keepScreenOn)
                    put("azureApiKey", cfg.azureApiKey)
                    put("azureRegion", cfg.azureRegion)
                    put("deeplApiKey", cfg.deeplApiKey)
                    put("geminiApiKey", cfg.geminiApiKey)
                    put("geminiModel", cfg.geminiModel)
                    put("groqApiKey", cfg.groqApiKey)
                    put("groqModel", cfg.groqModel)
                    put("sugoiBeamWidth", cfg.sugoiBeamWidth)
                    put("qwenEndpoint", cfg.qwenEndpoint)
                    put("qwenApiKey", cfg.qwenApiKey)
                    put("enhancedPipeline", cfg.enhancedPipeline)
                    put("pipelineMethod", cfg.effectiveMethod)
                    put("chunkLinesCount", cfg.chunkLinesCount)
                    put("probeDet2Neighbors", cfg.probeDet2Neighbors)
                    put("probeStep3Orphans", cfg.probeStep3Orphans)
                    put("speedDet1", cfg.speedDet1)
                    put("speedDet2", cfg.speedDet2)
                    put("speedProbeNeighbors", cfg.speedProbeNeighbors)
                    put("speedProbeOrphans", cfg.speedProbeOrphans)
                    put("speedOcr", cfg.speedOcr)
                    put("speedTranslate", cfg.speedTranslate)
                }
                prefs.edit().putString("pipeline_config_json", obj.toString()).apply()
            } catch (e: Exception) {
                AppLogger.warn("Failed to persist PipelineConfig: ${e.message}")
            }
        }
    }

    private val _config = MutableStateFlow(loadSavedConfig())
    val config: StateFlow<PipelineConfig> = _config.asStateFlow()

    private val _lastDiagnostics = MutableStateFlow<TranslationStepDiagnostics?>(null)
    val lastDiagnostics: StateFlow<TranslationStepDiagnostics?> = _lastDiagnostics.asStateFlow()

    fun setOriginalImage(bitmap: Bitmap, uri: Uri?) {
        _originalBitmap.value = bitmap
        _selectedImageUri.value = uri
        _translationResult.value = null
        _userExcludedBoxes.value = emptyList()
        _highlightedBox.value = null
        _highlightedBoxLabel.value = ""
        AppLogger.info("Selected new image: ${bitmap.width}x${bitmap.height} (URI: $uri)")
    }

    private val _baselineResult = MutableStateFlow<TranslationPipelineResult?>(null)
    val baselineResult: StateFlow<TranslationPipelineResult?> = _baselineResult.asStateFlow()

    fun snapshotBaseline() {
        _baselineResult.value = _translationResult.value
        AppLogger.info("Saved current translation as Baseline for Before/After comparison")
    }

    fun clearBaseline() {
        _baselineResult.value = null
    }

    /**
     * Dynamically reloads configuration from /sdcard/Download/KisaraTranslator/sigma_config.json if present.
     * Enables zero-rebuild live tuning via ADB push.
     */
    fun reloadExternalSigmaConfig(): Boolean {
        return try {
            val sigmaFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "KisaraTranslator/sigma_config.json"
            )
            if (sigmaFile.exists() && sigmaFile.length() > 0) {
                val jsonStr = sigmaFile.readText()
                val obj = JSONObject(jsonStr)
                val current = _config.value
                val updated = current.copy(
                    detector = if (obj.has("detector")) DetectorType.valueOf(obj.getString("detector")) else current.detector,
                    ocr = if (obj.has("ocr")) OcrType.valueOf(obj.getString("ocr")) else current.ocr,
                    translator = if (obj.has("translator")) TranslatorType.valueOf(obj.getString("translator")) else current.translator,
                    sourceLang = obj.optString("sourceLang", current.sourceLang),
                    targetLang = obj.optString("targetLang", current.targetLang),
                    bubbleGroupingEnabled = obj.optBoolean("bubbleGroupingEnabled", current.bubbleGroupingEnabled),
                    assignmentMode = obj.optInt("assignmentMode", current.assignmentMode),
                    lineSortingOrder = obj.optInt("lineSortingOrder", current.lineSortingOrder),
                    fillBubbleBackground = obj.optBoolean("fillBubbleBackground", current.fillBubbleBackground),
                    textScaleFactor = obj.optDouble("textScaleFactor", current.textScaleFactor.toDouble()).toFloat(),
                    sugoiBeamWidth = obj.optInt("sugoiBeamWidth", current.sugoiBeamWidth),
                    qwenEndpoint = obj.optString("qwenEndpoint", current.qwenEndpoint),
                    qwenApiKey = obj.optString("qwenApiKey", current.qwenApiKey),
                    enhancedPipeline = if (obj.has("enhancedPipeline")) obj.getBoolean("enhancedPipeline") else current.enhancedPipeline,
                    pipelineMethod = if (obj.has("pipelineMethod")) obj.getInt("pipelineMethod") else current.pipelineMethod,
                    chunkLinesCount = if (obj.has("chunkLinesCount")) obj.getInt("chunkLinesCount").coerceIn(1, 3) else current.chunkLinesCount,
                    probeDet2Neighbors = if (obj.has("probeDet2Neighbors")) obj.getBoolean("probeDet2Neighbors") else current.probeDet2Neighbors,
                    probeStep3Orphans = if (obj.has("probeStep3Orphans")) obj.getBoolean("probeStep3Orphans") else current.probeStep3Orphans,
                )
                _config.value = updated
                AppLogger.success("Reloaded active Sigma config dynamically from: ${sigmaFile.absolutePath}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            AppLogger.warn("Failed to reload sigma_config.json: ${e.message}")
            false
        }
    }

    fun setConfig(newConfig: PipelineConfig) {
        _config.value = newConfig
        saveConfigToDisk(newConfig)
    }

    val cacheDir: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KisaraTranslator/steps/cache").apply { mkdirs() }

    fun saveStep1Cache(boxes: List<Rect>, bubbles: List<Rect> = emptyList()) {
        try {
            val arr = JSONArray()
            boxes.forEach { b ->
                arr.put(JSONObject().apply {
                    put("l", b.left)
                    put("t", b.top)
                    put("r", b.right)
                    put("b", b.bottom)
                })
            }
            File(cacheDir, "step1_detection.json").writeText(arr.toString())

            if (bubbles.isNotEmpty()) {
                val bArr = JSONArray()
                bubbles.forEach { b ->
                    bArr.put(JSONObject().apply {
                        put("l", b.left)
                        put("t", b.top)
                        put("r", b.right)
                        put("b", b.bottom)
                    })
                }
                File(cacheDir, "step1_bubbles.json").writeText(bArr.toString())
            }
        } catch (e: Exception) {
            AppLogger.warn("Failed to save step1 cache: ${e.message}")
        }
    }

    fun loadStep1Cache(): List<Rect>? {
        return try {
            val file = File(cacheDir, "step1_detection.json")
            if (!file.exists()) return null
            val arr = JSONArray(file.readText())
            val list = mutableListOf<Rect>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Rect(obj.getInt("l"), obj.getInt("t"), obj.getInt("r"), obj.getInt("b")))
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    fun loadStep1BubblesCache(): List<Rect>? {
        return try {
            val file = File(cacheDir, "step1_bubbles.json")
            if (!file.exists()) return null
            val arr = JSONArray(file.readText())
            val list = mutableListOf<Rect>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Rect(obj.getInt("l"), obj.getInt("t"), obj.getInt("r"), obj.getInt("b")))
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    fun saveStep2Cache(blocks: List<TranslationBlock>) {
        try {
            val arr = JSONArray()
            blocks.forEach { b ->
                arr.put(JSONObject().apply {
                    put("text", b.text)
                    put("x", b.x.toDouble())
                    put("y", b.y.toDouble())
                    put("w", b.width.toDouble())
                    put("h", b.height.toDouble())
                    put("symW", b.symWidth.toDouble())
                    put("symH", b.symHeight.toDouble())
                    put("angle", b.angle.toDouble())
                    put("isBubble", b.isBubble)
                })
            }
            File(cacheDir, "step2_ocr.json").writeText(arr.toString())
        } catch (e: Exception) {
            AppLogger.warn("Failed to save step2 cache: ${e.message}")
        }
    }

    fun loadStep2Cache(): List<TranslationBlock>? {
        return try {
            val file = File(cacheDir, "step2_ocr.json")
            if (!file.exists()) return null
            val arr = JSONArray(file.readText())
            val list = mutableListOf<TranslationBlock>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TranslationBlock(
                        text = obj.getString("text"),
                        x = obj.getDouble("x").toFloat(),
                        y = obj.getDouble("y").toFloat(),
                        width = obj.getDouble("w").toFloat(),
                        height = obj.getDouble("h").toFloat(),
                        symWidth = obj.optDouble("symW", 0.0).toFloat(),
                        symHeight = obj.optDouble("symH", 0.0).toFloat(),
                        angle = obj.optDouble("angle", 0.0).toFloat(),
                        isBubble = obj.optBoolean("isBubble", true),
                    )
                )
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    fun saveStep3Cache(blocks: List<TranslationBlock>) {
        try {
            val arr = JSONArray()
            blocks.forEach { b ->
                arr.put(JSONObject().apply {
                    put("text", b.text)
                    put("x", b.x.toDouble())
                    put("y", b.y.toDouble())
                    put("w", b.width.toDouble())
                    put("h", b.height.toDouble())
                    put("symW", b.symWidth.toDouble())
                    put("symH", b.symHeight.toDouble())
                    put("angle", b.angle.toDouble())
                    put("isBubble", b.isBubble)
                })
            }
            File(cacheDir, "step3_grouping.json").writeText(arr.toString())
        } catch (e: Exception) {
            AppLogger.warn("Failed to save step3 cache: ${e.message}")
        }
    }

    fun loadStep3Cache(): List<TranslationBlock>? {
        return try {
            val file = File(cacheDir, "step3_grouping.json")
            if (!file.exists()) return null
            val arr = JSONArray(file.readText())
            val list = mutableListOf<TranslationBlock>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TranslationBlock(
                        text = obj.getString("text"),
                        x = obj.getDouble("x").toFloat(),
                        y = obj.getDouble("y").toFloat(),
                        width = obj.getDouble("w").toFloat(),
                        height = obj.getDouble("h").toFloat(),
                        symWidth = obj.optDouble("symW", 0.0).toFloat(),
                        symHeight = obj.optDouble("symH", 0.0).toFloat(),
                        angle = obj.optDouble("angle", 0.0).toFloat(),
                        isBubble = obj.optBoolean("isBubble", true),
                    )
                )
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    fun saveStep4Cache(page: PageTranslation) {
        try {
            val arr = JSONArray()
            page.blocks.forEach { b ->
                arr.put(JSONObject().apply {
                    put("text", b.text)
                    put("translation", b.translation)
                    put("x", b.x.toDouble())
                    put("y", b.y.toDouble())
                    put("w", b.width.toDouble())
                    put("h", b.height.toDouble())
                    put("symW", b.symWidth.toDouble())
                    put("symH", b.symHeight.toDouble())
                    put("angle", b.angle.toDouble())
                    put("isBubble", b.isBubble)
                })
            }
            File(cacheDir, "step4_translation.json").writeText(arr.toString())
        } catch (e: Exception) {
            AppLogger.warn("Failed to save step4 cache: ${e.message}")
        }
    }

    fun loadStep4Cache(): PageTranslation? {
        return try {
            val file = File(cacheDir, "step4_translation.json")
            if (!file.exists()) return null
            val arr = JSONArray(file.readText())
            val list = mutableListOf<TranslationBlock>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TranslationBlock(
                        text = obj.getString("text"),
                        translation = obj.optString("translation", ""),
                        x = obj.getDouble("x").toFloat(),
                        y = obj.getDouble("y").toFloat(),
                        width = obj.getDouble("w").toFloat(),
                        height = obj.getDouble("h").toFloat(),
                        symWidth = obj.optDouble("symW", 0.0).toFloat(),
                        symHeight = obj.optDouble("symH", 0.0).toFloat(),
                        angle = obj.optDouble("angle", 0.0).toFloat(),
                        isBubble = obj.optBoolean("isBubble", true),
                    )
                )
            }
            PageTranslation(blocks = list)
        } catch (_: Exception) {
            null
        }
    }

    fun startSinglePageTranslation() {
        startPipeline(fromStep = 1, singleStepOnly = false)
    }

    fun startPipeline(fromStep: Int = 1, singleStepOnly: Boolean = false) {
        val bitmap = _originalBitmap.value ?: return
        if (_isProcessing.value) return

        activeJob?.cancel()
        activeJob = serviceScope.launch {
            _isProcessing.value = true
            try {
                val isM5 = _config.value.effectiveMethod in listOf(4, 5, 6, 7, 8, 9)
                val stepName = when (fromStep) {
                    1 -> "Step 1: Detection"
                    2 -> if (isM5) "Step 2-4: Lines/Grouping/OCR" else "Step 2: OCR"
                    3 -> "Step 3: Grouping"
                    4 -> if (isM5) "Step 5: Translation" else "Step 4: Translation"
                    5 -> if (isM5) "Step 6-7: Inpainting/Rendering" else "Step 5: Rendering"
                    else -> "Step $fromStep"
                }
                val modeStr = if (singleStepOnly) "Single Step [$stepName]" else "From [$stepName] onwards"
                AppLogger.step("Executing Pipeline: $modeStr")

                val result = executePipeline(bitmap, _config.value, fromStep, singleStepOnly) { stepMsg ->
                    _currentStep.value = stepMsg
                }
                _translationResult.value = result
                _lastDiagnostics.value = result.diagnostics
                AppLogger.success("Pipeline completed in ${result.diagnostics.totalTimeMs}ms (${result.diagnostics.finalBlocksCount} text blocks)")

                exportLatestToStorage(result)
            } catch (e: Exception) {
                AppLogger.error("Pipeline execution failed", e)
                _currentStep.value = "Failed: ${e.message}"
            } finally {
                _isProcessing.value = false
                scheduleIdleMemoryOptimization()
            }
        }
    }

    private var idleCleanupJob: Job? = null

    /**
     * Keeps ONNX sessions warm in RAM for consecutive page turns.
     * Only evicts and closes model sessions if the app remains idle for [delayMs].
     */
    fun scheduleIdleMemoryOptimization(delayMs: Long = 5_000L) {
        idleCleanupJob?.cancel()
        idleCleanupJob = serviceScope.launch {
            kotlinx.coroutines.delay(delayMs)
            optimizeMemory()
            AppLogger.info("Idle timeout reached: Released warm ONNX sessions from RAM")
        }
    }

    fun cancelTranslation() {
        activeJob?.cancel()
        activeJob = null
        _isProcessing.value = false
        _currentStep.value = "Cancelled"
        AppLogger.warn("Translation cancelled by user")
    }

    fun updateBlockTranslation(index: Int, newText: String) {
        val current = _translationResult.value ?: return
        if (index !in current.pageTranslation.blocks.indices) return

        val blocks = current.pageTranslation.blocks.toMutableList()
        val oldBlock = blocks[index]
        blocks[index] = oldBlock.copy(translation = newText)
        val updatedPage = current.pageTranslation.copy(blocks = blocks)

        // Re-render
        val newRendered = ImageUtils.renderTranslatedPage(
            original = current.originalBitmap,
            translation = updatedPage,
            rawBoxes = current.detectedBoxes,
            fillBubbleBackground = _config.value.fillBubbleBackground,
            textScaleFactor = _config.value.textScaleFactor,
        )

        _translationResult.value = current.copy(
            translatedBitmap = newRendered,
            pageTranslation = updatedPage,
        )
        AppLogger.info("Updated text block #$index: '$newText'")
        exportLatestToStorage(_translationResult.value!!)
    }

    fun optimizeMemory() {
        idleCleanupJob?.cancel()
        val nativeBefore = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val runtime = Runtime.getRuntime()
        val javaBefore = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        comicTextDetector.runCatching { close() }
        bubbleDetector.runCatching { close() }
        mangaTextDetector2024.runCatching { close() }
        paddleOcrDetector.runCatching { close() }
        mangaOcrEngine.runCatching { close() }
        ctc48pxOcrEngine.runCatching { close() }
        paddleOcrRecognizer.runCatching { close() }
        bubbleSegmentationEngine.runCatching { close() }

        ResourceMonitor.optimizeRam(
            context = context,
            externalJavaBefore = javaBefore,
            externalNativeBefore = nativeBefore,
        )
        AppLogger.info("RAM freed: Unloaded all ONNX sessions and executed GC.")
    }

    private fun exportLatestToStorage(result: TranslationPipelineResult) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KisaraTranslator").apply { mkdirs() }
                val stepsDir = File(baseDir, "steps").apply { mkdirs() }
                val cropsDir = File(stepsDir, "crops").apply { mkdirs() }

                val origFile = File(baseDir, "original_latest.jpg")
                val transFile = File(baseDir, "translated_latest.jpg")
                val jsonFile = File(baseDir, "translation_latest.json")

                FileOutputStream(origFile).use { out ->
                    result.originalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                FileOutputStream(transFile).use { out ->
                    result.translatedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }

                // Intermediate Step 1: Original
                FileOutputStream(File(stepsDir, "step1_original.jpg")).use { out ->
                    result.originalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }

                // Intermediate Step 2: Detection Boxes
                if (result.detectedBoxes.isNotEmpty()) {
                    val boxesBitmap = ImageUtils.renderBoxesOverlay(result.originalBitmap, result.detectedBoxes)
                    FileOutputStream(File(stepsDir, "step2_detection_boxes.jpg")).use { out ->
                        boxesBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                }

                // Intermediate Step 2b: OCR Crop Patches
                result.ocrCrops.forEachIndexed { i, crop ->
                    val cropFile = File(cropsDir, String.format(Locale.US, "crop_%03d.jpg", i + 1))
                    FileOutputStream(cropFile).use { out ->
                        crop.cropBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                }

                // Intermediate Step 3: Inpainted Clean Page
                result.inpaintedBitmap?.let { inpainted ->
                    FileOutputStream(File(stepsDir, "step3_inpainted_clean.jpg")).use { out ->
                        inpainted.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                }

                // Intermediate Step 4: Final Translated Page
                FileOutputStream(File(stepsDir, "step4_final_translated.jpg")).use { out ->
                    result.translatedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }

                val rootJson = JSONObject().apply {
                    put("width", result.pageTranslation.imgWidth)
                    put("height", result.pageTranslation.imgHeight)
                    put("totalTimeMs", result.diagnostics.totalTimeMs)
                    put("detectionTimeMs", result.diagnostics.detectionTimeMs)
                    put("ocrTimeMs", result.diagnostics.ocrTimeMs)
                    put("groupingTimeMs", result.diagnostics.groupingTimeMs)
                    put("translationTimeMs", result.diagnostics.translationTimeMs)
                    put("renderingTimeMs", result.diagnostics.renderingTimeMs)
                    put("detectedBoxesCount", result.detectedBoxes.size)
                    put("finalBlocksCount", result.pageTranslation.blocks.size)

                    val blocksArray = JSONArray()
                    result.pageTranslation.blocks.forEach { b ->
                        val bObj = JSONObject().apply {
                            put("text", b.text)
                            put("translation", b.translation)
                            put("x", b.x)
                            put("y", b.y)
                            put("w", b.width)
                            put("h", b.height)
                            put("angle", b.angle)
                        }
                        blocksArray.put(bObj)
                    }
                    put("blocks", blocksArray)
                }

                jsonFile.writeText(rootJson.toString(2))
                File(stepsDir, "step_diagnostics.json").writeText(rootJson.toString(2))
                AppLogger.info("Exported latest results & intermediate steps to: ${baseDir.absolutePath} (steps/ folder created)")
            } catch (e: Exception) {
                AppLogger.warn("Failed to export latest results: ${e.message}")
            }
        }
    }

    private suspend fun executeOcr(
        bitmap: Bitmap,
        lineRegions: List<Rect>,
        config: PipelineConfig,
    ): Pair<List<TranslationBlock>, List<com.raen.kisaratranslator.data.model.OcrCropDebug>> {
        when (config.ocr) {
            OcrType.MANGA_OCR_INT8 -> {
                if (!mangaOcrEngine.isReady) {
                    throw IllegalStateException("MangaOCR INT8 is not ready. Please download required model files in Model Manager.")
                }
            }
            OcrType.MANGA_OCR -> {
                if (!mangaOcrEngine.isReady) {
                    throw IllegalStateException("MangaOCR FP32 is not ready. Please download required model files in Model Manager.")
                }
            }
            OcrType.MANGA_48PX_CTC -> {
                if (!ctc48pxOcrEngine.isReady) {
                    throw IllegalStateException("Manga Translator 48px CTC is not ready. Please download ocr-48px-ctc.onnx and alphabet in Model Manager.")
                }
            }
            OcrType.PADDLE_OCR -> {
                if (!paddleOcrRecognizer.isReady) {
                    throw IllegalStateException("PaddleOCR Recognizer is not ready. Please download required model files in Model Manager.")
                }
            }
            OcrType.MLKIT -> {
                // User explicitly selected ML Kit OCR
            }
        }

        val rawBlocks = mutableListOf<TranslationBlock>()
        val ocrCrops = mutableListOf<com.raen.kisaratranslator.data.model.OcrCropDebug>()
        val mlKitExplicit = if (config.ocr == OcrType.MLKIT) MlKitOcrRecognizer(config.sourceLang) else null

        try {
            val ocrConcurrency = when (config.speedOcr.coerceIn(1, 5)) {
                1 -> 1
                2 -> 1
                3 -> 2
                4 -> 3
                else -> 4
            }
            val semaphore = kotlinx.coroutines.sync.Semaphore(ocrConcurrency)
            val results = kotlinx.coroutines.coroutineScope {
                lineRegions.mapIndexed { idx, region ->
                    async(Dispatchers.Default) {
                        val pad = 4
                        val safeLeft = (region.left - pad).coerceIn(0, bitmap.width - 1)
                        val safeTop = (region.top - pad).coerceIn(0, bitmap.height - 1)
                        val safeRight = (region.right + pad).coerceIn(safeLeft + 1, bitmap.width)
                        val safeBottom = (region.bottom + pad).coerceIn(safeTop + 1, bitmap.height)
                        val safeW = safeRight - safeLeft
                        val safeH = safeBottom - safeTop

                        val crop = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeW, safeH)
                        val text: String
                        try {
                            val baseTokens = if (config.effectiveMethod == 3) 64 else 36
                            val maxTokens = when (config.speedOcr.coerceIn(1, 5)) {
                                1 -> 24
                                2 -> 30
                                else -> baseTokens
                            }
                            text = semaphore.withPermit {
                                when (config.ocr) {
                                    OcrType.MANGA_OCR_INT8 -> mangaOcrEngine.recognize(crop, requireInt8 = true, maxTokens = maxTokens)
                                    OcrType.MANGA_OCR -> mangaOcrEngine.recognize(crop, requireInt8 = false, maxTokens = maxTokens)
                                    OcrType.MANGA_48PX_CTC -> ctc48pxOcrEngine.recognize(crop)
                                    OcrType.PADDLE_OCR -> paddleOcrRecognizer.recognize(crop)
                                    OcrType.MLKIT -> mlKitExplicit?.recognize(crop) ?: ""
                                }
                            }
                        } catch (e: Exception) {
                            crop.recycle()
                            AppLogger.error("OCR recognition failed on line #${idx + 1}", e)
                            throw IllegalStateException("OCR failed on line #${idx + 1} with ${config.ocr.displayName}: ${e.message}", e)
                        }

                        val result = if (text.isNotBlank()) {
                            val cropDebug = com.raen.kisaratranslator.data.model.OcrCropDebug(
                                index = idx,
                                rect = region,
                                cropBitmap = crop.copy(crop.config ?: Bitmap.Config.ARGB_8888, false),
                                rawText = text,
                            )
                            val block = TranslationBlock(
                                text = text,
                                width = region.width().toFloat(),
                                height = region.height().toFloat(),
                                x = region.left.toFloat(),
                                y = region.top.toFloat(),
                                symWidth = region.width().toFloat() / max(text.length, 1),
                                symHeight = region.height().toFloat() / max(text.length, 1),
                                angle = if (region.height() > region.width() * 1.3f) 90f else 0f,
                                isBubble = true,
                            )
                            AppLogger.step("OCR Line #${idx + 1}/${lineRegions.size}: '$text'")
                            Pair(block, cropDebug)
                        } else {
                            null
                        }
                        crop.recycle()
                        result
                    }
                }.awaitAll()
            }

            for (res in results.filterNotNull()) {
                rawBlocks.add(res.first)
                ocrCrops.add(res.second)
            }
        } finally {
            mlKitExplicit?.close()
        }
        return Pair(rawBlocks, ocrCrops)
    }

    data class ProbedBoxResult(
        val isValid: Boolean,
        val text: String,
        val crop: Bitmap?,
    )

    private suspend fun probeBoxWithOcr(bitmap: Bitmap, box: Rect, maxTokens: Int = 12): ProbedBoxResult {
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
            val text = mangaOcrEngine.recognize(zoomedCrop, requireInt8 = true, maxTokens = maxTokens).trim()
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

    private suspend fun validateExtraBoxWithOcr(bitmap: Bitmap, box: Rect): Boolean {
        val res = probeBoxWithOcr(bitmap, box)
        res.crop?.recycle()
        return res.isValid
    }

    private suspend fun verifyNonBubbledLines(
        bitmap: Bitmap,
        bubbleLines: List<Rect>,
        orphanLines: List<Rect>,
        speedLevel: Int = 3,
        onStep: (String) -> Unit = {},
    ): Pair<List<Rect>, List<com.raen.kisaratranslator.data.model.OcrCropDebug>> {
        // Bubble lines are 100% immune from the noise filter. Only orphan lines outside bubbles are probed.
        if (!mangaOcrEngine.isReady || orphanLines.isEmpty()) {
            return Pair(bubbleLines + orphanLines, emptyList())
        }

        val concurrency = when (speedLevel.coerceIn(1, 5)) {
            1 -> 1
            2 -> 1
            3 -> 2
            4 -> 3
            else -> 4
        }
        val probeTokens = when (speedLevel.coerceIn(1, 5)) {
            1 -> 4
            2 -> 6
            3 -> 12
            4 -> 14
            else -> 16
        }
        val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency)
        val probedList = mutableListOf<com.raen.kisaratranslator.data.model.OcrCropDebug>()
        val confirmedOutside = mutableListOf<Rect>()

        val probeResults = coroutineScope {
            orphanLines.mapIndexed { idx, line ->
                async(Dispatchers.Default) {
                    val res = semaphore.withPermit {
                        probeBoxWithOcr(bitmap, line, maxTokens = probeTokens)
                    }
                    val cropBmp = res.crop ?: run {
                        val padX = (line.width() * 0.20f).toInt().coerceIn(2, 12)
                        val padY = (line.height() * 0.20f).toInt().coerceIn(2, 12)
                        val safeLeft = (line.left - padX).coerceIn(0, bitmap.width - 1)
                        val safeTop = (line.top - padY).coerceIn(0, bitmap.height - 1)
                        val safeRight = (line.right + padX).coerceIn(safeLeft + 1, bitmap.width)
                        val safeBottom = (line.bottom + padY).coerceIn(safeTop + 1, bitmap.height)
                        Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)
                    }
                    Triple(idx, line, Pair(res, cropBmp))
                }
            }.awaitAll()
        }

        for ((idx, line, pair) in probeResults) {
            val (res, cropBmp) = pair
            if (res.isValid) {
                confirmedOutside.add(line)
                probedList.add(
                    com.raen.kisaratranslator.data.model.OcrCropDebug(
                        index = idx,
                        rect = line,
                        cropBitmap = cropBmp,
                        rawText = "✓ Text: ${res.text}",
                    )
                )
            } else {
                probedList.add(
                    com.raen.kisaratranslator.data.model.OcrCropDebug(
                        index = idx,
                        rect = line,
                        cropBitmap = cropBmp,
                        rawText = if (res.text.isBlank()) "✗ Noise: (No glyphs)" else "✗ Noise: ${res.text}",
                    )
                )
            }
        }

        val dropped = orphanLines.size - confirmedOutside.size
        if (dropped > 0) {
            AppLogger.info("Step 3 Non-Bubbled Check: Dropped $dropped noise lines (${confirmedOutside.size}/${orphanLines.size} confirmed text outside bubbles)")
        }
        return Pair(bubbleLines + confirmedOutside, probedList)
    }

    private suspend fun executeOcrMethod5(
        bitmap: Bitmap,
        lobes: List<com.raen.kisaratranslator.engine.grouping.Method5LobeGrouper.DialogueLobe>,
        config: PipelineConfig,
        onProgress: (done: Int, total: Int, currentText: String) -> Unit = { _, _, _ -> },
    ): Pair<List<TranslationBlock>, List<com.raen.kisaratranslator.data.model.OcrCropDebug>> {
        val rawBlocks = mutableListOf<TranslationBlock>()
        val ocrCrops = mutableListOf<com.raen.kisaratranslator.data.model.OcrCropDebug>()
        val ocrConcurrency = when (config.speedOcr.coerceIn(1, 5)) {
            1 -> 1
            2 -> 1
            3 -> 2
            4 -> 3
            else -> 4
        }
        val semaphore = kotlinx.coroutines.sync.Semaphore(ocrConcurrency)
        val completedCounter = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            lobes.mapIndexed { lobeIdx, lobe ->
                async(Dispatchers.Default) {
                    if (!lobe.requiresLineFallback && lobe.lines.size <= config.chunkLinesCount) {
                        // Multi-line sweet-spot OCR: Feed whole bunch crop to MangaOCR!
                        val pad = 8
                        val safeLeft = (lobe.bounds.left - pad).coerceIn(0, bitmap.width - 1)
                        val safeTop = (lobe.bounds.top - pad).coerceIn(0, bitmap.height - 1)
                        val safeRight = (lobe.bounds.right + pad).coerceIn(safeLeft + 1, bitmap.width)
                        val safeBottom = (lobe.bounds.bottom + pad).coerceIn(safeTop + 1, bitmap.height)
                        val crop = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

                        val text = semaphore.withPermit {
                            when (config.ocr) {
                                OcrType.MANGA_OCR_INT8 -> mangaOcrEngine.recognize(crop, requireInt8 = true, maxTokens = 48)
                                OcrType.MANGA_OCR -> mangaOcrEngine.recognize(crop, requireInt8 = false, maxTokens = 48)
                                OcrType.MANGA_48PX_CTC -> ctc48pxOcrEngine.recognize(crop)
                                OcrType.PADDLE_OCR -> paddleOcrRecognizer.recognize(crop)
                                else -> mangaOcrEngine.recognize(crop, requireInt8 = true, maxTokens = 48)
                            }
                        }

                        val done = completedCounter.incrementAndGet()
                        onProgress(done, lobes.size, text)

                        val cropDebug = com.raen.kisaratranslator.data.model.OcrCropDebug(
                            index = lobeIdx,
                            rect = lobe.bounds,
                            cropBitmap = crop.copy(crop.config ?: Bitmap.Config.ARGB_8888, false),
                            rawText = "$text [Multi-Line ${lobe.lines.size} cols]",
                        )
                        crop.recycle()

                        if (text.isNotBlank()) {
                            val block = TranslationBlock(
                                text = text,
                                width = lobe.bounds.width().toFloat(),
                                height = lobe.bounds.height().toFloat(),
                                x = lobe.bounds.left.toFloat(),
                                y = lobe.bounds.top.toFloat(),
                                symWidth = lobe.bounds.width().toFloat() / max(text.length, 1),
                                symHeight = lobe.bounds.height().toFloat() / max(text.length, 1),
                                angle = if (lobe.bounds.height() > lobe.bounds.width() * 1.3f) 90f else 0f,
                                isBubble = lobe.isBubble,
                            )
                            Pair(block, listOf(cropDebug))
                        } else null
                    } else {
                        // Oversized / Compound Bubble Fallback: chunking OCR with Japanese RTL joining!
                        val chunks = lobe.lines.chunked(config.chunkLinesCount)
                        val chunkTexts = mutableListOf<String>()
                        val chunkCrops = mutableListOf<com.raen.kisaratranslator.data.model.OcrCropDebug>()

                        for ((chunkIdx, chunkLines) in chunks.withIndex()) {
                            val pad = 0
                            val minX = chunkLines.minOf { it.left }
                            val minY = chunkLines.minOf { it.top }
                            val maxX = chunkLines.maxOf { it.right }
                            val maxY = chunkLines.maxOf { it.bottom }

                            val safeLeft = (minX - pad).coerceIn(0, bitmap.width - 1)
                            val safeTop = (minY - pad).coerceIn(0, bitmap.height - 1)
                            val safeRight = (maxX + pad).coerceIn(safeLeft + 1, bitmap.width)
                            val safeBottom = (maxY + pad).coerceIn(safeTop + 1, bitmap.height)
                            val chunkCrop = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

                            val cText = semaphore.withPermit {
                                when (config.ocr) {
                                    OcrType.MANGA_OCR_INT8 -> mangaOcrEngine.recognize(chunkCrop, requireInt8 = true, maxTokens = 36)
                                    OcrType.MANGA_OCR -> mangaOcrEngine.recognize(chunkCrop, requireInt8 = false, maxTokens = 36)
                                    OcrType.MANGA_48PX_CTC -> ctc48pxOcrEngine.recognize(chunkCrop)
                                    OcrType.PADDLE_OCR -> paddleOcrRecognizer.recognize(chunkCrop)
                                    else -> mangaOcrEngine.recognize(chunkCrop, requireInt8 = true, maxTokens = 36)
                                }
                            }

                            if (cText.isNotBlank()) {
                                chunkTexts.add(cText)
                                val chunkRect = Rect(safeLeft, safeTop, safeRight, safeBottom)
                                chunkCrops.add(
                                    com.raen.kisaratranslator.data.model.OcrCropDebug(
                                        index = lobeIdx * 100 + chunkIdx,
                                        rect = chunkRect,
                                        cropBitmap = chunkCrop.copy(chunkCrop.config ?: Bitmap.Config.ARGB_8888, false),
                                        rawText = "Chunk ${chunkIdx + 1}/${chunks.size} (${chunkLines.size} cols): $cText",
                                    )
                                )
                            }
                            chunkCrop.recycle()
                        }

                        val fullText = chunkTexts.joinToString("")
                        val done = completedCounter.incrementAndGet()
                        onProgress(done, lobes.size, fullText)

                        if (fullText.isNotBlank()) {
                            val block = TranslationBlock(
                                text = fullText,
                                width = lobe.bounds.width().toFloat(),
                                height = lobe.bounds.height().toFloat(),
                                x = lobe.bounds.left.toFloat(),
                                y = lobe.bounds.top.toFloat(),
                                symWidth = lobe.bounds.width().toFloat() / max(fullText.length, 1),
                                symHeight = lobe.bounds.height().toFloat() / max(fullText.length, 1),
                                angle = if (lobe.bounds.height() > lobe.bounds.width() * 1.3f) 90f else 0f,
                                isBubble = lobe.isBubble,
                            )
                            Pair(block, chunkCrops)
                        } else null
                    }
                }
            }.awaitAll()
        }.filterNotNull().forEach { (block, debugCrops) ->
            rawBlocks.add(block)
            ocrCrops.addAll(debugCrops)
        }

        return Pair(rawBlocks, ocrCrops)
    }

    private suspend fun executeOcrMethod6(
        bitmap: Bitmap,
        units: List<com.raen.kisaratranslator.engine.grouping.Method6BubbleGrouper.DialogueBubbleUnit>,
        config: PipelineConfig,
        onProgress: (done: Int, total: Int, currentText: String) -> Unit = { _, _, _ -> },
    ): Pair<List<TranslationBlock>, List<com.raen.kisaratranslator.data.model.OcrCropDebug>> {
        val rawBlocks = mutableListOf<TranslationBlock>()
        val ocrCrops = mutableListOf<com.raen.kisaratranslator.data.model.OcrCropDebug>()
        val ocrConcurrency = when (config.speedOcr.coerceIn(1, 5)) {
            1 -> 1
            2 -> 1
            else -> 2
        }
        val semaphore = kotlinx.coroutines.sync.Semaphore(ocrConcurrency)
        val completedCounter = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            units.mapIndexed { unitIdx, unit ->
                async(Dispatchers.Default) {
                    if (unit.lines.size <= config.chunkLinesCount) {
                        // Sweet-spot bubble size: direct multi-line OCR in 1 single pass
                        val pad = 8
                        val safeLeft = (unit.bounds.left - pad).coerceIn(0, bitmap.width - 1)
                        val safeTop = (unit.bounds.top - pad).coerceIn(0, bitmap.height - 1)
                        val safeRight = (unit.bounds.right + pad).coerceIn(safeLeft + 1, bitmap.width)
                        val safeBottom = (unit.bounds.bottom + pad).coerceIn(safeTop + 1, bitmap.height)
                        val crop = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

                        val text = semaphore.withPermit {
                            when (config.ocr) {
                                OcrType.MANGA_OCR_INT8 -> mangaOcrEngine.recognize(crop, requireInt8 = true, maxTokens = 48)
                                OcrType.MANGA_OCR -> mangaOcrEngine.recognize(crop, requireInt8 = false, maxTokens = 48)
                                OcrType.MANGA_48PX_CTC -> ctc48pxOcrEngine.recognize(crop)
                                OcrType.PADDLE_OCR -> paddleOcrRecognizer.recognize(crop)
                                else -> mangaOcrEngine.recognize(crop, requireInt8 = true, maxTokens = 48)
                            }
                        }

                        val done = completedCounter.incrementAndGet()
                        onProgress(done, units.size, text)

                        val cropDebug = com.raen.kisaratranslator.data.model.OcrCropDebug(
                            index = unitIdx,
                            rect = unit.bounds,
                            cropBitmap = crop.copy(crop.config ?: Bitmap.Config.ARGB_8888, false),
                            rawText = "$text [Bubble ${unit.lines.size} cols]",
                        )
                        crop.recycle()

                        if (text.isNotBlank()) {
                            val block = TranslationBlock(
                                text = text,
                                width = unit.bounds.width().toFloat(),
                                height = unit.bounds.height().toFloat(),
                                x = unit.bounds.left.toFloat(),
                                y = unit.bounds.top.toFloat(),
                                symWidth = unit.bounds.width().toFloat() / max(text.length, 1),
                                symHeight = unit.bounds.height().toFloat() / max(text.length, 1),
                                angle = if (unit.bounds.height() > unit.bounds.width() * 1.3f) 90f else 0f,
                                isBubble = unit.isBubble,
                            )
                            Pair(block, listOf(cropDebug))
                        } else null
                    } else {
                        // Large bubble (> chunkLinesCount lines): Internal Line Chunking in slices of <= chunkLinesCount lines
                        val chunks = unit.lines.chunked(config.chunkLinesCount)
                        val chunkTexts = mutableListOf<String>()
                        val chunkCrops = mutableListOf<com.raen.kisaratranslator.data.model.OcrCropDebug>()

                        for ((chunkIdx, chunkLines) in chunks.withIndex()) {
                            val pad = 0
                            val minX = chunkLines.minOf { it.left }
                            val minY = chunkLines.minOf { it.top }
                            val maxX = chunkLines.maxOf { it.right }
                            val maxY = chunkLines.maxOf { it.bottom }

                            val safeLeft = (minX - pad).coerceIn(0, bitmap.width - 1)
                            val safeTop = (minY - pad).coerceIn(0, bitmap.height - 1)
                            val safeRight = (maxX + pad).coerceIn(safeLeft + 1, bitmap.width)
                            val safeBottom = (maxY + pad).coerceIn(safeTop + 1, bitmap.height)
                            val chunkCrop = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

                            val cText = semaphore.withPermit {
                                when (config.ocr) {
                                    OcrType.MANGA_OCR_INT8 -> mangaOcrEngine.recognize(chunkCrop, requireInt8 = true, maxTokens = 36)
                                    OcrType.MANGA_OCR -> mangaOcrEngine.recognize(chunkCrop, requireInt8 = false, maxTokens = 36)
                                    OcrType.MANGA_48PX_CTC -> ctc48pxOcrEngine.recognize(chunkCrop)
                                    OcrType.PADDLE_OCR -> paddleOcrRecognizer.recognize(chunkCrop)
                                    else -> mangaOcrEngine.recognize(chunkCrop, requireInt8 = true, maxTokens = 36)
                                }
                            }
                            if (cText.isNotBlank()) {
                                chunkTexts.add(cText)
                                val chunkRect = Rect(safeLeft, safeTop, safeRight, safeBottom)
                                chunkCrops.add(
                                    com.raen.kisaratranslator.data.model.OcrCropDebug(
                                        index = unitIdx * 100 + chunkIdx,
                                        rect = chunkRect,
                                        cropBitmap = chunkCrop.copy(chunkCrop.config ?: Bitmap.Config.ARGB_8888, false),
                                        rawText = "Chunk ${chunkIdx + 1}/${chunks.size} (${chunkLines.size} cols): $cText",
                                    )
                                )
                            }
                            chunkCrop.recycle()
                        }

                        val fullText = chunkTexts.joinToString("")
                        val done = completedCounter.incrementAndGet()
                        onProgress(done, units.size, fullText)

                        if (fullText.isNotBlank()) {
                            val block = TranslationBlock(
                                text = fullText,
                                width = unit.bounds.width().toFloat(),
                                height = unit.bounds.height().toFloat(),
                                x = unit.bounds.left.toFloat(),
                                y = unit.bounds.top.toFloat(),
                                symWidth = unit.bounds.width().toFloat() / max(fullText.length, 1),
                                symHeight = unit.bounds.height().toFloat() / max(fullText.length, 1),
                                angle = if (unit.bounds.height() > unit.bounds.width() * 1.3f) 90f else 0f,
                                isBubble = unit.isBubble,
                            )
                            Pair(block, chunkCrops)
                        } else null
                    }
                }
            }.awaitAll().filterNotNull().forEach { (block, crops) ->
                rawBlocks.add(block)
                ocrCrops.addAll(crops)
            }
        }
        return Pair(rawBlocks, ocrCrops)
    }

    private suspend fun executeOcrMethod7(
        bitmap: Bitmap,
        units: List<com.raen.kisaratranslator.engine.grouping.Method7CrunchGrouper.DialogueUnit>,
        config: PipelineConfig,
        onProgress: (done: Int, total: Int, currentText: String) -> Unit = { _, _, _ -> },
    ): Pair<List<TranslationBlock>, List<com.raen.kisaratranslator.data.model.OcrCropDebug>> {
        val rawBlocks = mutableListOf<TranslationBlock>()
        val ocrCrops = mutableListOf<com.raen.kisaratranslator.data.model.OcrCropDebug>()
        val semaphore = kotlinx.coroutines.sync.Semaphore(2)
        val completedCounter = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            units.mapIndexed { unitIdx, unit ->
                async(Dispatchers.Default) {
                    if (unit.lines.size <= config.chunkLinesCount) {
                        // Sweet-spot: direct multi-line OCR in 1 pass
                        val pad = 8
                        val safeLeft   = (unit.bounds.left   - pad).coerceIn(0, bitmap.width  - 1)
                        val safeTop    = (unit.bounds.top    - pad).coerceIn(0, bitmap.height - 1)
                        val safeRight  = (unit.bounds.right  + pad).coerceIn(safeLeft + 1, bitmap.width)
                        val safeBottom = (unit.bounds.bottom + pad).coerceIn(safeTop  + 1, bitmap.height)
                        val crop = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

                        val text = semaphore.withPermit {
                            when (config.ocr) {
                                OcrType.MANGA_OCR_INT8 -> mangaOcrEngine.recognize(crop, requireInt8 = true,  maxTokens = 48)
                                OcrType.MANGA_OCR      -> mangaOcrEngine.recognize(crop, requireInt8 = false, maxTokens = 48)
                                OcrType.MANGA_48PX_CTC -> ctc48pxOcrEngine.recognize(crop)
                                OcrType.PADDLE_OCR     -> paddleOcrRecognizer.recognize(crop)
                                else                   -> mangaOcrEngine.recognize(crop, requireInt8 = true,  maxTokens = 48)
                            }
                        }

                        val done = completedCounter.incrementAndGet()
                        onProgress(done, units.size, text)

                        val cropDebug = com.raen.kisaratranslator.data.model.OcrCropDebug(
                            index = unitIdx,
                            rect = unit.bounds,
                            cropBitmap = crop.copy(crop.config ?: Bitmap.Config.ARGB_8888, false),
                            rawText = "$text [M7 ${if (unit.isBubble) "B" else "O"} ${unit.lines.size}L]",
                        )
                        crop.recycle()

                        if (text.isNotBlank()) {
                            val block = TranslationBlock(
                                text      = text,
                                width     = unit.bounds.width().toFloat(),
                                height    = unit.bounds.height().toFloat(),
                                x         = unit.bounds.left.toFloat(),
                                y         = unit.bounds.top.toFloat(),
                                symWidth  = unit.bounds.width().toFloat()  / max(text.length, 1),
                                symHeight = unit.bounds.height().toFloat() / max(text.length, 1),
                                angle     = if (unit.bounds.height() > unit.bounds.width() * 1.3f) 90f else 0f,
                                isBubble  = unit.isBubble,
                            )
                            Pair(block, listOf(cropDebug))
                        } else null
                    } else {
                        // Large unit: chunk into slices of <= chunkLinesCount lines
                        val chunks = unit.lines.chunked(config.chunkLinesCount)
                        val chunkTexts = mutableListOf<String>()
                        val chunkCrops = mutableListOf<com.raen.kisaratranslator.data.model.OcrCropDebug>()

                        for ((chunkIdx, chunkLines) in chunks.withIndex()) {
                            val minX = chunkLines.minOf { it.left }
                            val minY = chunkLines.minOf { it.top }
                            val maxX = chunkLines.maxOf { it.right }
                            val maxY = chunkLines.maxOf { it.bottom }

                            val safeLeft   = minX.coerceIn(0, bitmap.width  - 1)
                            val safeTop    = minY.coerceIn(0, bitmap.height - 1)
                            val safeRight  = maxX.coerceIn(safeLeft + 1, bitmap.width)
                            val safeBottom = maxY.coerceIn(safeTop  + 1, bitmap.height)
                            val chunkCrop  = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

                            val cText = semaphore.withPermit {
                                when (config.ocr) {
                                    OcrType.MANGA_OCR_INT8 -> mangaOcrEngine.recognize(chunkCrop, requireInt8 = true,  maxTokens = 36)
                                    OcrType.MANGA_OCR      -> mangaOcrEngine.recognize(chunkCrop, requireInt8 = false, maxTokens = 36)
                                    OcrType.MANGA_48PX_CTC -> ctc48pxOcrEngine.recognize(chunkCrop)
                                    OcrType.PADDLE_OCR     -> paddleOcrRecognizer.recognize(chunkCrop)
                                    else                   -> mangaOcrEngine.recognize(chunkCrop, requireInt8 = true,  maxTokens = 36)
                                }
                            }
                            chunkTexts.add(cText)
                            chunkCrops.add(
                                com.raen.kisaratranslator.data.model.OcrCropDebug(
                                    index      = unitIdx * 100 + chunkIdx,
                                    rect       = android.graphics.Rect(safeLeft, safeTop, safeRight, safeBottom),
                                    cropBitmap = chunkCrop.copy(chunkCrop.config ?: Bitmap.Config.ARGB_8888, false),
                                    rawText    = cText,
                                )
                            )
                            chunkCrop.recycle()
                        }

                        val done = completedCounter.incrementAndGet()
                        val fullText = chunkTexts.filter { it.isNotBlank() }.joinToString("")
                        onProgress(done, units.size, fullText)

                        if (fullText.isNotBlank()) {
                            val block = TranslationBlock(
                                text      = fullText,
                                width     = unit.bounds.width().toFloat(),
                                height    = unit.bounds.height().toFloat(),
                                x         = unit.bounds.left.toFloat(),
                                y         = unit.bounds.top.toFloat(),
                                symWidth  = unit.bounds.width().toFloat()  / max(fullText.length, 1),
                                symHeight = unit.bounds.height().toFloat() / max(fullText.length, 1),
                                angle     = if (unit.bounds.height() > unit.bounds.width() * 1.3f) 90f else 0f,
                                isBubble  = unit.isBubble,
                            )
                            Pair(block, chunkCrops)
                        } else null
                    }
                }
            }.awaitAll().filterNotNull().forEach { (block, crops) ->
                rawBlocks.add(block)
                ocrCrops.addAll(crops)
            }
        }
        return Pair(rawBlocks, ocrCrops)
    }

    private suspend fun executeOcrMethod8(
        bitmap: Bitmap,
        units: List<com.raen.kisaratranslator.engine.grouping.Method8SegmentGrouper.DialogueUnit>,
        config: PipelineConfig,
        onProgress: (done: Int, total: Int, currentText: String) -> Unit = { _, _, _ -> },
    ): Pair<List<TranslationBlock>, List<com.raen.kisaratranslator.data.model.OcrCropDebug>> {
        val rawBlocks = mutableListOf<TranslationBlock>()
        val ocrCrops = mutableListOf<com.raen.kisaratranslator.data.model.OcrCropDebug>()
        val semaphore = kotlinx.coroutines.sync.Semaphore(2)
        val completedCounter = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            units.mapIndexed { unitIdx, unit ->
                async(Dispatchers.Default) {
                    if (unit.lines.size <= config.chunkLinesCount) {
                        // Sweet-spot: direct multi-line OCR in 1 pass
                        val pad = 8
                        val safeLeft   = (unit.bounds.left   - pad).coerceIn(0, bitmap.width  - 1)
                        val safeTop    = (unit.bounds.top    - pad).coerceIn(0, bitmap.height - 1)
                        val safeRight  = (unit.bounds.right  + pad).coerceIn(safeLeft + 1, bitmap.width)
                        val safeBottom = (unit.bounds.bottom + pad).coerceIn(safeTop  + 1, bitmap.height)
                        val crop = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

                        val text = semaphore.withPermit {
                            when (config.ocr) {
                                OcrType.MANGA_OCR_INT8 -> mangaOcrEngine.recognize(crop, requireInt8 = true,  maxTokens = 48)
                                OcrType.MANGA_OCR      -> mangaOcrEngine.recognize(crop, requireInt8 = false, maxTokens = 48)
                                OcrType.MANGA_48PX_CTC -> ctc48pxOcrEngine.recognize(crop)
                                OcrType.PADDLE_OCR     -> paddleOcrRecognizer.recognize(crop)
                                else                   -> mangaOcrEngine.recognize(crop, requireInt8 = true,  maxTokens = 48)
                            }
                        }

                        val done = completedCounter.incrementAndGet()
                        onProgress(done, units.size, text)

                        val cropDebug = com.raen.kisaratranslator.data.model.OcrCropDebug(
                            index = unitIdx,
                            rect = unit.bounds,
                            cropBitmap = crop.copy(crop.config ?: Bitmap.Config.ARGB_8888, false),
                            rawText = "$text [M8 ${if (unit.isBubble) "B" else "O"} ${unit.lines.size}L]",
                        )
                        crop.recycle()

                        if (text.isNotBlank()) {
                            val block = TranslationBlock(
                                text      = text,
                                width     = unit.bounds.width().toFloat(),
                                height    = unit.bounds.height().toFloat(),
                                x         = unit.bounds.left.toFloat(),
                                y         = unit.bounds.top.toFloat(),
                                symWidth  = unit.bounds.width().toFloat()  / max(text.length, 1),
                                symHeight = unit.bounds.height().toFloat() / max(text.length, 1),
                                angle     = if (unit.bounds.height() > unit.bounds.width() * 1.3f) 90f else 0f,
                                isBubble  = unit.isBubble,
                            )
                            Pair(block, listOf(cropDebug))
                        } else null
                    } else {
                        // Large unit: chunk into slices of <= chunkLinesCount lines
                        val chunks = unit.lines.chunked(config.chunkLinesCount)
                        val chunkTexts = mutableListOf<String>()
                        val chunkCrops = mutableListOf<com.raen.kisaratranslator.data.model.OcrCropDebug>()

                        for ((chunkIdx, chunkLines) in chunks.withIndex()) {
                            val minX = chunkLines.minOf { it.left }
                            val minY = chunkLines.minOf { it.top }
                            val maxX = chunkLines.maxOf { it.right }
                            val maxY = chunkLines.maxOf { it.bottom }

                            val safeLeft   = minX.coerceIn(0, bitmap.width  - 1)
                            val safeTop    = minY.coerceIn(0, bitmap.height - 1)
                            val safeRight  = maxX.coerceIn(safeLeft + 1, bitmap.width)
                            val safeBottom = maxY.coerceIn(safeTop  + 1, bitmap.height)
                            val chunkCrop  = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)

                            val cText = semaphore.withPermit {
                                when (config.ocr) {
                                    OcrType.MANGA_OCR_INT8 -> mangaOcrEngine.recognize(chunkCrop, requireInt8 = true,  maxTokens = 36)
                                    OcrType.MANGA_OCR      -> mangaOcrEngine.recognize(chunkCrop, requireInt8 = false, maxTokens = 36)
                                    OcrType.MANGA_48PX_CTC -> ctc48pxOcrEngine.recognize(chunkCrop)
                                    OcrType.PADDLE_OCR     -> paddleOcrRecognizer.recognize(chunkCrop)
                                    else                   -> mangaOcrEngine.recognize(chunkCrop, requireInt8 = true,  maxTokens = 36)
                                }
                            }
                            chunkTexts.add(cText)
                            chunkCrops.add(
                                com.raen.kisaratranslator.data.model.OcrCropDebug(
                                    index      = unitIdx * 100 + chunkIdx,
                                    rect       = android.graphics.Rect(safeLeft, safeTop, safeRight, safeBottom),
                                    cropBitmap = chunkCrop.copy(chunkCrop.config ?: Bitmap.Config.ARGB_8888, false),
                                    rawText    = cText,
                                )
                            )
                            chunkCrop.recycle()
                        }

                        val done = completedCounter.incrementAndGet()
                        val fullText = chunkTexts.filter { it.isNotBlank() }.joinToString("")
                        onProgress(done, units.size, fullText)

                        if (fullText.isNotBlank()) {
                            val block = TranslationBlock(
                                text      = fullText,
                                width     = unit.bounds.width().toFloat(),
                                height    = unit.bounds.height().toFloat(),
                                x         = unit.bounds.left.toFloat(),
                                y         = unit.bounds.top.toFloat(),
                                symWidth  = unit.bounds.width().toFloat()  / max(fullText.length, 1),
                                symHeight = unit.bounds.height().toFloat() / max(fullText.length, 1),
                                angle     = if (unit.bounds.height() > unit.bounds.width() * 1.3f) 90f else 0f,
                                isBubble  = unit.isBubble,
                            )
                            Pair(block, chunkCrops)
                        } else null
                    }
                }
            }.awaitAll().filterNotNull().forEach { (block, crops) ->
                rawBlocks.add(block)
                ocrCrops.addAll(crops)
            }
        }
        return Pair(rawBlocks, ocrCrops)
    }

    suspend fun translateBitmap(
        bitmap: Bitmap,
        config: PipelineConfig,
        onStep: (String) -> Unit = {},
    ): TranslationPipelineResult = executePipeline(bitmap, config, fromStep = 1, singleStepOnly = false, onStep = onStep)

    suspend fun executePipeline(
        bitmap: Bitmap,
        initialConfig: PipelineConfig,
        fromStep: Int = 1,
        singleStepOnly: Boolean = false,
        onStep: (String) -> Unit = {},
    ): TranslationPipelineResult = wakeLockManager.withWakeLock("executePipeline") {
        withContext(Dispatchers.Default) {
            val config = if (reloadExternalSigmaConfig()) _config.value else initialConfig
            val startTime = System.currentTimeMillis()
            var detDuration = 0L
            var ocrDuration = 0L
            var groupDuration = 0L
            var transDuration = 0L
            var renderDuration = 0L
            var twoStageDetectedBubbles: List<Rect> = emptyList()

            val excluded = _userExcludedBoxes.value
            if (excluded.isNotEmpty()) {
                AppLogger.info("Executing Pipeline with ${excluded.size} User Excluded (Ignore) Zones active")
            }

            // ──────────────── Step 1: Detection ────────────────
            val isMethod5 = config.effectiveMethod in listOf(4, 5, 6, 7, 8, 9)
            var validRegions: List<Rect>
            var pass2PassedBoxes = emptyList<Rect>()
            var pass2RejectedBoxes = emptyList<Rect>()
            var pass2ProbedCrops = emptyList<com.raen.kisaratranslator.data.model.OcrCropDebug>()
            var method6ChunkBoxes = emptyList<Rect>()
            var method6ChunkCrops = emptyList<com.raen.kisaratranslator.data.model.OcrCropDebug>()
            var nonBubbledProbedCrops = emptyList<com.raen.kisaratranslator.data.model.OcrCropDebug>()
            if (fromStep <= 1) {
                onStep(if (isMethod5) "[1/7] Detecting speech bubbles & text..." else "[1/5] Detecting speech bubbles & text...")
                val methodLabel = if (config.effectiveMethod == 9) "Method 8 v3 (Pure Border Angle)" else if (config.effectiveMethod == 8) "Method 8 v2 (Laser Cut)" else if (config.effectiveMethod == 7) "Method 8 v1 (YOLO11n-seg)" else if (config.effectiveMethod == 6) "Method 7 (Geometric Crunch)" else if (config.effectiveMethod == 5) "Method 6 (Whole Bubble First)" else if (config.effectiveMethod == 4) "Method 5 (Lobe Clustering)" else "Method ${config.effectiveMethod}"
                AppLogger.step(if (isMethod5) "[Step 1/7] Running ${config.detector.name} ($methodLabel)..." else "[Step 1/5] Running ${config.detector.name} ($methodLabel)...")
                val detStart = System.currentTimeMillis()
                val detectedRegions = mutableListOf<Rect>()

                when (config.effectiveMethod) {
                    2, 4, 5, 6, 7, 8, 9 -> {
                        // Method 3, 5, 6, 7, 8: Two-Pass Focused Scan
                        if (config.detector == DetectorType.TWO_STAGE_YOLO_CTD && bubbleDetector.isReady) {
                            twoStageDetectedBubbles = bubbleDetector.detect(bitmap).map { it.rect }
                            if (excluded.isNotEmpty()) {
                                twoStageDetectedBubbles = twoStageDetectedBubbles.filterNot { touchesExcluded(it, excluded) }
                            }
                        }
                        if (comicTextDetector.isReady) {
                            val (passBoxes, ctdBubbles) = comicTextDetector.detectTwoPass(
                                bitmap = bitmap,
                                speedDet1 = config.speedDet1,
                                speedDet2 = config.speedDet2,
                            ) { stepMsg ->
                                onStep(stepMsg)
                            }
                            if (excluded.isNotEmpty()) {
                                comicTextDetector.filterExcludedBoxes { !touchesExcluded(it, excluded) }
                            }
                            val effectiveDetBubbles = (if (twoStageDetectedBubbles.isNotEmpty()) twoStageDetectedBubbles else ctdBubbles)
                                .let { if (excluded.isNotEmpty()) it.filterNot { b -> touchesExcluded(b, excluded) } else it }
                            if (isMethod5 && config.probeDet2Neighbors && mangaOcrEngine.isReady) {
                                val neighborCandidates = comicTextDetector.generateNeighborCandidateBoxes(
                                    comicTextDetector.lastPass1Boxes,
                                    effectiveDetBubbles,
                                    bitmap.width,
                                    bitmap.height
                                ).let { if (excluded.isNotEmpty()) it.filterNot { c -> touchesExcluded(c.rect, excluded) } else it }
                                val validatedPass2 = mutableListOf<Rect>()
                                val rejectedPass2 = mutableListOf<Rect>()
                                val probedCrops = mutableListOf<com.raen.kisaratranslator.data.model.OcrCropDebug>()

                                val probeConcurrency = when (config.speedProbeNeighbors.coerceIn(1, 5)) {
                                    1 -> 1
                                    2 -> 1
                                    3 -> 2
                                    4 -> 3
                                    else -> 4
                                }
                                val probeTokens = when (config.speedProbeNeighbors.coerceIn(1, 5)) {
                                    1 -> 4
                                    2 -> 6
                                    3 -> 12
                                    4 -> 14
                                    else -> 16
                                }
                                val probeSemaphore = kotlinx.coroutines.sync.Semaphore(probeConcurrency)

                                val probeResults = coroutineScope {
                                    neighborCandidates.mapIndexed { candIdx, cand ->
                                        async(Dispatchers.Default) {
                                            val probeRes = probeSemaphore.withPermit {
                                                probeBoxWithOcr(bitmap, cand.rect, maxTokens = probeTokens)
                                            }
                                            Triple(candIdx, cand, probeRes)
                                        }
                                    }.awaitAll()
                                }

                                for ((candIdx, cand, probeRes) in probeResults) {
                                    if (probeRes.isValid) {
                                        validatedPass2.add(cand.rect)
                                        if (probeRes.crop != null) {
                                            probedCrops.add(
                                                com.raen.kisaratranslator.data.model.OcrCropDebug(
                                                    index = candIdx,
                                                    rect = cand.rect,
                                                    cropBitmap = probeRes.crop,
                                                    rawText = "✓ Passed [${cand.direction}]: ${probeRes.text}",
                                                )
                                            )
                                        }
                                    } else {
                                        rejectedPass2.add(cand.rect)
                                        if (probeRes.crop != null) {
                                            probedCrops.add(
                                                com.raen.kisaratranslator.data.model.OcrCropDebug(
                                                    index = candIdx,
                                                    rect = cand.rect,
                                                    cropBitmap = probeRes.crop,
                                                    rawText = "✗ Rejected [${cand.direction}]",
                                                )
                                            )
                                        }
                                    }
                                }
                                pass2PassedBoxes = validatedPass2
                                pass2RejectedBoxes = rejectedPass2
                                pass2ProbedCrops = probedCrops
                                comicTextDetector.updatePass2Boxes(validatedPass2)
                                detectedRegions.addAll(comicTextDetector.lastPass1Boxes + validatedPass2)
                                AppLogger.info("Det 2 Probing: Verified ${validatedPass2.size}/${neighborCandidates.size} Japanese text neighbors (${rejectedPass2.size} rejected)")
                            } else {
                                pass2PassedBoxes = emptyList()
                                pass2RejectedBoxes = emptyList()
                                pass2ProbedCrops = emptyList()
                                comicTextDetector.updatePass2Boxes(emptyList())
                                detectedRegions.addAll(comicTextDetector.lastPass1Boxes.ifEmpty { passBoxes })
                            }
                            if (twoStageDetectedBubbles.isEmpty()) {
                                twoStageDetectedBubbles = ctdBubbles
                            }
                        } else if (mangaTextDetector2024.isReady) {
                            detectedRegions.addAll(mangaTextDetector2024.detect(bitmap))
                        } else if (paddleOcrDetector.isReady) {
                            detectedRegions.addAll(paddleOcrDetector.detect(bitmap))
                        }
                        AppLogger.info("Two-Pass Detection (Method ${config.effectiveMethod}): Found ${twoStageDetectedBubbles.size} bubbles and ${detectedRegions.size} candidate boxes")
                    }
                    1 -> {
                        // Method 2 Enhanced: Single Sensitive Pass
                        when (config.detector) {
                            DetectorType.TWO_STAGE_YOLO_CTD -> {
                                if (!bubbleDetector.isReady) throw IllegalStateException("YOLO Bubble Detector is not ready.")
                                if (!comicTextDetector.isReady) throw IllegalStateException("ComicTextDetector is not ready.")
                                twoStageDetectedBubbles = bubbleDetector.detect(bitmap).map { it.rect }
                                val textLines = comicTextDetector.detect(bitmap, maskThreshold = 0.30f, nmsIoUThreshold = 0.35f)
                                detectedRegions.addAll(textLines)
                            }
                            DetectorType.COMIC_TEXT_DETECTOR -> {
                                if (!comicTextDetector.isReady) throw IllegalStateException("ComicTextDetector is not ready.")
                                val boxes = comicTextDetector.detect(bitmap, maskThreshold = 0.30f, nmsIoUThreshold = 0.35f)
                                detectedRegions.addAll(boxes)
                                twoStageDetectedBubbles = comicTextDetector.lastDetectedBubbles
                            }
                            DetectorType.MANGA_DETECTOR_2024 -> {
                                if (!mangaTextDetector2024.isReady) throw IllegalStateException("Manga Text Detector 2024 is not ready.")
                                detectedRegions.addAll(mangaTextDetector2024.detect(bitmap))
                            }
                            DetectorType.PADDLE_OCR_DET -> {
                                if (!paddleOcrDetector.isReady) throw IllegalStateException("PaddleOCR Detector is not ready.")
                                detectedRegions.addAll(paddleOcrDetector.detect(bitmap))
                            }
                            DetectorType.MLKIT -> {
                                val mlKit = MlKitOcrRecognizer(config.sourceLang)
                                try {
                                    detectedRegions.addAll(mlKit.detectBlocks(bitmap).map { it.second })
                                } finally {
                                    mlKit.close()
                                }
                            }
                        }
                    }
                    else -> {
                        // Method 1 Standard: Baseline
                        when (config.detector) {
                            DetectorType.TWO_STAGE_YOLO_CTD -> {
                                if (!bubbleDetector.isReady) throw IllegalStateException("YOLO Bubble Detector is not ready.")
                                if (!comicTextDetector.isReady) throw IllegalStateException("ComicTextDetector is not ready.")
                                twoStageDetectedBubbles = bubbleDetector.detect(bitmap).map { it.rect }
                                val textLines = comicTextDetector.detect(bitmap)
                                detectedRegions.addAll(textLines)
                            }
                            DetectorType.COMIC_TEXT_DETECTOR -> {
                                if (!comicTextDetector.isReady) throw IllegalStateException("ComicTextDetector is not ready.")
                                val boxes = comicTextDetector.detect(bitmap)
                                detectedRegions.addAll(boxes)
                                twoStageDetectedBubbles = comicTextDetector.lastDetectedBubbles
                            }
                            DetectorType.MANGA_DETECTOR_2024 -> {
                                if (!mangaTextDetector2024.isReady) throw IllegalStateException("Manga Text Detector 2024 is not ready.")
                                detectedRegions.addAll(mangaTextDetector2024.detect(bitmap))
                            }
                            DetectorType.PADDLE_OCR_DET -> {
                                if (!paddleOcrDetector.isReady) throw IllegalStateException("PaddleOCR Detector is not ready.")
                                detectedRegions.addAll(paddleOcrDetector.detect(bitmap))
                            }
                            DetectorType.MLKIT -> {
                                val mlKit = MlKitOcrRecognizer(config.sourceLang)
                                try {
                                    detectedRegions.addAll(mlKit.detectBlocks(bitmap).map { it.second })
                                } finally {
                                    mlKit.close()
                                }
                            }
                        }
                    }
                }
                detDuration = System.currentTimeMillis() - detStart
                AppLogger.info("Stage 1 complete: Found ${detectedRegions.size} boxes in ${detDuration}ms (Method ${config.effectiveMethod})")

                if (excluded.isNotEmpty()) {
                    val beforeCount = detectedRegions.size
                    detectedRegions.removeAll { touchesExcluded(it, excluded) }
                    twoStageDetectedBubbles = twoStageDetectedBubbles.filterNot { touchesExcluded(it, excluded) }
                    pass2PassedBoxes = pass2PassedBoxes.filterNot { touchesExcluded(it, excluded) }
                    pass2RejectedBoxes = pass2RejectedBoxes.filterNot { touchesExcluded(it, excluded) }
                    pass2ProbedCrops = pass2ProbedCrops.filterNot { touchesExcluded(it.rect, excluded) }
                    comicTextDetector.filterExcludedBoxes { !touchesExcluded(it, excluded) }
                    AppLogger.info("User Excluded Zones: Filtered out ${beforeCount - detectedRegions.size} boxes intersecting ${excluded.size} ignore zones")
                }

                // SFX filter — Method 4, 5 & 6 preserve all detected regions so non-bubble text reaches Step 3.
                // Step 3 will verify orphan lines with MangaOCR AI.
                // Older methods (1-3) use the perimeter luminance filter.
                validRegions = if (config.effectiveMethod in listOf(3, 4, 5, 6, 7, 8, 9)) {
                    detectedRegions
                } else {
                    val sfxMinBright = if (config.effectiveMethod >= 1) 6 else 9
                    val filtered = detectedRegions.filter { ImageUtils.isLikelySpeechBubble(bitmap, it, sfxMinBright) }
                    if (filtered.isNotEmpty()) filtered else detectedRegions
                }
                if (excluded.isNotEmpty()) {
                    validRegions = validRegions.filterNot { touchesExcluded(it, excluded) }
                }
                if (validRegions.size < detectedRegions.size) {
                    AppLogger.info("SFX Filter: Excluded ${detectedRegions.size - validRegions.size} non-bubble / clothing regions")
                }
                saveStep1Cache(validRegions, twoStageDetectedBubbles)

                if (singleStepOnly && fromStep == 1) {
                    val overlay = ImageUtils.renderBoxesOverlay(bitmap, validRegions)
                    val diag = buildDiagnostics(
                        detDuration = detDuration,
                        totalDuration = detDuration,
                        detectedBoxesCount = validRegions.size,
                    )
                    val p1 = comicTextDetector.lastPass1Boxes.ifEmpty { validRegions }
                    val p2 = comicTextDetector.lastPass2Boxes
                    return@withContext TranslationPipelineResult(
                        originalBitmap = bitmap,
                        translatedBitmap = overlay,
                        pageTranslation = PageTranslation(imgWidth = bitmap.width.toFloat(), imgHeight = bitmap.height.toFloat()),
                        diagnostics = diag,
                        detectedBoxes = validRegions,
                        pass1Boxes = p1,
                        pass2Boxes = p2,
                        pass2BoxesPassed = pass2PassedBoxes,
                        pass2BoxesRejected = pass2RejectedBoxes,
                        pass2Crops = pass2ProbedCrops,
                        bubbleBoxes = twoStageDetectedBubbles,
                        pass2Bitmap = comicTextDetector.lastPass2Bitmap,
                    )
                }
            } else {
                twoStageDetectedBubbles = (loadStep1BubblesCache() ?: emptyList()).let { if (excluded.isNotEmpty()) it.filterNot { b -> touchesExcluded(b, excluded) } else it }
                validRegions = (loadStep1Cache() ?: run {
                    throw IllegalStateException("Step 1 detection cache not found. Please run Step 1 Detection first.")
                }).let { if (excluded.isNotEmpty()) it.filterNot { r -> touchesExcluded(r, excluded) } else it }
            }

            val isRtl = config.sourceLang.lowercase() in listOf("ja", "zh", "ko")
            val effectiveBubbles = (if (twoStageDetectedBubbles.isNotEmpty()) {
                twoStageDetectedBubbles
            } else if (comicTextDetector.lastDetectedBubbles.isNotEmpty()) {
                comicTextDetector.lastDetectedBubbles
            } else {
                emptyList()
            }).let { if (excluded.isNotEmpty()) it.filterNot { b -> touchesExcluded(b, excluded) } else it }

            var pass1Boxes: List<Rect> = comicTextDetector.lastPass1Boxes.let { if (excluded.isNotEmpty()) it.filterNot { b -> touchesExcluded(b, excluded) } else it }
            var pass2Boxes: List<Rect> = comicTextDetector.lastPass2Boxes.let { if (excluded.isNotEmpty()) it.filterNot { b -> touchesExcluded(b, excluded) } else it }
            if (pass1Boxes.isEmpty()) pass1Boxes = validRegions

            var extractedLineBoxes = emptyList<Rect>()
            var groupedLobeBoxes = emptyList<Rect>()
            var crunchSplitLobeBoxes = emptyList<Rect>()
            var crunchOriginalBubbleBoxes = emptyList<Rect>()
            var crunchPointsAList = emptyList<Point>()
            var crunchPointsBList = emptyList<Point>()
            var crunchCutLinesList = emptyList<List<Point>>()
            var crunchSplitsList = emptyList<com.raen.kisaratranslator.engine.grouping.CrunchSplitter.SplitResult>()
            var method5Lobes = emptyList<com.raen.kisaratranslator.engine.grouping.Method5LobeGrouper.DialogueLobe>()
            var method6Units = emptyList<com.raen.kisaratranslator.engine.grouping.Method6BubbleGrouper.DialogueBubbleUnit>()
            var method7Units = emptyList<com.raen.kisaratranslator.engine.grouping.Method7CrunchGrouper.DialogueUnit>()
            var method8Units = emptyList<com.raen.kisaratranslator.engine.grouping.Method8SegmentGrouper.DialogueUnit>()

            // Method 1: pre-merge raw boxes into line regions before OCR (geometry-based column grouping)
            // Method 2: skip pre-merge — feed raw CTD boxes directly (cleaner crops for MangaOCR)
            // Method 3: vertical line reconstruction with gap bridging (bridges missing mid-glyphs into full line strips)
            // Method 4: smart-grouping first into full-bubble and coherent dialogue units for direct multi-line OCR
            // Method 5: intra-bubble column clustering (lobe separation) with dynamic sweet-spot OCR fallback
            // Method 6: whole bubble detection first -> single box -> internal line chunking
            // Method 7: geometric crunch splitter — pixel mask IoA association, no new model
            // Method 8 v1: YOLO11n-seg pretrained bubble segmentation — distance transform watershed
            // Method 8 v3: Pure Border Angle (Zero Fallback)
            // Method 8 v2: YOLO11n-seg + High-Res Contour Crunch Detection & Obstacle-Aware Laser Seam Cut
            val lineRegions = when (config.effectiveMethod) {
                9 -> {
                    onStep("[2/7] Extracting vertical line strips (Method 8 v3)...")
                    AppLogger.step("[Step 2/7] Method 8 v3: Extracting vertical line strips...")
                    val lineDetails = method5LobeGrouper.extractVerticalLinesDetailed(validRegions, effectiveBubbles, bitmap.width, bitmap.height, isRtl)
                    val rawLines = lineDetails.allLines
                    onStep("[3/7] Method 8 v3: Pure Border Angle (Zero Fallback)...")
                    AppLogger.step("[Step 3/7] Method 8 v3: YOLO11n-seg + Pure Border Angle (Zero Fallback)...")

                    val (validatedLines, nonBubbledResult) = if (config.probeStep3Orphans) {
                        verifyNonBubbledLines(bitmap, lineDetails.bubbleLines, lineDetails.orphanLines, speedLevel = config.speedProbeOrphans, onStep = onStep)
                    } else {
                        Pair(lineDetails.allLines, emptyList())
                    }
                    nonBubbledProbedCrops = nonBubbledResult

                    extractedLineBoxes = validatedLines
                    method8Units = method8SegmentGrouper.groupDialogueUnits(
                        textLines      = validatedLines,
                        segEngine      = bubbleSegmentationEngine,
                        rawBubbles     = effectiveBubbles,
                        bitmap         = bitmap,
                        bitmapWidth    = bitmap.width,
                        bitmapHeight   = bitmap.height,
                        isRtl          = isRtl,
                        useLaserCut    = true,
                        crunchMode     = Method8SegmentGrouper.CrunchMode.PURE_BORDER_ANGLE,
                    )
                    groupedLobeBoxes = method8Units.map { it.bounds }
                    crunchSplitsList = method8SegmentGrouper.lastCrunchSplits
                    crunchSplitLobeBoxes = method8SegmentGrouper.lastCrunchSplits.flatMap { it.splitLobeRects }
                    crunchOriginalBubbleBoxes = method8SegmentGrouper.lastCrunchSplits.mapNotNull { it.originalRect }
                    crunchPointsAList = method8SegmentGrouper.lastCrunchSplits.mapNotNull { it.crunchPointA }
                    crunchPointsBList = method8SegmentGrouper.lastCrunchSplits.mapNotNull { it.crunchPointB }
                    crunchCutLinesList = method8SegmentGrouper.lastCrunchSplits.map { it.cutLinePoints }.filter { it.isNotEmpty() }
                    AppLogger.info("Method 8 v3 (Pure Border Angle): Extracted ${rawLines.size} lines, grouped into ${method8Units.size} dialogue units (${crunchSplitLobeBoxes.size} split lobes, ${crunchPointsAList.size} crunch points A-B)")
                    validatedLines
                }
                8 -> {
                    onStep("[2/7] Extracting vertical line strips (Method 8 v2)...")
                    AppLogger.step("[Step 2/7] Method 8 v2: Extracting vertical line strips...")
                    val lineDetails = method5LobeGrouper.extractVerticalLinesDetailed(validRegions, effectiveBubbles, bitmap.width, bitmap.height, isRtl)
                    val rawLines = lineDetails.allLines
                    onStep("[3/7] Method 8 v2: Laser Crunch Cut + IoA Association...")
                    AppLogger.step("[Step 3/7] Method 8 v2: YOLO11n-seg + Laser Contour Cut + Obstacle Avoidance...")

                    val (validatedLines, nonBubbledResult) = if (config.probeStep3Orphans) {
                        verifyNonBubbledLines(bitmap, lineDetails.bubbleLines, lineDetails.orphanLines, speedLevel = config.speedProbeOrphans, onStep = onStep)
                    } else {
                        Pair(lineDetails.allLines, emptyList())
                    }
                    nonBubbledProbedCrops = nonBubbledResult

                    extractedLineBoxes = validatedLines
                    method8Units = method8SegmentGrouper.groupDialogueUnits(
                        textLines      = validatedLines,
                        segEngine      = bubbleSegmentationEngine,
                        rawBubbles     = effectiveBubbles,
                        bitmap         = bitmap,
                        bitmapWidth    = bitmap.width,
                        bitmapHeight   = bitmap.height,
                        isRtl          = isRtl,
                        useLaserCut    = true,
                    )
                    groupedLobeBoxes = method8Units.map { it.bounds }
                    crunchSplitsList = method8SegmentGrouper.lastCrunchSplits
                    crunchSplitLobeBoxes = method8SegmentGrouper.lastCrunchSplits.flatMap { it.splitLobeRects }
                    crunchOriginalBubbleBoxes = method8SegmentGrouper.lastCrunchSplits.mapNotNull { it.originalRect }
                    crunchPointsAList = method8SegmentGrouper.lastCrunchSplits.mapNotNull { it.crunchPointA }
                    crunchPointsBList = method8SegmentGrouper.lastCrunchSplits.mapNotNull { it.crunchPointB }
                    crunchCutLinesList = method8SegmentGrouper.lastCrunchSplits.map { it.cutLinePoints }.filter { it.isNotEmpty() }
                    AppLogger.info("Method 8 v2 (Laser): Extracted ${rawLines.size} lines, grouped into ${method8Units.size} dialogue units (${crunchSplitLobeBoxes.size} split lobes, ${crunchPointsAList.size} crunch points A-B)")
                    validatedLines
                }
                7 -> {
                    onStep("[2/7] Extracting vertical line strips (Method 8 v1)...")
                    AppLogger.step("[Step 2/7] Method 8 v1: Extracting vertical line strips...")
                    val lineDetails = method5LobeGrouper.extractVerticalLinesDetailed(validRegions, effectiveBubbles, bitmap.width, bitmap.height, isRtl)
                    val rawLines = lineDetails.allLines
                    onStep("[3/7] Method 8 v1: YOLO-seg Bubble Grouping...")
                    AppLogger.step("[Step 3/7] Method 8 v1: YOLO-seg Bubble Mask + IoA Association...")

                    val (validatedLines, nonBubbledResult) = if (config.probeStep3Orphans) {
                        verifyNonBubbledLines(bitmap, lineDetails.bubbleLines, lineDetails.orphanLines, speedLevel = config.speedProbeOrphans, onStep = onStep)
                    } else {
                        Pair(lineDetails.allLines, emptyList())
                    }
                    nonBubbledProbedCrops = nonBubbledResult

                    extractedLineBoxes = validatedLines
                    method8Units = method8SegmentGrouper.groupDialogueUnits(
                        textLines      = validatedLines,
                        segEngine      = bubbleSegmentationEngine,
                        rawBubbles     = effectiveBubbles,
                        bitmap         = bitmap,
                        bitmapWidth    = bitmap.width,
                        bitmapHeight   = bitmap.height,
                        isRtl          = isRtl,
                        useLaserCut    = false,
                    )
                    groupedLobeBoxes = method8Units.map { it.bounds }
                    crunchSplitsList = method8SegmentGrouper.lastCrunchSplits
                    crunchSplitLobeBoxes = method8SegmentGrouper.lastCrunchSplits.flatMap { it.splitLobeRects }
                    crunchOriginalBubbleBoxes = method8SegmentGrouper.lastCrunchSplits.mapNotNull { it.originalRect }
                    crunchPointsAList = method8SegmentGrouper.lastCrunchSplits.mapNotNull { it.crunchPointA }
                    crunchPointsBList = method8SegmentGrouper.lastCrunchSplits.mapNotNull { it.crunchPointB }
                    crunchCutLinesList = method8SegmentGrouper.lastCrunchSplits.map { it.cutLinePoints }.filter { it.isNotEmpty() }
                    AppLogger.info("Method 8 v1: Extracted ${rawLines.size} lines, grouped into ${method8Units.size} dialogue units (${crunchSplitLobeBoxes.size} split lobes)")
                    validatedLines
                }
                6 -> {
                    onStep("[2/7] Extracting vertical line strips (Method 7)...")
                    AppLogger.step("[Step 2/7] Method 7: Extracting vertical line strips...")
                    val lineDetails = method5LobeGrouper.extractVerticalLinesDetailed(validRegions, effectiveBubbles, bitmap.width, bitmap.height, isRtl)
                    val rawLines = lineDetails.allLines
                    onStep("[3/7] Method 7: Geometric Crunch Grouping...")
                    AppLogger.step("[Step 3/7] Method 7: Geometric Crunch Split + IoA Association...")

                    val (validatedLines, nonBubbledResult) = if (config.probeStep3Orphans) {
                        verifyNonBubbledLines(bitmap, lineDetails.bubbleLines, lineDetails.orphanLines, speedLevel = config.speedProbeOrphans, onStep = onStep)
                    } else {
                        Pair(lineDetails.allLines, emptyList())
                    }
                    nonBubbledProbedCrops = nonBubbledResult

                    extractedLineBoxes = validatedLines
                    method7Units = method7CrunchGrouper.groupDialogueUnits(
                        textLines      = validatedLines,
                        rawBubbles     = effectiveBubbles,
                        bitmap         = bitmap,
                        bitmapWidth    = bitmap.width,
                        bitmapHeight   = bitmap.height,
                        isRtl          = isRtl,
                    )
                    groupedLobeBoxes = method7Units.map { it.bounds }
                    crunchSplitsList = method7CrunchGrouper.lastCrunchSplits
                    crunchSplitLobeBoxes = method7CrunchGrouper.lastCrunchSplits.flatMap { it.splitLobeRects }
                    crunchOriginalBubbleBoxes = method7CrunchGrouper.lastCrunchSplits.mapNotNull { it.originalRect }
                    crunchPointsAList = method7CrunchGrouper.lastCrunchSplits.mapNotNull { it.crunchPointA }
                    crunchPointsBList = method7CrunchGrouper.lastCrunchSplits.mapNotNull { it.crunchPointB }
                    crunchCutLinesList = method7CrunchGrouper.lastCrunchSplits.map { it.cutLinePoints }.filter { it.isNotEmpty() }
                    AppLogger.info("Method 7: Extracted ${rawLines.size} lines (validated: ${validatedLines.size}), crunch-grouped into ${method7Units.size} dialogue units (${method7Units.count { it.isBubble }} bubble, ${method7Units.count { !it.isBubble }} orphan, ${crunchSplitLobeBoxes.size} split lobes)")
                    validatedLines
                }
                5 -> {
                    onStep("[2/7] Extracting vertical line strips...")
                    AppLogger.step("[Step 2/7] Extracting vertical line strips...")
                    val lineDetails = method5LobeGrouper.extractVerticalLinesDetailed(validRegions, effectiveBubbles, bitmap.width, bitmap.height, isRtl)
                    val rawLines = lineDetails.allLines
                    onStep("[3/7] Method 6 Whole Bubble First Grouping...")
                    AppLogger.step("[Step 3/7] Method 6: Whole Bubble First Grouping...")

                    val (validatedLines, nonBubbledResult) = if (config.probeStep3Orphans) {
                        verifyNonBubbledLines(bitmap, lineDetails.bubbleLines, lineDetails.orphanLines, speedLevel = config.speedProbeOrphans, onStep = onStep)
                    } else {
                        Pair(lineDetails.allLines, emptyList())
                    }
                    nonBubbledProbedCrops = nonBubbledResult

                    extractedLineBoxes = validatedLines
                    method6Units = method6BubbleGrouper.groupDialogueUnits(validatedLines, effectiveBubbles, bitmap.width, bitmap.height, isRtl, bitmap)
                    groupedLobeBoxes = method6Units.map { it.bounds }
                    AppLogger.info("Method 6: Extracted ${rawLines.size} lines (validated: ${validatedLines.size}), grouped into ${method6Units.size} dialogue bubble units")
                    validatedLines
                }
                4 -> {
                    onStep("[2/7] Extracting vertical line strips...")
                    AppLogger.step("[Step 2/7] Extracting vertical line strips...")
                    val lineDetails = method5LobeGrouper.extractVerticalLinesDetailed(validRegions, effectiveBubbles, bitmap.width, bitmap.height, isRtl)
                    val rawLines = lineDetails.allLines
                    onStep("[3/7] Grouping into dialogue lobes...")
                    AppLogger.step("[Step 3/7] Grouping into dialogue lobes...")

                    // Step 3 Orphan Line Verification:
                    // Filter lines outside bubbles with MangaOCR INT8 check
                    // Bubble lines are 100% immune and preserved; only orphan lines are probed.
                    val (validatedLines, nonBubbledResult) = if (config.probeStep3Orphans) {
                        verifyNonBubbledLines(bitmap, lineDetails.bubbleLines, lineDetails.orphanLines, speedLevel = config.speedProbeOrphans, onStep = onStep)
                    } else {
                        Pair(lineDetails.allLines, emptyList())
                    }
                    nonBubbledProbedCrops = nonBubbledResult

                    extractedLineBoxes = validatedLines
                    method5Lobes = method5LobeGrouper.groupIntoLobes(validatedLines, effectiveBubbles, bitmap.width, bitmap.height, isRtl, bitmap, config.chunkLinesCount)
                    groupedLobeBoxes = method5Lobes.map { it.bounds }
                    AppLogger.info("Method 5: Extracted ${rawLines.size} lines (validated: ${validatedLines.size}), grouped into ${method5Lobes.size} dialogue lobes (${method5Lobes.count { it.requiresLineFallback }} fallback)")
                    validatedLines
                }
                3 -> {
                    val dialogueUnits = smartBubbleGrouper.groupDialogueUnits(validRegions, effectiveBubbles, bitmap, isRtl)
                    AppLogger.info("Method 4: Smart-grouped into ${dialogueUnits.size} full-bubble dialogue units for direct OCR")
                    dialogueUnits
                }
                2 -> {
                    val bridged = groupRawBoxesIntoLinesWithBridging(validRegions, effectiveBubbles, isRtl)
                    AppLogger.info("Method 3: Bridged ${validRegions.size} raw boxes into ${bridged.size} contiguous line strips for OCR")
                    bridged
                }
                1 -> {
                    AppLogger.info("Method 2: Skipping pre-merge — OCR on ${validRegions.size} raw CTD boxes directly")
                    validRegions
                }
                else -> {
                    groupRawBoxesIntoLines(validRegions, isRtl)
                }
            }

            // ──────────────── Step 2: OCR ────────────────
            var rawBlocks: List<TranslationBlock>
            var ocrCrops = mutableListOf<com.raen.kisaratranslator.data.model.OcrCropDebug>()
            if (fromStep <= 2) {
                mangaOcrEngine.setSpeedLevel(config.speedOcr)
                val ocrStepPrefix = if (isMethod5) "[4/7]" else "[2/5]"
                val stepCount = if (config.effectiveMethod in 7..9) "${method8Units.size} units / ${lineRegions.size} lines"
                                else if (config.effectiveMethod == 6) "${method7Units.size} units / ${lineRegions.size} lines"
                                else if (config.effectiveMethod == 5) "${method6Units.size} bubbles / ${lineRegions.size} lines"
                                else if (config.effectiveMethod == 4) "${method5Lobes.size} lobes / ${lineRegions.size} lines"
                                else "${lineRegions.size} lines"
                onStep("$ocrStepPrefix Recognizing text ($stepCount)...")
                AppLogger.step("[$ocrStepPrefix] OCR recognizing $stepCount with ${config.ocr.name}...")
                val ocrStart = System.currentTimeMillis()
                val (blocks, crops) = when (config.effectiveMethod) {
                    9, 8, 7 -> {
                        executeOcrMethod8(bitmap, method8Units, config) { done, total, currentText ->
                            val preview = if (currentText.length > 12) currentText.take(12) + "..." else currentText
                            val msg = "[4/7] OCR ($done/$total units): $preview"
                            onStep(msg)
                            AppLogger.step(msg)
                        }
                    }
                    6 -> {
                        executeOcrMethod7(bitmap, method7Units, config) { done, total, currentText ->
                            val preview = if (currentText.length > 12) currentText.take(12) + "..." else currentText
                            val msg = "[4/7] OCR ($done/$total units): $preview"
                            onStep(msg)
                            AppLogger.step(msg)
                        }
                    }
                    5 -> {
                        executeOcrMethod6(bitmap, method6Units, config) { done, total, currentText ->
                            val preview = if (currentText.length > 12) currentText.take(12) + "..." else currentText
                            val msg = "[4/7] OCR ($done/$total bubbles): $preview"
                            onStep(msg)
                            AppLogger.step(msg)
                        }
                    }
                    4 -> {
                        executeOcrMethod5(bitmap, method5Lobes, config) { done, total, currentText ->
                            val preview = if (currentText.length > 12) currentText.take(12) + "..." else currentText
                            val msg = "[4/7] OCR ($done/$total lobes): $preview"
                            onStep(msg)
                            AppLogger.step(msg)
                        }
                    }
                    else -> {
                        executeOcr(bitmap, lineRegions, config)
                    }
                }
                rawBlocks = blocks
                ocrCrops = crops.toMutableList()
                if (config.effectiveMethod == 5) {
                    method6ChunkBoxes = crops.map { it.rect }
                    method6ChunkCrops = crops
                }
                ocrDuration = System.currentTimeMillis() - ocrStart
                AppLogger.info("Stage 2 complete: Recognized ${rawBlocks.size} text blocks in ${ocrDuration}ms")
                saveStep2Cache(rawBlocks)

                if (singleStepOnly && fromStep == 2) {
                    val dummyPage = PageTranslation(blocks = rawBlocks.toMutableList(), imgWidth = bitmap.width.toFloat(), imgHeight = bitmap.height.toFloat())
                    val diag = buildDiagnostics(
                        detDuration = detDuration,
                        ocrDuration = ocrDuration,
                        totalDuration = detDuration + ocrDuration,
                        detectedBoxesCount = validRegions.size,
                        finalBlocksCount = rawBlocks.size,
                    )
                    return@withContext TranslationPipelineResult(
                        originalBitmap = bitmap,
                        translatedBitmap = bitmap,
                        pageTranslation = dummyPage,
                        diagnostics = diag,
                        detectedBoxes = validRegions,
                        pass1Boxes = pass1Boxes,
                        pass2Boxes = pass2Boxes,
                        pass2BoxesPassed = pass2PassedBoxes,
                        pass2BoxesRejected = pass2RejectedBoxes,
                        pass2Crops = pass2ProbedCrops,
                        lineBoxes = lineRegions,
                        groupedBoxes = groupedLobeBoxes,
                        bubbleBoxes = effectiveBubbles,
                        m6ChunkBoxes = method6ChunkBoxes,
                        m6ChunkCrops = method6ChunkCrops,
                        nonBubbledCrops = nonBubbledProbedCrops,
                        ocrCrops = ocrCrops,
                        crunchSplitBoxes = crunchSplitLobeBoxes,
                        crunchOriginalBoxes = crunchOriginalBubbleBoxes,
                        crunchPointsA = crunchPointsAList,
                        crunchPointsB = crunchPointsBList,
                        crunchCutLines = crunchCutLinesList,
                        crunchSplits = crunchSplitsList,
                        pass2Bitmap = comicTextDetector.lastPass2Bitmap,
                    )
                }
            } else {
                val cachedRaw = loadStep2Cache()
                rawBlocks = if (cachedRaw != null) {
                    if (excluded.isNotEmpty()) {
                        cachedRaw.filterNot { touchesExcluded(Rect(it.x.toInt(), it.y.toInt(), (it.x + it.width).toInt(), (it.y + it.height).toInt()), excluded) }
                    } else cachedRaw
                } else run {
                    AppLogger.warn("Step 2 cache not found, running OCR")
                    val (b, c) = when (config.effectiveMethod) {
                        9, 8, 7 -> {
                            executeOcrMethod8(bitmap, method8Units, config) { done, total, currentText ->
                                val preview = if (currentText.length > 12) currentText.take(12) + "..." else currentText
                                val msg = "[4/7] OCR ($done/$total units): $preview"
                                onStep(msg)
                                AppLogger.step(msg)
                            }
                        }
                        6 -> {
                            executeOcrMethod7(bitmap, method7Units, config) { done, total, currentText ->
                                val preview = if (currentText.length > 12) currentText.take(12) + "..." else currentText
                                val msg = "[4/7] OCR ($done/$total units): $preview"
                                onStep(msg)
                                AppLogger.step(msg)
                            }
                        }
                        5 -> {
                            executeOcrMethod6(bitmap, method6Units, config) { done, total, currentText ->
                                val preview = if (currentText.length > 12) currentText.take(12) + "..." else currentText
                                val msg = "[4/7] OCR ($done/$total bubbles): $preview"
                                onStep(msg)
                                AppLogger.step(msg)
                            }
                        }
                        4 -> {
                            executeOcrMethod5(bitmap, method5Lobes, config) { done, total, currentText ->
                                val preview = if (currentText.length > 12) currentText.take(12) + "..." else currentText
                                val msg = "[4/7] OCR ($done/$total lobes): $preview"
                                onStep(msg)
                                AppLogger.step(msg)
                            }
                        }
                        else -> {
                            executeOcr(bitmap, lineRegions, config)
                        }
                    }
                    ocrCrops = c.toMutableList()
                    if (config.effectiveMethod == 5) {
                        method6ChunkBoxes = c.map { it.rect }
                        method6ChunkCrops = c
                    }
                    saveStep2Cache(b)
                    b
                }
            }

            // ──────────────── Step 3: Grouping ────────────────
            var finalBlocks: List<TranslationBlock>
            if (fromStep <= 3) {
                if (!isMethod5) {
                    onStep("[3/5] Grouping speech bubbles & reading order...")
                    AppLogger.step("[Step 3/5] Consolidating blocks (Mode: ${config.assignmentMode}, RTL: ${config.sourceLang})...")
                }
                val groupStart = System.currentTimeMillis()

                finalBlocks = if (config.effectiveMethod in listOf(3, 4, 5, 6, 7, 8, 9)) {
                    // Method 4, 5, 6, 7, 8 v1, 8 v2, 8 v3: Dialogue units were already smart-grouped prior to OCR!
                    rawBlocks
                } else if (config.bubbleGroupingEnabled && rawBlocks.size > 1) {
                    bubbleGroupingCoordinator.groupBlocks(
                        blocks = rawBlocks,
                        bubbleRegions = effectiveBubbles,
                        isRtl = isRtl,
                        assignmentMode = config.assignmentMode,
                        sortingOrder = config.lineSortingOrder,
                        hardBubbleFence = config.effectiveMethod >= 1,
                    )
                } else {
                    rawBlocks
                }
                groupDuration = System.currentTimeMillis() - groupStart
                AppLogger.info("Stage 3 complete: Grouped into ${finalBlocks.size} bubbles in ${groupDuration}ms")
                saveStep3Cache(finalBlocks)

                if (singleStepOnly && fromStep == 3) {
                    val dummyPage = PageTranslation(blocks = finalBlocks.toMutableList(), imgWidth = bitmap.width.toFloat(), imgHeight = bitmap.height.toFloat())
                    val diag = buildDiagnostics(
                        groupDuration = groupDuration,
                        totalDuration = groupDuration,
                        detectedBoxesCount = validRegions.size,
                        finalBlocksCount = finalBlocks.size,
                    )
                    return@withContext TranslationPipelineResult(
                        originalBitmap = bitmap,
                        translatedBitmap = bitmap,
                        pageTranslation = dummyPage,
                        diagnostics = diag,
                        detectedBoxes = validRegions,
                        pass1Boxes = pass1Boxes,
                        pass2Boxes = pass2Boxes,
                        pass2BoxesPassed = pass2PassedBoxes,
                        pass2BoxesRejected = pass2RejectedBoxes,
                        pass2Crops = pass2ProbedCrops,
                        lineBoxes = lineRegions,
                        groupedBoxes = groupedLobeBoxes,
                        bubbleBoxes = effectiveBubbles,
                        m6ChunkBoxes = method6ChunkBoxes,
                        m6ChunkCrops = method6ChunkCrops,
                        nonBubbledCrops = nonBubbledProbedCrops,
                        ocrCrops = ocrCrops,
                        crunchSplitBoxes = crunchSplitLobeBoxes,
                        crunchOriginalBoxes = crunchOriginalBubbleBoxes,
                        crunchPointsA = crunchPointsAList,
                        crunchPointsB = crunchPointsBList,
                        crunchCutLines = crunchCutLinesList,
                        crunchSplits = crunchSplitsList,
                        pass2Bitmap = comicTextDetector.lastPass2Bitmap,
                    )
                }
            } else {
                val cachedFinal = loadStep3Cache()
                finalBlocks = if (cachedFinal != null) {
                    if (excluded.isNotEmpty()) {
                        cachedFinal.filterNot { touchesExcluded(Rect(it.x.toInt(), it.y.toInt(), (it.x + it.width).toInt(), (it.y + it.height).toInt()), excluded) }
                    } else cachedFinal
                } else run {
                    AppLogger.warn("Step 3 cache not found, using rawBlocks")
                    saveStep3Cache(rawBlocks)
                    rawBlocks
                }
            }

            // ──────────────── Step 4: Translation ────────────────
            var pageTranslation: PageTranslation
            if (fromStep <= 4) {
                pageTranslation = PageTranslation(
                    blocks = finalBlocks.map { it.copy() }.toMutableList(),
                    imgWidth = bitmap.width.toFloat(),
                    imgHeight = bitmap.height.toFloat(),
                )
                val transPrefix = if (isMethod5) "[5/7]" else "[4/5]"
                onStep("$transPrefix Translating (${finalBlocks.size} blocks)...")
                AppLogger.step("[$transPrefix] Translating ${finalBlocks.size} blocks via ${config.translator.name} (${config.sourceLang} -> ${config.targetLang})...")
                val transStart = System.currentTimeMillis()
                val translator: TextTranslator = when (config.translator) {
                    TranslatorType.SUGOI_ONNX -> SugoiTranslator(
                        modelManager = modelManager,
                        fromLang = config.sourceLang,
                        toLang = config.targetLang,
                        beamWidth = config.sugoiBeamWidth,
                        speedLevel = config.speedTranslate,
                    )
                    TranslatorType.GEMINI_FLASH -> com.raen.kisaratranslator.engine.translator.GeminiTranslator(
                        apiKey = config.geminiApiKey,
                        model = config.geminiModel,
                        fromLang = config.sourceLang,
                        toLang = config.targetLang,
                    )
                    TranslatorType.GROQ_LLAMA -> com.raen.kisaratranslator.engine.translator.GroqTranslator(
                        apiKey = config.groqApiKey,
                        model = config.groqModel,
                        fromLang = config.sourceLang,
                        toLang = config.targetLang,
                    )
                    TranslatorType.MICROSOFT_AZURE -> com.raen.kisaratranslator.engine.translator.MicrosoftAzureTranslator(
                        apiKey = config.azureApiKey,
                        region = config.azureRegion,
                        fromLang = config.sourceLang,
                        toLang = config.targetLang,
                    )
                    TranslatorType.DEEPL -> com.raen.kisaratranslator.engine.translator.DeepLTranslator(
                        apiKey = config.deeplApiKey,
                        fromLang = config.sourceLang,
                        toLang = config.targetLang,
                    )
                    TranslatorType.OPUS_MT -> OpusMtTranslator(modelManager, config.sourceLang, config.targetLang)
                    TranslatorType.GOOGLE_TRANSLATE -> GoogleTranslator(config.sourceLang, config.targetLang)
                    TranslatorType.MLKIT_TRANSLATE -> MLKitTranslator(config.sourceLang, config.targetLang)
                    TranslatorType.QWEN_0_5B -> {
                        mangaOcrEngine.close()
                        com.raen.kisaratranslator.engine.translator.QwenTranslator(
                            modelManager = modelManager,
                            endpoint = config.qwenEndpoint,
                            apiKey = config.qwenApiKey,
                            fromLang = config.sourceLang,
                            toLang = config.targetLang,
                        )
                    }
                }
                try {
                    translator.translate(pageTranslation) { done, total ->
                        val msg = "$transPrefix Translating ($done/$total)..."
                        onStep(msg)
                        AppLogger.step(msg)
                    }
                } finally {
                    translator.close()
                }
                transDuration = System.currentTimeMillis() - transStart
                AppLogger.info("Stage 4 complete: Translation done in ${transDuration}ms")
                saveStep4Cache(pageTranslation)

                if (singleStepOnly && fromStep == 4) {
                    val diag = buildDiagnostics(
                        transDuration = transDuration,
                        totalDuration = transDuration,
                        detectedBoxesCount = validRegions.size,
                        finalBlocksCount = finalBlocks.size,
                    )
                    return@withContext TranslationPipelineResult(
                        originalBitmap = bitmap,
                        translatedBitmap = bitmap,
                        pageTranslation = pageTranslation,
                        diagnostics = diag,
                        detectedBoxes = validRegions,
                        pass1Boxes = pass1Boxes,
                        pass2Boxes = pass2Boxes,
                        pass2BoxesPassed = pass2PassedBoxes,
                        pass2BoxesRejected = pass2RejectedBoxes,
                        pass2Crops = pass2ProbedCrops,
                        lineBoxes = lineRegions,
                        groupedBoxes = groupedLobeBoxes,
                        bubbleBoxes = effectiveBubbles,
                        m6ChunkBoxes = method6ChunkBoxes,
                        m6ChunkCrops = method6ChunkCrops,
                        nonBubbledCrops = nonBubbledProbedCrops,
                        ocrCrops = ocrCrops,
                        crunchSplitBoxes = crunchSplitLobeBoxes,
                        crunchOriginalBoxes = crunchOriginalBubbleBoxes,
                        crunchPointsA = crunchPointsAList,
                        crunchPointsB = crunchPointsBList,
                        crunchCutLines = crunchCutLinesList,
                        crunchSplits = crunchSplitsList,
                        pass2Bitmap = comicTextDetector.lastPass2Bitmap,
                    )
                }
            } else {
                pageTranslation = loadStep4Cache() ?: run {
                    AppLogger.warn("Step 4 cache not found, using raw text")
                    PageTranslation(blocks = finalBlocks.map { it.copy() }.toMutableList(), imgWidth = bitmap.width.toFloat(), imgHeight = bitmap.height.toFloat())
                }
                if (excluded.isNotEmpty()) {
                    pageTranslation.blocks.removeAll { touchesExcluded(Rect(it.x.toInt(), it.y.toInt(), (it.x + it.width).toInt(), (it.y + it.height).toInt()), excluded) }
                }
            }

            // ──────────────── Step 5: Rendering ────────────────
            val inpaintPrefix = if (isMethod5) "[6/7]" else "[5/5]"
            onStep(if (isMethod5) "[6/7] Inpainting bubble backgrounds..." else "[5/5] Inpainting & rendering typography...")
            AppLogger.step("[$inpaintPrefix] Inpainting bubble backgrounds & rendering typography...")
            val renderStart = System.currentTimeMillis()
            // Method 1: erase all validRegions (Step 1 raw boxes)
            // Method 2 & 3: erase only Step 1 boxes whose centroid falls inside a Step 3 grouped block
            val inpaintedBitmap = if (config.effectiveMethod >= 1) {
                ImageUtils.renderInpaintedPageV2(
                    original = bitmap,
                    rawBoxes = validRegions,
                    groupedBlocks = finalBlocks,
                    fillBubbleBackground = config.fillBubbleBackground,
                )
            } else {
                ImageUtils.renderInpaintedPage(
                    original = bitmap,
                    rawBoxes = validRegions,
                    fillBubbleBackground = config.fillBubbleBackground,
                )
            }
            if (isMethod5) {
                onStep("[7/7] Rendering translated typography...")
                AppLogger.step("[Step 7/7] Rendering translated typography...")
            }
            val renderedBitmap = ImageUtils.renderTranslatedPage(
                original = bitmap,
                translation = pageTranslation,
                rawBoxes = validRegions,
                fillBubbleBackground = config.fillBubbleBackground,
                textScaleFactor = config.textScaleFactor,
            )
            renderDuration = System.currentTimeMillis() - renderStart
            val totalDuration = System.currentTimeMillis() - startTime

            val diagnostics = buildDiagnostics(
                detDuration = detDuration,
                ocrDuration = ocrDuration,
                groupDuration = groupDuration,
                transDuration = transDuration,
                renderDuration = renderDuration,
                totalDuration = totalDuration,
                detectedBoxesCount = validRegions.size,
                finalBlocksCount = finalBlocks.size,
            )
            onStep("Done (${totalDuration}ms)")

            TranslationPipelineResult(
                originalBitmap = bitmap,
                translatedBitmap = renderedBitmap,
                pageTranslation = pageTranslation,
                diagnostics = diagnostics,
                detectedBoxes = validRegions,
                pass1Boxes = pass1Boxes,
                pass2Boxes = pass2Boxes,
                pass2BoxesPassed = pass2PassedBoxes,
                pass2BoxesRejected = pass2RejectedBoxes,
                pass2Crops = pass2ProbedCrops,
                lineBoxes = lineRegions,
                groupedBoxes = groupedLobeBoxes,
                bubbleBoxes = effectiveBubbles,
                m6ChunkBoxes = method6ChunkBoxes,
                m6ChunkCrops = method6ChunkCrops,
                nonBubbledCrops = nonBubbledProbedCrops,
                ocrCrops = ocrCrops,
                crunchSplitBoxes = crunchSplitLobeBoxes,
                crunchOriginalBoxes = crunchOriginalBubbleBoxes,
                crunchPointsA = crunchPointsAList,
                crunchPointsB = crunchPointsBList,
                crunchCutLines = crunchCutLinesList,
                crunchSplits = crunchSplitsList,
                inpaintedBitmap = inpaintedBitmap,
                pass2Bitmap = comicTextDetector.lastPass2Bitmap,
            )
        }
    }

    /**
     * Method 3 Line Reconstruction with Gap Bridging:
     * Bridges vertical gaps between character boxes in the same column (up to 2.5x char height)
     * so that missing mid-column characters are enclosed in the single contiguous line crop
     * passed to MangaOCR, while strictly honoring speech bubble boundaries.
     */
    private fun groupRawBoxesIntoLinesWithBridging(
        boxes: List<Rect>,
        bubbleRegions: List<Rect>,
        isRtl: Boolean,
    ): List<Rect> {
        if (boxes.size <= 1) return boxes

        val parent = IntArray(boxes.size) { it }
        fun find(i: Int): Int {
            var curr = i
            while (parent[curr] != curr) {
                parent[curr] = parent[parent[curr]]
                curr = parent[curr]
            }
            return curr
        }
        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) parent[rootI] = rootJ
        }

        // Map each box to its enclosing speech bubble if available
        val bubbleAssignment = IntArray(boxes.size) { -1 }
        if (bubbleRegions.isNotEmpty()) {
            for (i in boxes.indices) {
                val b = boxes[i]
                val cx = b.centerX()
                val cy = b.centerY()
                for (bi in bubbleRegions.indices) {
                    if (bubbleRegions[bi].contains(cx, cy)) {
                        bubbleAssignment[i] = bi
                        break
                    }
                }
            }
        }

        for (i in boxes.indices) {
            val b1 = boxes[i]
            for (j in i + 1 until boxes.size) {
                val b2 = boxes[j]

                // Never merge across distinct speech bubble envelopes
                if (bubbleAssignment[i] != -1 && bubbleAssignment[j] != -1 && bubbleAssignment[i] != bubbleAssignment[j]) {
                    continue
                }

                val avgW = (b1.width() + b2.width()) / 2f
                val avgH = (b1.height() + b2.height()) / 2f
                val minW = min(b1.width(), b2.width())
                val minH = min(b1.height(), b2.height())

                if (isRtl) {
                    val horizOverlap = min(b1.right, b2.right) - max(b1.left, b2.left)
                    val centerDx = kotlin.math.abs(b1.centerX() - b2.centerX())
                    val dy = max(0, max(b1.top, b2.top) - min(b1.bottom, b2.bottom))

                    // Same column tolerance: centers aligned or horizontal overlap
                    val sameCol = centerDx < minW * 0.55f || horizOverlap > minW * 0.30f

                    // Vertical gap bridging: normal adjacent characters have dy <= avgH * 1.0f;
                    // Missing middle glyph has dy between 1.0x and 2.5x avgH. Bridging unions them!
                    val adjacentOrBridgeableVert = dy <= avgH * 2.50f

                    if (sameCol && adjacentOrBridgeableVert) {
                        union(i, j)
                    }
                } else {
                    val vertOverlap = min(b1.bottom, b2.bottom) - max(b1.top, b2.top)
                    val centerDy = kotlin.math.abs(b1.centerY() - b2.centerY())
                    val dx = max(0, max(b1.left, b2.left) - min(b1.right, b2.right))

                    val sameRow = centerDy < minH * 0.55f || vertOverlap > minH * 0.30f
                    val adjacentOrBridgeableHoriz = dx <= avgW * 2.50f

                    if (sameRow && adjacentOrBridgeableHoriz) {
                        union(i, j)
                    }
                }
            }
        }

        val groups = boxes.indices.groupBy { find(it) }
        val lineRects = groups.values.map { indices ->
            val groupBoxes = indices.map { boxes[it] }
            val left = groupBoxes.minOf { it.left }
            val top = groupBoxes.minOf { it.top }
            val right = groupBoxes.maxOf { it.right }
            val bottom = groupBoxes.maxOf { it.bottom }
            Rect(left, top, right, bottom)
        }

        // Sort lines in manga reading order (RTL: right-to-left, top-to-bottom)
        return if (isRtl) {
            lineRects.sortedWith(
                compareByDescending<Rect> { it.right / 35 }
                    .thenBy { it.top }
            )
        } else {
            lineRects.sortedWith(
                compareBy<Rect> { it.top / 35 }
                    .thenBy { it.left }
            )
        }
    }

    private fun groupRawBoxesIntoLines(boxes: List<Rect>, isRtl: Boolean): List<Rect> {
        if (boxes.size <= 1) return boxes

        val parent = IntArray(boxes.size) { it }
        fun find(i: Int): Int {
            var curr = i
            while (parent[curr] != curr) {
                parent[curr] = parent[parent[curr]]
                curr = parent[curr]
            }
            return curr
        }
        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) parent[rootI] = rootJ
        }

        for (i in boxes.indices) {
            val b1 = boxes[i]
            for (j in i + 1 until boxes.size) {
                val b2 = boxes[j]

                val avgW = (b1.width() + b2.width()) / 2f
                val avgH = (b1.height() + b2.height()) / 2f

                if (isRtl) {
                    val horizOverlap = min(b1.right, b2.right) - max(b1.left, b2.left)
                    val minW = min(b1.width(), b2.width())
                    val centerDx = kotlin.math.abs(b1.centerX() - b2.centerX())
                    val dy = max(0, max(b1.top, b2.top) - min(b1.bottom, b2.bottom))

                    val sameCol = centerDx < minW * 0.45f && (horizOverlap > minW * 0.35f || centerDx < minW * 0.30f)
                    val adjacentVert = dy < max(avgH * 1.10f, avgW * 1.80f)

                    if (sameCol && adjacentVert) {
                        union(i, j)
                    }
                } else {
                    val vertOverlap = min(b1.bottom, b2.bottom) - max(b1.top, b2.top)
                    val minH = min(b1.height(), b2.height())
                    val centerDy = kotlin.math.abs(b1.centerY() - b2.centerY())
                    val dx = max(0, max(b1.left, b2.left) - min(b1.right, b2.right))

                    val sameRow = centerDy < minH * 0.45f && (vertOverlap > minH * 0.35f || centerDy < minH * 0.30f)
                    val adjacentHoriz = dx < max(avgW * 1.10f, avgH * 1.80f)

                    if (sameRow && adjacentHoriz) {
                        union(i, j)
                    }
                }
            }
        }

        val groups = boxes.indices.groupBy { find(it) }
        return groups.values.map { indices ->
            val groupBoxes = indices.map { boxes[it] }
            val left = groupBoxes.minOf { it.left }
            val top = groupBoxes.minOf { it.top }
            val right = groupBoxes.maxOf { it.right }
            val bottom = groupBoxes.maxOf { it.bottom }
            Rect(left, top, right, bottom)
        }
    }

    override fun close() {
        optimizeMemory()
    }
}
