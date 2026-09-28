package com.raen.kisaratranslator.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun BeforeAfterTranslationSlider(
    beforeBitmap: Bitmap,
    afterBitmap: Bitmap,
    modifier: Modifier = Modifier,
) {
    var splitFraction by remember { mutableFloatStateOf(0.5f) }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val widthPx = size.width.toFloat()
                        if (widthPx > 0f) {
                            splitFraction = (offset.x / widthPx).coerceIn(0.01f, 0.99f)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val widthPx = size.width.toFloat()
                        if (widthPx > 0f) {
                            splitFraction = (change.position.x / widthPx).coerceIn(0.01f, 0.99f)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val currentSplitX = (widthPx * splitFraction).coerceIn(0f, widthPx)

        val beforeImage = remember(beforeBitmap) { beforeBitmap.asImageBitmap() }
        val afterImage = remember(afterBitmap) { afterBitmap.asImageBitmap() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val splitX = canvasWidth * splitFraction

            // 1. Draw Translated image (full canvas)
            drawImage(
                image = afterImage,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(canvasWidth.toInt(), canvasHeight.toInt()),
            )

            // 2. Draw Original image clipped to left [0 .. splitX]
            val leftClipPath = Path().apply {
                addRect(Rect(0f, 0f, splitX, canvasHeight))
            }

            clipPath(leftClipPath) {
                drawImage(
                    image = beforeImage,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(canvasWidth.toInt(), canvasHeight.toInt()),
                )
            }

            // 3. Draw vertical divider line
            drawLine(
                color = Color.White,
                start = Offset(splitX, 0f),
                end = Offset(splitX, canvasHeight),
                strokeWidth = 3.dp.toPx(),
            )
        }

        // Circular draggable handle in center
        Surface(
            modifier = Modifier
                .offset { IntOffset((currentSplitX - 18.dp.toPx()).roundToInt(), 0) }
                .size(36.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 6.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Top Badges
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
        ) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.7f),
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
                    text = "Translated (AI)",
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
