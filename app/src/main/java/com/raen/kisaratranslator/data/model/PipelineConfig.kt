package com.raen.kisaratranslator.data.model

enum class DetectorType(val displayName: String) {
    TWO_STAGE_YOLO_CTD("Two-Stage (YOLO + ComicText) [Default]"),
    MANGA_DETECTOR_2024("Manga Text Detector 2024-12-25 (DBNet)"),
    COMIC_TEXT_DETECTOR("Comic Text Detector (ONNX)"),
    PADDLE_OCR_DET("PaddleOCR DBNet (ONNX)"),
    MLKIT("Google ML Kit Vision (On-device)"),
}

enum class OcrType(val displayName: String) {
    MANGA_OCR_INT8("MangaOCR ViT INT8 Quantized [Default]"),
    MANGA_48PX_CTC("Manga Translator 48px CTC (Fastest)"),
    MANGA_OCR("MangaOCR ViT FP32 (Original)"),
    PADDLE_OCR("PaddleOCR v5 (ONNX CTC)"),
    MLKIT("Google ML Kit OCR (On-device)"),
}

enum class TranslatorType(val displayName: String) {
    SUGOI_ONNX("Sugoi (Manga ONNX)"),
    GEMINI_FLASH("Google Gemini Flash (Free API)"),
    GROQ_LLAMA("Groq Llama-3.3 70B (Fastest Cloud)"),
    DEEPL("DeepL API (Free/Pro)"),
    MICROSOFT_AZURE("Microsoft Azure Translator (API Key)"),
    OPUS_MT("Opus-MT (Marian Offline)"),
    GOOGLE_TRANSLATE("Google Translate (Web/GTX)"),
    MLKIT_TRANSLATE("Google ML Kit (On-Device)"),
    QWEN_0_5B("Qwen2.5-0.5B (Local SLM)"),
}

data class LanguageOption(val code: String, val displayName: String) {
    companion object {
        val SOURCE_LANGUAGES = listOf(
            LanguageOption("ja", "Japanese (日本語)"),
            LanguageOption("zh", "Chinese (中文)"),
            LanguageOption("ko", "Korean (한국어)"),
            LanguageOption("en", "English"),
        )

        val TARGET_LANGUAGES = listOf(
            LanguageOption("en", "English"),
            LanguageOption("es", "Spanish (Español)"),
            LanguageOption("fr", "French (Français)"),
            LanguageOption("de", "German (Deutsch)"),
            LanguageOption("ru", "Russian (Русский)"),
            LanguageOption("id", "Indonesian (Bahasa)"),
            LanguageOption("pt", "Português"),
            LanguageOption("it", "Italiano"),
            LanguageOption("ja", "Japanese (日本語)"),
        )
    }
}

data class PipelineConfig(
    val detector: DetectorType = DetectorType.TWO_STAGE_YOLO_CTD,
    val ocr: OcrType = OcrType.MANGA_OCR_INT8,
    val translator: TranslatorType = TranslatorType.SUGOI_ONNX,
    val sourceLang: String = "ja",
    val targetLang: String = "en",
    val bubbleGroupingEnabled: Boolean = true,
    val assignmentMode: Int = 0, // 0 = Point-in-Polygon Centroid, 1 = Spatial Proximity DSU
    val lineSortingOrder: Int = 0, // 0 = Auto/RTL Manga, 1 = LTR Webtoon, 2 = Disabled
    val fillBubbleBackground: Boolean = true,
    val textScaleFactor: Float = 1.0f,
    val keepScreenOn: Boolean = true,
    val azureApiKey: String = "",
    val azureRegion: String = "global",
    val deeplApiKey: String = "",
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val groqApiKey: String = "",
    val groqModel: String = "qwen/qwen3.6-27b",
    val sugoiBeamWidth: Int = 1, // 1 = Fast Greedy (default), 2 = Quality Beam Search
    val qwenEndpoint: String = "",
    val qwenApiKey: String = "",
    val enhancedPipeline: Boolean = false, // false = Method 1 (Standard), true = Method 2 (Enhanced)
    val pipelineMethod: Int = 9, // 0..4 = Legacy, 5 = Method 6, 6 = Method 7, 7 = Method 8 v1, 8 = Method 8 v2, 9 = Method 8 v3 (Pure Border Angle)
    val chunkLinesCount: Int = 2, // 1..3 lines per OCR chunk (default 2)
    val probeDet2Neighbors: Boolean = true, // Det 2: Probe 4-way adjacent neighbor cells around detected bubbles
    val probeStep3Orphans: Boolean = true, // Step 3: Probe orphan lines with MangaOCR AI noise filter
    val speedDet1: Int = 3, // 1=Lowest .. 5=Highest (default Normal = 3)
    val speedDet2: Int = 3, // 1=Lowest .. 5=Highest (default Normal = 3)
    val speedProbeNeighbors: Int = 3, // 1=Lowest .. 5=Highest (default Normal = 3)
    val speedProbeOrphans: Int = 3, // 1=Lowest .. 5=Highest (default Normal = 3)
    val speedOcr: Int = 3, // 1=Lowest .. 5=Highest (default Normal = 3)
    val speedTranslate: Int = 3, // 1=Lowest .. 5=Highest (default Normal = 3)
) {
    val effectiveMethod: Int
        get() = when {
            pipelineMethod in 1..9 -> pipelineMethod
            enhancedPipeline -> 1
            else -> 0
        }
}

enum class SpeedResourceLevel(val level: Int, val label: String, val shortDesc: String) {
    LOWEST(1, "Lowest", "Min CPU/RAM, battery saver, sequential"),
    LOW(2, "Low", "Low resource consumption, 1-2 workers"),
    NORMAL(3, "Normal", "Balanced speed & resources [Default]"),
    HIGH(4, "High", "High concurrency & multi-threaded"),
    HIGHEST(5, "Highest", "Max CPU horsepower & instant speed"),
    ;

    companion object {
        fun fromLevel(lvl: Int): SpeedResourceLevel = entries.firstOrNull { it.level == lvl.coerceIn(1, 5) } ?: NORMAL
    }
}
