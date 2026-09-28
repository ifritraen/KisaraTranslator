package com.raen.kisaratranslator

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.raen.kisaratranslator.data.logger.AppLogger
import com.raen.kisaratranslator.data.logger.LogLevel
import com.raen.kisaratranslator.ui.MainScreen
import com.raen.kisaratranslator.ui.theme.KisaraTranslatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as KisaraTranslatorApp

        lifecycleScope.launch {
            app.wakeLockManager.isLockHeld.collectLatest { isHeld ->
                if (isHeld) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            KisaraTranslatorTheme {
                MainScreen(
                    translationService = app.translationService,
                    batchService = app.batchTranslationService,
                    modelManager = app.modelManager,
                    wakeLockManager = app.wakeLockManager,
                )
            }
        }

        handleAutoBenchmarkIntent(intent, app)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = application as KisaraTranslatorApp
        handleAutoBenchmarkIntent(intent, app)
    }

    private fun handleAutoBenchmarkIntent(intent: Intent?, app: KisaraTranslatorApp) {
        if (intent?.getBooleanExtra("auto_benchmark", false) == true) {
            lifecycleScope.launch(Dispatchers.IO) {
                // Short grace delay to ensure Compose & Services are initialized
                delay(600)
                val benchmarkFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "KisaraTranslator/original_latest.jpg"
                )
                if (benchmarkFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(benchmarkFile.absolutePath)
                    if (bitmap != null) {
                        val startStep = intent.getIntExtra("start_step", 1)
                        val singleStep = intent.getBooleanExtra("single_step", false)
                        val detectorName = intent.getStringExtra("detector")
                        val ocrName = intent.getStringExtra("ocr")
                        val translatorName = intent.getStringExtra("translator")
                        val azureKey = intent.getStringExtra("azure_key")
                        val azureRegion = intent.getStringExtra("azure_region")
                        val deeplKey = intent.getStringExtra("deepl_key")
                        val geminiKey = intent.getStringExtra("gemini_key")
                        val groqKey = intent.getStringExtra("groq_key")
                        val geminiModel = intent.getStringExtra("gemini_model")
                        val groqModel = intent.getStringExtra("groq_model")

                        var cfg = app.translationService.config.value
                        val methodNum = intent.getIntExtra("pipeline_method", -1)
                        if (methodNum in 0..4) {
                            cfg = cfg.copy(pipelineMethod = methodNum)
                        }
                        if (detectorName != null) {
                            try { cfg = cfg.copy(detector = com.raen.kisaratranslator.data.model.DetectorType.valueOf(detectorName)) } catch (_: Exception) {}
                        }
                        if (ocrName != null) {
                            try { cfg = cfg.copy(ocr = com.raen.kisaratranslator.data.model.OcrType.valueOf(ocrName)) } catch (_: Exception) {}
                        }
                        if (translatorName != null) {
                            try { cfg = cfg.copy(translator = com.raen.kisaratranslator.data.model.TranslatorType.valueOf(translatorName)) } catch (_: Exception) {}
                        }
                        if (azureKey != null) {
                            cfg = cfg.copy(azureApiKey = azureKey)
                        }
                        if (azureRegion != null) {
                            cfg = cfg.copy(azureRegion = azureRegion)
                        }
                        if (deeplKey != null) {
                            cfg = cfg.copy(deeplApiKey = deeplKey)
                        }
                        if (geminiKey != null) {
                            cfg = cfg.copy(geminiApiKey = geminiKey)
                        }
                        if (groqKey != null) {
                            cfg = cfg.copy(groqApiKey = groqKey)
                        }
                        if (geminiModel != null) {
                            cfg = cfg.copy(geminiModel = geminiModel)
                        }
                        if (groqModel != null) {
                            cfg = cfg.copy(groqModel = groqModel)
                        }
                        app.translationService.setConfig(cfg)
                        AppLogger.log(LogLevel.INFO, "Auto-Benchmark triggered via Intent: ${benchmarkFile.absolutePath} (StartStep: $startStep, SingleStep: $singleStep, Detector: ${cfg.detector.name}, OCR: ${cfg.ocr.name})")
                        app.translationService.setOriginalImage(bitmap, Uri.fromFile(benchmarkFile))
                        app.translationService.startPipeline(fromStep = startStep, singleStepOnly = singleStep)
                    } else {
                        AppLogger.log(LogLevel.ERROR, "Auto-Benchmark: Failed to decode bitmap at ${benchmarkFile.absolutePath}")
                    }
                } else {
                    AppLogger.log(LogLevel.WARN, "Auto-Benchmark: Target file not found at ${benchmarkFile.absolutePath}")
                }
            }
        }
    }
}
