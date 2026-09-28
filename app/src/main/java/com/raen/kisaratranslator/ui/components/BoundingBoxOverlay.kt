package com.raen.kisaratranslator.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.raen.kisaratranslator.data.model.TranslationBlock

@Composable
fun BoundingBoxOverlay(
    blocks: List<TranslationBlock>,
    imageWidth: Float,
    imageHeight: Float,
    selectedBlock: TranslationBlock?,
    onBlockSelected: (TranslationBlock?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 0f || imageHeight <= 0f) return

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(blocks, imageWidth, imageHeight) {
                detectTapGestures { offset ->
                    val scaleX = size.width.toFloat() / imageWidth
                    val scaleY = size.height.toFloat() / imageHeight
                    val tapImgX = offset.x / scaleX
                    val tapImgY = offset.y / scaleY

                    // Find block containing tap
                    val clicked = blocks.firstOrNull { b ->
                        tapImgX >= b.x && tapImgX <= (b.x + b.width) &&
                            tapImgY >= b.y && tapImgY <= (b.y + b.height)
                    }
                    onBlockSelected(clicked)
                }
            },
    ) {
        val scaleX = size.width / imageWidth
        val scaleY = size.height / imageHeight

        for ((idx, block) in blocks.withIndex()) {
            val isSelected = block == selectedBlock
            val boxLeft = block.x * scaleX
            val boxTop = block.y * scaleY
            val boxWidth = block.width * scaleX
            val boxHeight = block.height * scaleY

            val strokeColor = if (isSelected) Color(0xFF00E676) else Color(0xFF00B0FF)
            val fillColor = if (isSelected) Color(0x3300E676) else Color(0x1A00B0FF)

            // Fill
            drawRect(
                color = fillColor,
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
            )

            // Outline
            drawRect(
                color = strokeColor,
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
                style = Stroke(width = if (isSelected) 3f else 1.5f),
            )

            // Small badge with index #1, #2
            drawCircle(
                color = strokeColor,
                radius = 10f,
                center = Offset(boxLeft + 10f, boxTop + 10f),
            )
        }
    }
}
