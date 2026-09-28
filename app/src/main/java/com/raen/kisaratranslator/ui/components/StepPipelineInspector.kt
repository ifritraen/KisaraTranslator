package com.raen.kisaratranslator.ui.components

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.raen.kisaratranslator.engine.grouping.CrunchSplitter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raen.kisaratranslator.data.model.OcrCropDebug
import com.raen.kisaratranslator.data.model.TranslationBlock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Step 1: Detection Boxes Overlay
 * Shows raw detected rectangular speech & text boxes before grouping.
 * Crisp hollow outline with compact outer micro-tag so characters are 100% visible.
 */
@Composable
fun DetectionBoxesOverlay(
    boxes: List<Rect>,
    imageWidth: Float,
    imageHeight: Float,
    modifier: Modifier = Modifier,
    boxColor: Color = Color(0xFF00E676),
    fillColor: Color = Color.Transparent,
    badgePrefix: String = "",
) {
    if (imageWidth <= 0f || imageHeight <= 0f) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        for ((idx, box) in boxes.withIndex()) {
            val boxLeft = box.left * scaleX
            val boxTop = box.top * scaleY
            val boxWidth = box.width() * scaleX
            val boxHeight = box.height() * scaleY

            // Optional subtle fill (only if explicitly non-transparent)
            if (fillColor.alpha > 0.05f) {
                drawRect(
                    color = fillColor,
                    topLeft = Offset(boxLeft, boxTop),
                    size = Size(boxWidth, boxHeight),
                )
            }

            // Crisp hollow outline
            drawRect(
                color = boxColor,
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
                style = Stroke(width = 1.8f),
            )

            // Compact outer micro-tag above top border (never blocks characters inside!)
            if (badgePrefix.isNotEmpty()) {
                val label = "$badgePrefix${idx + 1}"
                drawContext.canvas.nativeCanvas.apply {
                    val textPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 12f
                        isFakeBoldText = true
                    }
                    val textW = textPaint.measureText(label)
                    val tagH = 14f
                    val tagW = textW + 6f
                    val tagX = boxLeft
                    val tagY = (boxTop - tagH).coerceAtLeast(0f)

                    val bgPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(220, 15, 15, 20)
                        style = android.graphics.Paint.Style.FILL
                    }
                    drawRect(tagX, tagY, tagX + tagW, tagY + tagH, bgPaint)

                    val borderPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(
                            255,
                            (boxColor.red * 255).toInt(),
                            (boxColor.green * 255).toInt(),
                            (boxColor.blue * 255).toInt(),
                        )
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 1.2f
                    }
                    drawRect(tagX, tagY, tagX + tagW, tagY + tagH, borderPaint)

                    drawText(label, tagX + 3f, tagY + 11f, textPaint)
                }
            }
        }
    }
}

/**
 * Universal Focus Highlight Overlay
 * Highlights a selected bounding box across any view mode with a glowing animated spotlight,
 * cinema dim scrim on background, corner brackets, and floating badge.
 */
@Composable
fun TargetBoxHighlightOverlay(
    targetRect: Rect,
    label: String = "Selected",
    imageWidth: Float,
    imageHeight: Float,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 0f || imageHeight <= 0f) return

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight
        val left = targetRect.left * scaleX
        val top = targetRect.top * scaleY
        val right = targetRect.right * scaleX
        val bottom = targetRect.bottom * scaleY
        val w = right - left
        val h = bottom - top

        // 1. Cinema Dim Scrim (spotlight cutout)
        val scrimColor = Color(0x77000000)
        drawRect(scrimColor, topLeft = Offset(0f, 0f), size = Size(size.width, top.coerceAtLeast(0f)))
        drawRect(scrimColor, topLeft = Offset(0f, bottom), size = Size(size.width, (size.height - bottom).coerceAtLeast(0f)))
        drawRect(scrimColor, topLeft = Offset(0f, top), size = Size(left.coerceAtLeast(0f), h.coerceAtLeast(0f)))
        drawRect(scrimColor, topLeft = Offset(right, top), size = Size((size.width - right).coerceAtLeast(0f), h.coerceAtLeast(0f)))

        // 2. Translucent Gold Fill
        drawRect(
            color = Color(0xFFFFEA00).copy(alpha = 0.20f),
            topLeft = Offset(left, top),
            size = Size(w, h),
        )

        // 3. Pulsing Gold Glowing Border
        drawRect(
            color = Color(0xFFFFEA00).copy(alpha = pulseAlpha),
            topLeft = Offset(left, top),
            size = Size(w, h),
            style = Stroke(width = 3.5f),
        )

        // 4. Viewfinder High-Tech Corner Brackets (Cyan)
        val bracketLen = kotlin.math.min(18f, kotlin.math.min(w, h) / 3f)
        val bracketColor = Color(0xFF00E5FF)
        val bracketWidth = 4.5f

        // Top-left corner
        drawLine(bracketColor, Offset(left - 2f, top), Offset(left + bracketLen, top), bracketWidth)
        drawLine(bracketColor, Offset(left, top - 2f), Offset(left, top + bracketLen), bracketWidth)

        // Top-right corner
        drawLine(bracketColor, Offset(right + 2f, top), Offset(right - bracketLen, top), bracketWidth)
        drawLine(bracketColor, Offset(right, top - 2f), Offset(right, top + bracketLen), bracketWidth)

        // Bottom-left corner
        drawLine(bracketColor, Offset(left - 2f, bottom), Offset(left + bracketLen, bottom), bracketWidth)
        drawLine(bracketColor, Offset(left, bottom + 2f), Offset(left, bottom - bracketLen), bracketWidth)

        // Bottom-right corner
        drawLine(bracketColor, Offset(right + 2f, bottom), Offset(right - bracketLen, bottom), bracketWidth)
        drawLine(bracketColor, Offset(right, bottom + 2f), Offset(right, bottom - bracketLen), bracketWidth)

        // 5. Floating Badge Pill with Target Info & Dismiss Indicator
        drawContext.canvas.nativeCanvas.apply {
            val badgeText = "★ $label  ${targetRect.width()}x${targetRect.height()}  [✕]"
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 14f
                isFakeBoldText = true
            }
            val textWidth = textPaint.measureText(badgeText)
            val pillH = 22f
            val pillW = textWidth + 14f
            val pillX = (left + (w - pillW) / 2f).coerceIn(4f, (size.width - pillW - 4f).coerceAtLeast(4f))
            val pillY = if (top > 28f) top - pillH - 4f else bottom + 6f

            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(235, 20, 20, 25)
                style = android.graphics.Paint.Style.FILL
            }
            val borderPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(255, 255, 234, 0)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1.5f
            }

            val r = 6f
            drawRoundRect(
                android.graphics.RectF(pillX, pillY, pillX + pillW, pillY + pillH),
                r, r, bgPaint
            )
            drawRoundRect(
                android.graphics.RectF(pillX, pillY, pillX + pillW, pillY + pillH),
                r, r, borderPaint
            )
            drawText(badgeText, pillX + 7f, pillY + 16f, textPaint)
        }
    }
}

/**
 * Step 2: Line Strips Overlay
 * Shows merged/bridged vertical line bounding boxes before bubble grouping and OCR.
 * In Method 3, vertical gaps (dy <= 2.5 * char height) are bridged into full line strips.
 */
@Composable
fun LineStripsOverlay(
    lines: List<Rect>,
    imageWidth: Float,
    imageHeight: Float,
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 0f || imageHeight <= 0f || lines.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        for ((idx, box) in lines.withIndex()) {
            val boxLeft = box.left * scaleX
            val boxTop = box.top * scaleY
            val boxWidth = box.width() * scaleX
            val boxHeight = box.height() * scaleY

            // Box fill
            drawRect(
                color = Color(0x33FFB300),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
            )
            // Box outline
            drawRect(
                color = Color(0xFFFFB300),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
                style = Stroke(
                    width = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                ),
            )

            // Index badge: Stagger vertical offset on adjacent columns to prevent collisions
            val badgeRadius = 13f
            val staggerY = if (idx % 2 == 0) 4f else (badgeRadius * 2 + 6f)
            val cx = boxLeft + badgeRadius + 2f
            val cy = boxTop + badgeRadius + staggerY
            drawCircle(
                color = Color(0xFFFFB300),
                radius = badgeRadius,
                center = Offset(cx, cy),
            )

            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 15f
                    isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText("L${idx + 1}", cx, cy + 5f, paint)
            }
        }
    }
}

/**
 * Step 2: Line Strips Gallery
 * Shows individual vertical line strips with dimensions, coordinates, and tap-to-highlight support.
 */
@Composable
fun LineStripsGallery(
    lines: List<Rect>,
    selectedRect: Rect? = null,
    onSelectBox: (Rect, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return

    val listState = rememberLazyListState()
    LaunchedEffect(selectedRect) {
        if (selectedRect != null) {
            val idx = lines.indexOf(selectedRect)
            if (idx >= 0) {
                listState.animateScrollToItem(idx)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Vertical Line Strips (${lines.size} Lines)",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFD54F),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Single-Column Strips",
                color = Color.Gray,
                fontSize = 11.sp,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(lines) { idx, line ->
                val isSelected = selectedRect == line
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF33301E) else Color(0xFF222226)),
                    modifier = Modifier
                        .width(140.dp)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFFFD54F).copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelectBox(line, "Line #${idx + 1}") },
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "L${idx + 1}",
                            color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFFFD54F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${line.width()}x${line.height()} px",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "(${line.left}, ${line.top})",
                            color = Color.Gray,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Step: Grouped Lobes Overlay
 * Shows intra-bubble clustered dialogue lobes (bunches) with distinct cyan borders.
 */
@Composable
fun GroupedLobesOverlay(
    lobes: List<Rect>,
    imageWidth: Float,
    imageHeight: Float,
    nonBubbledCrops: List<OcrCropDebug> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 0f || imageHeight <= 0f || (lobes.isEmpty() && nonBubbledCrops.isEmpty())) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        // 1. Draw discarded non-bubbled noise boxes
        for (crop in nonBubbledCrops.filter { it.rawText.startsWith("✗") }) {
            val boxLeft = crop.rect.left * scaleX
            val boxTop = crop.rect.top * scaleY
            val boxWidth = crop.rect.width() * scaleX
            val boxHeight = crop.rect.height() * scaleY

            drawRect(
                color = Color(0x22FF1744),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
            )
            drawRect(
                color = Color(0xFFFF1744),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
                style = Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                ),
            )

            drawContext.canvas.nativeCanvas.apply {
                val bgPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(200, 40, 10, 10)
                    style = android.graphics.Paint.Style.FILL
                }
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(255, 255, 100, 100)
                    textSize = 14f
                    isFakeBoldText = true
                }
                val label = "✗ Noise"
                val tw = textPaint.measureText(label)
                drawRect(boxLeft, boxTop - 15f, boxLeft + tw + 6f, boxTop + 1f, bgPaint)
                drawText(label, boxLeft + 3f, boxTop - 3f, textPaint)
            }
        }

        // 2. Draw grouped dialogue lobes
        for ((idx, box) in lobes.withIndex()) {
            val boxLeft = box.left * scaleX
            val boxTop = box.top * scaleY
            val boxWidth = box.width() * scaleX
            val boxHeight = box.height() * scaleY

            // Lobe fill
            drawRect(
                color = Color(0x3300E5FF),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
            )
            // Lobe border
            drawRect(
                color = Color(0xFF00E5FF),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
                style = Stroke(
                    width = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f)),
                ),
            )

            // Badge
            val badgeRadius = 14f
            val cx = boxLeft + badgeRadius + 4f
            val cy = boxTop + badgeRadius + 4f
            drawCircle(
                color = Color(0xFF00E5FF),
                radius = badgeRadius,
                center = Offset(cx, cy),
            )

            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 17f
                    isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText("G${idx + 1}", cx, cy + 6f, paint)
            }
        }
    }
}

/**
 * Step 4b: Crunch Split Overlay
 * Shows original conjoined bubble envelopes, separated lobes,
 * detected crunch constriction points A & B, and obstacle-aware laser cut lines.
 */
@Composable
fun CrunchSplitOverlay(
    originalEnvelopes: List<Rect>,
    splitLobes: List<Rect>,
    pointsA: List<Point> = emptyList(),
    pointsB: List<Point> = emptyList(),
    cutLines: List<List<Point>> = emptyList(),
    imageWidth: Float,
    imageHeight: Float,
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 0f || imageHeight <= 0f) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        // 1. Draw original conjoined compound envelopes (dashed red/amber)
        for ((idx, box) in originalEnvelopes.withIndex()) {
            val boxLeft = box.left * scaleX
            val boxTop = box.top * scaleY
            val boxWidth = box.width() * scaleX
            val boxHeight = box.height() * scaleY

            // Soft crimson fill
            drawRect(
                color = Color(0x22FF5252),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
            )
            // Dashed red border
            drawRect(
                color = Color(0xFFFF5252),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
                style = Stroke(
                    width = 2.2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f)),
                ),
            )

            // Conjoined badge
            drawContext.canvas.nativeCanvas.apply {
                val bgPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(220, 60, 15, 15)
                    style = android.graphics.Paint.Style.FILL
                }
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(255, 255, 120, 120)
                    textSize = 13f
                    isFakeBoldText = true
                }
                val label = "Conjoined #${idx + 1} (${box.width()}x${box.height()})"
                val tw = textPaint.measureText(label)
                val tagH = 16f
                val tagY = (boxTop - tagH).coerceAtLeast(0f)
                drawRect(boxLeft, tagY, boxLeft + tw + 8f, tagY + tagH, bgPaint)
                drawText(label, boxLeft + 4f, tagY + 12f, textPaint)
            }
        }

        // 2. Draw separated lobes resulting from crunch splitting
        val lobeColors = listOf(Color(0xFF00E5FF), Color(0xFFE040FB), Color(0xFF00E676), Color(0xFFFFD54F))
        for ((idx, box) in splitLobes.withIndex()) {
            val boxLeft = box.left * scaleX
            val boxTop = box.top * scaleY
            val boxWidth = box.width() * scaleX
            val boxHeight = box.height() * scaleY

            val lobeColor = lobeColors[idx % lobeColors.size]

            // Lobe fill
            drawRect(
                color = lobeColor.copy(alpha = 0.15f),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
            )
            // Solid lobe outline
            drawRect(
                color = lobeColor,
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
                style = Stroke(width = 2.5f),
            )

            // Pill badge
            val badgeText = "✂️ Lobe #${idx + 1}"
            drawContext.canvas.nativeCanvas.apply {
                val textPaint = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.WHITE
                    textSize = 13f
                    isFakeBoldText = true
                }
                val tw = textPaint.measureText(badgeText)
                val pillW = tw + 14f
                val pillH = 18f
                val pillX = (boxLeft + 4f).coerceAtLeast(2f)
                val pillY = (boxTop + 4f).coerceAtLeast(2f)

                val bgPaint = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.argb(230, 20, 20, 25)
                    style = android.graphics.Paint.Style.FILL
                }
                val borderPaint = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.argb(
                        255,
                        (lobeColor.red * 255).toInt(),
                        (lobeColor.green * 255).toInt(),
                        (lobeColor.blue * 255).toInt(),
                    )
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 1.5f
                }
                val r = 5f
                drawRoundRect(
                    android.graphics.RectF(pillX, pillY, pillX + pillW, pillY + pillH),
                    r, r, bgPaint
                )
                drawRoundRect(
                    android.graphics.RectF(pillX, pillY, pillX + pillW, pillY + pillH),
                    r, r, borderPaint
                )
                drawText(badgeText, pillX + 6f, pillY + 13f, textPaint)
            }
        }

        // 3. Draw Laser Cut Seams (neon yellow/lime with glow)
        for (linePoints in cutLines) {
            if (linePoints.size < 2) continue
            val path = Path().apply {
                val start = linePoints.first()
                moveTo(start.x * scaleX, start.y * scaleY)
                for (i in 1 until linePoints.size) {
                    val pt = linePoints[i]
                    lineTo(pt.x * scaleX, pt.y * scaleY)
                }
            }
            // Outer glowing blur effect
            drawPath(
                path = path,
                color = Color(0x66EEFF41),
                style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            // Inner vibrant dashed laser cut
            drawPath(
                path = path,
                color = Color(0xFFEEFF41),
                style = Stroke(
                    width = 2.8f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
                ),
            )
        }

        // 4. Draw Crunch Points A (Cyan glow circle, crosshair, and label pill)
        for ((idx, pt) in pointsA.withIndex()) {
            val px = pt.x * scaleX
            val py = pt.y * scaleY

            // Outer glow
            drawCircle(
                color = Color(0x6600E5FF),
                radius = 12f,
                center = Offset(px, py),
            )
            // Inner circle
            drawCircle(
                color = Color(0xFF00E5FF),
                radius = 5.5f,
                center = Offset(px, py),
            )
            // Center white dot
            drawCircle(
                color = Color.White,
                radius = 2f,
                center = Offset(px, py),
            )
            // Crosshairs
            drawLine(
                color = Color(0xFF00E5FF),
                start = Offset(px - 14f, py),
                end = Offset(px + 14f, py),
                strokeWidth = 2f,
            )
            drawLine(
                color = Color(0xFF00E5FF),
                start = Offset(px, py - 14f),
                end = Offset(px, py + 14f),
                strokeWidth = 2f,
            )

            // Label "Crunch A"
            drawContext.canvas.nativeCanvas.apply {
                val bgPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(230, 0, 70, 90)
                    style = android.graphics.Paint.Style.FILL
                }
                val borderPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(255, 0, 229, 255)
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 1.5f
                }
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 12f
                    isFakeBoldText = true
                }
                val label = "Crunch A"
                val tw = textPaint.measureText(label)
                val pillX = px + 8f
                val pillY = (py - 18f).coerceAtLeast(2f)
                drawRoundRect(
                    android.graphics.RectF(pillX, pillY, pillX + tw + 8f, pillY + 16f),
                    4f, 4f, bgPaint
                )
                drawRoundRect(
                    android.graphics.RectF(pillX, pillY, pillX + tw + 8f, pillY + 16f),
                    4f, 4f, borderPaint
                )
                drawText(label, pillX + 4f, pillY + 12f, textPaint)
            }
        }

        // 5. Draw Crunch Points B (Amber/Gold glow circle, crosshair, and label pill)
        for ((idx, pt) in pointsB.withIndex()) {
            val px = pt.x * scaleX
            val py = pt.y * scaleY

            // Outer glow
            drawCircle(
                color = Color(0x66FFD600),
                radius = 12f,
                center = Offset(px, py),
            )
            // Inner circle
            drawCircle(
                color = Color(0xFFFFD600),
                radius = 5.5f,
                center = Offset(px, py),
            )
            // Center white dot
            drawCircle(
                color = Color.White,
                radius = 2f,
                center = Offset(px, py),
            )
            // Crosshairs
            drawLine(
                color = Color(0xFFFFD600),
                start = Offset(px - 14f, py),
                end = Offset(px + 14f, py),
                strokeWidth = 2f,
            )
            drawLine(
                color = Color(0xFFFFD600),
                start = Offset(px, py - 14f),
                end = Offset(px, py + 14f),
                strokeWidth = 2f,
            )

            // Label "Crunch B"
            drawContext.canvas.nativeCanvas.apply {
                val bgPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(230, 90, 70, 0)
                    style = android.graphics.Paint.Style.FILL
                }
                val borderPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(255, 255, 214, 0)
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 1.5f
                }
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 12f
                    isFakeBoldText = true
                }
                val label = "Crunch B"
                val tw = textPaint.measureText(label)
                val pillX = px + 8f
                val pillY = (py - 18f).coerceAtLeast(2f)
                drawRoundRect(
                    android.graphics.RectF(pillX, pillY, pillX + tw + 8f, pillY + 16f),
                    4f, 4f, bgPaint
                )
                drawRoundRect(
                    android.graphics.RectF(pillX, pillY, pillX + tw + 8f, pillY + 16f),
                    4f, 4f, borderPaint
                )
                drawText(label, pillX + 4f, pillY + 12f, textPaint)
            }
        }
    }
}

/**
 * Step 4b: Crunch Split Gallery
 * Shows cards for each detected conjoined envelope and separated lobe.
 */
@Composable
fun CrunchSplitGallery(
    originalEnvelopes: List<Rect>,
    splitLobes: List<Rect>,
    selectedRect: Rect? = null,
    onSelectBox: (Rect, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val allBoxes = remember(originalEnvelopes, splitLobes) { originalEnvelopes + splitLobes }

    LaunchedEffect(selectedRect) {
        if (selectedRect != null) {
            val idx = allBoxes.indexOfFirst {
                it == selectedRect ||
                    (it.left == selectedRect.left && it.top == selectedRect.top &&
                     it.right == selectedRect.right && it.bottom == selectedRect.bottom)
            }
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
    }

    if (originalEnvelopes.isEmpty() && splitLobes.isEmpty()) {
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B231D)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.5f)),
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✓", fontSize = 18.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("No Conjoined Bubbles Detected", fontSize = 13.sp, color = Color(0xFF81C784), fontWeight = FontWeight.Bold)
                    Text("All speech balloons in this page have clean single-lobe geometries.", fontSize = 11.sp, color = Color.Gray)
                }
            }
        }
        return
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
    ) {
        // First show original conjoined envelopes
        itemsIndexed(originalEnvelopes) { idx, env ->
            val isSelected = selectedRect == env
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(0xFF381B1B) else Color(0xFF221616)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFFF5252).copy(alpha = 0.6f),
                ),
                modifier = Modifier
                    .width(150.dp)
                    .clickable { onSelectBox(env, "Conjoined #${idx + 1}") },
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Conjoined #${idx + 1}", color = Color(0xFFFF8A80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${env.width()}x${env.height()} px", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text("(${env.left}, ${env.top})", color = Color.Gray, fontSize = 9.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Original Envelope", color = Color(0xFFEF9A9A), fontSize = 9.5.sp)
                }
            }
        }

        // Next show split lobes
        val lobeColors = listOf(Color(0xFF00E5FF), Color(0xFFE040FB), Color(0xFF00E676), Color(0xFFFFD54F))
        itemsIndexed(splitLobes) { idx, lobe ->
            val isSelected = selectedRect == lobe
            val color = lobeColors[idx % lobeColors.size]
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(0xFF1E2833) else Color(0xFF131A22)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) Color(0xFFFFEA00) else color.copy(alpha = 0.7f),
                ),
                modifier = Modifier
                    .width(150.dp)
                    .clickable { onSelectBox(lobe, "✂️ Lobe #${idx + 1}") },
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("✂️ Lobe #${idx + 1}", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${lobe.width()}x${lobe.height()} px", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text("(${lobe.left}, ${lobe.top})", color = Color.Gray, fontSize = 9.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Separated Lobe", color = Color(0xFF80D8FF), fontSize = 9.5.sp)
                }
            }
        }
    }
}

/**
 * Step 3: Non-Bubbled Line Verification Gallery
 * Shows vertical line candidates outside bubbles evaluated by MangaOCR INT8 mini-check,
 * color-coded by Pass (Confirmed Japanese Text) vs Reject (Discarded Noise/Artwork).
 */
@Composable
fun NonBubbledCheckGallery(
    crops: List<OcrCropDebug>,
    selectedRect: Rect? = null,
    onSelectBox: (Rect, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (crops.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("No non-bubbled lines to verify (all dialogue inside speech bubbles)", color = Color.Gray, fontSize = 12.sp)
        }
        return
    }

    val passedCount = crops.count { it.rawText.startsWith("✓") }
    val rejectedCount = crops.count { it.rawText.startsWith("✗") }

    val listState = rememberLazyListState()
    LaunchedEffect(selectedRect) {
        if (selectedRect != null) {
            val idx = crops.indexOfFirst {
                it.rect == selectedRect ||
                    (it.rect.left == selectedRect.left && it.rect.top == selectedRect.top &&
                     it.rect.right == selectedRect.right && it.rect.bottom == selectedRect.bottom)
            }
            if (idx >= 0) {
                listState.animateScrollToItem(idx)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Non-Bubbled Line Verification (${crops.size} Lines Evaluated)",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFD54F),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    text = "✓ $passedCount Confirmed Text",
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    softWrap = false,
                )
                Text(
                    text = "✗ $rejectedCount Noise Dropped",
                    color = Color(0xFFFF1744),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    softWrap = false,
                )
            }
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(crops) { idx, crop ->
                val isSelected = selectedRect == crop.rect
                val isPassed = crop.rawText.startsWith("✓")
                val borderColor = if (isSelected) Color(0xFFFFEA00) else if (isPassed) Color(0xFF00E676) else Color(0xFF552222)
                val bgColor = if (isSelected) Color(0xFF2B2B16) else if (isPassed) Color(0xFF162B1D) else Color(0xFF221616)

                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = bgColor),
                    modifier = Modifier
                        .width(180.dp)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = borderColor,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelectBox(crop.rect, if (isPassed) "Line #${idx + 1}" else "Noise #${idx + 1}") },
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "#${idx + 1} (${crop.rect.width()}x${crop.rect.height()})",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPassed) Color(0xFF80E8A7) else Color(0xFFEF9A9A),
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isPassed) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFFFF1744).copy(alpha = 0.2f),
                            ) {
                                Text(
                                    text = if (isPassed) "CONFIRMED" else "DISCARDED",
                                    color = if (isPassed) Color(0xFF00E676) else Color(0xFFFF1744),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Crop Preview
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!crop.cropBitmap.isRecycled) {
                                Image(
                                    bitmap = crop.cropBitmap.asImageBitmap(),
                                    contentDescription = "Non-Bubbled Crop",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = crop.rawText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Step 2: OCR Crops Gallery
 * Shows individual cropped text image patches side-by-side with recognized text and chunk info.
 */
@Composable
fun OcrCropsGallery(
    crops: List<OcrCropDebug>,
    selectedRect: Rect? = null,
    onSelectBox: (Rect, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (crops.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("No OCR crops available", color = Color.Gray, fontSize = 13.sp)
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(selectedRect) {
        if (selectedRect != null) {
            val idx = crops.indexOfFirst { it.rect == selectedRect }
            if (idx >= 0) {
                listState.animateScrollToItem(idx)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "OCR Chunks & Recognition (${crops.size} Chunks)",
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00E5FF),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Sweet-Spot ViT",
                color = Color.Gray,
                fontSize = 11.sp,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(crops) { idx, crop ->
                val isSelected = selectedRect == crop.rect
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF2B2B1E) else Color(0xFF1E242B)),
                    modifier = Modifier
                        .width(180.dp)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFFFFEA00) else Color(0xFF00E5FF).copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelectBox(crop.rect, "Chunk #${idx + 1}") },
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Chunk #${idx + 1}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF80D8FF),
                            )
                            Text(
                                text = "${crop.rect.width()}x${crop.rect.height()}",
                                fontSize = 9.sp,
                                color = Color.Gray,
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Crop Image Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(85.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!crop.cropBitmap.isRecycled) {
                                Image(
                                    bitmap = crop.cropBitmap.asImageBitmap(),
                                    contentDescription = "Chunk Crop #${idx + 1}",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Raw Text
                        Text(
                            text = if (crop.rawText.isNotBlank()) crop.rawText else "<empty>",
                            color = if (crop.rawText.isNotBlank()) Color.White else Color.Gray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Step 3: Reading Order & Grouping Overlay
 * Draws sequential order badges and connected flow arrows between grouped blocks.
 */
@Composable
fun ReadingOrderOverlay(
    blocks: List<TranslationBlock>,
    imageWidth: Float,
    imageHeight: Float,
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 0f || imageHeight <= 0f || blocks.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        val centers = mutableListOf<Offset>()

        // 1. Draw block bounds & badges
        for ((idx, block) in blocks.withIndex()) {
            val boxLeft = block.x * scaleX
            val boxTop = block.y * scaleY
            val boxWidth = block.width * scaleX
            val boxHeight = block.height * scaleY

            val cx = boxLeft + boxWidth / 2f
            val cy = boxTop + boxHeight / 2f
            centers.add(Offset(cx, cy))

            // Box outline
            drawRect(
                color = Color(0x229C27B0),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
            )
            drawRect(
                color = Color(0xFFBA68C8),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
                style = Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                ),
            )

            // Numbered badge
            val badgeRadius = 16f
            drawCircle(
                color = Color(0xFFAB47BC),
                radius = badgeRadius,
                center = Offset(cx, cy),
            )
            drawCircle(
                color = Color.White,
                radius = badgeRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f),
            )

            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 18f
                    isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText("${idx + 1}", cx, cy + 6f, paint)
            }
        }

        // 2. Draw directional flow arrows connecting (1 -> 2 -> 3...)
        for (i in 0 until centers.size - 1) {
            val start = centers[i]
            val end = centers[i + 1]

            // Line
            drawLine(
                color = Color(0xFFE040FB),
                start = start,
                end = end,
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )

            // Arrow head at end
            val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble()).toFloat()
            val arrowLength = 20f
            val arrowAngle = Math.toRadians(30.0).toFloat()

            val p1 = Offset(
                end.x - arrowLength * cos(angle - arrowAngle),
                end.y - arrowLength * sin(angle - arrowAngle),
            )
            val p2 = Offset(
                end.x - arrowLength * cos(angle + arrowAngle),
                end.y - arrowLength * sin(angle + arrowAngle),
            )

            val arrowPath = Path().apply {
                moveTo(end.x, end.y)
                lineTo(p1.x, p1.y)
                lineTo(p2.x, p2.y)
                close()
            }
            drawPath(arrowPath, color = Color(0xFFE040FB))
        }
    }
}

/**
 * Step 3: Reading Order Flow Gallery
 * Shows dialogue blocks ordered by Manga RTL reading order with tap-to-highlight support.
 */
@Composable
fun ReadingOrderGallery(
    blocks: List<TranslationBlock>,
    selectedRect: Rect? = null,
    onSelectBox: (Rect, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (blocks.isEmpty()) return

    val listState = rememberLazyListState()
    LaunchedEffect(selectedRect) {
        if (selectedRect != null) {
            val idx = blocks.indexOfFirst {
                it.x.toInt() == selectedRect.left && it.y.toInt() == selectedRect.top
            }
            if (idx >= 0) {
                listState.animateScrollToItem(idx)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Reading Order Flow (${blocks.size} Blocks)",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE040FB),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Manga RTL Sequence",
                color = Color.Gray,
                fontSize = 11.sp,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(blocks) { idx, block ->
                val blockRect = Rect(block.x.toInt(), block.y.toInt(), (block.x + block.width).toInt(), (block.y + block.height).toInt())
                val isSelected = selectedRect == blockRect
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF351E3D) else Color(0xFF221626)),
                    modifier = Modifier
                        .width(150.dp)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFE040FB).copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelectBox(blockRect, "Flow #${idx + 1}") },
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "#${idx + 1}",
                                color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFE040FB),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("${block.width.toInt()}x${block.height.toInt()}", color = Color.Gray, fontSize = 9.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = block.text.ifBlank { "(empty)" },
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Step 1: Det 1 Boxes & Bubbles Gallery
 * Displays detected speech bubble envelopes and orphan non-bubble text candidates with tap-to-highlight support.
 */
@Composable
fun Det1BoxesGallery(
    bubbles: List<Rect>,
    boxes: List<Rect>,
    selectedRect: Rect? = null,
    onSelectBox: (Rect, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val orphanBoxes = boxes.filter { box -> bubbles.none { b -> b.contains(box.centerX(), box.centerY()) } }
    val bubbleBoxes = boxes.filter { box -> bubbles.any { b -> b.contains(box.centerX(), box.centerY()) } }

    val listState = rememberLazyListState()
    LaunchedEffect(selectedRect) {
        if (selectedRect != null) {
            val bIdx = bubbles.indexOf(selectedRect).takeIf { it >= 0 }
                ?: bubbles.indexOfFirst { it.contains(selectedRect.centerX(), selectedRect.centerY()) }.takeIf { it >= 0 }
            if (bIdx != null) {
                listState.animateScrollToItem(bIdx)
            } else {
                val oIdx = orphanBoxes.indexOf(selectedRect)
                if (oIdx >= 0) {
                    listState.animateScrollToItem(bubbles.size + oIdx)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Det 1 Envelopes (${bubbles.size} Bubbles, ${orphanBoxes.size} Orphans)",
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00E5FF),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${boxes.size} Total Boxes",
                color = Color.Gray,
                fontSize = 11.sp,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Bubble Cards
            itemsIndexed(bubbles) { idx, bubble ->
                val insideCount = boxes.count { bubble.contains(it.centerX(), it.centerY()) }
                val isSelected = selectedRect == bubble || (selectedRect != null && bubble.contains(selectedRect.centerX(), selectedRect.centerY()))
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF1E3A45) else Color(0xFF16252B)),
                    modifier = Modifier
                        .width(160.dp)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFFFFEA00) else Color(0xFF00E5FF).copy(alpha = 0.6f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelectBox(bubble, "Bubble #${idx + 1}") },
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Bubble #${idx + 1}",
                                color = if (isSelected) Color(0xFFFFEA00) else Color(0xFF00E5FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("${bubble.width()}x${bubble.height()}", color = Color.Gray, fontSize = 9.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (insideCount > 0) "$insideCount text cols inside" else "Empty / SFX Bubble",
                            color = if (insideCount > 0) Color(0xFF00E676) else Color(0xFFFFB74D),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // Orphan Cards
            itemsIndexed(orphanBoxes) { idx, orphan ->
                val isSelected = selectedRect == orphan
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF3D2E1A) else Color(0xFF2C2216)),
                    modifier = Modifier
                        .width(160.dp)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFFF9100).copy(alpha = 0.6f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelectBox(orphan, "Orphan #${idx + 1}") },
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Orphan #${idx + 1}",
                                color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFFF9100),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("${orphan.width()}x${orphan.height()}", color = Color.Gray, fontSize = 9.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Outside Speech Bubbles",
                            color = Color(0xFFFFCC80),
                            fontSize = 10.sp,
                        )
                        Text(
                            text = "Pos: (${orphan.left}, ${orphan.top})",
                            color = Color.Gray,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Step 1: Det 2 Probed Candidates Gallery
 * Shows candidate neighbor patches probed with MangaOCR INT8 (1.8x zoom), color-coded by Pass / Reject with tap-to-highlight support.
 */
@Composable
fun Det2ProbedGallery(
    crops: List<OcrCropDebug>,
    selectedRect: Rect? = null,
    onSelectBox: (Rect, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (crops.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("No candidate neighbor cells probed in Det 2", color = Color.Gray, fontSize = 12.sp)
        }
        return
    }

    val passedCount = crops.count { it.rawText.startsWith("✓") }
    val rejectedCount = crops.count { it.rawText.startsWith("✗") }

    val listState = rememberLazyListState()
    LaunchedEffect(selectedRect) {
        if (selectedRect != null) {
            val idx = crops.indexOfFirst {
                it.rect == selectedRect ||
                    (it.rect.left == selectedRect.left && it.rect.top == selectedRect.top &&
                     it.rect.right == selectedRect.right && it.rect.bottom == selectedRect.bottom)
            }
            if (idx >= 0) {
                listState.animateScrollToItem(idx)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Det 2 Probed Neighbors (${crops.size} Probed)",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFD54F),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    text = "✓ $passedCount Passed",
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    softWrap = false,
                )
                Text(
                    text = "✗ $rejectedCount Rejected",
                    color = Color(0xFFFF1744),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    softWrap = false,
                )
            }
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(crops) { idx, crop ->
                val isSelected = selectedRect == crop.rect
                val isPassed = crop.rawText.startsWith("✓")
                val borderColor = if (isSelected) Color(0xFFFFEA00) else if (isPassed) Color(0xFF00E676) else Color(0xFF552222)
                val bgColor = if (isSelected) Color(0xFF2B2B16) else if (isPassed) Color(0xFF162B1D) else Color(0xFF221616)

                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = bgColor),
                    modifier = Modifier
                        .width(180.dp)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = borderColor,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelectBox(crop.rect, "Probed #${idx + 1}") },
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "#${idx + 1} (${crop.rect.width()}x${crop.rect.height()})",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPassed) Color(0xFF80E8A7) else Color(0xFFEF9A9A),
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isPassed) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFFFF1744).copy(alpha = 0.2f),
                            ) {
                                Text(
                                    text = if (isPassed) "VERIFIED" else "DISCARDED",
                                    color = if (isPassed) Color(0xFF00E676) else Color(0xFFFF1744),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Crop Preview
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!crop.cropBitmap.isRecycled) {
                                Image(
                                    bitmap = crop.cropBitmap.asImageBitmap(),
                                    contentDescription = "Probed Crop",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = crop.rawText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Method 6: 3-Line Chunks Overlay
 * Draws distinct colored dashed bounding boxes around each internal 3-line chunk.
 */
@Composable
fun Method6ChunksOverlay(
    chunks: List<Rect>,
    imageWidth: Float,
    imageHeight: Float,
    modifier: Modifier = Modifier,
) {
    val chunkColors = listOf(
        Color(0xFF00E5FF), // Cyan
        Color(0xFFE040FB), // Magenta
        Color(0xFFFFD54F), // Amber
        Color(0xFF00E676), // Green
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        chunks.forEachIndexed { idx, chunk ->
            val color = chunkColors[idx % chunkColors.size]
            val left = chunk.left * scaleX
            val top = chunk.top * scaleY
            val right = chunk.right * scaleX
            val bottom = chunk.bottom * scaleY
            val w = right - left
            val h = bottom - top

            // Fill
            drawRect(
                color = color.copy(alpha = 0.15f),
                topLeft = Offset(left, top),
                size = Size(w, h),
            )

            // Dashed border
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(
                    width = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                ),
            )

            // Chunk Badge
            val badgeX = left + 4f
            val badgeY = top + 18f
            drawContext.canvas.nativeCanvas.apply {
                val bgPaint = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.argb(220, 15, 15, 20)
                    style = android.graphics.Paint.Style.FILL
                }
                val textPaint = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.WHITE
                    textSize = 16f
                    isFakeBoldText = true
                }
                val label = "Chunk #${idx + 1}"
                val textWidth = textPaint.measureText(label)
                drawRect(
                    badgeX - 4f,
                    badgeY - 14f,
                    badgeX + textWidth + 6f,
                    badgeY + 4f,
                    bgPaint,
                )
                drawText(label, badgeX, badgeY, textPaint)
            }
        }
    }
}

/**
 * Method 6: 2-Line Chunks Gallery
 * Shows individual chunk crops and recognition status below image.
 */
@Composable
fun Method6ChunksGallery(
    crops: List<OcrCropDebug>,
    modifier: Modifier = Modifier,
) {
    if (crops.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("No 2-line chunks generated in Method 6", color = Color.Gray, fontSize = 12.sp)
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Method 6 Chunks (${crops.size} Chunks, ≤3 lines/chunk)",
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00E5FF),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Sweet-Spot ViT",
                color = Color.Gray,
                fontSize = 11.sp,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(crops) { idx, crop ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E242B)),
                    modifier = Modifier
                        .width(180.dp)
                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Chunk #${idx + 1}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF80D8FF),
                            )
                            Text(
                                text = "${crop.rect.width()}x${crop.rect.height()}",
                                fontSize = 9.sp,
                                color = Color.Gray,
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!crop.cropBitmap.isRecycled) {
                                Image(
                                    bitmap = crop.cropBitmap.asImageBitmap(),
                                    contentDescription = "Chunk Crop",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = crop.rawText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * User Defined Ignore Zones Overlay.
 * Renders user-drawn rectangular areas that are 100% excluded from all pipeline stages.
 * - Solid/dashed hazard styling (crimson/red border, diagonal hatch lines, translucent fill)
 * - Corner badge "🚫 IGNORE #n"
 * - Real-time drafting box with yellow dashed border while dragging
 */
@Composable
fun UserIgnoreZonesOverlay(
    excludedBoxes: List<Rect>,
    imageWidth: Float,
    imageHeight: Float,
    draftingBox: Rect? = null,
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 0f || imageHeight <= 0f) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        // 1. Draw all committed excluded boxes
        for ((idx, box) in excludedBoxes.withIndex()) {
            val left = box.left * scaleX
            val top = box.top * scaleY
            val width = box.width() * scaleX
            val height = box.height() * scaleY

            // Translucent crimson fill
            drawRect(
                color = Color(0x35E53935),
                topLeft = Offset(left, top),
                size = Size(width, height),
            )

            // Diagonal hazard hatch lines across the rectangle
            val hatchSpacing = 16.dp.toPx()
            var d = -height
            while (d < width) {
                val startX = (left + d).coerceIn(left, left + width)
                val startY = (top + (if (d < 0) -d else 0f)).coerceIn(top, top + height)
                val endX = (left + d + height).coerceIn(left, left + width)
                val endY = (top + (if (d + height > width) height - (d + height - width) else height)).coerceIn(top, top + height)
                if (startX < endX && startY < endY) {
                    drawLine(
                        color = Color(0x50FF5252),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
                d += hatchSpacing
            }

            // Bold Dashed Red Border
            drawRect(
                color = Color(0xFFFF1744),
                topLeft = Offset(left, top),
                size = Size(width, height),
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f),
                ),
            )

            // Badge text & pill
            val badgeText = "🚫 IGNORED #${idx + 1}"
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 9.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            val textWidth = paint.measureText(badgeText)
            val badgeHeight = 16.dp.toPx()
            val badgeWidth = textWidth + 12.dp.toPx()
            val badgeLeft = left.coerceAtLeast(0f)
            val badgeTop = (top - badgeHeight - 2.dp.toPx()).coerceAtLeast(0f)

            // Pill background
            val badgeBgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#D50000")
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawRoundRect(
                badgeLeft,
                badgeTop,
                badgeLeft + badgeWidth,
                badgeTop + badgeHeight,
                4.dp.toPx(),
                4.dp.toPx(),
                badgeBgPaint,
            )
            // Pill text
            drawContext.canvas.nativeCanvas.drawText(
                badgeText,
                badgeLeft + 6.dp.toPx(),
                badgeTop + badgeHeight - 4.dp.toPx(),
                paint,
            )
        }

        // 2. Draw live drafting box (while user is currently dragging)
        if (draftingBox != null) {
            val dLeft = draftingBox.left * scaleX
            val dTop = draftingBox.top * scaleY
            val dWidth = draftingBox.width() * scaleX
            val dHeight = draftingBox.height() * scaleY

            drawRect(
                color = Color(0x35FFD600),
                topLeft = Offset(dLeft, dTop),
                size = Size(dWidth, dHeight),
            )

            drawRect(
                color = Color(0xFFFFD600),
                topLeft = Offset(dLeft, dTop),
                size = Size(dWidth, dHeight),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                ),
            )

            val draftLabel = "✏️ [${draftingBox.width()}x${draftingBox.height()}]"
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 10.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            val textWidth = paint.measureText(draftLabel)
            val badgeBgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#FFD600")
                isAntiAlias = true
            }
            val bHeight = 16.dp.toPx()
            val bLeft = dLeft.coerceAtLeast(0f)
            val bTop = (dTop - bHeight - 2.dp.toPx()).coerceAtLeast(0f)

            drawContext.canvas.nativeCanvas.drawRoundRect(
                bLeft,
                bTop,
                bLeft + textWidth + 12.dp.toPx(),
                bTop + bHeight,
                4.dp.toPx(),
                4.dp.toPx(),
                badgeBgPaint,
            )
            drawContext.canvas.nativeCanvas.drawText(
                draftLabel,
                bLeft + 6.dp.toPx(),
                bTop + bHeight - 4.dp.toPx(),
                paint,
            )
        }
    }
}

/**
 * Detailed Crunch Inspection Overlay (Screen 4c. Crunch 2)
 *
 * Visually displays the intermediate steps of conjoined bubble splitting:
 * 1. Original Bubble Contour Envelope (dashed red border, subtle dark fill).
 * 2. Waist Scanning Line:
 *    - Dashed cyan horizontal line at waistY spanning the bubble width.
 *    - Text pill: "Waist Y={waistY} (Constriction: XX%)".
 * 3. Notch Search Windows:
 *    - searchWinA: dashed cyan rectangle with label "🔍 Win A [±10px]".
 *    - searchWinB: dashed amber rectangle with label "🔍 Win B [±10px]".
 * 4. Crunch Points A & B:
 *    - Point A: glowing cyan target circle + crosshairs + "Crunch A ({x},{y})".
 *    - Point B: glowing amber target circle + crosshairs + "Crunch B ({x},{y})".
 * 5. Text Obstacles / Clashing Line Bounding Boxes:
 *    - Bright crimson/orange filled dashed rectangles covering text lines clashing with the laser corridor.
 *    - Pill badge: "⚠️ Clash #{idx}".
 * 6. Laser Deflection Waypoints & Seam:
 *    - Glowing neon laser seam line connecting waypoints: A -> Deflections -> B.
 *    - Waypoint circle nodes labeled W1, W2, W3...
 *    - Strategy badge: "⚡ {cutStrategy}".
 */

/**
 * Generates an isolated bitmap where all background manga artwork is wiped to pure white,
 * retaining only the exact speech bubble silhouette (dilated by 3px to preserve the drawn ink contour).
 * If [blankText] is true (for Screen 4b. Crunch 1), the inside text bounding boxes are painted solid white.
 */
fun createIsolatedBubbleBitmap(
    sourceBitmap: Bitmap,
    splits: List<CrunchSplitter.SplitResult>,
    blankText: Boolean = false,
): Bitmap? {
    if (sourceBitmap.isRecycled) return null
    val w = sourceBitmap.width
    val h = sourceBitmap.height
    if (w <= 0 || h <= 0) return null

    val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    outBmp.eraseColor(android.graphics.Color.WHITE)
    if (splits.isEmpty()) return outBmp

    for (split in splits) {
        val mask = split.originalMask ?: continue
        val mw = mask.width
        val mh = mask.height
        if (mw <= 0 || mh <= 0) continue

        val left = mask.rect.left.coerceIn(0, w - 1)
        val top = mask.rect.top.coerceIn(0, h - 1)
        val right = mask.rect.right.coerceIn(left + 1, w)
        val bottom = mask.rect.bottom.coerceIn(top + 1, h)
        val copyW = right - left
        val copyH = bottom - top
        if (copyW <= 0 || copyH <= 0) continue

        // Fast dilation by 3px so the artist's original drawn black contour is fully preserved
        val dilated = BooleanArray(mw * mh)
        for (y in 0 until mh) {
            val rOff = y * mw
            for (x in 0 until mw) {
                if (mask.mask[rOff + x]) {
                    for (dy in -3..3) {
                        val ny = y + dy
                        if (ny in 0 until mh) {
                            val nrOff = ny * mw
                            for (dx in -3..3) {
                                val nx = x + dx
                                if (nx in 0 until mw && dx * dx + dy * dy <= 9) {
                                    dilated[nrOff + nx] = true
                                }
                            }
                        }
                    }
                }
            }
        }

        val pixels = IntArray(copyW * copyH)
        sourceBitmap.getPixels(pixels, 0, copyW, left, top, copyW, copyH)

        for (y in 0 until copyH) {
            val maskY = (top - mask.rect.top) + y
            val rowOffset = y * copyW
            for (x in 0 until copyW) {
                val maskX = (left - mask.rect.left) + x
                val isInside = if (maskX in 0 until mw && maskY in 0 until mh) {
                    dilated[maskY * mw + maskX]
                } else false
                if (!isInside) {
                    pixels[rowOffset + x] = android.graphics.Color.WHITE
                }
            }
        }

        // In Screen 1: paint inside text lines pure white (blank out text inside bubble)
        if (blankText) {
            for (line in split.insideLines) {
                val lLeft = (line.left - left).coerceIn(0, copyW)
                val lTop = (line.top - top).coerceIn(0, copyH)
                val lRight = (line.right - left).coerceIn(0, copyW)
                val lBottom = (line.bottom - top).coerceIn(0, copyH)
                for (ly in lTop until lBottom) {
                    val rowOff = ly * copyW
                    for (lx in lLeft until lRight) {
                        pixels[rowOff + lx] = android.graphics.Color.WHITE
                    }
                }
            }
        }

        outBmp.setPixels(pixels, 0, copyW, left, top, copyW, copyH)
    }
    return outBmp
}

/**
 * Screen 4b. Crunch 1: Clean Polygon Mask & Waist Notches
 * Displays only the speech bubble polygon mask on a pure white canvas (everything else is white).
 * Inside text areas are painted pure white (blanked out).
 * Shows waist scanning line, search window boxes with inward-pointing directional arrows,
 * and pinpointed Crunch Points A and B.
 */
@Composable
fun Crunch1MaskWaistOverlay(
    splits: List<CrunchSplitter.SplitResult>,
    imageWidth: Float,
    imageHeight: Float,
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 0f || imageHeight <= 0f) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        if (splits.isEmpty()) {
            drawContext.canvas.nativeCanvas.apply {
                val p = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(180, 100, 100, 100)
                    textSize = 32f
                    isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText("✓ No Conjoined Bubbles on This Page", size.width / 2f, size.height / 2f, p)
            }
            return@Canvas
        }

        for ((sIdx, split) in splits.withIndex()) {
            val orig = split.originalRect ?: continue
            val origLeft = orig.left * scaleX
            val origTop = orig.top * scaleY
            val origWidth = orig.width() * scaleX
            val origHeight = orig.height() * scaleY

            // 1. Mask Boundary Stroke
            drawRect(
                color = Color(0xFF37474F),
                topLeft = Offset(origLeft, origTop),
                size = Size(origWidth, origHeight),
                style = Stroke(
                    width = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                ),
            )

            // 2. Blanked-out Text Areas
            for ((tIdx, textRect) in split.insideLines.withIndex()) {
                val tLeft = textRect.left * scaleX
                val tTop = textRect.top * scaleY
                val tWidth = textRect.width() * scaleX
                val tHeight = textRect.height() * scaleY

                drawRect(
                    color = Color(0x3090A4AE),
                    topLeft = Offset(tLeft, tTop),
                    size = Size(tWidth, tHeight),
                )
                drawRect(
                    color = Color(0xFF78909C),
                    topLeft = Offset(tLeft, tTop),
                    size = Size(tWidth, tHeight),
                    style = Stroke(
                        width = 1.2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)),
                    ),
                )

                // Label: Blanked Text
                drawContext.canvas.nativeCanvas.apply {
                    val p = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(220, 80, 90, 100)
                        textSize = 9.5f
                        isFakeBoldText = true
                    }
                    drawText("Text #${tIdx + 1} (Blanked)", tLeft + 3f, tTop + 11f, p)
                }
            }

            // 3. Waist Scanning Line
            val wy = split.waistY
            if (wy != null) {
                val lineY = wy * scaleY
                drawLine(
                    color = Color(0xFFFF4081),
                    start = Offset(origLeft - 10f, lineY),
                    end = Offset(origLeft + origWidth + 10f, lineY),
                    strokeWidth = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f)),
                )

                val ratioPct = if (split.waistRatio != null) "${((1f - split.waistRatio) * 100).toInt()}% dip" else "Waist"
                val waistLabel = "Waist Line Y=$wy ($ratioPct)"
                drawContext.canvas.nativeCanvas.apply {
                    val pBg = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(230, 40, 10, 25)
                        style = android.graphics.Paint.Style.FILL
                    }
                    val pBorder = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 255, 64, 129)
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 1.2f
                    }
                    val pText = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 255, 180, 200)
                        textSize = 11f
                        isFakeBoldText = true
                    }
                    val tw = pText.measureText(waistLabel)
                    val pH = 16f
                    val pX = origLeft + 6f
                    val pY = (lineY - pH - 3f).coerceAtLeast(origTop)
                    drawRoundRect(android.graphics.RectF(pX, pY, pX + tw + 10f, pY + pH), 4f, 4f, pBg)
                    drawRoundRect(android.graphics.RectF(pX, pY, pX + tw + 10f, pY + pH), 4f, 4f, pBorder)
                    drawText(waistLabel, pX + 5f, pY + 12f, pText)
                }
            }

            val wx = split.waistX
            if (wx != null) {
                val lineX = wx * scaleX
                drawLine(
                    color = Color(0xFFFF4081),
                    start = Offset(lineX, origTop - 10f),
                    end = Offset(lineX, origTop + origHeight + 10f),
                    strokeWidth = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f)),
                )
            }

            // 4. Search Window A with Inward Arrow
            val winA = split.searchWinA
            if (winA != null) {
                val waLeft = winA.left * scaleX
                val waTop = winA.top * scaleY
                val waWidth = winA.width() * scaleX
                val waHeight = winA.height() * scaleY

                drawRect(
                    color = Color(0x1800E5FF),
                    topLeft = Offset(waLeft, waTop),
                    size = Size(waWidth, waHeight),
                )
                drawRect(
                    color = Color(0xFF00E5FF),
                    topLeft = Offset(waLeft, waTop),
                    size = Size(waWidth, waHeight),
                    style = Stroke(
                        width = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
                    ),
                )

                // Directional Arrow inside Window A pointing inward (towards right)
                val arrowMidY = waTop + waHeight / 2f
                val arrowStartX = waLeft + 4f
                val arrowEndX = (waLeft + waWidth - 6f).coerceAtLeast(arrowStartX + 10f)
                drawLine(
                    color = Color(0xFF00E5FF),
                    start = Offset(arrowStartX, arrowMidY),
                    end = Offset(arrowEndX, arrowMidY),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = Color(0xFF00E5FF),
                    start = Offset(arrowEndX - 6f, arrowMidY - 5f),
                    end = Offset(arrowEndX, arrowMidY),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = Color(0xFF00E5FF),
                    start = Offset(arrowEndX - 6f, arrowMidY + 5f),
                    end = Offset(arrowEndX, arrowMidY),
                    strokeWidth = 2f,
                )

                drawContext.canvas.nativeCanvas.apply {
                    val pText = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 0, 229, 255)
                        textSize = 9.5f
                        isFakeBoldText = true
                    }
                    drawText("🔍 Search Win A ➔", waLeft + 3f, waTop + 12f, pText)
                }
            }

            // 5. Search Window B with Inward Arrow
            val winB = split.searchWinB
            if (winB != null) {
                val wbLeft = winB.left * scaleX
                val wbTop = winB.top * scaleY
                val wbWidth = winB.width() * scaleX
                val wbHeight = winB.height() * scaleY

                drawRect(
                    color = Color(0x18FFD54F),
                    topLeft = Offset(wbLeft, wbTop),
                    size = Size(wbWidth, wbHeight),
                )
                drawRect(
                    color = Color(0xFFFFD54F),
                    topLeft = Offset(wbLeft, wbTop),
                    size = Size(wbWidth, wbHeight),
                    style = Stroke(
                        width = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
                    ),
                )

                // Directional Arrow inside Window B pointing inward (towards left)
                val arrowMidY = wbTop + wbHeight / 2f
                val arrowStartX = wbLeft + wbWidth - 4f
                val arrowEndX = (wbLeft + 6f).coerceAtMost(arrowStartX - 10f)
                drawLine(
                    color = Color(0xFFFFD54F),
                    start = Offset(arrowStartX, arrowMidY),
                    end = Offset(arrowEndX, arrowMidY),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = Color(0xFFFFD54F),
                    start = Offset(arrowEndX + 6f, arrowMidY - 5f),
                    end = Offset(arrowEndX, arrowMidY),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = Color(0xFFFFD54F),
                    start = Offset(arrowEndX + 6f, arrowMidY + 5f),
                    end = Offset(arrowEndX, arrowMidY),
                    strokeWidth = 2f,
                )

                drawContext.canvas.nativeCanvas.apply {
                    val pText = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 255, 213, 79)
                        textSize = 9.5f
                        isFakeBoldText = true
                    }
                    drawText("⬅ 🔍 Search Win B", wbLeft + 3f, wbTop + 12f, pText)
                }
            }

            // 6. Crunch Point A
            val ptA = split.crunchPointA
            if (ptA != null) {
                val ax = ptA.x * scaleX; val ay = ptA.y * scaleY
                drawCircle(color = Color(0x8800E5FF), radius = 13f, center = Offset(ax, ay))
                drawCircle(color = Color(0xFF00E5FF), radius = 5.5f, center = Offset(ax, ay))
                drawCircle(color = Color.White, radius = 2f, center = Offset(ax, ay))
                drawLine(color = Color(0xFF00E5FF), start = Offset(ax - 14f, ay), end = Offset(ax + 14f, ay), strokeWidth = 2f)
                drawLine(color = Color(0xFF00E5FF), start = Offset(ax, ay - 14f), end = Offset(ax, ay + 14f), strokeWidth = 2f)
                drawContext.canvas.nativeCanvas.apply {
                    val label = "Crunch Point A (${ptA.x}, ${ptA.y})"
                    val pBg = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(230, 0, 45, 55)
                        style = android.graphics.Paint.Style.FILL
                    }
                    val pBorder = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 0, 229, 255)
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 1f
                    }
                    val pText = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 128, 240, 255)
                        textSize = 10.5f
                        isFakeBoldText = true
                    }
                    val tw = pText.measureText(label)
                    val pH = 15f
                    val px = ax + 8f
                    val py = (ay - pH - 2f).coerceAtLeast(origTop)
                    drawRoundRect(android.graphics.RectF(px, py, px + tw + 8f, py + pH), 4f, 4f, pBg)
                    drawRoundRect(android.graphics.RectF(px, py, px + tw + 8f, py + pH), 4f, 4f, pBorder)
                    drawText(label, px + 4f, py + 11f, pText)
                }
            }

            // 7. Crunch Point B
            val ptB = split.crunchPointB
            if (ptB != null) {
                val bx = ptB.x * scaleX; val by = ptB.y * scaleY
                drawCircle(color = Color(0x88FFD54F), radius = 13f, center = Offset(bx, by))
                drawCircle(color = Color(0xFFFFD54F), radius = 5.5f, center = Offset(bx, by))
                drawCircle(color = Color.White, radius = 2f, center = Offset(bx, by))
                drawLine(color = Color(0xFFFFD54F), start = Offset(bx - 14f, by), end = Offset(bx + 14f, by), strokeWidth = 2f)
                drawLine(color = Color(0xFFFFD54F), start = Offset(bx, by - 14f), end = Offset(bx, by + 14f), strokeWidth = 2f)
                drawContext.canvas.nativeCanvas.apply {
                    val label = "Crunch Point B (${ptB.x}, ${ptB.y})"
                    val pBg = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(230, 50, 40, 10)
                        style = android.graphics.Paint.Style.FILL
                    }
                    val pBorder = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 255, 213, 79)
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 1f
                    }
                    val pText = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 255, 235, 150)
                        textSize = 10.5f
                        isFakeBoldText = true
                    }
                    val tw = pText.measureText(label)
                    val pH = 15f
                    val px = bx - tw - 12f
                    val py = (by - pH - 2f).coerceAtLeast(origTop)
                    drawRoundRect(android.graphics.RectF(px, py, px + tw + 8f, py + pH), 4f, 4f, pBg)
                    drawRoundRect(android.graphics.RectF(px, py, px + tw + 8f, py + pH), 4f, 4f, pBorder)
                    drawText(label, px + 4f, py + 11f, pText)
                }
            }

            // 8. Header Pill
            drawContext.canvas.nativeCanvas.apply {
                val header = "Compound Bubble #${sIdx + 1}: Silhouette & Waist Analysis"
                val pText = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 11.5f
                    isFakeBoldText = true
                }
                val tw = pText.measureText(header)
                val pH = 17f
                val pBg = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(230, 20, 25, 30)
                    style = android.graphics.Paint.Style.FILL
                }
                val pBorder = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(255, 100, 120, 140)
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 1f
                }
                val pX = origLeft + 4f
                val pY = (origTop - pH - 4f).coerceAtLeast(2f)
                drawRoundRect(android.graphics.RectF(pX, pY, pX + tw + 10f, pY + pH), 4f, 4f, pBg)
                drawRoundRect(android.graphics.RectF(pX, pY, pX + tw + 10f, pY + pH), 4f, 4f, pBorder)
                drawText(header, pX + 5f, pY + 12f, pText)
            }
        }
    }
}

/**
 * Screen 4c. Crunch 2: Seam Line & Obstacle Deflections Overlay
 * Shows original conjoined bubble envelopes, laser cut seam line connecting A and B,
 * clashing obstacle text boxes with deflection waypoints W1, W2...
 */
@Composable
fun Crunch2DetailedOverlay(
    splits: List<CrunchSplitter.SplitResult>,
    imageWidth: Float,
    imageHeight: Float,
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 0f || imageHeight <= 0f || splits.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        for ((sIdx, split) in splits.withIndex()) {
            val orig = split.originalRect ?: continue
            val origLeft = orig.left * scaleX
            val origTop = orig.top * scaleY
            val origWidth = orig.width() * scaleX
            val origHeight = orig.height() * scaleY

            // 1. Original envelope (dashed red)
            drawRect(
                color = Color(0x18FF5252),
                topLeft = Offset(origLeft, origTop),
                size = Size(origWidth, origHeight),
            )
            drawRect(
                color = Color(0xFFFF5252),
                topLeft = Offset(origLeft, origTop),
                size = Size(origWidth, origHeight),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                ),
            )

            // Envelope badge
            drawContext.canvas.nativeCanvas.apply {
                val bgPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(220, 60, 15, 15)
                    style = android.graphics.Paint.Style.FILL
                }
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(255, 255, 120, 120)
                    textSize = 12f
                    isFakeBoldText = true
                }
                val label = "Conjoined #${sIdx + 1} (${orig.width()}x${orig.height()})"
                val tw = textPaint.measureText(label)
                val tagH = 16f
                val tagY = (origTop - tagH).coerceAtLeast(0f)
                drawRect(origLeft, tagY, origLeft + tw + 8f, tagY + tagH, bgPaint)
                drawText(label, origLeft + 4f, tagY + 12f, textPaint)
            }

            // 2. Waist Scanning Line
            val wy = split.waistY
            if (wy != null) {
                val lineY = wy * scaleY
                drawLine(
                    color = Color(0xFF00E5FF),
                    start = Offset(origLeft, lineY),
                    end = Offset(origLeft + origWidth, lineY),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )

                // Waist label pill
                val ratioPct = if (split.waistRatio != null) "${((1f - split.waistRatio) * 100).toInt()}% dip" else "Waist"
                val waistLabel = "Waist Y=$wy ($ratioPct)"
                drawContext.canvas.nativeCanvas.apply {
                    val pBg = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(230, 0, 40, 50)
                        style = android.graphics.Paint.Style.FILL
                    }
                    val pBorder = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 0, 229, 255)
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 1.2f
                    }
                    val pText = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 128, 240, 255)
                        textSize = 11f
                        isFakeBoldText = true
                    }
                    val tw = pText.measureText(waistLabel)
                    val pH = 15f
                    val pX = origLeft + 4f
                    val pY = (lineY - pH - 2f).coerceAtLeast(origTop)
                    drawRoundRect(android.graphics.RectF(pX, pY, pX + tw + 8f, pY + pH), 4f, 4f, pBg)
                    drawRoundRect(android.graphics.RectF(pX, pY, pX + tw + 8f, pY + pH), 4f, 4f, pBorder)
                    drawText(waistLabel, pX + 4f, pY + 11f, pText)
                }
            }

            // 3. Notch Search Windows (A & B)
            val winA = split.searchWinA
            if (winA != null) {
                val waLeft = winA.left * scaleX
                val waTop = winA.top * scaleY
                val waWidth = winA.width() * scaleX
                val waHeight = winA.height() * scaleY
                drawRect(
                    color = Color(0x1500E5FF),
                    topLeft = Offset(waLeft, waTop),
                    size = Size(waWidth, waHeight),
                )
                drawRect(
                    color = Color(0xFF00E5FF),
                    topLeft = Offset(waLeft, waTop),
                    size = Size(waWidth, waHeight),
                    style = Stroke(
                        width = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                    ),
                )
                // Window A label
                drawContext.canvas.nativeCanvas.apply {
                    val pText = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 0, 229, 255)
                        textSize = 10f
                        isFakeBoldText = true
                    }
                    val label = "🔍 Win A [±10px]"
                    drawText(label, waLeft + 2f, waTop + 11f, pText)
                }
            }

            val winB = split.searchWinB
            if (winB != null) {
                val wbLeft = winB.left * scaleX
                val wbTop = winB.top * scaleY
                val wbWidth = winB.width() * scaleX
                val wbHeight = winB.height() * scaleY
                drawRect(
                    color = Color(0x15FFD54F),
                    topLeft = Offset(wbLeft, wbTop),
                    size = Size(wbWidth, wbHeight),
                )
                drawRect(
                    color = Color(0xFFFFD54F),
                    topLeft = Offset(wbLeft, wbTop),
                    size = Size(wbWidth, wbHeight),
                    style = Stroke(
                        width = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                    ),
                )
                // Window B label
                drawContext.canvas.nativeCanvas.apply {
                    val pText = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 255, 213, 79)
                        textSize = 10f
                        isFakeBoldText = true
                    }
                    val label = "🔍 Win B [±10px]"
                    drawText(label, wbLeft + 2f, wbTop + 11f, pText)
                }
            }

            // 4. Text Obstacles / Clashing Lines
            for ((cIdx, clash) in split.clashingLines.withIndex()) {
                val cLeft = clash.left * scaleX
                val cTop = clash.top * scaleY
                val cWidth = clash.width() * scaleX
                val cHeight = clash.height() * scaleY

                drawRect(
                    color = Color(0x35FF3D00),
                    topLeft = Offset(cLeft, cTop),
                    size = Size(cWidth, cHeight),
                )
                drawRect(
                    color = Color(0xFFFF3D00),
                    topLeft = Offset(cLeft, cTop),
                    size = Size(cWidth, cHeight),
                    style = Stroke(
                        width = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 3f)),
                    ),
                )

                // Obstacle badge
                drawContext.canvas.nativeCanvas.apply {
                    val bgPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(240, 50, 10, 5)
                        style = android.graphics.Paint.Style.FILL
                    }
                    val borderPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 255, 61, 0)
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 1f
                    }
                    val textPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 255, 138, 101)
                        textSize = 10f
                        isFakeBoldText = true
                    }
                    val label = "⚠️ Clash #${cIdx + 1}"
                    val tw = textPaint.measureText(label)
                    val pH = 14f
                    val pY = (cTop - pH - 1f).coerceAtLeast(origTop)
                    drawRoundRect(android.graphics.RectF(cLeft, pY, cLeft + tw + 6f, pY + pH), 3f, 3f, bgPaint)
                    drawRoundRect(android.graphics.RectF(cLeft, pY, cLeft + tw + 6f, pY + pH), 3f, 3f, borderPaint)
                    drawText(label, cLeft + 3f, pY + 10f, textPaint)
                }
            }

            // 5. Resulting Sub-Lobes
            val lobeColors = listOf(Color(0xFF00E5FF), Color(0xFF00E676), Color(0xFFE040FB), Color(0xFFFFD54F))
            for ((lIdx, lobe) in split.splitLobeRects.withIndex()) {
                val lLeft = lobe.left * scaleX
                val lTop = lobe.top * scaleY
                val lWidth = lobe.width() * scaleX
                val lHeight = lobe.height() * scaleY
                val col = lobeColors[lIdx % lobeColors.size]

                drawRect(
                    color = col.copy(alpha = 0.12f),
                    topLeft = Offset(lLeft, lTop),
                    size = Size(lWidth, lHeight),
                )
                drawRect(
                    color = col,
                    topLeft = Offset(lLeft, lTop),
                    size = Size(lWidth, lHeight),
                    style = Stroke(width = 2.2f),
                )

                // Lobe pill badge
                drawContext.canvas.nativeCanvas.apply {
                    val badge = "✂️ Lobe #${lIdx + 1}"
                    val tPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 11f
                        isFakeBoldText = true
                    }
                    val tw = tPaint.measureText(badge)
                    val bPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(220, 20, 20, 25)
                        style = android.graphics.Paint.Style.FILL
                    }
                    val brPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, (col.red * 255).toInt(), (col.green * 255).toInt(), (col.blue * 255).toInt())
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 1.2f
                    }
                    val pillX = lLeft + 4f
                    val pillY = lTop + 4f
                    drawRoundRect(android.graphics.RectF(pillX, pillY, pillX + tw + 10f, pillY + 16f), 4f, 4f, bPaint)
                    drawRoundRect(android.graphics.RectF(pillX, pillY, pillX + tw + 10f, pillY + 16f), 4f, 4f, brPaint)
                    drawText(badge, pillX + 5f, pillY + 12f, tPaint)
                }
            }

            // 6. Laser Cut Seam Line with Deflection Waypoints
            val cutPts = split.cutLinePoints
            if (cutPts.size >= 2) {
                val path = Path().apply {
                    moveTo(cutPts[0].x * scaleX, cutPts[0].y * scaleY)
                    for (i in 1 until cutPts.size) {
                        lineTo(cutPts[i].x * scaleX, cutPts[i].y * scaleY)
                    }
                }
                // Outer glow
                drawPath(
                    path = path,
                    color = Color(0x7776FF03),
                    style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                // Inner bright dashed laser line
                drawPath(
                    path = path,
                    color = Color(0xFF76FF03),
                    style = Stroke(
                        width = 3f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f)),
                    ),
                )

                // Waypoint markers
                for ((wIdx, pt) in cutPts.withIndex()) {
                    val wx = pt.x * scaleX
                    val wyPt = pt.y * scaleY
                    drawCircle(
                        color = Color(0xFF76FF03),
                        radius = 4f,
                        center = Offset(wx, wyPt),
                    )
                    drawCircle(
                        color = Color.Black,
                        radius = 2f,
                        center = Offset(wx, wyPt),
                    )
                    // Tag label
                    drawContext.canvas.nativeCanvas.apply {
                        val wpText = if (wIdx == 0) "A" else if (wIdx == cutPts.size - 1) "B" else "W$wIdx"
                        val pText = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 10f
                            isFakeBoldText = true
                        }
                        drawText(wpText, wx + 4f, wyPt - 4f, pText)
                    }
                }

                // Strategy tag badge
                if (split.cutStrategy != null) {
                    val midPt = cutPts[cutPts.size / 2]
                    val mx = midPt.x * scaleX
                    val my = (midPt.y * scaleY - 18f).coerceAtLeast(origTop)
                    drawContext.canvas.nativeCanvas.apply {
                        val sText = "⚡ ${split.cutStrategy}"
                        val pText = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(255, 118, 255, 3)
                            textSize = 11f
                            isFakeBoldText = true
                        }
                        val tw = pText.measureText(sText)
                        val pBg = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(230, 15, 30, 15)
                            style = android.graphics.Paint.Style.FILL
                        }
                        val pBorder = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(255, 118, 255, 3)
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = 1f
                        }
                        val px = (mx - tw / 2).coerceAtLeast(origLeft)
                        drawRoundRect(android.graphics.RectF(px, my, px + tw + 8f, my + 15f), 4f, 4f, pBg)
                        drawRoundRect(android.graphics.RectF(px, my, px + tw + 8f, my + 15f), 4f, 4f, pBorder)
                        drawText(sText, px + 4f, my + 11f, pText)
                    }
                }
            }

            // 7. Crunch Points A & B Crosshairs
            val ptA = split.crunchPointA
            if (ptA != null) {
                val ax = ptA.x * scaleX; val ay = ptA.y * scaleY
                drawCircle(color = Color(0x8800E5FF), radius = 12f, center = Offset(ax, ay))
                drawCircle(color = Color(0xFF00E5FF), radius = 5f, center = Offset(ax, ay))
                drawCircle(color = Color.White, radius = 2f, center = Offset(ax, ay))
                drawLine(color = Color(0xFF00E5FF), start = Offset(ax - 12f, ay), end = Offset(ax + 12f, ay), strokeWidth = 2f)
                drawLine(color = Color(0xFF00E5FF), start = Offset(ax, ay - 12f), end = Offset(ax, ay + 12f), strokeWidth = 2f)
                drawContext.canvas.nativeCanvas.apply {
                    val label = "Crunch A (${ptA.x},${ptA.y})"
                    val pText = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 0, 229, 255)
                        textSize = 10.5f
                        isFakeBoldText = true
                    }
                    drawText(label, ax + 8f, ay - 6f, pText)
                }
            }

            val ptB = split.crunchPointB
            if (ptB != null) {
                val bx = ptB.x * scaleX; val by = ptB.y * scaleY
                drawCircle(color = Color(0x88FFD54F), radius = 12f, center = Offset(bx, by))
                drawCircle(color = Color(0xFFFFD54F), radius = 5f, center = Offset(bx, by))
                drawCircle(color = Color.White, radius = 2f, center = Offset(bx, by))
                drawLine(color = Color(0xFFFFD54F), start = Offset(bx - 12f, by), end = Offset(bx + 12f, by), strokeWidth = 2f)
                drawLine(color = Color(0xFFFFD54F), start = Offset(bx, by - 12f), end = Offset(bx, by + 12f), strokeWidth = 2f)
                drawContext.canvas.nativeCanvas.apply {
                    val label = "Crunch B (${ptB.x},${ptB.y})"
                    val pText = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 255, 213, 79)
                        textSize = 10.5f
                        isFakeBoldText = true
                    }
                    drawText(label, bx - pText.measureText(label) - 8f, by - 6f, pText)
                }
            }
        }
    }
}

/**
 * Screen 4d. Crunch 3: Separated Lobes & Text Allocation Overlay
 * Displays fully separated conjoined bubble lobes on a pure white canvas (everything else is white).
 * Lobe 1 in vibrant Cyan and Lobe 2 in vibrant Emerald, with their respective text lines
 * neatly assigned and color-coded inside.
 */
@Composable
fun Crunch3SeparatedLobesOverlay(
    splits: List<CrunchSplitter.SplitResult>,
    imageWidth: Float,
    imageHeight: Float,
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 0f || imageHeight <= 0f) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        if (splits.isEmpty()) {
            drawContext.canvas.nativeCanvas.apply {
                val p = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(180, 100, 100, 100)
                    textSize = 32f
                    isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText("✓ All Bubbles Separated into Clean Lobes", size.width / 2f, size.height / 2f, p)
            }
            return@Canvas
        }

        for ((sIdx, split) in splits.withIndex()) {
            val lobes = split.splitLobeRects
            if (lobes.isEmpty()) continue

            // 1. Draw Separated Lobes (Lobe 1 Cyan, Lobe 2 Emerald)
            val lobeColors = listOf(Color(0xFF00E5FF), Color(0xFF00E676), Color(0xFFE040FB), Color(0xFFFFD54F))
            for ((lIdx, lobe) in lobes.withIndex()) {
                val lLeft = lobe.left * scaleX
                val lTop = lobe.top * scaleY
                val lWidth = lobe.width() * scaleX
                val lHeight = lobe.height() * scaleY
                val color = lobeColors[lIdx % lobeColors.size]

                // Soft lobe tint
                drawRect(
                    color = color.copy(alpha = 0.16f),
                    topLeft = Offset(lLeft, lTop),
                    size = Size(lWidth, lHeight),
                )
                // Solid bold lobe border
                drawRect(
                    color = color,
                    topLeft = Offset(lLeft, lTop),
                    size = Size(lWidth, lHeight),
                    style = Stroke(width = 2.8f),
                )

                // Lobe pill badge
                drawContext.canvas.nativeCanvas.apply {
                    val badge = "✂️ Lobe #${lIdx + 1} (${lobe.width()}x${lobe.height()})"
                    val tPaint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.WHITE
                        textSize = 11.5f
                        isFakeBoldText = true
                    }
                    val tw = tPaint.measureText(badge)
                    val bPaint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.argb(230, 20, 20, 25)
                        style = android.graphics.Paint.Style.FILL
                    }
                    val brPaint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 1.4f
                    }
                    val pillX = lLeft + 4f
                    val pillY = (lTop - 18f).coerceAtLeast(2f)
                    drawRoundRect(android.graphics.RectF(pillX, pillY, pillX + tw + 10f, pillY + 16f), 4f, 4f, bPaint)
                    drawRoundRect(android.graphics.RectF(pillX, pillY, pillX + tw + 10f, pillY + 16f), 4f, 4f, brPaint)
                    drawText(badge, pillX + 5f, pillY + 12f, tPaint)
                }
            }

            // 2. Draw Assigned Text Lines inside Each Lobe
            for (line in split.insideLines) {
                var bestLobeIdx = 0
                var bestOverlap = -1
                lobes.forEachIndexed { idx, lobe ->
                    val ix = max(0, min(lobe.right, line.right) - max(lobe.left, line.left))
                    val iy = max(0, min(lobe.bottom, line.bottom) - max(lobe.top, line.top))
                    val overlap = ix * iy
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap
                        bestLobeIdx = idx
                    }
                }
                val assignedColor = lobeColors[bestLobeIdx % lobeColors.size]

                val tLeft = line.left * scaleX
                val tTop = line.top * scaleY
                val tWidth = line.width() * scaleX
                val tHeight = line.height() * scaleY

                drawRect(
                    color = assignedColor.copy(alpha = 0.20f),
                    topLeft = Offset(tLeft, tTop),
                    size = Size(tWidth, tHeight),
                )
                drawRect(
                    color = assignedColor,
                    topLeft = Offset(tLeft, tTop),
                    size = Size(tWidth, tHeight),
                    style = Stroke(width = 2f),
                )

                // Assigned badge on text
                drawContext.canvas.nativeCanvas.apply {
                    val pText = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 9.5f
                        isFakeBoldText = true
                    }
                    val tag = "L${bestLobeIdx + 1} Text"
                    val tw = pText.measureText(tag)
                    val pBg = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(230, (assignedColor.red * 150).toInt(), (assignedColor.green * 150).toInt(), (assignedColor.blue * 150).toInt())
                        style = android.graphics.Paint.Style.FILL
                    }
                    val px = tLeft + 2f
                    val py = tTop + 2f
                    drawRoundRect(android.graphics.RectF(px, py, px + tw + 6f, py + 12f), 3f, 3f, pBg)
                    drawText(tag, px + 3f, py + 9.5f, pText)
                }
            }

            // 3. Draw Laser Cut Seam Dividing the Lobes
            val cutPts = split.cutLinePoints
            if (cutPts.size >= 2) {
                val path = Path().apply {
                    moveTo(cutPts[0].x * scaleX, cutPts[0].y * scaleY)
                    for (i in 1 until cutPts.size) {
                        lineTo(cutPts[i].x * scaleX, cutPts[i].y * scaleY)
                    }
                }
                drawPath(
                    path = path,
                    color = Color.White,
                    style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path = path,
                    color = Color(0xFFFFD54F),
                    style = Stroke(
                        width = 2.2f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                    ),
                )
            }
        }
    }
}

/**
 * Contextual Step Gallery for Screen 4d. Crunch 3
 * Shows cards for each separated lobe produced by crunch splitting.
 */
@Composable
fun Crunch3LobesGallery(
    splits: List<CrunchSplitter.SplitResult>,
    selectedRect: Rect? = null,
    onSelectBox: (Rect, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    if (splits.isEmpty()) {
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B231D)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.5f)),
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✓", fontSize = 18.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("All Bubbles Cleanly Separated", fontSize = 13.sp, color = Color(0xFF81C784), fontWeight = FontWeight.Bold)
                    Text("Zero conjoined compound bubble waists remain on this page.", fontSize = 11.sp, color = Color.Gray)
                }
            }
        }
        return
    }

    val lobeItems = remember(splits) {
        val list = mutableListOf<Triple<Rect, String, Color>>()
        splits.forEachIndexed { sIdx, split ->
            val lobes = split.splitLobeRects
            if (lobes.isNotEmpty()) {
                list.add(Triple(lobes[0], "Lobe 1 (S#${sIdx + 1})", Color(0xFF00E5FF)))
            }
            if (lobes.size > 1) {
                list.add(Triple(lobes[1], "Lobe 2 (S#${sIdx + 1})", Color(0xFF00E676)))
            }
            for (i in 2 until lobes.size) {
                list.add(Triple(lobes[i], "Lobe #${i + 1} (S#${sIdx + 1})", Color(0xFFE040FB)))
            }
        }
        list
    }

    LaunchedEffect(selectedRect) {
        if (selectedRect != null) {
            val idx = lobeItems.indexOfFirst {
                it.first == selectedRect ||
                    (it.first.left == selectedRect.left && it.first.top == selectedRect.top &&
                     it.first.right == selectedRect.right && it.first.bottom == selectedRect.bottom)
            }
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
    ) {
        itemsIndexed(lobeItems) { idx, (rect, label, color) ->
            val isSelected = selectedRect == rect
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(0xFF1E2833) else Color(0xFF131A22)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) Color(0xFFFFEA00) else color.copy(alpha = 0.7f),
                ),
                modifier = Modifier
                    .width(160.dp)
                    .clickable { onSelectBox(rect, label) },
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Surface(
                            color = Color(0xFF222B35),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                "${rect.width()}x${rect.height()}",
                                color = Color.LightGray,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Pos: (${rect.left}, ${rect.top})", color = Color.Gray, fontSize = 9.5.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("✂️ Separated Unit", color = color.copy(alpha = 0.85f), fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * Shows detailed cards for each conjoined split:
 * - Bubble size & coordinates
 * - Waist level & constriction ratio
 * - Search windows A & B
 * - Crunch points A & B
 * - Clashing text obstacles
 * - Laser deflection strategy & waypoints
 */
@Composable
fun Crunch2InspectionGallery(
    splits: List<CrunchSplitter.SplitResult>,
    selectedRect: Rect? = null,
    onSelectBox: (Rect, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    if (splits.isEmpty()) {
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B231D)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.5f)),
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✓", fontSize = 18.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("No Conjoined Bubbles Detected", fontSize = 13.sp, color = Color(0xFF81C784), fontWeight = FontWeight.Bold)
                    Text("All speech balloons in this page have clean single-lobe geometries.", fontSize = 11.sp, color = Color.Gray)
                }
            }
        }
        return
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
    ) {
        itemsIndexed(splits) { idx, split ->
            val orig = split.originalRect ?: Rect()
            val isSelected = selectedRect == orig
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(0xFF381B1B) else Color(0xFF1E1E24)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) Color(0xFFFFEA00) else Color(0xFF76FF03).copy(alpha = 0.6f),
                ),
                modifier = Modifier
                    .width(180.dp)
                    .clickable { onSelectBox(orig, "Conjoined #${idx + 1}") },
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Split #${idx + 1}", color = Color(0xFF76FF03), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Surface(
                            color = Color(0xFF2A2A35),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                "${orig.width()}x${orig.height()}",
                                color = Color.LightGray,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        split.cutStrategy ?: "Laser Cut",
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // Metrics
                    val waistText = if (split.waistY != null) "Waist Y=${split.waistY}" else if (split.waistX != null) "Waist X=${split.waistX}" else "Waist: N/A"
                    val ratioText = if (split.waistRatio != null) "${((1f - split.waistRatio) * 100).toInt()}% dip" else ""
                    Text("$waistText · $ratioText", color = Color(0xFF80D8FF), fontSize = 9.5.sp)

                    val aText = if (split.crunchPointA != null) "A: (${split.crunchPointA.x}, ${split.crunchPointA.y})" else "A: None"
                    val bText = if (split.crunchPointB != null) "B: (${split.crunchPointB.x}, ${split.crunchPointB.y})" else "B: None"
                    Text("$aText · $bText", color = Color(0xFFFFD54F), fontSize = 9.sp)

                    val clashCount = split.clashingLines.size
                    val clashColor = if (clashCount > 0) Color(0xFFFF5252) else Color(0xFF00E676)
                    Text(
                        if (clashCount > 0) "⚠️ $clashCount clashing obstacles" else "✓ 0 clashes (clean cross)",
                        color = clashColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "Produces ${split.splitLobeRects.size} sub-lobes · ${split.cutLinePoints.size} waypoints",
                        color = Color.Gray,
                        fontSize = 8.5.sp,
                    )
                }
            }
        }
    }
}

