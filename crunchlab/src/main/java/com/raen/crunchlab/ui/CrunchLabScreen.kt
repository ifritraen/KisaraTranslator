package com.raen.crunchlab.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raen.crunchlab.data.CrunchExporter
import com.raen.crunchlab.data.CrunchPartitionItem
import com.raen.crunchlab.data.DebugModuleSnapshot
import com.raen.crunchlab.data.DebugSnapshotManager
import com.raen.crunchlab.data.ModelDownloader
import com.raen.crunchlab.data.ProcessedPage
import com.raen.crunchlab.data.ReadingGroupItem
import com.raen.crunchlab.data.TextCategory
import com.raen.crunchlab.data.TextLineItem
import com.raen.crunchlab.data.TextOrientation
import com.raen.crunchlab.data.TranslationBlock
import com.raen.crunchlab.engine.BubbleMask
import com.raen.crunchlab.engine.BubbleSegmentationEngine
import com.raen.crunchlab.engine.ComicTextDetector
import com.raen.crunchlab.engine.Method3WaistEngine
import com.raen.crunchlab.engine.grouping.TextCategorizer
import com.raen.crunchlab.engine.grouping.VerticalLineStitcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Modular Translation Debugging Studio.
 *
 * Module Architecture:
 * - Module 1 (CTD):
 *     Step 1.1: Text Heatmap (Continuous probability map)
 *     Step 1.2: Pass 1 Lines + Recombined Zenkaku Em-Boxes + Heatmap Islands
 * - Module 2 (Lines):
 *     Step 2.1: Conjoined Split (SmartBubbleGrouper peanut waist constriction)
 *     Step 2.2: Bubble Grouping & RTL Reading Order (BubbleGroupingCoordinator)
 * - Module 3 (Crunch):
 *     Step 3.1: AI Waist & Conf, Step 3.2: Ink Cutline, Step 3.3: Lobe Partitioning (A/B)
 *
 * Execution Invariants:
 * - "Run Current": Runs strictly active module (using cached upstream data if present).
 * - "Run Before": Forces execution from Module 1 up to active module sequentially, refreshing data.
 * - No future modules ever run.
 */

data class BoxInspectionInfo(
    val title: String,
    val subtitle: String,
    val dimensions: String,
    val recognizedText: String? = null,
    val confidence: String? = null,
    val tagColor: Color = Color(0xFF00E5FF),
    val rect: Rect,
    val badgeNumber: String = ""
)

enum class DebugModule(val id: Int, val shortTag: String, val shortName: String, val title: String) {
    MODULE_1_CTD(1, "M1", "M1: CTD Text", "Module 1: Comic Text Detector"),
    MODULE_1_5_CATEGORIZE(2, "M1.5", "M1.5: Categorize", "Module 1.5: Text Categorization (Bubbled/Orphan/SFX)"),
    MODULE_2_LINES(3, "M2", "M2: Vertical Lines", "Module 2: Single Vertical Lines"),
    MODULE_3_CRUNCH(4, "M3", "M3: Waist Crunch", "Module 3: Waist Crunch (Method 3)")
}

enum class M1SubStep(val id: Int, val label: String) {
    HEATMAP(0, "1.1 Heatmap"),
    LINE_BOXES(1, "1.2 Pass 1 Lines")
}

enum class M1_5SubStep(val id: Int, val label: String) {
    ALL(0, "1.5.1 All Categorized"),
    BUBBLED(1, "1.5.2 Bubbled"),
    ORPHAN(2, "1.5.3 Orphan"),
    SFX(3, "1.5.4 SFX")
}

enum class M2SubStep(val id: Int, val label: String) {
    ALL_LINES(0, "2.1 Single Vertical Lines"),
    BUBBLED_LINES(1, "2.2 Bubbled Lines"),
    ORPHAN_LINES(2, "2.3 Orphan Lines")
}

enum class M3SubStep(val id: Int, val label: String) {
    AI_WAIST(0, "3.1 AI Waist & Candidates"),
    SNAPPED_CUTLINE(1, "3.2 Snapped Cutline"),
    LOBE_PARTITION(2, "3.3 Lobe A/B Partition")
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CrunchLabScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val downloader = remember { ModelDownloader(context) }
    val segEngine = remember { BubbleSegmentationEngine(downloader) }
    val ctdEngine = remember { ComicTextDetector(downloader) }
    val method3WaistEngine = remember { Method3WaistEngine(downloader) }
    val snapshotManager = remember { DebugSnapshotManager(context) }

    val downloadProgress by downloader.progress.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            segEngine.close()
            ctdEngine.close()
            method3WaistEngine.close()
        }
    }

    var isAllModelsReady by remember { mutableStateOf(downloader.isAllModelsReady()) }
    var showDatasetBrowser by remember { mutableStateOf(false) }
    var showCbzBrowserSheet by remember { mutableStateOf(false) }

    // Navigation & State
    var activeModule by remember { mutableStateOf(DebugModule.MODULE_1_CTD) }
    var m1SubStep by remember { mutableStateOf(M1SubStep.LINE_BOXES) }
    var m1_5SubStep by remember { mutableStateOf(M1_5SubStep.ALL) }
    var m2SubStep by remember { mutableStateOf(M2SubStep.ALL_LINES) }
    var m3SubStep by remember { mutableStateOf(M3SubStep.LOBE_PARTITION) }

    var processedPages by remember { mutableStateOf<List<ProcessedPage>>(emptyList()) }
    var activePageIndex by remember { mutableIntStateOf(0) }

    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Select Manga DB, Pick .CBZ or Pick Custom images to start") }

    var spotlightRect by remember { mutableStateOf<Rect?>(null) }
    var inspectedBoxInfo by remember { mutableStateOf<BoxInspectionInfo?>(null) }
    val bubbleListState = rememberLazyListState()

    var cleanupJob by remember { mutableStateOf<Job?>(null) }

    fun scheduleAutoCleanup() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            delay(3000L)
            withContext(Dispatchers.Default) {
                ctdEngine.releaseInferenceBuffers()
                segEngine.close()
                method3WaistEngine.close()
                System.gc()
                System.runFinalization()
                Log.i("CrunchLab", "3-second auto-cleanup: Released ONNX sessions, native buffers, and executed GC")
            }
        }
    }

    // Viewport: Zoom, Pan & Wipe Curtain
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var wipeCurtainFraction by remember { mutableFloatStateOf(1.0f) }

    // Snapshot Dialogs
    var showSaveSnapshotDialog by remember { mutableStateOf(false) }
    var showLoadSnapshotDialog by remember { mutableStateOf(false) }
    var saveSlotInput by remember { mutableStateOf("") }
    var savedSnapshotFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    val activePage = processedPages.getOrNull(activePageIndex)
    val displayBmp = if (activeModule == DebugModule.MODULE_3_CRUNCH) {
        activePage?.isolatedBitmap ?: activePage?.sourceBitmap
    } else {
        activePage?.sourceBitmap
    }

    // Helper: Generate Heatmap Bitmap from FloatArray (Step 1.1)
    fun getOrCreateHeatmap(page: ProcessedPage): Bitmap? {
        if (page.m1HeatmapBitmap != null) return page.m1HeatmapBitmap
        val prob = page.m1TextProb ?: return null
        val dim = 1024
        val bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(dim * dim)
        for (i in 0 until dim * dim) {
            val p = prob[i]
            if (p < 0.20f) {
                pixels[i] = 0
            } else {
                val norm = ((p - 0.20f) / 0.80f).coerceIn(0f, 1f)
                val r = 255
                val g = ((1f - norm) * 220).toInt()
                val b = 0
                val a = (110 + (norm * 135)).toInt()
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, dim, 0, 0, dim, dim)
        page.m1HeatmapBitmap = bmp
        return bmp
    }

    // Pipeline Execution Engine with Strict Upstream-Only Dependencies
    fun runModule(targetModule: DebugModule, forceUpstream: Boolean = false) {
        val page = processedPages.getOrNull(activePageIndex) ?: return
        cleanupJob?.cancel()
        scope.launch {
            isProcessing = true
            try {
                var curr = page
                val bmp = curr.sourceBitmap

                // Model Check
                val needM1 = curr.m1Lines.isEmpty() || forceUpstream || targetModule == DebugModule.MODULE_1_CTD
                if (needM1 && !downloader.isComicTextModelReady()) {
                    statusMessage = "ComicText model missing. Please click Get Models."
                    isProcessing = false
                    return@launch
                }
                if (targetModule == DebugModule.MODULE_3_CRUNCH) {
                    if (!downloader.isBubbleModelReady() || !downloader.isWaistModelReady()) {
                        statusMessage = "BubbleSeg or Waist model missing (place manga_waist_model.onnx in Download)."
                        isProcessing = false
                        return@launch
                    }
                }

                // Upstream Invariant: Only run upstream modules (M1 -> targetModule)
                // If M1 is already run, skip M1 and take the output data of M1!

                // Dependency 1: Module 1 (CTD Detection & Continuous Heatmap)
                if (needM1) {
                    statusMessage = "[M1] CTD Detection & Heatmap extraction for ${curr.label}..."
                    val res = withContext(Dispatchers.Default) {
                        ctdEngine.runModule1(bmp) { msg ->
                            statusMessage = msg
                        }
                    }
                    curr = curr.copy(
                        m1TextProb = res.probMap,
                        m1Lines = res.lines,
                        m1Bubbles = res.bubbles,
                        m1Pass2Boxes = res.pass2Boxes,
                        m1Pass2Bitmap = res.pass2Bitmap,
                        m1Pass2PassedBoxes = res.pass2PassedBoxes,
                        m1Pass2RejectedBoxes = res.pass2RejectedBoxes,
                        m1Pass2Texts = res.pass2BoxTexts,
                        m1HeatmapBitmap = null
                    )
                }

                // Dependency 2: Module 1.5 (Categorization: Bubbled, Orphan, SFX)
                if (targetModule.id >= 2) {
                    val needM1_5 = needM1 || curr.m1_5CategorizedBoxes.isEmpty() || targetModule == DebugModule.MODULE_1_5_CATEGORIZE || forceUpstream
                    if (needM1_5) {
                        val masks = if (curr.detectedMasks.isNotEmpty() && !forceUpstream) {
                            curr.detectedMasks
                        } else {
                            statusMessage = "[M1.5] Running Manga109 bubble segmentation..."
                            withContext(Dispatchers.Default) {
                                segEngine.detectMasks(bmp)
                            }
                        }

                        statusMessage = "[M1.5] Categorizing character boxes into Bubbled, Orphan, and SFX..."
                        val catRes = withContext(Dispatchers.Default) {
                            TextCategorizer.categorizeBoxes(
                                rawBoxes = curr.m1Lines.map { it.rect },
                                bubbleRegions = curr.m1Bubbles,
                                bubbleMasks = masks,
                                bitmap = bmp,
                                bitmapWidth = bmp.width,
                                bitmapHeight = bmp.height,
                                isRtl = true
                            )
                        }
                        curr = curr.copy(
                            detectedMasks = masks,
                            m1_5CategorizedBoxes = catRes.allBoxes,
                            m1_5BubbledCount = catRes.bubbledBoxes.size,
                            m1_5OrphanCount = catRes.orphanBoxes.size,
                            m1_5SfxCount = catRes.sfxBoxes.size
                        )
                    }
                }

                // Dependency 3: Module 2 (Single Vertical Line Stitching per Column)
                if (targetModule.id >= 3) {
                    val needM2 = needM1 || curr.m2VerticalLines.isEmpty() || targetModule == DebugModule.MODULE_2_LINES || forceUpstream
                    if (needM2) {
                        val masks = curr.detectedMasks
                        statusMessage = "[M2] Stitching character boxes into single vertical lines per column..."
                        val rawBoxes = curr.m1_5CategorizedBoxes.ifEmpty {
                            curr.m1Lines.map { it.copy(category = TextCategory.BUBBLED) }
                        }
                        val stitchedOut = withContext(Dispatchers.Default) {
                            VerticalLineStitcher.stitchLines(
                                categorizedBoxes = rawBoxes,
                                bubbleRegions = curr.m1Bubbles,
                                bubbleMasks = masks,
                                bitmap = bmp,
                                bitmapWidth = bmp.width,
                                bitmapHeight = bmp.height,
                                isRtl = true
                            )
                        }
                        curr = curr.copy(
                            m2VerticalLines = stitchedOut.allLines,
                            m2SuppressedFuriganaCount = stitchedOut.suppressedFuriganaCount
                        )
                    }
                }

                // Target: Module 3 (Waist Crunch Method 3)
                if (targetModule.id >= 4) {
                    val masks = if (curr.detectedMasks.isNotEmpty() && !forceUpstream) {
                        curr.detectedMasks
                    } else {
                        statusMessage = "[M3] Running Manga109 bubble segmentation..."
                        withContext(Dispatchers.Default) {
                            segEngine.detectMasks(bmp)
                        }
                    }

                    statusMessage = "[M3] Waist prediction, auto-suggest candidates & lobe partitioning (${masks.size} bubbles)..."
                    val partitions = withContext(Dispatchers.Default) {
                        method3WaistEngine.processPage(
                            bitmap = bmp,
                            masks = masks,
                            textLines = curr.m2VerticalLines.ifEmpty { curr.m1Lines },
                            readingGroups = emptyList()
                        )
                    }

                    statusMessage = "[M3] Rendering isolated bubble layer..."
                    val isolated = withContext(Dispatchers.Default) {
                        IsolatedBubbleRenderer.renderIsolatedBubbles(bmp, masks)
                    } ?: bmp

                    curr = curr.copy(
                        isolatedBitmap = isolated,
                        detectedMasks = masks,
                        m3Partitions = partitions
                    )
                }

                val updatedList = processedPages.toMutableList()
                updatedList[activePageIndex] = curr
                processedPages = updatedList
                activeModule = targetModule

                val countMsg = when (targetModule) {
                    DebugModule.MODULE_1_CTD -> {
                        "${curr.m1Lines.size} text lines (${curr.m1Bubbles.size} bubbles)"
                    }
                    DebugModule.MODULE_1_5_CATEGORIZE -> {
                        "${curr.m1_5CategorizedBoxes.size} boxes (${curr.m1_5BubbledCount} bubbled, ${curr.m1_5OrphanCount} orphan, ${curr.m1_5SfxCount} sfx)"
                    }
                    DebugModule.MODULE_2_LINES -> {
                        val bubbled = curr.m2VerticalLines.count { it.category == TextCategory.BUBBLED }
                        val orphan = curr.m2VerticalLines.count { it.category == TextCategory.ORPHAN }
                        val sfx = curr.m2VerticalLines.count { it.category == TextCategory.SFX }
                        val furi = curr.m2SuppressedFuriganaCount
                        "${curr.m2VerticalLines.size} single vertical lines ($bubbled bubbled, $orphan orphan, $sfx sfx, $furi furigana absorbed)"
                    }
                    DebugModule.MODULE_3_CRUNCH -> {
                        val crunched = curr.m3Partitions.count { it.isConjoined }
                        val splits = curr.m3Partitions.sumOf { it.splitLines.size / 2 }
                        val totalCand = curr.m3Partitions.sumOf { it.candidatePoints.size }
                        if (splits > 0) {
                            "$crunched conjoined ($totalCand candidates, $splits straddling lines sliced), ${curr.m3Partitions.size} total"
                        } else {
                            "$crunched conjoined ($totalCand candidates), ${curr.m3Partitions.size} total"
                        }
                    }
                }
                statusMessage = "✓ ${targetModule.shortName} complete! ($countMsg)"

                // Auto-export full telemetry JSON & step images to /sdcard/Download/CrunchLab/
                val pageToExport = curr
                withContext(Dispatchers.IO) {
                    CrunchExporter.exportPage(context, pageToExport)
                }
            } catch (e: Exception) {
                statusMessage = "Error in ${targetModule.shortName}: ${e.message}"
                Log.e("CrunchLab", "Execution error", e)
            } finally {
                isProcessing = false
                scheduleAutoCleanup()
            }
        }
    }

    fun loadPagesFromBitmaps(items: List<Pair<String, Bitmap>>) {
        if (items.isEmpty()) return
        scope.launch {
            isProcessing = true
            statusMessage = "Loading ${items.size} pages..."
            scale = 1f
            offset = Offset.Zero
            spotlightRect = null
            inspectedBoxInfo = null
            activeModule = DebugModule.MODULE_1_CTD
            m1SubStep = M1SubStep.LINE_BOXES
            m1_5SubStep = M1_5SubStep.ALL
            m2SubStep = M2SubStep.ALL_LINES
            m3SubStep = M3SubStep.LOBE_PARTITION
            processedPages = items.mapIndexed { idx, (label, bmp) ->
                ProcessedPage(
                    index = idx,
                    label = label,
                    sourceBitmap = bmp,
                    isolatedBitmap = bmp
                )
            }
            activePageIndex = 0
            isProcessing = false
            statusMessage = "Loaded ${items.size} pages. Ready to run M1, M2, or M3."
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val decoded = withContext(Dispatchers.IO) {
                    uris.mapIndexedNotNull { idx, uri ->
                        val stream: InputStream? = context.contentResolver.openInputStream(uri)
                        val bmp = BitmapFactory.decodeStream(stream)
                        stream?.close()
                        if (bmp != null) "Page $idx" to bmp else null
                    }
                }
                loadPagesFromBitmaps(decoded)
            }
        }
    }

    fun loadPagesFromFiles(files: List<File>) {
        if (files.isEmpty()) return
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                files.mapNotNull { file ->
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) file.nameWithoutExtension to bmp else null
                }
            }
            loadPagesFromBitmaps(decoded)
        }
    }

    LaunchedEffect(downloadProgress.isDownloading) {
        if (!downloadProgress.isDownloading) {
            isAllModelsReady = downloader.isAllModelsReady()
        }
    }

    Scaffold(
        topBar = {
            if (activeModule == DebugModule.MODULE_3_CRUNCH && activePage?.m3Partitions?.isNotEmpty() == true) {
                Surface(
                    color = Color(0xFF1B1B1B),
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        LazyRow(
                            state = bubbleListState,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().height(68.dp)
                        ) {
                            itemsIndexed(activePage.m3Partitions) { idx, item ->
                                val isSelected = spotlightRect == item.bubbleRect
                                val bg = when {
                                    isSelected -> Color(0xFF0091EA)
                                    item.isCutRejected -> Color(0xFF5D1010)
                                    item.isConjoined -> Color(0xFF4A148C)
                                    else -> Color(0xFF212121)
                                }

                                Surface(
                                    color = bg,
                                    shape = RoundedCornerShape(6.dp),
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00E5FF)) else null,
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .clickable {
                                            spotlightRect = item.bubbleRect
                                        }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            "Bubble #${idx + 1} ${if (item.isConjoined) "✂️" else "⚪"}",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            if (item.isConjoined) "Conj ${(item.confConj * 100).toInt()}% (${item.candidatePoints.size} cand)" else "Single",
                                            color = if (item.isConjoined) Color(0xFFFFD600) else Color.LightGray,
                                            fontSize = 9.sp
                                        )
                                        if (item.isCutRejected) {
                                            Text(
                                                "REJECTED",
                                                color = Color(0xFFFF5252),
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        } else if (item.isConjoined) {
                                            Text(
                                                "A:${item.lobeALineIds.size} B:${item.lobeBLineIds.size}",
                                                color = Color(0xFF69F0AE),
                                                fontSize = 8.sp
                                            )
                                            if (item.splitLines.isNotEmpty()) {
                                                Text(
                                                    "✂️ Split: ${item.splitLines.size / 2}",
                                                    color = Color(0xFF00E5FF),
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = Color(0xFF1B1B1B),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    // Row 3: Page Selector Bar
                    if (processedPages.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            processedPages.forEachIndexed { pIdx, page ->
                                val isSelected = activePageIndex == pIdx
                                val m1Count = page.m1Lines.size
                                val m1_5Count = page.m1_5CategorizedBoxes.size
                                val m2Count = page.m2VerticalLines.size
                                val m3Crunched = page.m3Partitions.count { it.isConjoined && !it.isCutRejected }

                                Surface(
                                    color = if (isSelected) Color(0xFF0091EA) else Color(0xFF262626),
                                    shape = RoundedCornerShape(6.dp),
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF)) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444444)),
                                    modifier = Modifier.clickable {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        activePageIndex = pIdx
                                        spotlightRect = null
                                        inspectedBoxInfo = null
                                        scale = 1f
                                        offset = Offset.Zero
                                        wipeCurtainFraction = 1.0f
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "📄 ${page.label}",
                                            color = if (isSelected) Color.White else Color.LightGray,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        val statusBadge = when {
                                            page.m3Partitions.isNotEmpty() -> "✂️ $m3Crunched"
                                            page.m2VerticalLines.isNotEmpty() -> "📑 $m2Count"
                                            page.m1_5CategorizedBoxes.isNotEmpty() -> "🏷️ $m1_5Count"
                                            page.m1Lines.isNotEmpty() -> "🔤 $m1Count"
                                            else -> "⚪"
                                        }
                                        Text(statusBadge, fontSize = 9.sp, color = Color.LightGray)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Row 2: Sub-Step Segment Selectors for Active Module
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (activeModule) {
                            DebugModule.MODULE_1_CTD -> {
                                M1SubStep.entries.forEach { step ->
                                    val isSel = m1SubStep == step
                                    Surface(
                                        color = if (isSel) Color(0xFFFF9800) else Color(0xFF2A2A2A),
                                        shape = RoundedCornerShape(6.dp),
                                        border = if (isSel) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444444)),
                                        modifier = Modifier.clickable { m1SubStep = step }
                                    ) {
                                        Text(
                                            step.label,
                                            color = if (isSel) Color.White else Color.LightGray,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                            DebugModule.MODULE_1_5_CATEGORIZE -> {
                                M1_5SubStep.entries.forEach { step ->
                                    val isSel = m1_5SubStep == step
                                    Surface(
                                        color = if (isSel) Color(0xFF00E676) else Color(0xFF2A2A2A),
                                        shape = RoundedCornerShape(6.dp),
                                        border = if (isSel) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444444)),
                                        modifier = Modifier.clickable { m1_5SubStep = step }
                                    ) {
                                        Text(
                                            step.label,
                                            color = if (isSel) Color.Black else Color.LightGray,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(start = 4.dp)
                                ) {
                                    val bCount = activePage?.m1_5BubbledCount ?: 0
                                    val oCount = activePage?.m1_5OrphanCount ?: 0
                                    val sCount = activePage?.m1_5SfxCount ?: 0
                                    Surface(color = Color(0xFF00E676), shape = RoundedCornerShape(4.dp)) {
                                        Text("Bubbled ($bCount)", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                    }
                                    Surface(color = Color(0xFFFFD600), shape = RoundedCornerShape(4.dp)) {
                                        Text("Orphan ($oCount)", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                    }
                                    Surface(color = Color(0xFFFF3D00), shape = RoundedCornerShape(4.dp)) {
                                        Text("SFX ($sCount)", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                    }
                                }
                            }
                            DebugModule.MODULE_2_LINES -> {
                                M2SubStep.entries.forEach { step ->
                                    val isSel = m2SubStep == step
                                    Surface(
                                        color = if (isSel) Color(0xFF9C27B0) else Color(0xFF2A2A2A),
                                        shape = RoundedCornerShape(6.dp),
                                        border = if (isSel) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444444)),
                                        modifier = Modifier.clickable { m2SubStep = step }
                                    ) {
                                        Text(
                                            step.label,
                                            color = if (isSel) Color.White else Color.LightGray,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                val count = activePage?.m2VerticalLines?.size ?: 0
                                val furiCount = activePage?.m2SuppressedFuriganaCount ?: 0
                                Surface(color = Color(0xFF1E88E5), shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(start = 4.dp)) {
                                    Text("→ Feeds M3 ($count vertical lines, $furiCount furigana merged)", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                                }
                            }
                            DebugModule.MODULE_3_CRUNCH -> {
                                M3SubStep.entries.forEach { step ->
                                    val isSel = m3SubStep == step
                                    Surface(
                                        color = if (isSel) Color(0xFF00E5FF) else Color(0xFF2A2A2A),
                                        shape = RoundedCornerShape(6.dp),
                                        border = if (isSel) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444444)),
                                        modifier = Modifier.clickable { m3SubStep = step }
                                    ) {
                                        Text(
                                            step.label,
                                            color = if (isSel) Color.Black else Color.LightGray,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Row 1: Source & Module Selector Ribbon
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 📁 DB
                        Surface(
                            color = Color(0xFF6200EA),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                showDatasetBrowser = true
                            }
                        ) {
                            Text(
                                "📁 DB",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)
                            )
                        }

                        // 📦 .CBZ
                        Surface(
                            color = Color(0xFF7C4DFF),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                showCbzBrowserSheet = true
                            }
                        ) {
                            Text(
                                "📦 .CBZ",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)
                            )
                        }

                        // 🖼️ Cus
                        Surface(
                            color = Color(0xFF1976D2),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                pickerLauncher.launch("image/*")
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("Cus", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // MODULE TABS (M1, M1.5, M2, M3) WITH EMBEDDED PLAY BUTTON
                        DebugModule.entries.forEach { mod ->
                            val isSelected = activeModule == mod
                            val hasData = when (mod) {
                                DebugModule.MODULE_1_CTD -> activePage?.m1Lines?.isNotEmpty() == true
                                DebugModule.MODULE_1_5_CATEGORIZE -> activePage?.m1_5CategorizedBoxes?.isNotEmpty() == true
                                DebugModule.MODULE_2_LINES -> activePage?.m2VerticalLines?.isNotEmpty() == true
                                DebugModule.MODULE_3_CRUNCH -> activePage?.m3Partitions?.isNotEmpty() == true
                            }
                            val tabBg = when {
                                isSelected -> Color(0xFF00E5FF)
                                hasData -> Color(0xFF2E7D32)
                                else -> Color(0xFF2C2C2C)
                            }
                            val textColor = if (isSelected) Color.Black else Color.White

                            Surface(
                                color = tabBg,
                                shape = RoundedCornerShape(6.dp),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color.White) else null,
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 6.dp, end = 3.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .clickable {
                                                focusManager.clearFocus()
                                                keyboardController?.hide()
                                                activeModule = mod
                                            }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (hasData && !isSelected) {
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(10.dp))
                                            Spacer(modifier = Modifier.width(2.dp))
                                        }
                                        Text(
                                            text = mod.shortTag,
                                            color = textColor,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    // Embedded mini play button to execute this specific module
                                    Surface(
                                        color = if (isProcessing) Color.Gray else if (isSelected) Color(0xFF0091EA) else Color(0xFFFF6D00),
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable(enabled = !isProcessing && processedPages.isNotEmpty()) {
                                                focusManager.clearFocus()
                                                keyboardController?.hide()
                                                activeModule = mod
                                                runModule(mod, forceUpstream = false)
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Filled.PlayArrow,
                                                contentDescription = "Run ${mod.shortName}",
                                                tint = Color.White,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (processedPages.isNotEmpty()) {
                            // ⏩ RUN BEFORE BUTTON (M1 -> activeModule)
                            Surface(
                                color = if (isProcessing) Color.Gray else Color(0xFFD500F9),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.clickable(enabled = !isProcessing) {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    runModule(activeModule, forceUpstream = true)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        "Run Before",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // 🔄 RESET CURRENT PAGE DATA BUTTON
                            if (activePage != null) {
                                Surface(
                                    color = Color(0xFFC62828),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable(enabled = !isProcessing) {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        val resetPage = ProcessedPage(
                                            index = activePage.index,
                                            label = activePage.label,
                                            sourceBitmap = activePage.sourceBitmap,
                                            isolatedBitmap = activePage.sourceBitmap
                                        )
                                        val list = processedPages.toMutableList()
                                        list[activePageIndex] = resetPage
                                        processedPages = list
                                        spotlightRect = null
                                        inspectedBoxInfo = null
                                        statusMessage = "Reset data for ${activePage.label}"
                                    }
                                ) {
                                    Text(
                                        "🔄 Reset",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            // 💾 SNAPSHOT SAVE BUTTON
                            Surface(
                                color = Color(0xFF00C853),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.clickable {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    saveSlotInput = "snap_${SimpleDateFormat("HHmmss", Locale.US).format(Date())}"
                                    showSaveSnapshotDialog = true
                                }
                            ) {
                                Text(
                                    "💾 Save Snap",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }

                            // 📤 EXPORT TELEMETRY & STEP IMAGES BUTTON
                            Surface(
                                color = Color(0xFFFF6D00),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.clickable(enabled = !isProcessing && activePage != null) {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    activePage?.let { p ->
                                        scope.launch {
                                            statusMessage = "Exporting telemetry & all step images..."
                                            withContext(Dispatchers.IO) {
                                                CrunchExporter.exportPage(context, p)
                                            }
                                            statusMessage = "✓ Export complete to /sdcard/Download/CrunchLab/"
                                        }
                                    }
                                }
                            ) {
                                Text(
                                    "📤 Export",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // 📂 SNAPSHOT LOAD BUTTON
                        if (processedPages.isNotEmpty() && activePage != null) {
                            Surface(
                                color = Color(0xFF0091EA),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.clickable {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    savedSnapshotFiles = snapshotManager.listSnapshots(activePage.label)
                                    showLoadSnapshotDialog = true
                                }
                            ) {
                                Text(
                                    "📂 Load Snap",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // 📥 Models Status Button
                        Surface(
                            color = if (isAllModelsReady) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                if (!isAllModelsReady) {
                                    downloader.startDownloadAll {
                                        isAllModelsReady = downloader.isAllModelsReady()
                                    }
                                }
                            }
                        ) {
                            Text(
                                if (isAllModelsReady) "✓ Models OK" else "📥 Get Models",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Row 0: Bottom-most Status & Progress Indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFFFD600)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = statusMessage,
                            color = if (isProcessing) Color(0xFFFFD600) else Color.LightGray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Download Progress Indicator
                    AnimatedVisibility(visible = downloadProgress.isDownloading) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                            LinearProgressIndicator(
                                progress = { downloadProgress.progress },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = Color(0xFFE040FB),
                                trackColor = Color(0xFF333333)
                            )
                            Text(
                                "Downloading: ${downloadProgress.status}",
                                color = Color.LightGray,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF121212))
        ) {
            // Main Viewport Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(0.dp))
                    .onSizeChanged { containerSize = it }
            ) {
                if (displayBmp != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    if (zoom != 1f || scale > 1f) {
                                        scale = (scale * zoom).coerceIn(1f, 8f)
                                        if (scale > 1f) {
                                            offset += pan
                                        } else {
                                            offset = Offset.Zero
                                        }
                                    } else {
                                        if (containerSize.width > 0) {
                                            wipeCurtainFraction = (wipeCurtainFraction + pan.x / containerSize.width).coerceIn(0f, 1f)
                                        }
                                    }
                                }
                            }
                            .pointerInput(activePage, activeModule) {
                                detectTapGestures(
                                    onTap = { tapOffset ->
                                        focusManager.clearFocus()
                                        keyboardController?.hide()

                                        if (containerSize.width > 0 && containerSize.height > 0) {
                                            val canvasW = containerSize.width.toFloat()
                                            val canvasH = containerSize.height.toFloat()
                                            val cx = canvasW / 2f
                                            val cy = canvasH / 2f

                                            val xL = cx + (tapOffset.x - cx - offset.x) / scale
                                            val yL = cy + (tapOffset.y - cy - offset.y) / scale

                                            val bmpW = displayBmp.width.toFloat()
                                            val bmpH = displayBmp.height.toFloat()
                                            val scaleFactor = min(canvasW / bmpW, canvasH / bmpH)
                                            val drawW = bmpW * scaleFactor
                                            val drawH = bmpH * scaleFactor
                                            val drawLeft = (canvasW - drawW) / 2f
                                            val drawTop = (canvasH - drawH) / 2f

                                            val bmpX = (xL - drawLeft) / scaleFactor
                                            val bmpY = (yL - drawTop) / scaleFactor

                                            if (bmpX in 0f..bmpW && bmpY in 0f..bmpH) {
                                                val bx = bmpX.toInt()
                                                val by = bmpY.toInt()

                                                when (activeModule) {
                                                    DebugModule.MODULE_3_CRUNCH -> {
                                                        val partitions = activePage?.m3Partitions ?: emptyList()
                                                        val hitIdx = partitions.indices.firstOrNull { idx ->
                                                            partitions[idx].bubbleRect.contains(bx, by)
                                                        }
                                                        if (hitIdx != null) {
                                                            val part = partitions[hitIdx]
                                                            spotlightRect = part.bubbleRect
                                                            val cuts = part.splitLines.size / 2
                                                            val statusDesc = if (part.isCutRejected) {
                                                                "Cut Rejected"
                                                            } else if (part.isConjoined) {
                                                                "Conjoined Bubble ($cuts waist cut${if (cuts != 1) "s" else ""})"
                                                            } else {
                                                                "Single Convex Bubble"
                                                            }
                                                            inspectedBoxInfo = BoxInspectionInfo(
                                                                badgeNumber = "#B${hitIdx + 1}",
                                                                title = "Speech Bubble",
                                                                subtitle = statusDesc,
                                                                dimensions = "${part.bubbleRect.width()} × ${part.bubbleRect.height()} px",
                                                                tagColor = if (part.isConjoined && !part.isCutRejected) Color(0xFF00E5FF) else Color(0xFFFFB300),
                                                                rect = part.bubbleRect
                                                            )
                                                            scope.launch { bubbleListState.animateScrollToItem(hitIdx) }
                                                        } else {
                                                            spotlightRect = null
                                                            inspectedBoxInfo = null
                                                        }
                                                    }
                                                    DebugModule.MODULE_1_5_CATEGORIZE -> {
                                                        val boxes = activePage?.m1_5CategorizedBoxes ?: emptyList()
                                                        val candidates = boxes.filter {
                                                            val r = it.rect
                                                            bx in (r.left - 8)..(r.right + 8) && by in (r.top - 8)..(r.bottom + 8)
                                                        }
                                                        val hit = candidates.minByOrNull {
                                                            val dx = it.rect.centerX() - bx
                                                            val dy = it.rect.centerY() - by
                                                            dx * dx + dy * dy
                                                        }
                                                        if (hit != null) {
                                                            spotlightRect = hit.rect
                                                            val catDesc = when (hit.category) {
                                                                TextCategory.BUBBLED -> "Bubbled Character Box"
                                                                TextCategory.ORPHAN -> "Orphan Dialogue/Narration Box"
                                                                TextCategory.SFX -> "Sound Effect (SFX) Box"
                                                            }
                                                            val catColor = when (hit.category) {
                                                                TextCategory.BUBBLED -> Color(0xFF00E676)
                                                                TextCategory.ORPHAN -> Color(0xFFFFD600)
                                                                TextCategory.SFX -> Color(0xFFFF3D00)
                                                            }
                                                            inspectedBoxInfo = BoxInspectionInfo(
                                                                badgeNumber = "#${hit.id}",
                                                                title = "Text Categorization",
                                                                subtitle = catDesc,
                                                                dimensions = "${hit.rect.width()} × ${hit.rect.height()} px",
                                                                recognizedText = null,
                                                                confidence = "Module 1.5 Classifier",
                                                                tagColor = catColor,
                                                                rect = hit.rect
                                                            )
                                                        } else {
                                                            spotlightRect = null
                                                            inspectedBoxInfo = null
                                                        }
                                                    }
                                                    DebugModule.MODULE_2_LINES -> {
                                                        val vlines = activePage?.m2VerticalLines ?: emptyList()
                                                        val candidates = vlines.filter {
                                                            val r = it.rect
                                                            bx in (r.left - 8)..(r.right + 8) && by in (r.top - 8)..(r.bottom + 8)
                                                        }
                                                        val hit = candidates.minByOrNull {
                                                            val dx = it.rect.centerX() - bx
                                                            val dy = it.rect.centerY() - by
                                                            dx * dx + dy * dy
                                                        }
                                                        if (hit != null) {
                                                            spotlightRect = hit.rect
                                                            val catDesc = when (hit.category) {
                                                                TextCategory.BUBBLED -> "Bubbled Column"
                                                                TextCategory.ORPHAN -> "Orphan Column"
                                                                TextCategory.SFX -> "SFX Column"
                                                            }
                                                            val catColor = when (hit.category) {
                                                                TextCategory.BUBBLED -> Color(0xFF00E676)
                                                                TextCategory.ORPHAN -> Color(0xFFFFD600)
                                                                TextCategory.SFX -> Color(0xFFFF3D00)
                                                            }
                                                            inspectedBoxInfo = BoxInspectionInfo(
                                                                badgeNumber = "#${hit.id}",
                                                                title = "Single Vertical Line",
                                                                subtitle = "$catDesc (Column #${hit.id})",
                                                                dimensions = "${hit.rect.width()} × ${hit.rect.height()} px",
                                                                recognizedText = null,
                                                                confidence = "→ Feeds M3 as single vertical strip",
                                                                tagColor = catColor,
                                                                rect = hit.rect
                                                            )
                                                        } else {
                                                            spotlightRect = null
                                                            inspectedBoxInfo = null
                                                        }
                                                    }
                                                    DebugModule.MODULE_1_CTD -> {
                                                        // 1. Check Pass 1 Lines with 8px proximity tolerance
                                                        val lines = activePage?.m1Lines ?: emptyList()
                                                        val lineHit = lines.filter {
                                                            val r = it.rect
                                                            bx in (r.left - 8)..(r.right + 8) && by in (r.top - 8)..(r.bottom + 8)
                                                        }.minByOrNull {
                                                            val dx = it.rect.centerX() - bx
                                                            val dy = it.rect.centerY() - by
                                                            dx * dx + dy * dy
                                                        }

                                                        // 3. Check legacy Pass 2 Neighbor candidates (only for Line Boxes view in legacy snapshots)
                                                        val p2Passed = activePage?.m1Pass2PassedBoxes ?: emptyList()
                                                        val p2Rejected = activePage?.m1Pass2RejectedBoxes ?: emptyList()
                                                        val p2All = activePage?.m1Pass2Boxes ?: emptyList()
                                                        val p2Candidates = (p2Passed + p2Rejected + p2All).distinct()
                                                        val p2Hit = if (m1SubStep == M1SubStep.LINE_BOXES && lineHit == null) {
                                                            p2Candidates.filter {
                                                                bx in (it.left - 8)..(it.right + 8) && by in (it.top - 8)..(it.bottom + 8)
                                                            }.minByOrNull {
                                                                val dx = it.centerX() - bx
                                                                val dy = it.centerY() - by
                                                                dx * dx + dy * dy
                                                            }
                                                        } else null

                                                        // 4. On-the-fly continuous probability map sampling (for Screen 1.1)
                                                        val rawHeatHit = if (m1SubStep == M1SubStep.HEATMAP && lineHit == null) {
                                                            val pMap = activePage?.m1TextProb
                                                            val bmp = activePage?.sourceBitmap
                                                            if (pMap != null && bmp != null) {
                                                                findHeatmapCellAt(pMap, bmp.width, bmp.height, bx, by)
                                                            } else null
                                                        } else null

                                                        // 5. Speech Bubbles (evaluated LAST so it never intercepts character taps inside bubbles)
                                                        val bubbles = activePage?.m1Bubbles ?: emptyList()
                                                        val bubbleHit = bubbles.firstOrNull { it.contains(bx, by) }

                                                        when {
                                                            lineHit != null -> {
                                                                spotlightRect = lineHit.rect
                                                                val text = activePage?.m1Pass2Texts?.get(lineHit.rect)
                                                                inspectedBoxInfo = BoxInspectionInfo(
                                                                    badgeNumber = "#${lineHit.id}",
                                                                    title = "Pass 1 Text Line",
                                                                    subtitle = "Detected at p ≥ 0.30",
                                                                    dimensions = "${lineHit.rect.width()} × ${lineHit.rect.height()} px",
                                                                    recognizedText = text,
                                                                    confidence = "Global CTD pass",
                                                                    tagColor = Color(0xFF00E676),
                                                                    rect = lineHit.rect
                                                                )
                                                            }
                                                            rawHeatHit != null -> {
                                                                val (cellRect, cellProb) = rawHeatHit
                                                                spotlightRect = cellRect
                                                                inspectedBoxInfo = BoxInspectionInfo(
                                                                    badgeNumber = "#H-raw",
                                                                    title = "Heatmap Character Cell",
                                                                    subtitle = "Heatmap Peak p = ${String.format("%.2f", cellProb)}",
                                                                    dimensions = "${cellRect.width()} × ${cellRect.height()} px",
                                                                    recognizedText = null,
                                                                    confidence = "Direct float seg map",
                                                                    tagColor = Color(0xFFFF9100),
                                                                    rect = cellRect
                                                                )
                                                            }
                                                            p2Hit != null -> {
                                                                spotlightRect = p2Hit
                                                                val pIdx = p2Passed.indexOf(p2Hit)
                                                                val rIdx = p2Rejected.indexOf(p2Hit)
                                                                val aIdx = p2All.indexOf(p2Hit)
                                                                val num = when {
                                                                    pIdx >= 0 -> "P${pIdx + 1}"
                                                                    rIdx >= 0 -> "P${p2Passed.size + rIdx + 1}"
                                                                    aIdx >= 0 -> "P${aIdx + 1}"
                                                                    else -> "P"
                                                                }
                                                                val isPassed = p2Hit in p2Passed
                                                                val text = activePage?.m1Pass2Texts?.get(p2Hit)
                                                                inspectedBoxInfo = BoxInspectionInfo(
                                                                    badgeNumber = "#$num",
                                                                    title = "Candidate Probe Box",
                                                                    subtitle = if (isPassed) "✓ OCR Confirmed" else "Probed Candidate Cell",
                                                                    dimensions = "${p2Hit.width()} × ${p2Hit.height()} px",
                                                                    recognizedText = text,
                                                                    confidence = "Legacy probe box",
                                                                    tagColor = if (isPassed) Color(0xFF00E676) else Color(0xFFFF1744),
                                                                    rect = p2Hit
                                                                )
                                                            }
                                                            bubbleHit != null -> {
                                                                spotlightRect = bubbleHit
                                                                val bIdx = bubbles.indexOf(bubbleHit)
                                                                val num = if (bIdx >= 0) "B${bIdx + 1}" else "B"
                                                                inspectedBoxInfo = BoxInspectionInfo(
                                                                    badgeNumber = "#$num",
                                                                    title = "Speech Bubble",
                                                                    subtitle = "YOLO Detected Bubble",
                                                                    dimensions = "${bubbleHit.width()} × ${bubbleHit.height()} px",
                                                                    tagColor = Color(0xFF00B0FF),
                                                                    rect = bubbleHit
                                                                )
                                                            }
                                                            else -> {
                                                                spotlightRect = null
                                                                inspectedBoxInfo = null
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                spotlightRect = null
                                                inspectedBoxInfo = null
                                            }
                                        }
                                    },
                                    onDoubleTap = {
                                        if (wipeCurtainFraction < 0.99f) {
                                            wipeCurtainFraction = 1.0f
                                        } else if (scale > 1.2f) {
                                            scale = 1f; offset = Offset.Zero
                                        } else {
                                            scale = 2.5f
                                        }
                                    }
                                )
                            }
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        ) {
                            val canvasW = size.width
                            val canvasH = size.height
                            val bmpW = displayBmp.width.toFloat()
                            val bmpH = displayBmp.height.toFloat()

                            val scaleFactor = min(canvasW / bmpW, canvasH / bmpH)
                            val drawW = bmpW * scaleFactor
                            val drawH = bmpH * scaleFactor
                            val drawLeft = (canvasW - drawW) / 2f
                            val drawTop = (canvasH - drawH) / 2f

                            // Draw Base Manga Image
                            val baseImageToDraw = displayBmp

                            if (baseImageToDraw != null && !baseImageToDraw.isRecycled) {
                                drawImage(
                                    image = baseImageToDraw.asImageBitmap(),
                                    dstOffset = IntOffset(drawLeft.toInt(), drawTop.toInt()),
                                    dstSize = IntSize(drawW.toInt(), drawH.toInt())
                                )
                            }

                            fun toCanvasPoint(p: Point): Offset {
                                return Offset(
                                    x = drawLeft + p.x * scaleFactor,
                                    y = drawTop + p.y * scaleFactor
                                )
                            }

                            fun toCanvasRect(r: Rect): androidx.compose.ui.geometry.Rect {
                                val scaleX = drawW / bmpW
                                val scaleY = drawH / bmpH
                                return androidx.compose.ui.geometry.Rect(
                                    left = drawLeft + r.left * scaleX,
                                    top = drawTop + r.top * scaleY,
                                    right = drawLeft + r.right * scaleX,
                                    bottom = drawTop + r.bottom * scaleY,
                                )
                            }

                            // Spotlight Highlight
                            spotlightRect?.let { r ->
                                val cRect = toCanvasRect(r)
                                drawRect(
                                    color = Color(0xFFFFD600),
                                    topLeft = Offset(cRect.left - 4f, cRect.top - 4f),
                                    size = Size(cRect.width + 8f, cRect.height + 8f),
                                    style = Stroke(width = 3.5f)
                                )
                            }

                            // Wipe Curtain Clipping
                            clipRect(
                                left = 0f,
                                top = 0f,
                                right = canvasW * wipeCurtainFraction,
                                bottom = canvasH
                            ) {
                                when (activeModule) {
                                    // ══════════════════ MODULE 1 (CTD) ══════════════════
                                    DebugModule.MODULE_1_CTD -> {
                                        when (m1SubStep) {
                                            M1SubStep.HEATMAP -> {
                                                // Step 1.1: Raw Continuous Probability Heatmap (Preserved)
                                                activePage?.let { p ->
                                                    val hmBmp = getOrCreateHeatmap(p)
                                                    if (hmBmp != null && !hmBmp.isRecycled) {
                                                        drawImage(
                                                            image = hmBmp.asImageBitmap(),
                                                            dstOffset = IntOffset(drawLeft.toInt(), drawTop.toInt()),
                                                            dstSize = IntSize(drawW.toInt(), drawH.toInt())
                                                        )
                                                    }
                                                }
                                            }
                                            M1SubStep.LINE_BOXES -> {
                                                // Step 1.2: Pass 1 Line Bounding Boxes (Green) + YOLO Bubbles (Cyan)
                                                activePage?.m1Lines?.forEach { line ->
                                                    val cRect = toCanvasRect(line.rect)
                                                    drawRect(
                                                        color = Color(0xFF00E676),
                                                        topLeft = Offset(cRect.left, cRect.top),
                                                        size = Size(cRect.width, cRect.height),
                                                        style = Stroke(width = 1.8f)
                                                    )
                                                }

                                                activePage?.m1Bubbles?.forEach { b ->
                                                    val cRect = toCanvasRect(b)
                                                    drawRect(
                                                        color = Color(0xFF00B0FF),
                                                        topLeft = Offset(cRect.left, cRect.top),
                                                        size = Size(cRect.width, cRect.height),
                                                        style = Stroke(
                                                            width = 1.6f,
                                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // ══════════════════ MODULE 1.5 (CATEGORIZATION) ══════════════════
                                    DebugModule.MODULE_1_5_CATEGORIZE -> {
                                        // Background reference bubbles in cyan dashed
                                        activePage?.m1Bubbles?.forEach { b ->
                                            val cb = toCanvasRect(b)
                                            drawRect(
                                                color = Color(0xFF00B0FF),
                                                topLeft = Offset(cb.left, cb.top),
                                                size = Size(cb.width, cb.height),
                                                style = Stroke(width = 1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                                            )
                                        }

                                        val allBoxes = activePage?.m1_5CategorizedBoxes ?: emptyList()
                                        val displayBoxes = when (m1_5SubStep) {
                                            M1_5SubStep.ALL -> allBoxes
                                            M1_5SubStep.BUBBLED -> allBoxes.filter { it.category == TextCategory.BUBBLED }
                                            M1_5SubStep.ORPHAN -> allBoxes.filter { it.category == TextCategory.ORPHAN }
                                            M1_5SubStep.SFX -> allBoxes.filter { it.category == TextCategory.SFX }
                                        }

                                        displayBoxes.forEachIndexed { idx, item ->
                                            val cRect = toCanvasRect(item.rect)
                                            val color = when (item.category) {
                                                TextCategory.BUBBLED -> Color(0xFF00E676) // Green
                                                TextCategory.ORPHAN -> Color(0xFFFFD600)  // Gold
                                                TextCategory.SFX -> Color(0xFFFF3D00)     // Red
                                            }

                                            // Draw bounding box around character box
                                            drawRect(
                                                color = color,
                                                topLeft = Offset(cRect.left, cRect.top),
                                                size = Size(cRect.width, cRect.height),
                                                style = Stroke(width = 2.0f)
                                            )
                                            drawRect(
                                                color = color.copy(alpha = 0.12f),
                                                topLeft = Offset(cRect.left, cRect.top),
                                                size = Size(cRect.width, cRect.height)
                                            )
                                        }
                                    }

                                    // ══════════════════ MODULE 2 (SINGLE VERTICAL LINES) ══════════════════
                                    DebugModule.MODULE_2_LINES -> {
                                        val vLines = activePage?.m2VerticalLines ?: emptyList()
                                        val linePalette = listOf(
                                            Color(0xFF00E5FF), // Cyan
                                            Color(0xFFFFD600), // Yellow
                                            Color(0xFFFF4081), // Pink
                                            Color(0xFF76FF03), // Lime
                                            Color(0xFFFF9100), // Orange
                                            Color(0xFFE040FB), // Purple
                                            Color(0xFF00E676), // Green
                                            Color(0xFF40C4FF), // Light Blue
                                        )

                                        // Background reference bubbles (cyan dashed)
                                        activePage?.m1Bubbles?.forEach { b ->
                                            val cb = toCanvasRect(b)
                                            drawRect(
                                                color = Color(0xFF00B0FF),
                                                topLeft = Offset(cb.left, cb.top),
                                                size = Size(cb.width, cb.height),
                                                style = Stroke(width = 1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                                            )
                                        }

                                        val displayLines = when (m2SubStep) {
                                            M2SubStep.ALL_LINES -> vLines
                                            M2SubStep.BUBBLED_LINES -> vLines.filter { it.category == TextCategory.BUBBLED }
                                            M2SubStep.ORPHAN_LINES -> vLines.filter { it.category == TextCategory.ORPHAN }
                                        }

                                        displayLines.forEachIndexed { idx, line ->
                                            val cRect = toCanvasRect(line.rect)
                                            val color = linePalette[idx % linePalette.size]

                                            // Draw bounding box around continuous single vertical line strip
                                            drawRect(
                                                color = color,
                                                topLeft = Offset(cRect.left, cRect.top),
                                                size = Size(cRect.width, cRect.height),
                                                style = Stroke(width = 2.4f)
                                            )

                                            // Draw subtle translucent tint
                                            drawRect(
                                                color = color.copy(alpha = 0.15f),
                                                topLeft = Offset(cRect.left, cRect.top),
                                                size = Size(cRect.width, cRect.height)
                                            )

                                            // Draw #ID badge pill
                                            drawContext.canvas.nativeCanvas.apply {
                                                val badgeText = "#${line.id}"
                                                val tPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                                    this.color = android.graphics.Color.BLACK
                                                    textSize = 14f
                                                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                                                }
                                                val tW = tPaint.measureText(badgeText)
                                                val pillH = 18f
                                                val pillW = tW + 8f
                                                val bLeft = cRect.left
                                                val bTop = (cRect.top - pillH).coerceAtLeast(drawTop)

                                                val bgP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                                    this.color = color.toArgb()
                                                    style = android.graphics.Paint.Style.FILL
                                                }
                                                val bdrP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                                    this.color = android.graphics.Color.WHITE
                                                    style = android.graphics.Paint.Style.STROKE
                                                    strokeWidth = 1f
                                                }
                                                val rF = android.graphics.RectF(bLeft, bTop, bLeft + pillW, bTop + pillH)
                                                drawRoundRect(rF, 3f, 3f, bgP)
                                                drawRoundRect(rF, 3f, 3f, bdrP)
                                                val textY = bTop + pillH / 2f - (tPaint.descent() + tPaint.ascent()) / 2f
                                                drawText(badgeText, bLeft + 4f, textY, tPaint)
                                            }
                                        }
                                    }

                                    // ══════════════════ MODULE 3 (WAIST CRUNCH) ══════════════════
                                    DebugModule.MODULE_3_CRUNCH -> {
                                        val partitions = activePage?.m3Partitions ?: emptyList()
                                        partitions.forEachIndexed { idx, item ->
                                            val cRect = toCanvasRect(item.bubbleRect)

                                            // Draw bubble boundary
                                            val bubbleColor = when {
                                                item.isCutRejected -> Color(0xFFFF1744)
                                                item.isConjoined -> Color(0xFFD500F9)
                                                else -> Color(0xFF757575)
                                            }
                                            drawRect(
                                                color = bubbleColor,
                                                topLeft = Offset(cRect.left, cRect.top),
                                                size = Size(cRect.width, cRect.height),
                                                style = Stroke(width = 1.8f)
                                            )

                                            if (item.isConjoined) {
                                                // Draw OpenCV concavity defect candidates (Auto-Suggest notches)
                                                if (m3SubStep == M3SubStep.AI_WAIST || m3SubStep == M3SubStep.SNAPPED_CUTLINE) {
                                                    item.candidatePoints.forEach { pt ->
                                                        val cp = toCanvasPoint(pt)
                                                        drawCircle(
                                                            color = Color(0xFF00E5FF),
                                                            radius = 3.5f,
                                                            center = cp,
                                                            style = Stroke(width = 1.5f)
                                                        )
                                                        drawCircle(
                                                            color = Color(0xFF00E5FF).copy(alpha = 0.30f),
                                                            radius = 5.5f,
                                                            center = cp
                                                        )
                                                    }
                                                }

                                                // Draw raw waist keypoints
                                                if (m3SubStep == M3SubStep.AI_WAIST || m3SubStep == M3SubStep.SNAPPED_CUTLINE || m3SubStep == M3SubStep.LOBE_PARTITION) {
                                                    item.p1Raw?.let { p ->
                                                        val cp = toCanvasPoint(p)
                                                        drawCircle(color = Color(0xFFFFD600), radius = 5f, center = cp)
                                                    }
                                                    item.p2Raw?.let { p ->
                                                        val cp = toCanvasPoint(p)
                                                        drawCircle(color = Color(0xFFFFD600), radius = 5f, center = cp)
                                                    }
                                                }

                                                // Draw snapped cutline
                                                if (m3SubStep == M3SubStep.SNAPPED_CUTLINE || m3SubStep == M3SubStep.LOBE_PARTITION) {
                                                    if (item.p1Snapped != null && item.p2Snapped != null) {
                                                        val cp1 = toCanvasPoint(item.p1Snapped)
                                                        val cp2 = toCanvasPoint(item.p2Snapped)
                                                        drawLine(
                                                            color = if (item.isCutRejected) Color(0xFFFF1744) else Color(0xFF00E676),
                                                            start = cp1,
                                                            end = cp2,
                                                            strokeWidth = 3f,
                                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                                                        )
                                                        drawCircle(color = Color(0xFF00E676), radius = 6f, center = cp1)
                                                        drawCircle(color = Color(0xFF00E676), radius = 6f, center = cp2)
                                                    }
                                                }

                                                // Draw lobe partitions
                                                if (m3SubStep == M3SubStep.LOBE_PARTITION) {
                                                    val baseLines = activePage?.m2VerticalLines?.ifEmpty { activePage?.m1Lines } ?: activePage?.m1Lines ?: emptyList()
                                                    val linesMap = baseLines.associateBy { it.id }.toMutableMap()
                                                    item.splitLines.forEach { sl -> linesMap[sl.id] = sl }
                                                    val splitIds = item.splitLines.map { it.id }.toSet()

                                                    item.lobeALineIds.forEach { id ->
                                                        linesMap[id]?.let { line ->
                                                            val lr = toCanvasRect(line.rect)
                                                            val isSplit = id in splitIds
                                                            drawRect(
                                                                color = Color(0xFF2979FF),
                                                                topLeft = Offset(lr.left, lr.top),
                                                                size = Size(lr.width, lr.height),
                                                                style = Stroke(width = if (isSplit) 3.0f else 2.2f)
                                                            )
                                                            drawRect(
                                                                color = Color(0xFF2979FF).copy(alpha = if (isSplit) 0.18f else 0.06f),
                                                                topLeft = Offset(lr.left, lr.top),
                                                                size = Size(lr.width, lr.height)
                                                            )
                                                        }
                                                    }
                                                    item.lobeBLineIds.forEach { id ->
                                                        linesMap[id]?.let { line ->
                                                            val lr = toCanvasRect(line.rect)
                                                            val isSplit = id in splitIds
                                                            drawRect(
                                                                color = Color(0xFFFF4081),
                                                                topLeft = Offset(lr.left, lr.top),
                                                                size = Size(lr.width, lr.height),
                                                                style = Stroke(width = if (isSplit) 3.0f else 2.2f)
                                                            )
                                                            drawRect(
                                                                color = Color(0xFFFF4081).copy(alpha = if (isSplit) 0.22f else 0.08f),
                                                                topLeft = Offset(lr.left, lr.top),
                                                                size = Size(lr.width, lr.height)
                                                            )
                                                        }
                                                    }

                                                    if (item.isCutRejected) {
                                                        drawRect(
                                                            color = Color(0xFFFF1744),
                                                            topLeft = Offset(cRect.left, cRect.top),
                                                            size = Size(cRect.width, cRect.height),
                                                            style = Stroke(
                                                                width = 2.5f,
                                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No image loaded. Use 📁 Manga DB or 🖼️ Pick Custom to begin.", color = Color.Gray, fontSize = 13.sp)
                    }
                }

                // Floating Interactive Box Inspector Card
                if (inspectedBoxInfo != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp, start = 14.dp, end = 14.dp)
                    ) {
                        inspectedBoxInfo?.let { info ->
                            Surface(
                                color = Color(0xF0181818),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, info.tagColor.copy(alpha = 0.7f)),
                                shadowElevation = 8.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { /* absorb taps */ }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            if (info.badgeNumber.isNotBlank()) {
                                                Surface(
                                                    color = info.tagColor,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = info.badgeNumber,
                                                        color = Color.Black,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Black,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(9.dp)
                                                        .background(info.tagColor, CircleShape)
                                                )
                                            }
                                            Text(
                                                text = info.title,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Surface(
                                                color = info.tagColor.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = info.subtitle,
                                                    color = info.tagColor,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                inspectedBoxInfo = null
                                                spotlightRect = null
                                            },
                                            modifier = Modifier.size(22.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Close",
                                                tint = Color.LightGray,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Size: ${info.dimensions}",
                                            color = Color(0xFFAAAAAA),
                                            fontSize = 10.sp
                                        )
                                        if (!info.confidence.isNullOrBlank()) {
                                            Text(
                                                text = "•  ${info.confidence}",
                                                color = Color(0xFFAAAAAA),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                    if (!info.recognizedText.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Surface(
                                            color = Color(0xFF262626),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "「${info.recognizedText}」",
                                                color = Color(0xFF00E5FF),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }
    }

    // ══════════════════ MODALS: DATASET BROWSER ══════════════════
    if (showDatasetBrowser) {
        DatasetBrowserSheet(
            onDismiss = { showDatasetBrowser = false },
            onLoadPages = { files ->
                showDatasetBrowser = false
                loadPagesFromFiles(files)
            }
        )
    }

    // ══════════════════ MODALS: CBZ ARCHIVE BROWSER ══════════════════
    if (showCbzBrowserSheet) {
        CbzBrowserSheet(
            onDismiss = { showCbzBrowserSheet = false },
            onLoadPages = { pageItems ->
                showCbzBrowserSheet = false
                loadPagesFromBitmaps(pageItems)
            }
        )
    }

    // ══════════════════ MODALS: SAVE SNAPSHOT ══════════════════
    if (showSaveSnapshotDialog && activePage != null) {
        AlertDialog(
            onDismissRequest = { showSaveSnapshotDialog = false },
            title = { Text("Save Snapshot for ${activePage.label}", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Saves intermediate state of Modules 1, 2, and 3 so they can be loaded instantly without re-running models.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = saveSlotInput,
                        onValueChange = { saveSlotInput = it },
                        label = { Text("Snapshot Slot Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• Module 1: ${activePage.m1Lines.size} lines, ${activePage.m1Bubbles.size} bubbles", fontSize = 10.sp)
                    Text("• Module 1.5: ${activePage.m1_5CategorizedBoxes.size} categorized", fontSize = 10.sp)
                    Text("• Module 2: ${activePage.m2VerticalLines.size} vertical lines", fontSize = 10.sp)
                    Text("• Module 3: ${activePage.m3Partitions.size} bubble partitions", fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val slot = saveSlotInput.trim().ifEmpty { "snapshot_default" }
                    val snap = DebugModuleSnapshot(
                        slotName = slot,
                        timestamp = System.currentTimeMillis(),
                        module1Lines = activePage.m1Lines,
                        module1Bubbles = activePage.m1Bubbles,
                        module1Pass2Boxes = activePage.m1Pass2Boxes,
                        module1_5CategorizedBoxes = activePage.m1_5CategorizedBoxes,
                        module2VerticalLines = activePage.m2VerticalLines,
                        module2ConjoinedSplitBubbles = activePage.m2ConjoinedSplitBubbles,
                        module3Partitions = activePage.m3Partitions
                    )
                    snapshotManager.saveSnapshot(activePage.label, snap)
                    statusMessage = "💾 Snapshot '$slot' saved successfully!"
                    showSaveSnapshotDialog = false
                }) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveSnapshotDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ══════════════════ MODALS: LOAD SNAPSHOT ══════════════════
    if (showLoadSnapshotDialog && activePage != null) {
        AlertDialog(
            onDismissRequest = { showLoadSnapshotDialog = false },
            title = { Text("Saved Snapshots: ${activePage.label}", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                if (savedSnapshotFiles.isEmpty()) {
                    Text("No snapshots found for this page.", color = Color.Gray, fontSize = 11.sp)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        items(savedSnapshotFiles) { file ->
                            val snap = remember(file) { snapshotManager.loadSnapshot(file) }
                            Surface(
                                color = Color(0xFF262626),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(file.nameWithoutExtension, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White)
                                        if (snap != null) {
                                            Text(
                                                "M1: ${snap.module1Lines.size}L · M1.5: ${snap.module1_5CategorizedBoxes.size}C · M2: ${snap.module2VerticalLines.size}V · M3: ${snap.module3Partitions.size}P",
                                                fontSize = 9.sp,
                                                color = Color(0xFF00E5FF)
                                            )
                                        }
                                    }
                                    TextButton(onClick = {
                                        if (snap != null) {
                                            val updated = activePage.copy(
                                                m1Lines = snap.module1Lines,
                                                m1Bubbles = snap.module1Bubbles,
                                                m1Pass2Boxes = snap.module1Pass2Boxes,
                                                m1_5CategorizedBoxes = snap.module1_5CategorizedBoxes,
                                                m2VerticalLines = snap.module2VerticalLines,
                                                m2ConjoinedSplitBubbles = snap.module2ConjoinedSplitBubbles,
                                                m3Partitions = snap.module3Partitions
                                            )
                                            val list = processedPages.toMutableList()
                                            list[activePageIndex] = updated
                                            processedPages = list
                                            statusMessage = "📂 Loaded snapshot '${file.nameWithoutExtension}'!"
                                            showLoadSnapshotDialog = false
                                        }
                                    }) {
                                        Text("Load", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    IconButton(onClick = {
                                        snapshotManager.deleteSnapshot(file)
                                        savedSnapshotFiles = snapshotManager.listSnapshots(activePage.label)
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLoadSnapshotDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

private fun findHeatmapCellAt(
    probMap: FloatArray,
    imgW: Int,
    imgH: Int,
    bx: Int,
    by: Int,
    minPeakProb: Float = 0.10f
): Pair<Rect, Float>? {
    if (imgW <= 0 || imgH <= 0 || probMap.size < 1024 * 1024) return null
    val scaleX = 1024f / imgW.toFloat()
    val scaleY = 1024f / imgH.toFloat()
    val invScaleX = imgW.toFloat() / 1024f
    val invScaleY = imgH.toFloat() / 1024f

    val cx = (bx * scaleX).toInt().coerceIn(0, 1023)
    val cy = (by * scaleY).toInt().coerceIn(0, 1023)

    var maxP = 0f
    var peakX = cx
    var peakY = cy
    for (dy in -5..5) {
        val ny = (cy + dy).coerceIn(0, 1023)
        val rowOff = ny * 1024
        for (dx in -5..5) {
            val nx = (cx + dx).coerceIn(0, 1023)
            val p = probMap[rowOff + nx]
            if (p > maxP) {
                maxP = p
                peakX = nx
                peakY = ny
            }
        }
    }
    if (maxP < minPeakProb) return null

    val visited = BooleanArray(1024 * 1024)
    val queue = IntArray(4096)
    var qHead = 0
    var qTail = 0
    val startIdx = peakY * 1024 + peakX
    queue[qTail++] = (peakY shl 16) or (peakX and 0xFFFF)
    visited[startIdx] = true

    var minX = peakX
    var maxX = peakX
    var minY = peakY
    var maxY = peakY
    val clusterCutoff = maxOf(0.06f, maxP * 0.35f)

    while (qHead < qTail && qTail < queue.size - 4) {
        val packed = queue[qHead++]
        val px = packed and 0xFFFF
        val py = packed ushr 16

        if (px < minX) minX = px
        if (px > maxX) maxX = px
        if (py < minY) minY = py
        if (py > maxY) maxY = py

        val nxList = intArrayOf(px - 1, px + 1, px, px)
        val nyList = intArrayOf(py, py, py - 1, py + 1)
        for (i in 0..3) {
            val nx = nxList[i]
            val ny = nyList[i]
            if (nx in 0..1023 && ny in 0..1023) {
                val nIdx = ny * 1024 + nx
                if (!visited[nIdx] && probMap[nIdx] >= clusterCutoff) {
                    visited[nIdx] = true
                    queue[qTail++] = (ny shl 16) or (nx and 0xFFFF)
                }
            }
        }
    }

    val rLeft = ((minX - 1) * invScaleX).toInt().coerceAtLeast(0)
    val rTop = ((minY - 1) * invScaleY).toInt().coerceAtLeast(0)
    val rRight = ((maxX + 2) * invScaleX).toInt().coerceAtMost(imgW)
    val rBottom = ((maxY + 2) * invScaleY).toInt().coerceAtMost(imgH)
    return if (rRight - rLeft >= 4 && rBottom - rTop >= 4) {
        Pair(Rect(rLeft, rTop, rRight, rBottom), maxP)
    } else null
}
