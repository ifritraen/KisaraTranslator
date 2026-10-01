package com.raen.kisaratranslator.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.raen.kisaratranslator.core.util.ImageUtils
import com.raen.kisaratranslator.data.model.PageTranslation
import com.raen.kisaratranslator.data.model.TranslationBlock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.raen.kisaratranslator.data.service.TranslationPipelineResult
import com.raen.kisaratranslator.data.service.TranslationService
import com.raen.kisaratranslator.ui.components.BoundingBoxOverlay
import com.raen.kisaratranslator.ui.components.Det1BoxesGallery
import com.raen.kisaratranslator.ui.components.Det2ProbedGallery
import com.raen.kisaratranslator.ui.components.DetectionBoxesOverlay
import com.raen.kisaratranslator.ui.components.createIsolatedBubbleBitmap
import com.raen.kisaratranslator.ui.components.Crunch1MaskWaistOverlay
import com.raen.kisaratranslator.ui.components.CrunchSplitGallery
import com.raen.kisaratranslator.ui.components.CrunchSplitOverlay
import com.raen.kisaratranslator.ui.components.Crunch2DetailedOverlay
import com.raen.kisaratranslator.ui.components.Crunch2InspectionGallery
import com.raen.kisaratranslator.ui.components.Crunch3SeparatedLobesOverlay
import com.raen.kisaratranslator.ui.components.Crunch3LobesGallery
import com.raen.kisaratranslator.ui.components.GroupedLobesOverlay
import com.raen.kisaratranslator.ui.components.LineStripsGallery
import com.raen.kisaratranslator.ui.components.LineStripsOverlay
import com.raen.kisaratranslator.ui.components.Method6ChunksOverlay
import com.raen.kisaratranslator.ui.components.StepComparisonSliderContainer
import com.raen.kisaratranslator.ui.components.NonBubbledCheckGallery
import com.raen.kisaratranslator.ui.components.OcrCropsGallery
import com.raen.kisaratranslator.ui.components.ReadingOrderGallery
import com.raen.kisaratranslator.ui.components.ReadingOrderOverlay
import com.raen.kisaratranslator.ui.components.TargetBoxHighlightOverlay
import com.raen.kisaratranslator.ui.components.UserIgnoreZonesOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.raen.kisaratranslator.data.model.ViewMode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.math.max
import kotlin.math.min
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SinglePageScreen(
    translationService: TranslationService,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val originalBitmap by translationService.originalBitmap.collectAsState()
    val translationResult by translationService.translationResult.collectAsState()
    val isProcessing by translationService.isProcessing.collectAsState()
    val currentStep by translationService.currentStep.collectAsState()
    val config by translationService.config.collectAsState()
    val stepDiagnostics by translationService.stepDiagnostics.collectAsState()
    val viewMode by translationService.currentViewMode.collectAsState()
    val highlightedBox by translationService.highlightedBox.collectAsState()
    val highlightedBoxLabel by translationService.highlightedBoxLabel.collectAsState()
    val userExcludedBoxes by translationService.userExcludedBoxes.collectAsState()

    var isDrawingIgnoreZone by remember { mutableStateOf(false) }
    var dragStartOffset by remember { mutableStateOf<Offset?>(null) }
    var currentDragOffset by remember { mutableStateOf<Offset?>(null) }
    var draftingBox by remember { mutableStateOf<Rect?>(null) }
    var movingBoxOriginal by remember { mutableStateOf<Rect?>(null) }
    var movingBoxCurrent by remember { mutableStateOf<Rect?>(null) }

    val scrollState = rememberScrollState()
    var inspectingBlockIndex by remember { mutableIntStateOf(-1) }
    var editedTranslation by remember { mutableStateOf("") }
    val galleryBringIntoViewRequester = remember { BringIntoViewRequester() }

    val onSelectGalleryBox: (Rect, String) -> Unit = { rect, label ->
        if (highlightedBox == rect) {
            translationService.setHighlightedBox(null, "")
        } else {
            translationService.setHighlightedBox(rect, label)
            scope.launch {
                scrollState.animateScrollTo(0)
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    ImageUtils.decodeBitmapFromUri(context, uri)
                }
                if (bmp != null) {
                    translationService.setOriginalImage(bmp, uri)
                    inspectingBlockIndex = -1
                    translationService.setHighlightedBox(null, "")
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp)
            .verticalScroll(scrollState),
    ) {
        // 1. TOP OF EVERYTHING: Main Image / Empty State
        if (originalBitmap == null) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Select Manga Page", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Auto-detects speech balloons & vertical text", color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { imagePicker.launch("image/*") },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pick Image from Gallery")
                    }
                }
            }
        } else {
            val bmp = originalBitmap!!

            // Image Container at the VERY TOP
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
            ) {
                if (translationResult != null) {
                    StepComparisonSliderContainer(
                        originalBitmap = bmp,
                        stepLabel = viewMode.label,
                        initialSplitFraction = 0.0f,
                        dragEnabled = !isDrawingIgnoreZone,
                        zoomEnabled = !isDrawingIgnoreZone,
                        onTap = { offset, canvasW, canvasH ->
                            if (!isDrawingIgnoreZone && canvasW > 0f && canvasH > 0f && translationResult != null) {
                                val imgX = (offset.x / canvasW * bmp.width).toInt()
                                val imgY = (offset.y / canvasH * bmp.height).toInt()
                                val match = findTappedBox(viewMode, translationResult!!, imgX, imgY)
                                if (match != null) {
                                    val (rect, label) = match
                                    if (highlightedBox == rect) {
                                        translationService.setHighlightedBox(null, "")
                                    } else {
                                        translationService.setHighlightedBox(rect, label)
                                        if (viewMode == ViewMode.TRANSLATED) {
                                            val bIdx = translationResult!!.pageTranslation.blocks.indexOfFirst {
                                                it.x.toInt() == rect.left && it.y.toInt() == rect.top
                                            }
                                            if (bIdx >= 0) {
                                                inspectingBlockIndex = bIdx
                                                editedTranslation = translationResult!!.pageTranslation.blocks[bIdx].translation
                                            }
                                        }
                                        translationService.setQuickInspectExpanded(true)
                                    }
                                } else {
                                    translationService.setHighlightedBox(null, "")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when (viewMode) {
                            ViewMode.DETECTION_1 -> {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Pass 1 Global Detection Boxes",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                            val bubbles = translationResult!!.bubbleBoxes
                            val p1 = translationResult!!.pass1Boxes.ifEmpty { translationResult!!.detectedBoxes }
                            val bubbleText = p1.filter { box -> bubbles.any { b -> b.contains(box.centerX(), box.centerY()) } }
                            val orphanText = p1.filter { box -> bubbles.none { b -> b.contains(box.centerX(), box.centerY()) } }

                            // 1. Draw Bubble Envelopes in Cyan
                            if (bubbles.isNotEmpty()) {
                                DetectionBoxesOverlay(
                                    boxes = bubbles,
                                    imageWidth = bmp.width.toFloat(),
                                    imageHeight = bmp.height.toFloat(),
                                    boxColor = Color(0xFF00E5FF),
                                    fillColor = Color.Transparent,
                                    badgePrefix = "Bubble-",
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            // 2. Draw Inside-Bubble Text in Emerald Green
                            if (bubbleText.isNotEmpty()) {
                                DetectionBoxesOverlay(
                                    boxes = bubbleText,
                                    imageWidth = bmp.width.toFloat(),
                                    imageHeight = bmp.height.toFloat(),
                                    boxColor = Color(0xFF00E676),
                                    fillColor = Color.Transparent,
                                    badgePrefix = "B-",
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            // 3. Draw Orphan Non-Bubble Text in Amber
                            if (orphanText.isNotEmpty()) {
                                DetectionBoxesOverlay(
                                    boxes = orphanText,
                                    imageWidth = bmp.width.toFloat(),
                                    imageHeight = bmp.height.toFloat(),
                                    boxColor = Color(0xFFFF9100),
                                    fillColor = Color.Transparent,
                                    badgePrefix = "Orphan-",
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        ViewMode.DETECTION_2 -> {
                            val p2Bmp = translationResult!!.pass2Bitmap
                            val displayBmp = if (p2Bmp != null && !p2Bmp.isRecycled) p2Bmp else bmp
                            if (!displayBmp.isRecycled) {
                                Image(
                                    bitmap = displayBmp.asImageBitmap(),
                                    contentDescription = "Pass 2 Boost Detection Boxes",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                val p2Passed = translationResult!!.pass2BoxesPassed.ifEmpty { translationResult!!.pass2Boxes }
                                val p2Rejected = translationResult!!.pass2BoxesRejected
                                DetectionBoxesOverlay(
                                    boxes = p2Passed,
                                    imageWidth = displayBmp.width.toFloat(),
                                    imageHeight = displayBmp.height.toFloat(),
                                    boxColor = Color(0xFF00E676),
                                    fillColor = Color.Transparent,
                                    badgePrefix = "✓ ",
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (p2Rejected.isNotEmpty()) {
                                    DetectionBoxesOverlay(
                                        boxes = p2Rejected,
                                        imageWidth = displayBmp.width.toFloat(),
                                        imageHeight = displayBmp.height.toFloat(),
                                        boxColor = Color(0xFFFF1744),
                                        fillColor = Color.Transparent,
                                        badgePrefix = "✗ ",
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                        ViewMode.LINE_STRIPS -> {
                            if (!bmp.isRecycled) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Line Strips",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                LineStripsOverlay(
                                    lines = translationResult!!.lineBoxes.ifEmpty { translationResult!!.detectedBoxes },
                                    imageWidth = bmp.width.toFloat(),
                                    imageHeight = bmp.height.toFloat(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        ViewMode.GROUPING -> {
                            if (!bmp.isRecycled) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Grouped Dialogue Lobes",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                GroupedLobesOverlay(
                                    lobes = translationResult!!.groupedBoxes.ifEmpty { translationResult!!.lineBoxes },
                                    imageWidth = bmp.width.toFloat(),
                                    imageHeight = bmp.height.toFloat(),
                                    nonBubbledCrops = translationResult!!.nonBubbledCrops,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        ViewMode.CRUNCH_1 -> {
                            if (!bmp.isRecycled) {
                                val isolatedBmp = remember<Bitmap?>(bmp, translationResult?.crunchSplits) {
                                    createIsolatedBubbleBitmap(bmp, translationResult?.crunchSplits ?: emptyList(), blankText = true)
                                }
                                val displayBmp = isolatedBmp ?: bmp
                                Image(
                                    bitmap = displayBmp.asImageBitmap(),
                                    contentDescription = "Crunch 1: Clean Polygon Mask & Waist Notches",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Crunch1MaskWaistOverlay(
                                    splits = translationResult!!.crunchSplits,
                                    imageWidth = bmp.width.toFloat(),
                                    imageHeight = bmp.height.toFloat(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        ViewMode.CRUNCH_2 -> {
                            if (!bmp.isRecycled) {
                                val isolatedBmp = remember<Bitmap?>(bmp, translationResult?.crunchSplits) {
                                    createIsolatedBubbleBitmap(bmp, translationResult?.crunchSplits ?: emptyList(), blankText = false)
                                }
                                val displayBmp = isolatedBmp ?: bmp
                                Image(
                                    bitmap = displayBmp.asImageBitmap(),
                                    contentDescription = "Crunch 2: Seam Line & Obstacle Deflections",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Crunch2DetailedOverlay(
                                    splits = translationResult!!.crunchSplits,
                                    imageWidth = bmp.width.toFloat(),
                                    imageHeight = bmp.height.toFloat(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        ViewMode.CRUNCH_3 -> {
                            if (!bmp.isRecycled) {
                                val isolatedBmp = remember<Bitmap?>(bmp, translationResult?.crunchSplits) {
                                    createIsolatedBubbleBitmap(bmp, translationResult?.crunchSplits ?: emptyList(), blankText = false)
                                }
                                val displayBmp = isolatedBmp ?: bmp
                                Image(
                                    bitmap = displayBmp.asImageBitmap(),
                                    contentDescription = "Crunch 3: Separated Lobes & Text Allocation",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Crunch3SeparatedLobesOverlay(
                                    splits = translationResult!!.crunchSplits,
                                    imageWidth = bmp.width.toFloat(),
                                    imageHeight = bmp.height.toFloat(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        ViewMode.OCR_CROPS -> {
                            if (!bmp.isRecycled) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "OCR Chunks Overlay",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                val chunks = translationResult!!.m6ChunkBoxes.ifEmpty { translationResult!!.ocrCrops.map { it.rect } }
                                Method6ChunksOverlay(
                                    chunks = chunks,
                                    imageWidth = bmp.width.toFloat(),
                                    imageHeight = bmp.height.toFloat(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        ViewMode.ORDER_FLOW -> {
                            if (!bmp.isRecycled) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Reading Order Flow",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                ReadingOrderOverlay(
                                    blocks = translationResult!!.pageTranslation.blocks,
                                    imageWidth = bmp.width.toFloat(),
                                    imageHeight = bmp.height.toFloat(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        ViewMode.INPAINTED -> {
                            val inpBmp = translationResult!!.inpaintedBitmap
                            val inpaintedBmp = if (inpBmp != null && !inpBmp.isRecycled) inpBmp else bmp
                            if (!inpaintedBmp.isRecycled) {
                                Image(
                                    bitmap = inpaintedBmp.asImageBitmap(),
                                    contentDescription = "Inpainted Clean Page",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        ViewMode.TRANSLATED -> {
                            val transBmp = translationResult!!.translatedBitmap
                            val displayBmp = if (!transBmp.isRecycled) transBmp else bmp
                            if (!displayBmp.isRecycled) {
                                Image(
                                    bitmap = displayBmp.asImageBitmap(),
                                    contentDescription = "Translated Page",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }

                // Universal Focus Spotlight Overlay when a box is selected from galleries
                if (highlightedBox != null) {
                    TargetBoxHighlightOverlay(
                        targetRect = highlightedBox!!,
                        label = highlightedBoxLabel,
                        imageWidth = bmp.width.toFloat(),
                        imageHeight = bmp.height.toFloat(),
                        onDismiss = {
                            translationService.setHighlightedBox(null, "")
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                } else {
                    StepComparisonSliderContainer(
                        originalBitmap = bmp,
                        stepLabel = "Original",
                        initialSplitFraction = 0.0f,
                        dragEnabled = false,
                        zoomEnabled = !isDrawingIgnoreZone,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Original Page",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // User Ignore (Excluded) Zones Overlay — ONLY visible when in draw/edit mode
                // Placed at the bottom of UI layers on the image canvas
                if (isDrawingIgnoreZone && (userExcludedBoxes.isNotEmpty() || draftingBox != null || movingBoxCurrent != null)) {
                    val displayBoxes = if (movingBoxOriginal != null && movingBoxCurrent != null) {
                        userExcludedBoxes.map { if (it == movingBoxOriginal) movingBoxCurrent!! else it }
                    } else {
                        userExcludedBoxes
                    }
                    UserIgnoreZonesOverlay(
                        excludedBoxes = displayBoxes,
                        imageWidth = bmp.width.toFloat(),
                        imageHeight = bmp.height.toFloat(),
                        draftingBox = draftingBox,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Interactive Drawing & Gesture Surface (active only when drawing mode is enabled)
                if (isDrawingIgnoreZone) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(bmp, userExcludedBoxes) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val scaleX = bmp.width.toFloat() / size.width
                                    val scaleY = bmp.height.toFloat() / size.height
                                    val startImgX = (down.position.x * scaleX).toInt()
                                    val startImgY = (down.position.y * scaleY).toInt()
                                    val hitBox = userExcludedBoxes.lastOrNull { it.contains(startImgX, startImgY) }

                                    if (hitBox != null) {
                                        var wasLongPress = false
                                        var pointerUp = false
                                        try {
                                            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                    if (!change.pressed) {
                                                        pointerUp = true
                                                        change.consume()
                                                        break
                                                    }
                                                }
                                            }
                                        } catch (_: TimeoutCancellationException) {
                                            wasLongPress = true
                                        }

                                        if (pointerUp) {
                                            // Tap on existing box -> delete it
                                            translationService.removeUserExcludedBox(hitBox)
                                        } else if (wasLongPress) {
                                            // Long tap & drag on ignored box -> move the box
                                            movingBoxOriginal = hitBox
                                            var currentRect: Rect = hitBox
                                            movingBoxCurrent = currentRect

                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!change.pressed) {
                                                    change.consume()
                                                    break
                                                }
                                                val delta = change.positionChange()
                                                change.consume()
                                                val dx = (delta.x * scaleX).toInt()
                                                val dy = (delta.y * scaleY).toInt()
                                                val newLeft = (currentRect.left + dx).coerceIn(0, bmp.width - currentRect.width())
                                                val newTop = (currentRect.top + dy).coerceIn(0, bmp.height - currentRect.height())
                                                currentRect = Rect(newLeft, newTop, newLeft + currentRect.width(), newTop + currentRect.height())
                                                movingBoxCurrent = currentRect
                                            }

                                            if (currentRect != hitBox) {
                                                translationService.replaceUserExcludedBox(hitBox, currentRect)
                                            }
                                            movingBoxOriginal = null
                                            movingBoxCurrent = null
                                        }
                                    } else {
                                        // Drag on empty canvas -> draw new exclusion box
                                        val start = down.position
                                        var curr = start
                                        val startLeft = (start.x * scaleX).toInt().coerceIn(0, bmp.width)
                                        val startTop = (start.y * scaleY).toInt().coerceIn(0, bmp.height)
                                        draftingBox = Rect(startLeft, startTop, startLeft, startTop)

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) {
                                                change.consume()
                                                break
                                            }
                                            curr = change.position
                                            change.consume()
                                            val left = (min(start.x, curr.x) * scaleX).toInt().coerceIn(0, bmp.width)
                                            val top = (min(start.y, curr.y) * scaleY).toInt().coerceIn(0, bmp.height)
                                            val right = (max(start.x, curr.x) * scaleX).toInt().coerceIn(0, bmp.width)
                                            val bottom = (max(start.y, curr.y) * scaleY).toInt().coerceIn(0, bmp.height)
                                            draftingBox = Rect(left, top, right, bottom)
                                        }

                                        val finalBox = draftingBox
                                        if (finalBox != null && finalBox.width() >= 12 && finalBox.height() >= 12) {
                                            translationService.addUserExcludedBox(finalBox)
                                        }
                                        draftingBox = null
                                    }
                                }
                            }
                    )
                }
            }

            // Crunch info floating bar OUT of the image at the below of the image (active in step 4b. Crunch 1, 4c. Crunch 2, 4d. Crunch 3)
            if ((viewMode == ViewMode.CRUNCH_1 || viewMode == ViewMode.CRUNCH_2 || viewMode == ViewMode.CRUNCH_3) && translationResult != null && !isDrawingIgnoreZone) {
                Spacer(modifier = Modifier.height(6.dp))
                val origCount = translationResult!!.crunchOriginalBoxes.size
                val lobeCount = translationResult!!.crunchSplitBoxes.size
                val ptCount = translationResult!!.crunchPointsA.size
                val splitsCount = translationResult!!.crunchSplits.size
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        color = Color(0xDD181820),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (origCount > 0 || splitsCount > 0) Color(0xFFFF5252) else Color(0xFF00E676)),
                    ) {
                        val text = when (viewMode) {
                            ViewMode.CRUNCH_1 -> {
                                if (splitsCount > 0) "🔬 4b. Crunch 1: $splitsCount Conjoined Bubbles · Isolated polygon silhouette, blanked text & notch search arrows"
                                else "🔬 4b. Crunch 1: 0 Conjoined Bubbles (All bubbles single convex)"
                            }
                            ViewMode.CRUNCH_2 -> {
                                if (splitsCount > 0) "🔬 4c. Crunch 2: $splitsCount Laser Seams · Obstacle-aware deflection waypoints & text corridor routing"
                                else "🔬 4c. Crunch 2: 0 Conjoined Bubbles (No laser cuts needed)"
                            }
                            ViewMode.CRUNCH_3 -> {
                                if (splitsCount > 0) "✂️ 4d. Crunch 3: $splitsCount Bubbles Separated into $lobeCount Lobes · Lobe 1 (Cyan) & Lobe 2 (Emerald)"
                                else "✓ 4d. Crunch 3: 0 Conjoined Bubbles"
                            }
                            else -> ""
                        }
                        Text(
                            text = text,
                            color = if (origCount > 0 || splitsCount > 0) Color(0xFFFF8A80) else Color(0xFF81C784),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            // Floating instruction bar OUT of the image at the below of the image (active when drawing/editing)
            if (isDrawingIgnoreZone) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1416),
                    border = BorderStroke(1.dp, Color(0xFFFF1744)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Draw,
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Drag to draw • Tap to delete • Long tap & drag to move",
                                color = Color(0xFFFFCDD2),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF00C853),
                            modifier = Modifier.clickable { isDrawingIgnoreZone = false },
                        ) {
                            Text(
                                text = "Done",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. BELOW IMAGE: View Mode Switcher Chips (Step-by-Step Pipeline Selector)
            if (translationResult != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ViewMode.entries.forEach { mode ->
                        FilterChip(
                            selected = viewMode == mode,
                            onClick = {
                                translationService.setViewMode(mode)
                                translationService.setHighlightedBox(null, "")
                            },
                            label = { Text(mode.label, fontSize = 11.sp, fontWeight = if (viewMode == mode) FontWeight.Bold else FontWeight.Normal) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 5. Processing Status Bar
            if (isProcessing) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF263238)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(currentStep, fontSize = 12.sp, color = Color(0xFF80D8FF))
                        }
                        OutlinedButton(
                            onClick = { translationService.cancelTranslation() },
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text("Cancel", fontSize = 11.sp, color = Color(0xFFFF5252))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }



            // 7. Main Action Buttons Row (Translate / Re-Translate + Save + Share + Change Image)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Primary Action Button (Translate / Re-Translate)
                Button(
                    onClick = { translationService.startSinglePageTranslation() },
                    enabled = !isProcessing,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (translationResult == null) MaterialTheme.colorScheme.primary else Color(0xFF00C853),
                    ),
                ) {
                    Icon(
                        imageVector = if (translationResult == null) Icons.Outlined.PlayArrow else Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (translationResult == null) "Translate Page" else "Re-Translate",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }

                // Save to Gallery
                if (translationResult != null) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val saved = saveBitmapToGallery(context, translationResult!!.translatedBitmap)
                                Toast.makeText(
                                    context,
                                    if (saved) "Saved to Pictures/KisaraTranslator" else "Failed to save",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier.height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = "Save", modifier = Modifier.size(18.dp), tint = Color(0xFF00E676))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save", fontSize = 12.sp, color = Color.White)
                    }

                    // Share Image
                    OutlinedButton(
                        onClick = {
                            shareBitmap(context, translationResult!!.translatedBitmap)
                        },
                        modifier = Modifier.height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share", modifier = Modifier.size(18.dp), tint = Color(0xFF00B0FF))
                    }
                }

                // Change Image
                OutlinedButton(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = "Change Image", modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 6. Pipeline Step Controls
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Pipeline Step Controls",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        val totalMs = stepDiagnostics.totalTimeMs
                        Text(
                            text = if (totalMs > 0) "Total: ${formatStepTime(totalMs)}" else "Idle",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (stepDiagnostics.totalTimeMs > 0) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val steps = if (config.effectiveMethod in 6..9) {
                            listOf(
                                Triple(1, "1. Det", formatStepTime(stepDiagnostics.detectionTimeMs)),
                                Triple(2, "2. Lines", formatStepTime(stepDiagnostics.detectionTimeMs)),
                                Triple(3, "3. Group", formatStepTime(stepDiagnostics.groupingTimeMs)),
                                Triple(4, "4b. Crunch 1", formatStepTime(stepDiagnostics.groupingTimeMs)),
                                Triple(5, "4c. Crunch 2", formatStepTime(stepDiagnostics.groupingTimeMs)),
                                Triple(6, "4d. Crunch 3", formatStepTime(stepDiagnostics.groupingTimeMs)),
                                Triple(7, "5. OCR", formatStepTime(stepDiagnostics.ocrTimeMs)),
                                Triple(8, "6. Inpaint", formatStepTime(stepDiagnostics.renderingTimeMs)),
                                Triple(9, "7. Final", formatStepTime(stepDiagnostics.renderingTimeMs)),
                            )
                        } else if (config.effectiveMethod == 5) {
                            listOf(
                                Triple(1, "1. Det", formatStepTime(stepDiagnostics.detectionTimeMs)),
                                Triple(2, "2. Lines", formatStepTime(stepDiagnostics.detectionTimeMs)),
                                Triple(3, "3. Bubbles", formatStepTime(stepDiagnostics.groupingTimeMs)),
                                Triple(4, "4. OCR", formatStepTime(stepDiagnostics.ocrTimeMs)),
                                Triple(5, "5. Inpaint", formatStepTime(stepDiagnostics.renderingTimeMs)),
                                Triple(6, "6. Final", formatStepTime(stepDiagnostics.renderingTimeMs)),
                            )
                        } else if (config.effectiveMethod == 4) {
                            listOf(
                                Triple(1, "1. Det", formatStepTime(stepDiagnostics.detectionTimeMs)),
                                Triple(2, "2. Lines", formatStepTime(stepDiagnostics.detectionTimeMs)),
                                Triple(3, "3. Lobes", formatStepTime(stepDiagnostics.groupingTimeMs)),
                                Triple(4, "4. OCR", formatStepTime(stepDiagnostics.ocrTimeMs)),
                                Triple(5, "5. Inpaint", formatStepTime(stepDiagnostics.renderingTimeMs)),
                                Triple(6, "6. Final", formatStepTime(stepDiagnostics.renderingTimeMs)),
                            )
                        } else {
                            listOf(
                                Triple(1, "1. Detect", formatStepTime(stepDiagnostics.detectionTimeMs)),
                                Triple(2, "2. OCR", formatStepTime(stepDiagnostics.ocrTimeMs)),
                                Triple(3, "3. Group", formatStepTime(stepDiagnostics.groupingTimeMs)),
                                Triple(4, "4. Translate", formatStepTime(stepDiagnostics.translationTimeMs)),
                                Triple(5, "5. Render", formatStepTime(stepDiagnostics.renderingTimeMs)),
                            )
                        }
                        steps.forEach { (stepNum, name, timeStr) ->
                            OutlinedButton(
                                onClick = {
                                    val (pipelineStep, targetView) = if (config.effectiveMethod in 6..9) {
                                        when (stepNum) {
                                            1 -> Pair(1, ViewMode.DETECTION_1)
                                            2 -> Pair(2, ViewMode.LINE_STRIPS)
                                            3 -> Pair(2, ViewMode.GROUPING)
                                            4 -> Pair(2, ViewMode.CRUNCH_1)
                                            5 -> Pair(2, ViewMode.CRUNCH_2)
                                            6 -> Pair(2, ViewMode.CRUNCH_3)
                                            7 -> Pair(2, ViewMode.OCR_CROPS)
                                            8 -> Pair(5, ViewMode.INPAINTED)
                                            9 -> Pair(5, ViewMode.TRANSLATED)
                                            else -> Pair(stepNum, ViewMode.TRANSLATED)
                                        }
                                    } else if (config.effectiveMethod in 4..5) {
                                        when (stepNum) {
                                            1 -> Pair(1, ViewMode.DETECTION_1)
                                            2 -> Pair(2, ViewMode.LINE_STRIPS)
                                            3 -> Pair(2, ViewMode.GROUPING)
                                            4 -> Pair(2, ViewMode.OCR_CROPS)
                                            5 -> Pair(5, ViewMode.INPAINTED)
                                            6 -> Pair(5, ViewMode.TRANSLATED)
                                            else -> Pair(stepNum, ViewMode.TRANSLATED)
                                        }
                                    } else {
                                        when (stepNum) {
                                            1 -> Pair(1, ViewMode.DETECTION_1)
                                            2 -> Pair(2, ViewMode.OCR_CROPS)
                                            3 -> Pair(3, ViewMode.ORDER_FLOW)
                                            4 -> Pair(4, ViewMode.TRANSLATED)
                                            5 -> Pair(5, ViewMode.TRANSLATED)
                                            else -> Pair(stepNum, ViewMode.TRANSLATED)
                                        }
                                    }
                                    translationService.setViewMode(targetView)
                                    translationService.setHighlightedBox(null, "")
                                    translationService.startPipeline(fromStep = pipelineStep, singleStepOnly = false)
                                },
                                enabled = !isProcessing,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(46.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = timeStr,
                                        fontSize = 10.sp,
                                        color = if (timeStr == "—") MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else Color(0xFF00E676),
                                        fontWeight = if (timeStr != "—") FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 4. Contextual Step Galleries (Clicking a card takes directly to that box in View Mode Overlays)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(galleryBringIntoViewRequester)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 4a. Det 1 Boxes & Bubbles Gallery when DETECTION_1 view mode is active
                    if (translationResult != null && viewMode == ViewMode.DETECTION_1) {
                        Det1BoxesGallery(
                            bubbles = translationResult!!.bubbleBoxes,
                            boxes = translationResult!!.pass1Boxes.ifEmpty { translationResult!!.detectedBoxes },
                            selectedRect = highlightedBox,
                            onSelectBox = onSelectGalleryBox,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 4b. Det 2 Probed Candidates Gallery when DETECTION_2 view mode is active
                    if (translationResult != null && viewMode == ViewMode.DETECTION_2) {
                        Det2ProbedGallery(
                            crops = translationResult!!.pass2Crops,
                            selectedRect = highlightedBox,
                            onSelectBox = onSelectGalleryBox,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 4c. Line Strips Gallery when LINE_STRIPS view mode is active
                    if (translationResult != null && viewMode == ViewMode.LINE_STRIPS) {
                        LineStripsGallery(
                            lines = translationResult!!.lineBoxes.ifEmpty { translationResult!!.detectedBoxes },
                            selectedRect = highlightedBox,
                            onSelectBox = onSelectGalleryBox,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 4d. Non-Bubbled Verification Gallery when GROUPING (3. Group / 3. Bubbles) view mode is active
                    if (translationResult != null && viewMode == ViewMode.GROUPING) {
                        NonBubbledCheckGallery(
                            crops = translationResult!!.nonBubbledCrops,
                            selectedRect = highlightedBox,
                            onSelectBox = onSelectGalleryBox,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 4d-2. Crunch 1 Gallery when CRUNCH_1 (4b. Crunch 1) view mode is active
                    if (translationResult != null && viewMode == ViewMode.CRUNCH_1) {
                        CrunchSplitGallery(
                            originalEnvelopes = translationResult!!.crunchOriginalBoxes,
                            splitLobes = translationResult!!.crunchSplitBoxes,
                            selectedRect = highlightedBox,
                            onSelectBox = onSelectGalleryBox,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 4d-3. Crunch 2 Detailed Inspection Gallery when CRUNCH_2 (4c. Crunch 2) view mode is active
                    if (translationResult != null && viewMode == ViewMode.CRUNCH_2) {
                        Crunch2InspectionGallery(
                            splits = translationResult!!.crunchSplits,
                            selectedRect = highlightedBox,
                            onSelectBox = onSelectGalleryBox,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 4d-4. Crunch 3 Separated Lobes Gallery when CRUNCH_3 (4d. Crunch 3) view mode is active
                    if (translationResult != null && viewMode == ViewMode.CRUNCH_3) {
                        Crunch3LobesGallery(
                            splits = translationResult!!.crunchSplits,
                            selectedRect = highlightedBox,
                            onSelectBox = onSelectGalleryBox,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 4e. OCR Crops & Chunks Gallery when OCR_CROPS view mode is active
                    if (translationResult != null && viewMode == ViewMode.OCR_CROPS) {
                        OcrCropsGallery(
                            crops = translationResult!!.ocrCrops,
                            selectedRect = highlightedBox,
                            onSelectBox = onSelectGalleryBox,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 4f. Reading Order Flow Gallery when ORDER_FLOW view mode is active
                    if (translationResult != null && viewMode == ViewMode.ORDER_FLOW) {
                        ReadingOrderGallery(
                            blocks = translationResult!!.pageTranslation.blocks,
                            selectedRect = highlightedBox,
                            onSelectBox = onSelectGalleryBox,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // 4g. Dialogue Translation Inspector when TRANSLATED (8. Final) view mode is active
                    if (translationResult != null && viewMode == ViewMode.TRANSLATED) {
                        DialogueTranslationInspector(
                            pageTranslation = translationResult!!.pageTranslation,
                            selectedRect = highlightedBox,
                            onSelectBlock = { idx, block, rect, label ->
                                inspectingBlockIndex = idx
                                editedTranslation = block.translation
                                onSelectGalleryBox(rect, label)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }

            // 3. Deep Analytical Step Details Card
            if (translationResult != null) {
                DeepStepAnalyticsCard(
                    viewMode = viewMode,
                    result = translationResult!!,
                    config = config,
                    diagnostics = stepDiagnostics,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 5. Bounding Box Live Text Inspector / Editor Sheet
            if (translationResult != null && inspectingBlockIndex in translationResult!!.pageTranslation.blocks.indices) {
                val block = translationResult!!.pageTranslation.blocks[inspectingBlockIndex]
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Bubble # Inspector",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 14.sp,
                            )
                            IconButton(
                                onClick = { inspectingBlockIndex = -1 },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.Gray)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Original OCR:", fontSize = 11.sp, color = Color.Gray)
                        Text(
                            text = block.text,
                            color = Color(0xFF00E676),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Translation:", fontSize = 11.sp, color = Color.Gray)
                        OutlinedTextField(
                            value = editedTranslation,
                            onValueChange = { editedTranslation = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Enter manual translation...") },
                            maxLines = 3,
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    translationService.updateBlockTranslation(inspectingBlockIndex, editedTranslation)
                                    inspectingBlockIndex = -1
                                },
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Apply & Re-render")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Ignore / Exclude Zones Control Card (Placed at the bottom of every row/section)
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDrawingIgnoreZone) Color(0xFF281114)
                                     else if (userExcludedBoxes.isNotEmpty()) Color(0xFF1E1618)
                                     else Color(0xFF1A1A1E)
                ),
                border = BorderStroke(
                    1.dp,
                    if (isDrawingIgnoreZone) Color(0xFFFF1744)
                    else if (userExcludedBoxes.isNotEmpty()) Color(0x66FF5252)
                    else Color(0x22FFFFFF)
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = if (isDrawingIgnoreZone) Icons.Default.Draw else Icons.Default.Block,
                            contentDescription = null,
                            tint = if (isDrawingIgnoreZone) Color(0xFFFF1744) else if (userExcludedBoxes.isNotEmpty()) Color(0xFFFF5252) else Color.Gray,
                            modifier = Modifier.size(18.dp),
                        )
                        Column {
                            Text(
                                text = if (isDrawingIgnoreZone) "Drawing Ignore Area Active"
                                       else if (userExcludedBoxes.isEmpty()) "Ignore Zones (Untouched Art)"
                                       else "Ignore Zones: ${userExcludedBoxes.size} area(s) protected",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDrawingIgnoreZone) Color(0xFFFF5252) else Color.White,
                            )
                            Text(
                                text = if (isDrawingIgnoreZone) "Touch & drag rectangle on image above"
                                       else if (userExcludedBoxes.isEmpty()) "Mark complex art/SFX to bypass translation"
                                       else "These areas remain 100% untouched",
                                fontSize = 10.sp,
                                color = Color.Gray,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (userExcludedBoxes.isNotEmpty()) {
                            // Undo button
                            IconButton(
                                onClick = { translationService.removeLastUserExcludedBox() },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.Undo, contentDescription = "Undo", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            }
                            // Clear All button
                            IconButton(
                                onClick = { translationService.clearUserExcludedBoxes() },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Clear All", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                            }
                        }

                        // Toggle Drawing Button
                        Button(
                            onClick = { isDrawingIgnoreZone = !isDrawingIgnoreZone },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDrawingIgnoreZone) Color(0xFF00C853)
                                                 else if (userExcludedBoxes.isNotEmpty()) Color(0xFFD50000)
                                                 else Color(0xFF2A2A32)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(
                                imageVector = if (isDrawingIgnoreZone) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color.White,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isDrawingIgnoreZone) "Done" else if (userExcludedBoxes.isEmpty()) "Draw" else "Edit",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

private suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
    try {
        val filename = "Kisara_Trans_.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/KisaraTranslator")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                return@withContext true
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "KisaraTranslator")
            dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            return@withContext true
        }
        false
    } catch (e: Exception) {
        false
    }
}

private fun shareBitmap(context: Context, bitmap: Bitmap) {
    try {
        val cachePath = File(context.cacheDir, "images")
        cachePath.mkdirs()
        val stream = FileOutputStream(File(cachePath, "translated_image.png"))
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.close()

        val imagePath = File(context.cacheDir, "images/translated_image.png")
        val contentUri = FileProvider.getUriForFile(context, ".fileprovider", imagePath)

        if (contentUri != null) {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setDataAndType(contentUri, context.contentResolver.getType(contentUri))
                putExtra(Intent.EXTRA_STREAM, contentUri)
                type = "image/png"
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Translated Manga Page"))
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to share image", Toast.LENGTH_SHORT).show()
    }
}

private fun formatStepTime(timeMs: Long): String {
    return when {
        timeMs <= 0L -> "—"
        timeMs < 1000L -> "${timeMs}ms"
        timeMs < 60000L -> String.format(java.util.Locale.US, "%.1fs", timeMs / 1000f)
        else -> {
            val min = timeMs / 60000
            val sec = (timeMs % 60000) / 1000f
            String.format(java.util.Locale.US, "%dm%.0fs", min, sec)
        }
    }
}

@Composable
private fun DeepStepAnalyticsCard(
    viewMode: ViewMode,
    result: com.raen.kisaratranslator.data.service.TranslationPipelineResult,
    config: com.raen.kisaratranslator.data.model.PipelineConfig,
    diagnostics: com.raen.kisaratranslator.data.service.TranslationStepDiagnostics,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2E2E38), RoundedCornerShape(10.dp)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = viewMode.subtitle,
                    color = Color(0xFF80D8FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val badgeText = when (viewMode) {
                    ViewMode.DETECTION_1 -> "${result.pass1Boxes.ifEmpty { result.detectedBoxes }.size} boxes"
                    ViewMode.DETECTION_2 -> "${result.pass2BoxesPassed.size} verified"
                    ViewMode.LINE_STRIPS -> "${result.lineBoxes.size} lines"
                    ViewMode.GROUPING -> if (config.effectiveMethod == 5) "${result.groupedBoxes.size} bubbles" else "${result.groupedBoxes.size} lobes"
                    ViewMode.CRUNCH_1 -> "${result.crunchSplits.size} conjoined bubbles"
                    ViewMode.CRUNCH_2 -> "${result.crunchSplits.size} laser cut seams"
                    ViewMode.CRUNCH_3 -> "${result.crunchSplitBoxes.size} separated lobes"
                    ViewMode.OCR_CROPS -> "${result.m6ChunkBoxes.ifEmpty { result.ocrCrops.map { it.rect } }.size} chunks · ${formatStepTime(diagnostics.ocrTimeMs)}"
                    ViewMode.ORDER_FLOW -> formatStepTime(diagnostics.groupingTimeMs)
                    ViewMode.INPAINTED -> "Inpainted"
                    ViewMode.TRANSLATED -> formatStepTime(diagnostics.translationTimeMs)
                }
                Text(
                    text = badgeText,
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    softWrap = false,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            when (viewMode) {
                ViewMode.DETECTION_1 -> {
                    val mName = when (config.effectiveMethod) {
                        5 -> "Method 6 — Whole Bubble First & 3-Line Chunking"
                        4 -> "Method 5 — Hybrid Lobe & Sweet-Spot OCR"
                        3 -> "Method 4 — Full-Bubble Smart Grouping & Recognition"
                        2 -> "Method 3 — Two-Pass Focused Contrast Boost"
                        1 -> "Method 2 — Enhanced Single-Pass CTD"
                        else -> "Method 1 — Standard ComicTextDetector"
                    }
                    val p1 = result.pass1Boxes.ifEmpty { result.detectedBoxes }
                    MetricRow("Detector Engine", config.detector.displayName)
                    MetricRow("Pipeline Strategy", mName)
                    MetricRow("Pass 1 Text Boxes", "${p1.size} regions (Global)")
                    MetricRow("Candidate Bubble Zones", "${result.bubbleBoxes.size} balloons")
                    MetricRow("Stage 1 Latency", formatStepTime(diagnostics.detectionTimeMs))
                }
                ViewMode.DETECTION_2 -> {
                    val p2Pass = result.pass2BoxesPassed
                    val p2Rej = result.pass2BoxesRejected
                    MetricRow("Probing Status", if (config.probeDet2Neighbors) "Enabled" else "Disabled (Bypassed)")
                    MetricRow("Probing Strategy", "1-Unit Adjacent Cells (*b*) + MangaOCR INT8 (1.8x Zoom)")
                    MetricRow("Probed Candidates", "${result.pass2Crops.size} neighbor cells")
                    MetricRow("Verified Japanese", "${p2Pass.size} extra boxes confirmed")
                    MetricRow("Discarded Noise", "${p2Rej.size} non-text regions rejected")
                }
                ViewMode.LINE_STRIPS -> {
                    val strategy = when (config.effectiveMethod) {
                        5 -> "Method 6 Line Strips (Contiguous vertical columns inside bubble)"
                        4 -> "Method 5 Vertical Line Extraction (Contiguous single-column strips)"
                        3 -> "Method 4 Full-Bubble (Conjoined split, strict fence, panel barriers)"
                        2 -> "Method 3 Gap-Bridging (dy <= 2.5x char height, inside bubble)"
                        1 -> "Method 2 Direct Boxes (no line merging)"
                        else -> "Method 1 Column Pre-Merge (dx <= 1.2w, dy <= 0.6h)"
                    }
                    val lineCount = result.lineBoxes.size
                    val rawCount = result.detectedBoxes.size
                    val label = if (config.effectiveMethod == 3) "Dialogue Units" else "Vertical Line Strips"
                    MetricRow(label, "$lineCount strips (from $rawCount raw boxes)")
                    MetricRow("Extraction Strategy", strategy)
                    MetricRow("Reading Order", if (config.sourceLang.lowercase() in listOf("ja", "zh", "ko")) "RTL (Right-to-Left)" else "LTR")
                }
                ViewMode.GROUPING -> {
                    val lobeCount = result.groupedBoxes.size
                    val lineCount = result.lineBoxes.size
                    val strategy = if (config.effectiveMethod == 5) "Method 6 Whole Bubble First (100% Spatial Containment)"
                                    else "Method 5 Intra-Bubble Column Clustering (Lobe Separation)"
                    val unitLabel = if (config.effectiveMethod == 5) "$lobeCount bubble units (from $lineCount lines)"
                                    else "$lobeCount lobes (from $lineCount lines)"
                    MetricRow("Grouping Strategy", strategy)
                    MetricRow("Grouped Speech Units", unitLabel)
                    if (result.nonBubbledCrops.isNotEmpty()) {
                        val confirmed = result.nonBubbledCrops.count { it.rawText.startsWith("✓") }
                        val rejected = result.nonBubbledCrops.count { it.rawText.startsWith("✗") }
                        MetricRow("Non-Bubbled Verification", "$confirmed text, $rejected dropped noise")
                    } else if (!config.probeStep3Orphans) {
                        MetricRow("Non-Bubbled Verification", "Disabled (All orphan lines retained)")
                    }
                    MetricRow("Joined Bubble Splitting", "Automatic (Separates conjoined speech bubbles)")
                    MetricRow("Overlap Prevention", "Mathematical 0-Pixel Overlap Guarantee")
                }
                ViewMode.CRUNCH_1 -> {
                    val conjoinedCount = result.crunchOriginalBoxes.size
                    val lobeCount = result.crunchSplitBoxes.size
                    val strategy = when (config.effectiveMethod) {
                        9 -> "Method 8 v3 YOLO11n-seg + Pure Border Angle (Zero Fallback)"
                        8 -> "Method 8 v2 YOLO11n-seg + Laser Contour Waist Cut"
                        7 -> "Method 8 v1 YOLO11n-seg + Distance Transform Watershed"
                        6 -> "Method 7 Pure Geometric Crunch + Distance Transform Watershed"
                        else -> "Geometric Saddle-Point Constriction Split"
                    }
                    MetricRow("Inspection Mode", "Screen 4b: Clean Polygon Mask & Waist Notches")
                    MetricRow("Splitting Strategy", strategy)
                    MetricRow("Conjoined Bubbles", if (conjoinedCount > 0) "$conjoinedCount envelopes detected" else "0 (No conjoined bubbles)")
                    MetricRow("Crunch Points A-B", "${result.crunchPointsA.size} constriction pairs detected")
                    MetricRow("Waist Scanning", "5-point moving average W(y) smoothed profile")
                    MetricRow("Notch Search Window", "±10px neighborhood with inward search arrows")
                    MetricRow("Stage 3 Latency", formatStepTime(diagnostics.groupingTimeMs))
                }
                ViewMode.CRUNCH_2 -> {
                    val splits = result.crunchSplits
                    MetricRow("Inspection Mode", "Screen 4c: Deep Waist & Laser Seam Breakdown")
                    MetricRow("Obstacle Avoidance", "Intersection check with inside text lines")
                    MetricRow("Total Splits Inspected", "${splits.size} conjoined bubbles")
                    val clashingTotal = splits.sumOf { it.clashingLines.size }
                    MetricRow("Clashing Obstacles", if (clashingTotal > 0) "$clashingTotal lines deflected around" else "0 (Clean cut across corridor)")
                    val strategies = splits.mapNotNull { it.cutStrategy }.distinct().joinToString(", ").ifEmpty { "Laser Cut" }
                    MetricRow("Applied Strategies", strategies)
                    MetricRow("Stage 3 Latency", formatStepTime(diagnostics.groupingTimeMs))
                }
                ViewMode.CRUNCH_3 -> {
                    val lobeCount = result.crunchSplitBoxes.size
                    MetricRow("Inspection Mode", "Screen 4d: Separated Lobes & Text Allocation")
                    MetricRow("Separated Lobes", if (lobeCount > 0) "$lobeCount distinct lobes produced" else "0 lobes")
                    MetricRow("Lobe 1 Allocation", "Cyan boundary + assigned dialogue text units")
                    MetricRow("Lobe 2 Allocation", "Emerald boundary + assigned dialogue text units")
                    MetricRow("Overlap Prevention", "Mathematical 0-Pixel Overlap Guarantee")
                    MetricRow("Stage 3 Latency", formatStepTime(diagnostics.groupingTimeMs))
                }
                ViewMode.OCR_CROPS -> {
                    val chunkCount = result.m6ChunkBoxes.ifEmpty { result.ocrCrops.map { it.rect } }.size
                    val charCount = result.ocrCrops.sumOf { it.rawText.replace(" ", "").length }
                    val avgMs = if (result.ocrCrops.isNotEmpty()) diagnostics.ocrTimeMs / kotlin.math.max(1, result.ocrCrops.size) else 0L
                    val modeLabel = if (config.effectiveMethod == 5) "Method 6 Internal Line Chunking (≤${config.chunkLinesCount} lines/chunk)"
                                    else if (config.effectiveMethod == 4) "Dynamic Sweet-Spot (≤3 lines crop, >3 lines chunking)"
                                    else "Standard OCR"
                    MetricRow("OCR Recognizer", config.ocr.displayName)
                    MetricRow("Chunking Strategy", modeLabel)
                    MetricRow("Total OCR Chunks", "$chunkCount chunks (≤${config.chunkLinesCount} lines per chunk)")
                    MetricRow("Recognized Text", "$charCount characters total")
                    MetricRow("Decode Speed", "$avgMs ms/chunk average")
                    MetricRow("Int8 Optimization", if (config.ocr == com.raen.kisaratranslator.data.model.OcrType.MANGA_OCR_INT8) "Enabled (Quantized ONNX)" else "Standard")
                    MetricRow("Stage 2 Latency", formatStepTime(diagnostics.ocrTimeMs))
                }
                ViewMode.ORDER_FLOW -> {
                    val blocks = result.pageTranslation.blocks
                    val avgLines = if (blocks.isNotEmpty()) result.lineBoxes.size.toFloat() / blocks.size else 0f
                    MetricRow("Grouped Dialogue Bubbles", "${blocks.size} blocks")
                    MetricRow("Reading Order Flow", "Manga RTL (Top-Right -> Bottom-Left)")
                    MetricRow("Average Lines / Bubble", String.format(Locale.US, "%.1f lines", avgLines))
                    MetricRow("Stage 3 Latency", formatStepTime(diagnostics.groupingTimeMs))
                }
                ViewMode.INPAINTED -> {
                    val inpaintBmp = result.inpaintedBitmap
                    MetricRow("Inpaint Engine", "Centroid-Masked V2 Inpainting")
                    MetricRow("Background Mode", if (config.fillBubbleBackground) "Auto-fill Bubble Tone" else "Artwork Preservation (Masked)")
                    MetricRow("Canvas Resolution", "${inpaintBmp?.width ?: 0}x${inpaintBmp?.height ?: 0} px")
                    MetricRow("Stage 5 Latency", formatStepTime(diagnostics.renderingTimeMs))
                }
                ViewMode.TRANSLATED -> {
                    val blocks = result.pageTranslation.blocks
                    val bmp = result.originalBitmap
                    MetricRow("Image Resolution", "${bmp.width}x${bmp.height} px")
                    MetricRow("Translator Engine", config.translator.displayName)
                    MetricRow("Sugoi / SLM Mode", if (config.translator == com.raen.kisaratranslator.data.model.TranslatorType.SUGOI_ONNX) if (config.sugoiBeamWidth <= 1) "⚡ Fast (Greedy)" else "✨ Quality (Beam 2)" else "Direct SLM")
                    MetricRow("Translated Blocks", "${blocks.size} dialogue sentences")
                    MetricRow("Font Scale Multiplier", "${config.textScaleFactor}x")
                    MetricRow("Translation Latency", formatStepTime(diagnostics.translationTimeMs))
                    MetricRow("Typography Latency", formatStepTime(diagnostics.renderingTimeMs))
                    MetricRow("Total Pipeline Duration", formatStepTime(diagnostics.totalTimeMs))
                }
            }
        }
    }
}

@Composable
private fun DialogueTranslationInspector(
    pageTranslation: PageTranslation,
    selectedRect: Rect? = null,
    onSelectBlock: (Int, TranslationBlock, Rect, String) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Dialogue Translation Inspector",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${pageTranslation.blocks.size} blocks",
                    fontSize = 11.sp,
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.SemiBold,
                    softWrap = false,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (pageTranslation.blocks.isEmpty()) {
                Text("No translated dialogue blocks available.", fontSize = 11.sp, color = Color.Gray)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((idx, block) in pageTranslation.blocks.withIndex()) {
                        val blockRect = Rect(block.x.toInt(), block.y.toInt(), (block.x + block.width).toInt(), (block.y + block.height).toInt())
                        val isSelected = selectedRect == blockRect
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF2A364F) else Color(0xFF1E293B)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(0xFFFFEA00) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .clickable { onSelectBlock(idx, block, blockRect, "Block #${idx + 1}") },
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "#${idx + 1} (${block.x.toInt()}, ${block.y.toInt()} - ${block.width.toInt()}x${block.height.toInt()})",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF80D8FF),
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (block.isBubble) {
                                        Text(
                                            text = "Bubble",
                                            fontSize = 9.sp,
                                            color = Color(0xFF00E676),
                                            fontWeight = FontWeight.Medium,
                                            softWrap = false,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Fed (JA): ",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFFB74D),
                                    )
                                    Text(
                                        text = block.text.ifBlank { "(empty)" },
                                        fontSize = 11.sp,
                                        color = Color(0xFFECEFF1),
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Got (EN): ",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF81C784),
                                    )
                                    Text(
                                        text = block.translation.ifBlank { "(empty)" },
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium,
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

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 11.sp,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(0.58f)
                .padding(start = 8.dp),
        )
    }
}

/**
 * Resolves which bounding box on the manga canvas was tapped at (imgX, imgY) for the active ViewMode.
 * Returns the target Rect and its descriptive badge label, or null if empty space was tapped.
 */
private fun findTappedBox(
    viewMode: ViewMode,
    result: TranslationPipelineResult,
    imgX: Int,
    imgY: Int,
): Pair<Rect, String>? {
    val tolerance = 8
    fun Rect.hitTest(x: Int, y: Int): Boolean {
        return x in (left - tolerance)..(right + tolerance) && y in (top - tolerance)..(bottom + tolerance)
    }

    return when (viewMode) {
        ViewMode.DETECTION_1 -> {
            val bubbles = result.bubbleBoxes
            val p1 = result.pass1Boxes.ifEmpty { result.detectedBoxes }
            val orphanText = p1.filter { box -> bubbles.none { b -> b.contains(box.centerX(), box.centerY()) } }
            val hitOrphan = orphanText.filter { it.hitTest(imgX, imgY) }
                .minByOrNull { it.width() * it.height() }
            if (hitOrphan != null) {
                val idx = orphanText.indexOf(hitOrphan)
                Pair(hitOrphan, "Orphan #${idx + 1}")
            } else {
                val hitBubble = bubbles.filter { it.hitTest(imgX, imgY) }
                    .minByOrNull { it.width() * it.height() }
                if (hitBubble != null) {
                    val idx = bubbles.indexOf(hitBubble)
                    Pair(hitBubble, "Bubble #${idx + 1}")
                } else null
            }
        }
        ViewMode.DETECTION_2 -> {
            val crops = result.pass2Crops
            val hitCrop = crops.filter { it.rect.hitTest(imgX, imgY) }
                .minByOrNull { it.rect.width() * it.rect.height() }
            if (hitCrop != null) {
                val idx = crops.indexOf(hitCrop)
                Pair(hitCrop.rect, "Probed #${idx + 1}")
            } else {
                val p2All = result.pass2BoxesPassed + result.pass2BoxesRejected
                val hitP2 = p2All.filter { it.hitTest(imgX, imgY) }
                    .minByOrNull { it.width() * it.height() }
                hitP2?.let { Pair(it, "Probed Box") }
            }
        }
        ViewMode.LINE_STRIPS -> {
            val lines = result.lineBoxes.ifEmpty { result.detectedBoxes }
            val hitLine = lines.filter { it.hitTest(imgX, imgY) }
                .minByOrNull { it.width() * it.height() }
            if (hitLine != null) {
                val idx = lines.indexOf(hitLine)
                Pair(hitLine, "Line #${idx + 1}")
            } else null
        }
        ViewMode.GROUPING -> {
            val crops = result.nonBubbledCrops
            val hitCrop = crops.filter { it.rect.hitTest(imgX, imgY) }
                .minByOrNull { it.rect.width() * it.rect.height() }
            if (hitCrop != null) {
                val idx = crops.indexOf(hitCrop)
                val label = if (hitCrop.rawText.startsWith("✓")) "Line #${idx + 1}" else "Noise #${idx + 1}"
                Pair(hitCrop.rect, label)
            } else {
                val lobes = result.groupedBoxes.ifEmpty { result.lineBoxes }
                val hitLobe = lobes.filter { it.hitTest(imgX, imgY) }
                    .minByOrNull { it.width() * it.height() }
                if (hitLobe != null) {
                    val idx = lobes.indexOf(hitLobe)
                    Pair(hitLobe, "Group #${idx + 1}")
                } else null
            }
        }
        ViewMode.CRUNCH_1 -> {
            val splits = result.crunchSplits
            val origs = splits.mapNotNull { it.originalRect }
            val hitOrig = origs.filter { it.hitTest(imgX, imgY) }
                .minByOrNull { it.width() * it.height() }
            if (hitOrig != null) {
                val idx = origs.indexOf(hitOrig)
                Pair(hitOrig, "Conjoined #${idx + 1}")
            } else {
                val splitLobes = result.crunchSplitBoxes
                val hitLobe = splitLobes.filter { it.hitTest(imgX, imgY) }
                    .minByOrNull { it.width() * it.height() }
                if (hitLobe != null) {
                    val idx = splitLobes.indexOf(hitLobe)
                    Pair(hitLobe, "✂️ Lobe #${idx + 1}")
                } else null
            }
        }
        ViewMode.CRUNCH_2 -> {
            val splits = result.crunchSplits
            val allClashes = splits.flatMap { it.clashingLines }
            val hitClash = allClashes.filter { it.hitTest(imgX, imgY) }
                .minByOrNull { it.width() * it.height() }
            if (hitClash != null) {
                Pair(hitClash, "⚠️ Text Clash")
            } else {
                val splitLobes = splits.flatMap { it.splitLobeRects }
                val hitLobe = splitLobes.filter { it.hitTest(imgX, imgY) }
                    .minByOrNull { it.width() * it.height() }
                if (hitLobe != null) {
                    val idx = splitLobes.indexOf(hitLobe)
                    Pair(hitLobe, "✂️ Lobe #${idx + 1}")
                } else {
                    val origs = splits.mapNotNull { it.originalRect }
                    val hitOrig = origs.filter { it.hitTest(imgX, imgY) }
                        .minByOrNull { it.width() * it.height() }
                    if (hitOrig != null) {
                        val idx = origs.indexOf(hitOrig)
                        Pair(hitOrig, "Conjoined #${idx + 1}")
                    } else null
                }
            }
        }
        ViewMode.CRUNCH_3 -> {
            val splits = result.crunchSplits
            val splitLobes = splits.flatMap { it.splitLobeRects }.ifEmpty { result.crunchSplitBoxes }
            val hitLobe = splitLobes.filter { it.hitTest(imgX, imgY) }
                .minByOrNull { it.width() * it.height() }
            if (hitLobe != null) {
                val idx = splitLobes.indexOf(hitLobe)
                Pair(hitLobe, "✂️ Lobe #${idx + 1}")
            } else {
                val allInside = splits.flatMap { it.insideLines }
                val hitText = allInside.filter { it.hitTest(imgX, imgY) }
                    .minByOrNull { it.width() * it.height() }
                if (hitText != null) {
                    val idx = allInside.indexOf(hitText)
                    Pair(hitText, "Text #${idx + 1}")
                } else null
            }
        }
        ViewMode.OCR_CROPS -> {
            val crops = result.ocrCrops
            val hitCrop = crops.filter { it.rect.hitTest(imgX, imgY) }
                .minByOrNull { it.rect.width() * it.rect.height() }
            if (hitCrop != null) {
                val idx = crops.indexOf(hitCrop)
                Pair(hitCrop.rect, "Chunk #${idx + 1}")
            } else null
        }
        ViewMode.ORDER_FLOW -> {
            val blocks = result.pageTranslation.blocks
            val hitBlock = blocks.filter {
                Rect(it.x.toInt(), it.y.toInt(), (it.x + it.width).toInt(), (it.y + it.height).toInt()).hitTest(imgX, imgY)
            }.minByOrNull { it.width * it.height }
            if (hitBlock != null) {
                val idx = blocks.indexOf(hitBlock)
                val rect = Rect(hitBlock.x.toInt(), hitBlock.y.toInt(), (hitBlock.x + hitBlock.width).toInt(), (hitBlock.y + hitBlock.height).toInt())
                Pair(rect, "Flow #${idx + 1}")
            } else null
        }
        ViewMode.TRANSLATED -> {
            val blocks = result.pageTranslation.blocks
            val hitBlock = blocks.filter {
                Rect(it.x.toInt(), it.y.toInt(), (it.x + it.width).toInt(), (it.y + it.height).toInt()).hitTest(imgX, imgY)
            }.minByOrNull { it.width * it.height }
            if (hitBlock != null) {
                val idx = blocks.indexOf(hitBlock)
                val rect = Rect(hitBlock.x.toInt(), hitBlock.y.toInt(), (hitBlock.x + hitBlock.width).toInt(), (hitBlock.y + hitBlock.height).toInt())
                Pair(rect, "Block #${idx + 1}")
            } else null
        }
        ViewMode.INPAINTED -> null
    }
}