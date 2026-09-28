package com.raen.kisaratranslator.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Universal Before/After Comparison Slider Container with Pinch-to-Zoom & Pan.
 *
 * Wraps any pipeline display step (1. Det 1, 2. Det 2, 3. Lines, 4. Group, 4b. Chunks,
 * 4c. Crunch 2, 5. OCR, 6. Inpaint, 7. Final) with:
 * - Pinch-to-zoom (up to 6x) & 1-finger pan across the high-res manga page.
 * - Double-tap to quickly toggle between 2.5x zoom and 1x full view.
 * - Interactive horizontal comparison split between clean original and step overlays.
 * - Preserves vertical scrolling when at 1x zoom, while capturing pan gestures when zoomed in.
 */
@Composable
fun StepComparisonSliderContainer(
    originalBitmap: Bitmap,
    stepLabel: String,
    modifier: Modifier = Modifier,
    initialSplitFraction: Float = 0.0f,
    dragEnabled: Boolean = true,
    zoomEnabled: Boolean = true,
    onTap: ((Offset, Float, Float) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var splitFraction by remember(originalBitmap, stepLabel) { mutableFloatStateOf(initialSplitFraction) }
    var scale by remember(originalBitmap) { mutableFloatStateOf(1.0f) }
    var offsetX by remember(originalBitmap) { mutableFloatStateOf(0f) }
    var offsetY by remember(originalBitmap) { mutableFloatStateOf(0f) }

    if (!zoomEnabled && scale != 1.0f) {
        scale = 1.0f
        offsetX = 0f
        offsetY = 0f
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .pointerInput(originalBitmap, stepLabel, zoomEnabled, onTap) {
                detectTapGestures(
                    onDoubleTap = if (zoomEnabled) {
                        { tapOffset ->
                            if (scale > 1.05f) {
                                scale = 1.0f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                val targetScale = 2.5f
                                val maxOffX = (size.width * (targetScale - 1f)) / 2f
                                val maxOffY = (size.height * (targetScale - 1f)) / 2f
                                scale = targetScale
                                offsetX = ((size.width / 2f - tapOffset.x) * (targetScale - 1f)).coerceIn(-maxOffX, maxOffX)
                                offsetY = ((size.height / 2f - tapOffset.y) * (targetScale - 1f)).coerceIn(-maxOffY, maxOffY)
                            }
                        }
                    } else null,
                    onTap = { tapOffset ->
                        if (onTap != null) {
                            val centerX = size.width / 2f
                            val centerY = size.height / 2f
                            val unscaledX = centerX + (tapOffset.x - centerX - offsetX) / scale
                            val unscaledY = centerY + (tapOffset.y - centerY - offsetY) / scale
                            onTap(Offset(unscaledX, unscaledY), size.width.toFloat(), size.height.toFloat())
                        }
                    },
                )
            }
            .pointerInput(originalBitmap, stepLabel, dragEnabled, zoomEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val touchSlop = viewConfiguration.touchSlop
                    var isDraggingSplit = false
                    var isVerticalScroll = false
                    var totalPanX = 0f
                    var totalPanY = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (zoomEnabled && pressed.size >= 2) {
                            // Two-finger pinch / zoom & pan
                            val p0 = pressed[0].position
                            val p1 = pressed[1].position
                            val prevP0 = pressed[0].previousPosition
                            val prevP1 = pressed[1].previousPosition

                            val currDist = (p0 - p1).getDistance()
                            val prevDist = (prevP0 - prevP1).getDistance()

                            if (prevDist > 0f) {
                                val zoomFactor = currDist / prevDist
                                val newScale = (scale * zoomFactor).coerceIn(1.0f, 6.0f)
                                scale = newScale
                            }

                            val centroid = (p0 + p1) / 2f
                            val prevCentroid = (prevP0 + prevP1) / 2f
                            val pan = centroid - prevCentroid

                            val maxOffX = (size.width * (scale - 1f)) / 2f
                            val maxOffY = (size.height * (scale - 1f)) / 2f
                            offsetX = (offsetX + pan.x).coerceIn(-maxOffX, maxOffX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxOffY, maxOffY)

                            pressed.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            val change = pressed[0]
                            val pan = change.positionChange()

                            if (zoomEnabled && scale > 1.05f) {
                                // Zoomed in: 1-finger pan freely across the page
                                val maxOffX = (size.width * (scale - 1f)) / 2f
                                val maxOffY = (size.height * (scale - 1f)) / 2f
                                offsetX = (offsetX + pan.x).coerceIn(-maxOffX, maxOffX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxOffY, maxOffY)
                                change.consume()
                            } else if (dragEnabled && !isVerticalScroll) {
                                // At 1x zoom: discriminate horizontal comparison drag from vertical page scroll
                                totalPanX += pan.x
                                totalPanY += pan.y

                                if (isDraggingSplit) {
                                    splitFraction = (change.position.x / size.width.toFloat()).coerceIn(0.0f, 1.0f)
                                    change.consume()
                                } else if (kotlin.math.abs(totalPanX) > touchSlop && kotlin.math.abs(totalPanX) > kotlin.math.abs(totalPanY)) {
                                    isDraggingSplit = true
                                    splitFraction = (change.position.x / size.width.toFloat()).coerceIn(0.0f, 1.0f)
                                    change.consume()
                                } else if (kotlin.math.abs(totalPanY) > touchSlop && kotlin.math.abs(totalPanY) >= kotlin.math.abs(totalPanX)) {
                                    isVerticalScroll = true
                                    // Do NOT consume: let outer Column verticalScroll scroll the page!
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val currentSplitX = (widthPx * splitFraction).coerceIn(0f, widthPx)

        // 1. Step Content: Full view when splitFraction <= 0.005f, otherwise clipped to right of splitX
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(
                    if (splitFraction > 0.005f) {
                        object : Shape {
                            override fun createOutline(
                                size: Size,
                                layoutDirection: LayoutDirection,
                                density: Density,
                            ): Outline {
                                val splitX = (size.width * splitFraction).coerceIn(0f, size.width)
                                return Outline.Rectangle(Rect(splitX, 0f, size.width, size.height))
                            }
                        }
                    } else {
                        RectangleShape
                    }
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
            ) {
                content()
            }
        }

        // 2. Original Image on Left: clipped from 0 to currentSplitX
        if (splitFraction > 0.005f && !originalBitmap.isRecycled) {
            val originalImageBitmap = remember(originalBitmap) { originalBitmap.asImageBitmap() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(
                        object : Shape {
                            override fun createOutline(
                                size: Size,
                                layoutDirection: LayoutDirection,
                                density: Density,
                            ): Outline {
                                val splitX = (size.width * splitFraction).coerceIn(0f, size.width)
                                return Outline.Rectangle(Rect(0f, 0f, splitX, size.height))
                            }
                        }
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                ) {
                    Image(
                        bitmap = originalImageBitmap,
                        contentDescription = "Original Clean Page",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // 3. Vertical Divider Line (when split is active)
        if (splitFraction > 0.005f && splitFraction < 0.995f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val splitX = size.width * splitFraction
                drawLine(
                    color = Color.White,
                    start = Offset(splitX, 0f),
                    end = Offset(splitX, size.height),
                    strokeWidth = 2.5.dp.toPx(),
                )
            }
        }

        // 4. Circular Handle with SwapHoriz icon
        // Show if split is active (> 0.02f) OR when at 1x zoom (parked at edge)
        if (splitFraction > 0.02f || scale <= 1.05f) {
            val density = LocalDensity.current
            val handleRadiusPx = with(density) { 18.dp.toPx() }
            val edgePaddingPx = with(density) { 20.dp.toPx() }
            val handleX = if (splitFraction <= 0.02f) {
                edgePaddingPx
            } else {
                currentSplitX.coerceIn(handleRadiusPx, widthPx - handleRadiusPx)
            }

            Surface(
                modifier = Modifier
                    .offset { IntOffset((handleX - handleRadiusPx).roundToInt(), 0) }
                    .size(36.dp)
                    .clickable {
                        // Tap handle to toggle 50/50 comparison vs full view
                        splitFraction = if (splitFraction < 0.10f) 0.50f else 0.0f
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Slide to compare with original",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // 5. Comparison Badges (when split is active)
        if (splitFraction > 0.05f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            ) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                ) {
                    Text(
                        text = "Original B&W",
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                Surface(
                    modifier = Modifier.align(Alignment.TopEnd),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                ) {
                    Text(
                        text = stepLabel,
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // 6. Zoom Level Badge & Reset Button (visible when zoomed in)
        if (scale > 1.05f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clickable {
                        scale = 1.0f
                        offsetX = 0f
                        offsetY = 0f
                    },
                shape = RoundedCornerShape(16.dp),
                color = Color(0xEE181820),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Reset Zoom",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${String.format(java.util.Locale.US, "%.1f", scale)}x  Reset",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
