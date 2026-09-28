package com.raen.method3.annotator.ui

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raen.method3.annotator.data.AnnotationPhase
import com.raen.method3.annotator.data.CrunchPointAnnotation
import com.raen.method3.annotator.data.DividingLineAnnotation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun AnnotationCanvas(
    bitmap: Bitmap?,
    phase: AnnotationPhase,
    crunchPoints: List<CrunchPointAnnotation>,
    selectedPointIndex: Int?,
    onSelectPoint: (Int?) -> Unit,
    onAddCrunchPoint: (CrunchPointAnnotation) -> Unit,
    onUpdateCrunchPoint: (Int, CrunchPointAnnotation) -> Unit,
    onDeletePoint: (Int) -> Unit,
    dividingLines: List<DividingLineAnnotation>,
    onSetDividingLine: (DividingLineAnnotation) -> Unit,
    candidatePoints: List<Point> = emptyList(),
    onCandidatePointTapped: ((Point) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Drag drafting state for Crunch Point Bounding Box
    var dragStartImg by remember { mutableStateOf<Offset?>(null) }
    var dragCurrentImg by remember { mutableStateOf<Offset?>(null) }

    // Active line drafting points
    val currentLinePoints = remember { mutableStateListOf<Point>() }

    // Track active dragging of an existing point
    var draggingPointIndex by remember { mutableStateOf<Int?>(null) }
    var pointDragOffset by remember { mutableStateOf(Offset.Zero) }

    // Reset transform when bitmap changes
    LaunchedEffect(bitmap) {
        scale = 1f
        offset = Offset.Zero
        dragStartImg = null
        dragCurrentImg = null
        draggingPointIndex = null
        currentLinePoints.clear()
        onSelectPoint(null)
    }

    val currentCrunchPoints by rememberUpdatedState(crunchPoints)
    val currentSelectedPointIndex by rememberUpdatedState(selectedPointIndex)
    val currentPhase by rememberUpdatedState(phase)
    val currentOnSelectPoint by rememberUpdatedState(onSelectPoint)
    val currentOnAddCrunchPoint by rememberUpdatedState(onAddCrunchPoint)
    val currentOnUpdateCrunchPoint by rememberUpdatedState(onUpdateCrunchPoint)
    val currentOnSetDividingLine by rememberUpdatedState(onSetDividingLine)
    val currentCandidatePoints by rememberUpdatedState(candidatePoints)
    val currentOnCandidatePointTapped by rememberUpdatedState(onCandidatePointTapped)

    val imgBitmap = remember(bitmap) { bitmap?.asImageBitmap() }

    Box(
        modifier = modifier
            .background(Color(0xFF141419))
            .pointerInput(bitmap) {
                if (bitmap == null) return@pointerInput

                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    var lastScreenPos = firstDown.position
                    val downScreenPos = firstDown.position
                    var isMultiTouch = false

                    // Screen to image coordinate of initial touch
                    val (downImgX, downImgY) = screenToImage(
                        downScreenPos,
                        size,
                        bitmap.width,
                        bitmap.height,
                        scale,
                        offset
                    )

                    // Screen-space hit test for existing crunch points ONLY in CRUNCH_POINTS phase
                    val hitIdx = if (currentPhase == AnnotationPhase.CRUNCH_POINTS) {
                        findHitPointIndexScreen(
                            downScreenPos,
                            currentCrunchPoints,
                            size,
                            bitmap.width,
                            bitmap.height,
                            scale,
                            offset,
                            touchSlopPx = 36f
                        )
                    } else {
                        -1
                    }

                    var dragPointInitialRect: Rect? = null
                    var dragPointStartImg: Offset = Offset(downImgX, downImgY)

                    if (hitIdx != -1) {
                        draggingPointIndex = hitIdx
                        currentOnSelectPoint(hitIdx)
                        dragPointInitialRect = currentCrunchPoints[hitIdx].rect
                    } else {
                        // Tapped empty space
                        if (currentPhase == AnnotationPhase.CRUNCH_POINTS) {
                            var tappedCand: Point? = null
                            for (cand in currentCandidatePoints) {
                                val candScreen = imageToScreen(cand.x.toFloat(), cand.y.toFloat(), size, bitmap.width, bitmap.height, scale, offset)
                                val dist = kotlin.math.hypot(downScreenPos.x - candScreen.x, downScreenPos.y - candScreen.y)
                                if (dist < 32f) {
                                    tappedCand = cand
                                    break
                                }
                            }

                            if (tappedCand != null) {
                                currentOnCandidatePointTapped?.invoke(tappedCand)
                            } else if (currentCrunchPoints.size < 2) {
                                dragStartImg = Offset(downImgX, downImgY)
                                dragCurrentImg = Offset(downImgX, downImgY)
                            } else {
                                // Tapping outside deselects
                                currentOnSelectPoint(null)
                            }
                        } else if (currentPhase == AnnotationPhase.DIVIDING_LINE) {
                            dragStartImg = Offset(downImgX, downImgY)
                            dragCurrentImg = Offset(downImgX, downImgY)
                            currentLinePoints.clear()
                            currentLinePoints.add(Point(downImgX.toInt(), downImgY.toInt()))
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val activePointers = event.changes.filter { it.pressed }
                        if (activePointers.isEmpty()) break

                        if (activePointers.size >= 2) {
                            // MULTI-TOUCH: 2-FINGER PINCH-TO-ZOOM & PAN
                            isMultiTouch = true
                            dragStartImg = null
                            dragCurrentImg = null
                            draggingPointIndex = null
                            dragPointInitialRect = null

                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)

                            if (zoom != 1f || pan != Offset.Zero) {
                                val centerCanvas = Offset(size.width / 2f, size.height / 2f)
                                val cRel = centroid - centerCanvas
                                val oldScale = scale
                                val newScale = (oldScale * zoom).coerceIn(0.5f, 30f)
                                val ratio = newScale / oldScale
                                offset = (offset - cRel) * ratio + cRel + pan
                                scale = newScale
                            }
                            event.changes.forEach { it.consume() }
                        } else if (activePointers.size == 1 && !isMultiTouch) {
                            // SINGLE-TOUCH: DRAGGING / DRAFTING / ADJUSTING
                            val change = activePointers.first()
                            change.consume()
                            val screenPos = change.position

                            val (imgX, imgY) = screenToImage(
                                screenPos,
                                size,
                                bitmap.width,
                                bitmap.height,
                                scale,
                                offset
                            )

                            val activeIdx = draggingPointIndex
                            val initialRect = dragPointInitialRect

                            if (activeIdx != null && initialRect != null && activeIdx in currentCrunchPoints.indices) {
                                // ADJUSTING EXISTING POINT: smooth drift-free movement from start
                                val totalDx = imgX - dragPointStartImg.x
                                val totalDy = imgY - dragPointStartImg.y
                                val boxW = initialRect.width()
                                val boxH = initialRect.height()

                                val maxLeft = max(0, bitmap.width - boxW)
                                val maxTop = max(0, bitmap.height - boxH)

                                val newLeft = (initialRect.left + totalDx.roundToInt()).coerceIn(0, maxLeft)
                                val newTop = (initialRect.top + totalDy.roundToInt()).coerceIn(0, maxTop)
                                val newRight = newLeft + boxW
                                val newBottom = newTop + boxH
                                var finalLeft = newLeft
                                var finalTop = newTop
                                var finalCenter = Point((newLeft + newRight) / 2, (newTop + newBottom) / 2)

                                // Magnetic snap to candidate points
                                for (cand in currentCandidatePoints) {
                                    val distImg = kotlin.math.hypot((finalCenter.x - cand.x).toFloat(), (finalCenter.y - cand.y).toFloat())
                                    if (distImg * scale < 24.dp.toPx()) {
                                        finalCenter = Point(cand.x, cand.y)
                                        finalLeft = (cand.x - boxW / 2).coerceIn(0, maxLeft)
                                        finalTop = (cand.y - boxH / 2).coerceIn(0, maxTop)
                                        break
                                    }
                                }

                                val p = currentCrunchPoints[activeIdx]
                                currentOnUpdateCrunchPoint(
                                    activeIdx,
                                    p.copy(
                                        rect = Rect(finalLeft, finalTop, finalLeft + boxW, finalTop + boxH),
                                        center = finalCenter
                                    )
                                )
                            } else if (currentPhase == AnnotationPhase.CRUNCH_POINTS) {
                                if (dragStartImg != null) {
                                    dragCurrentImg = Offset(imgX, imgY)
                                } else if (currentCrunchPoints.size >= 2 && scale > 1.05f) {
                                    // 1-finger pan when zoomed in and notches are already placed
                                    offset += screenPos - lastScreenPos
                                }
                            } else if (currentPhase == AnnotationPhase.DIVIDING_LINE) {
                                dragCurrentImg = Offset(imgX, imgY)
                                val s = dragStartImg
                                if (s != null) {
                                    currentLinePoints.clear()
                                    currentLinePoints.add(Point(s.x.toInt(), s.y.toInt()))
                                    currentLinePoints.add(Point(imgX.toInt(), imgY.toInt()))
                                }
                            } else if (currentPhase == AnnotationPhase.DECISION && scale > 1.05f) {
                                // 1-finger pan in decision phase when zoomed in
                                offset += screenPos - lastScreenPos
                            }

                            lastScreenPos = screenPos
                        }
                    }

                    // On Gesture Finish
                    if (!isMultiTouch) {
                        if (draggingPointIndex != null) {
                            draggingPointIndex = null
                            dragPointInitialRect = null
                        } else if (currentPhase == AnnotationPhase.CRUNCH_POINTS) {
                            val s = dragStartImg
                            val c = dragCurrentImg
                            if (s != null && c != null) {
                                val x1 = min(s.x, c.x).toInt().coerceIn(0, bitmap.width)
                                val y1 = min(s.y, c.y).toInt().coerceIn(0, bitmap.height)
                                val x2 = max(s.x, c.x).toInt().coerceIn(0, bitmap.width)
                                val y2 = max(s.y, c.y).toInt().coerceIn(0, bitmap.height)

                                if (x2 - x1 >= 4 && y2 - y1 >= 4) {
                                    val id = currentCrunchPoints.size + 1
                                    val label = if (id == 1) "crunch_a" else if (id == 2) "crunch_b" else "crunch_$id"
                                    val center = Point((x1 + x2) / 2, (y1 + y2) / 2)
                                    val newPoint = CrunchPointAnnotation(id, label, Rect(x1, y1, x2, y2), center)
                                    currentOnAddCrunchPoint(newPoint)
                                    currentOnSelectPoint(currentCrunchPoints.size) // auto-select newly added point
                                }
                            }
                            dragStartImg = null
                            dragCurrentImg = null
                        } else if (currentPhase == AnnotationPhase.DIVIDING_LINE) {
                            val s = dragStartImg
                            val c = dragCurrentImg
                            if (s != null && c != null) {
                                val p1 = Point(s.x.toInt().coerceIn(0, bitmap.width), s.y.toInt().coerceIn(0, bitmap.height))
                                val p2 = Point(c.x.toInt().coerceIn(0, bitmap.width), c.y.toInt().coerceIn(0, bitmap.height))
                                if (distSq(p1, p2) >= 36) {
                                    currentOnSetDividingLine(DividingLineAnnotation(1, listOf(p1, p2)))
                                }
                            }
                            dragStartImg = null
                            dragCurrentImg = null
                            currentLinePoints.clear()
                        }
                    } else {
                        // Cancel any drafting state upon multi-touch finish
                        dragStartImg = null
                        dragCurrentImg = null
                        draggingPointIndex = null
                        dragPointInitialRect = null
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (imgBitmap == null || bitmap == null) return@Canvas

            val canvasW = size.width
            val canvasH = size.height
            val imgW = bitmap.width.toFloat()
            val imgH = bitmap.height.toFloat()

            // Calculate base aspect fit scale
            val baseScale = min(canvasW / imgW, canvasH / imgH)
            val effectiveScale = baseScale * scale

            val renderedW = imgW * effectiveScale
            val renderedH = imgH * effectiveScale

            val originX = (canvasW - renderedW) / 2f + offset.x
            val originY = (canvasH - renderedH) / 2f + offset.y

            // 1. Draw Raw Bubble Crop Image
            drawImage(
                image = imgBitmap,
                dstOffset = androidx.compose.ui.unit.IntOffset(originX.toInt(), originY.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(renderedW.toInt(), renderedH.toInt()),
                filterQuality = FilterQuality.High
            )

            // Coordinate mapping helpers
            fun toScreen(pt: Point): Offset = Offset(originX + pt.x * effectiveScale, originY + pt.y * effectiveScale)
            fun toScreen(x: Float, y: Float): Offset = Offset(originX + x * effectiveScale, originY + y * effectiveScale)

            // 1b. Draw Candidate Concavity Defect Targets (Soft glowing target markers)
            for (cand in currentCandidatePoints) {
                val cPos = toScreen(cand)
                val rOuter = 8.dp.toPx()
                val rInner = 2.5.dp.toPx()
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.20f),
                    radius = rOuter * 1.6f,
                    center = cPos
                )
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.85f),
                    radius = rOuter,
                    center = cPos,
                    style = Stroke(width = 1.5.dp.toPx())
                )
                drawCircle(
                    color = Color(0xFF00E5FF),
                    radius = rInner,
                    center = cPos
                )
            }

            // 2. Draw Committed Crunch Point Bounding Boxes
            val pointColors = listOf(
                Color(0xFF00E5FF), // Cut 1 Notch A (Cyan)
                Color(0xFFFFAB00), // Cut 1 Notch B (Amber)
                Color(0xFFE040FB), // Cut 2 Notch A (Magenta)
                Color(0xFF76FF03), // Cut 2 Notch B (Lime)
                Color(0xFFFF5252), // Cut 3 Notch A (Red)
                Color(0xFFFFD740), // Cut 3 Notch B (Gold)
                Color(0xFF40C4FF), // Cut 4 Notch A (Sky Blue)
                Color(0xFFFF6E40), // Cut 4 Notch B (Deep Orange)
                Color(0xFFB388FF), // Cut 5 Notch A (Lavender)
                Color(0xFF69F0AE), // Cut 5 Notch B (Mint Green)
                Color(0xFFFF4081), // Cut 6 Notch A (Pink)
                Color(0xFFEEFF41), // Cut 6 Notch B (Electric Yellow)
            )
            for ((idx, cp) in crunchPoints.withIndex()) {
                val isSelected = selectedPointIndex == idx
                val baseColor = pointColors.getOrElse(idx) { Color.Cyan }
                val color = if (isSelected) Color(0xFFFFFFFF) else baseColor

                val p1 = toScreen(cp.rect.left.toFloat(), cp.rect.top.toFloat())
                val p2 = toScreen(cp.rect.right.toFloat(), cp.rect.bottom.toFloat())
                val boxW = p2.x - p1.x
                val boxH = p2.y - p1.y

                // Semi-transparent box fill
                drawRect(
                    color = color.copy(alpha = if (isSelected) 0.35f else 0.18f),
                    topLeft = p1,
                    size = Size(boxW, boxH)
                )

                // Crisp border (thicker if selected)
                drawRect(
                    color = color,
                    topLeft = p1,
                    size = Size(boxW, boxH),
                    style = Stroke(
                        width = if (isSelected) 4.5.dp.toPx() else 3.dp.toPx(),
                        pathEffect = if (isSelected) PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f) else null
                    )
                )

                // Corner Handles if selected
                if (isSelected) {
                    val handleRadius = 5.dp.toPx()
                    drawCircle(Color.White, radius = handleRadius, center = p1)
                    drawCircle(Color.White, radius = handleRadius, center = Offset(p2.x, p1.y))
                    drawCircle(Color.White, radius = handleRadius, center = Offset(p1.x, p2.y))
                    drawCircle(Color.White, radius = handleRadius, center = p2)
                }

                // Center crosshair
                val cPos = toScreen(cp.center)
                val chLen = 9.dp.toPx()
                drawLine(color, Offset(cPos.x - chLen, cPos.y), Offset(cPos.x + chLen, cPos.y), strokeWidth = 2.5.dp.toPx())
                drawLine(color, Offset(cPos.x, cPos.y - chLen), Offset(cPos.x, cPos.y + chLen), strokeWidth = 2.5.dp.toPx())
                drawCircle(color, radius = 4.5.dp.toPx(), center = cPos)
            }

            // 3. Draw Active Dragging Bounding Box (Drafting New Box)
            val ds = dragStartImg
            val dc = dragCurrentImg
            if (phase == AnnotationPhase.CRUNCH_POINTS && ds != null && dc != null && draggingPointIndex == null) {
                val nextIdx = crunchPoints.size
                val baseColor = pointColors.getOrElse(nextIdx) { Color(0xFF00E5FF) }

                val x1 = min(ds.x, dc.x)
                val y1 = min(ds.y, dc.y)
                val x2 = max(ds.x, dc.x)
                val y2 = max(ds.y, dc.y)

                val p1 = toScreen(x1, y1)
                val p2 = toScreen(x2, y2)
                val wBox = p2.x - p1.x
                val hBox = p2.y - p1.y

                drawRect(
                    color = baseColor.copy(alpha = 0.20f),
                    topLeft = p1,
                    size = Size(wBox, hBox)
                )
                drawRect(
                    color = baseColor,
                    topLeft = p1,
                    size = Size(wBox, hBox),
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                )
            }

            // 4. Draw Committed Dividing Lines (Multi-cut distinct colors for Cuts 1 through 6)
            val cutColors = listOf(
                Color(0xFFEEFF41), // Cut 1: Vibrant Neon Lime
                Color(0xFFFF4081), // Cut 2: Neon Pink/Magenta
                Color(0xFF00E5FF), // Cut 3: Electric Cyan
                Color(0xFFFFAB00), // Cut 4: Bright Amber
                Color(0xFFE040FB), // Cut 5: Vivid Violet
                Color(0xFF76FF03), // Cut 6: Bright Spring Green
            )
            for ((lineIdx, dl) in dividingLines.withIndex()) {
                val lineColor = cutColors.getOrElse(lineIdx) { Color(0xFFEEFF41) }
                if (dl.points.size >= 2) {
                    val path = Path()
                    val first = toScreen(dl.points.first())
                    path.moveTo(first.x, first.y)
                    for (i in 1 until dl.points.size) {
                        val pt = toScreen(dl.points[i])
                        path.lineTo(pt.x, pt.y)
                    }

                    // Neon laser seam cut
                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(
                            width = 4.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )

                    // End handles
                    for (pt in dl.points) {
                        drawCircle(lineColor, radius = 4.5.dp.toPx(), center = toScreen(pt))
                    }
                }
            }

            // 5. Draw Active Line Drafting Points
            if (phase == AnnotationPhase.DIVIDING_LINE && currentLinePoints.size >= 2) {
                val path = Path()
                val first = toScreen(currentLinePoints.first())
                path.moveTo(first.x, first.y)
                for (i in 1 until currentLinePoints.size) {
                    val pt = toScreen(currentLinePoints[i])
                    path.lineTo(pt.x, pt.y)
                }

                drawPath(
                    path = path,
                    color = Color(0xFFEEFF41),
                    style = Stroke(
                        width = 3.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        // Floating Selected Point Info Pill with [Delete Point ✕] Button
        if (selectedPointIndex != null && selectedPointIndex in crunchPoints.indices) {
            val pt = crunchPoints[selectedPointIndex]
            val isA = selectedPointIndex == 0
            val labelColor = if (isA) Color(0xFF00E5FF) else Color(0xFFFFAB00)

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xEE22222E),
                border = androidx.compose.foundation.BorderStroke(1.dp, labelColor)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(labelColor))
                    Text(
                        text = "${if (isA) "Notch A" else "Notch B"} (${pt.center.x}, ${pt.center.y}) • Drag to move",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Button(
                        onClick = {
                            onDeletePoint(selectedPointIndex)
                            onSelectPoint(null)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Delete", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete Point", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Floating Quick Zoom Toolbar (Top-Right of Canvas)
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xCC1E1E28),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF383848))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { scale = (scale * 1.35f).coerceIn(0.5f, 25f) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = Color.White)
                }
                IconButton(
                    onClick = { scale = (scale / 1.35f).coerceIn(0.5f, 25f) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom Out", tint = Color.White)
                }
                IconButton(
                    onClick = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.FitScreen, contentDescription = "Reset Zoom", tint = Color(0xFF00E5FF))
                }
            }
        }
    }
}

private fun findHitPointIndexScreen(
    touchScreenPos: Offset,
    points: List<CrunchPointAnnotation>,
    canvasSize: androidx.compose.ui.unit.IntSize,
    imgW: Int,
    imgH: Int,
    scale: Float,
    offset: Offset,
    touchSlopPx: Float
): Int {
    if (points.isEmpty() || imgW <= 0 || imgH <= 0) return -1

    val baseScale = min(canvasSize.width.toFloat() / imgW.toFloat(), canvasSize.height.toFloat() / imgH.toFloat())
    val effectiveScale = baseScale * scale
    val renderedW = imgW * effectiveScale
    val renderedH = imgH * effectiveScale
    val originX = (canvasSize.width.toFloat() - renderedW) / 2f + offset.x
    val originY = (canvasSize.height.toFloat() - renderedH) / 2f + offset.y

    for (i in points.indices.reversed()) {
        val r = points[i].rect
        val left = originX + r.left * effectiveScale - touchSlopPx
        val right = originX + r.right * effectiveScale + touchSlopPx
        val top = originY + r.top * effectiveScale - touchSlopPx
        val bottom = originY + r.bottom * effectiveScale + touchSlopPx

        if (touchScreenPos.x in left..right && touchScreenPos.y in top..bottom) {
            return i
        }
    }
    return -1
}

private fun screenToImage(
    screenPos: Offset,
    canvasSize: androidx.compose.ui.unit.IntSize,
    imgW: Int,
    imgH: Int,
    scale: Float,
    offset: Offset
): Pair<Float, Float> {
    val baseScale = min(canvasSize.width.toFloat() / imgW.toFloat(), canvasSize.height.toFloat() / imgH.toFloat())
    val effectiveScale = baseScale * scale
    val renderedW = imgW * effectiveScale
    val renderedH = imgH * effectiveScale

    val originX = (canvasSize.width.toFloat() - renderedW) / 2f + offset.x
    val originY = (canvasSize.height.toFloat() - renderedH) / 2f + offset.y

    val imgX = ((screenPos.x - originX) / effectiveScale).coerceIn(0f, imgW.toFloat())
    val imgY = ((screenPos.y - originY) / effectiveScale).coerceIn(0f, imgH.toFloat())
    return Pair(imgX, imgY)
}

private fun imageToScreen(
    imgX: Float,
    imgY: Float,
    canvasSize: androidx.compose.ui.unit.IntSize,
    imgW: Int,
    imgH: Int,
    scale: Float,
    offset: Offset
): Offset {
    val baseScale = min(canvasSize.width.toFloat() / imgW.toFloat(), canvasSize.height.toFloat() / imgH.toFloat())
    val effectiveScale = baseScale * scale
    val renderedW = imgW * effectiveScale
    val renderedH = imgH * effectiveScale
    val originX = (canvasSize.width.toFloat() - renderedW) / 2f + offset.x
    val originY = (canvasSize.height.toFloat() - renderedH) / 2f + offset.y
    return Offset(originX + imgX * effectiveScale, originY + imgY * effectiveScale)
}

private fun distSq(p1: Point, p2: Point): Int {
    val dx = p1.x - p2.x
    val dy = p1.y - p2.y
    return dx * dx + dy * dy
}
