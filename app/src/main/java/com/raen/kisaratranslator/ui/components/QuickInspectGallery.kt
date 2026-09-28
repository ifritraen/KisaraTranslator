package com.raen.kisaratranslator.ui.components

import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raen.kisaratranslator.data.model.OcrCropDebug
import com.raen.kisaratranslator.data.model.TranslationBlock
import com.raen.kisaratranslator.data.model.ViewMode
import com.raen.kisaratranslator.data.service.TranslationPipelineResult
import com.raen.kisaratranslator.data.service.TranslationService

/**
 * Quick Inspect Contextual Step Galleries Container.
 *
 * Placed directly over the Resource Monitor (ResourceBar) with an up-arrow expand button.
 * - For 8. Final (ViewMode.TRANSLATED): Vertically scrollable LazyColumn with 2 equal-height rows visible.
 * - For all other steps: Horizontally scrollable LazyRow with full-height cards.
 * - Synchronized bidirectionally with TranslationService.highlightedBox and the manga canvas overlay.
 */
@Composable
fun QuickInspectContainer(
    translationService: TranslationService,
    result: TranslationPipelineResult,
    modifier: Modifier = Modifier,
) {
    val isExpanded by translationService.isQuickInspectExpanded.collectAsState()
    val viewMode by translationService.currentViewMode.collectAsState()
    val highlightedBox by translationService.highlightedBox.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        // Expandable Quick Inspect Panel
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                color = Color(0xFF141416),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF28282E)),
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A1E))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(getStepColor(viewMode)),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Quick Inspect • ${viewMode.label}",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = getStepColor(viewMode),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            val countText = getStepCountSummary(viewMode, result)
                            if (countText.isNotEmpty()) {
                                Text(
                                    text = countText,
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    fontWeight = FontWeight.Medium,
                                    softWrap = false,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            IconButton(
                                onClick = { translationService.setQuickInspectExpanded(false) },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Quick Inspect",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }

                    // Body: 8. Final (Vertically scrollable, 2 equal height rows) vs Others (Horizontally scrollable, full height)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        if (viewMode == ViewMode.TRANSLATED) {
                            // 8. Final: Vertically scrollable with two rows of equal height
                            QuickInspectFinalVerticalGallery(
                                blocks = result.pageTranslation.blocks,
                                highlightedBox = highlightedBox,
                                onSelectBox = { rect, label ->
                                    if (highlightedBox == rect) {
                                        translationService.setHighlightedBox(null, "")
                                    } else {
                                        translationService.setHighlightedBox(rect, label)
                                    }
                                },
                            )
                        } else {
                            // Others: Horizontally scrollable with full height cards
                            QuickInspectHorizontalGallery(
                                viewMode = viewMode,
                                result = result,
                                highlightedBox = highlightedBox,
                                onSelectBox = { rect, label ->
                                    if (highlightedBox == rect) {
                                        translationService.setHighlightedBox(null, "")
                                    } else {
                                        translationService.setHighlightedBox(rect, label)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Up/Down Arrow Toggle Button Directly Over ResourceBar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { translationService.setQuickInspectExpanded(!isExpanded) },
            color = Color(0xFF18181C),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF2C2C34)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = if (isExpanded) "Collapse Quick Inspect" else "Expand Quick Inspect",
                    tint = if (isExpanded) getStepColor(viewMode) else Color(0xFF00E5FF),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isExpanded) "Hide Quick Inspect (${viewMode.label})" else "Quick Inspect (${viewMode.label})",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isExpanded) getStepColor(viewMode) else Color(0xFF80D8FF),
                )
            }
        }
    }
}

/**
 * 8. Final Quick Inspect Gallery:
 * Vertically scrollable LazyColumn with 2 equal-height rows visible in the viewport.
 * Each card contains two equal-height rows: Fed (JA) and Got (EN).
 */
@Composable
private fun QuickInspectFinalVerticalGallery(
    blocks: List<TranslationBlock>,
    highlightedBox: Rect?,
    onSelectBox: (Rect, String) -> Unit,
) {
    if (blocks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No translated dialogue blocks", color = Color.Gray, fontSize = 11.sp)
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(highlightedBox) {
        if (highlightedBox != null) {
            val idx = blocks.indexOfFirst {
                it.x.toInt() == highlightedBox.left && it.y.toInt() == highlightedBox.top
            }
            if (idx >= 0) {
                listState.animateScrollToItem(idx)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        itemsIndexed(blocks) { idx, block ->
            val blockRect = Rect(block.x.toInt(), block.y.toInt(), (block.x + block.width).toInt(), (block.y + block.height).toInt())
            val isSelected = highlightedBox == blockRect

            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(0xFF263345) else Color(0xFF1E2128),
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) Color(0xFFFFEA00) else Color(0xFF2D323E),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillParentMaxHeight(0.48f) // Two equal-height rows visible in the viewport!
                    .clickable { onSelectBox(blockRect, "Block #${idx + 1}") },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // Row 1 (Top Half): Source Japanese Text + Badges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSelected) Color(0xFFFFEA00).copy(alpha = 0.25f) else Color(0xFF00E5FF).copy(alpha = 0.2f),
                            ) {
                                Text(
                                    text = "#${idx + 1}",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color(0xFFFFEA00) else Color(0xFF80D8FF),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = block.text.ifBlank { "(empty)" },
                                fontSize = 11.sp,
                                color = Color(0xFFFFCC80),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = "${block.width.toInt()}x${block.height.toInt()}",
                            fontSize = 8.5.sp,
                            color = Color.Gray,
                            softWrap = false,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }

                    // Row 2 (Bottom Half): English Translated Text
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = block.translation.ifBlank { "(empty)" },
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Quick Inspect Horizontal Gallery for all other pipeline steps:
 * Horizontally scrollable LazyRow with full-height cards.
 */
@Composable
private fun QuickInspectHorizontalGallery(
    viewMode: ViewMode,
    result: TranslationPipelineResult,
    highlightedBox: Rect?,
    onSelectBox: (Rect, String) -> Unit,
) {
    val listState = rememberLazyListState()

    when (viewMode) {
        ViewMode.DETECTION_1 -> {
            val bubbles = result.bubbleBoxes
            val p1 = result.pass1Boxes.ifEmpty { result.detectedBoxes }
            val orphanBoxes = p1.filter { box -> bubbles.none { b -> b.contains(box.centerX(), box.centerY()) } }

            LaunchedEffect(highlightedBox) {
                if (highlightedBox != null) {
                    val bIdx = bubbles.indexOf(highlightedBox).takeIf { it >= 0 }
                        ?: bubbles.indexOfFirst { it.contains(highlightedBox.centerX(), highlightedBox.centerY()) }.takeIf { it >= 0 }
                    if (bIdx != null) {
                        listState.animateScrollToItem(bIdx)
                    } else {
                        val oIdx = orphanBoxes.indexOf(highlightedBox)
                        if (oIdx >= 0) {
                            listState.animateScrollToItem(bubbles.size + oIdx)
                        }
                    }
                }
            }

            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                itemsIndexed(bubbles) { idx, bubble ->
                    val insideCount = p1.count { bubble.contains(it.centerX(), it.centerY()) }
                    val isSelected = highlightedBox == bubble || (highlightedBox != null && bubble.contains(highlightedBox.centerX(), highlightedBox.centerY()))
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF1E3A45) else Color(0xFF16252B)),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFFFFEA00) else Color(0xFF00E5FF).copy(alpha = 0.6f),
                        ),
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(150.dp)
                            .clickable { onSelectBox(bubble, "Bubble #${idx + 1}") },
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Bubble #${idx + 1}",
                                    color = if (isSelected) Color(0xFFFFEA00) else Color(0xFF00E5FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${bubble.width()}x${bubble.height()}",
                                    color = Color.Gray,
                                    fontSize = 9.sp,
                                    softWrap = false,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            Text(
                                text = if (insideCount > 0) "$insideCount text cols inside" else "Empty / SFX Bubble",
                                color = if (insideCount > 0) Color(0xFF00E676) else Color(0xFFFFB74D),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("Pos: (${bubble.left}, ${bubble.top})", color = Color.Gray, fontSize = 8.5.sp)
                        }
                    }
                }

                itemsIndexed(orphanBoxes) { idx, orphan ->
                    val isSelected = highlightedBox == orphan
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF3D2E1A) else Color(0xFF2C2216)),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFFF9100).copy(alpha = 0.6f),
                        ),
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(150.dp)
                            .clickable { onSelectBox(orphan, "Orphan #${idx + 1}") },
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Orphan #${idx + 1}",
                                    color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFFF9100),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${orphan.width()}x${orphan.height()}",
                                    color = Color.Gray,
                                    fontSize = 9.sp,
                                    softWrap = false,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            Text("Non-Bubble Text", color = Color(0xFFFFCC80), fontSize = 10.sp)
                            Text("Pos: (${orphan.left}, ${orphan.top})", color = Color.Gray, fontSize = 8.5.sp)
                        }
                    }
                }
            }
        }

        ViewMode.DETECTION_2 -> {
            val crops = result.pass2Crops
            LaunchedEffect(highlightedBox) {
                if (highlightedBox != null) {
                    val idx = crops.indexOfFirst {
                        it.rect == highlightedBox ||
                            (it.rect.left == highlightedBox.left && it.rect.top == highlightedBox.top &&
                             it.rect.right == highlightedBox.right && it.rect.bottom == highlightedBox.bottom)
                    }
                    if (idx >= 0) listState.animateScrollToItem(idx)
                }
            }
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                itemsIndexed(crops) { idx, crop ->
                    val isSelected = highlightedBox == crop.rect
                    val isPassed = crop.rawText.startsWith("✓")
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF2B2B16) else if (isPassed) Color(0xFF162B1D) else Color(0xFF221616)),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFFFFEA00) else if (isPassed) Color(0xFF00E676) else Color(0xFF662222),
                        ),
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(150.dp)
                            .clickable { onSelectBox(crop.rect, "Probed #${idx + 1}") },
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "#${idx + 1} (${crop.rect.width()}x${crop.rect.height()})",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPassed) Color(0xFF80E8A7) else Color(0xFFEF9A9A),
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (isPassed) "VERIFIED" else "DISCARDED",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPassed) Color(0xFF00E676) else Color(0xFFFF1744),
                                    softWrap = false,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            Box(modifier = Modifier.fillMaxWidth().height(65.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                                if (!crop.cropBitmap.isRecycled) {
                                    Image(bitmap = crop.cropBitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Text(text = crop.rawText, fontSize = 10.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        ViewMode.LINE_STRIPS -> {
            val lines = result.lineBoxes.ifEmpty { result.detectedBoxes }
            LaunchedEffect(highlightedBox) {
                if (highlightedBox != null) {
                    val idx = lines.indexOf(highlightedBox)
                    if (idx >= 0) listState.animateScrollToItem(idx)
                }
            }
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                itemsIndexed(lines) { idx, line ->
                    val isSelected = highlightedBox == line
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF3D3216) else Color(0xFF262014)),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFFFB300).copy(alpha = 0.6f),
                        ),
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(140.dp)
                            .clickable { onSelectBox(line, "Line #${idx + 1}") },
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Line #${idx + 1}",
                                    color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFFFB300),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${line.width()}x${line.height()}",
                                    color = Color.Gray,
                                    fontSize = 9.sp,
                                    softWrap = false,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            Text("Bridged Strip", color = Color(0xFFFFD54F), fontSize = 10.sp)
                            Text("Pos: (${line.left}, ${line.top})", color = Color.Gray, fontSize = 8.5.sp)
                        }
                    }
                }
            }
        }

        ViewMode.GROUPING -> {
            val lobes = result.groupedBoxes.ifEmpty { result.lineBoxes }
            val crops = result.nonBubbledCrops
            LaunchedEffect(highlightedBox) {
                if (highlightedBox != null) {
                    val lIdx = lobes.indexOfFirst {
                        it == highlightedBox ||
                            (it.left == highlightedBox.left && it.top == highlightedBox.top &&
                             it.right == highlightedBox.right && it.bottom == highlightedBox.bottom)
                    }
                    if (lIdx >= 0) {
                        listState.animateScrollToItem(lIdx)
                    } else {
                        val cIdx = crops.indexOfFirst {
                            it.rect == highlightedBox ||
                                (it.rect.left == highlightedBox.left && it.rect.top == highlightedBox.top &&
                                 it.rect.right == highlightedBox.right && it.rect.bottom == highlightedBox.bottom)
                        }
                        if (cIdx >= 0) {
                            listState.animateScrollToItem(lobes.size + cIdx)
                        }
                    }
                }
            }
            if (lobes.isEmpty() && crops.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No grouped lobes or text units", color = Color.Gray, fontSize = 11.sp)
                }
            } else {
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    // 1. Grouped Dialogue Lobes (matching canvas overlay)
                    itemsIndexed(lobes) { idx, lobe ->
                        val isSelected = highlightedBox == lobe ||
                            (highlightedBox != null && lobe.left == highlightedBox.left && lobe.top == highlightedBox.top &&
                             lobe.right == highlightedBox.right && lobe.bottom == highlightedBox.bottom)
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF163238) else Color(0xFF132226)),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) Color(0xFFFFEA00) else Color(0xFF00E5FF).copy(alpha = 0.6f),
                            ),
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(145.dp)
                                .clickable { onSelectBox(lobe, "Group #${idx + 1}") },
                        ) {
                            Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Group #${idx + 1}",
                                        color = if (isSelected) Color(0xFFFFEA00) else Color(0xFF00E5FF),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${lobe.width()}x${lobe.height()}",
                                        color = Color.Gray,
                                        fontSize = 9.sp,
                                        softWrap = false,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                                Text("Dialogue Lobe", color = Color(0xFF80DEEA), fontSize = 10.sp)
                                Text("Pos: (${lobe.left}, ${lobe.top})", color = Color.Gray, fontSize = 8.5.sp)
                            }
                        }
                    }

                    // 2. Verified Non-Bubbled Text / Discarded Noise Crops
                    itemsIndexed(crops) { idx, crop ->
                        val isSelected = highlightedBox == crop.rect ||
                            (highlightedBox != null && crop.rect.left == highlightedBox.left && crop.rect.top == highlightedBox.top &&
                             crop.rect.right == highlightedBox.right && crop.rect.bottom == highlightedBox.bottom)
                        val isPassed = crop.rawText.startsWith("✓")
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF2B2B16) else if (isPassed) Color(0xFF162B1D) else Color(0xFF221616)),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) Color(0xFFFFEA00) else if (isPassed) Color(0xFF00E676) else Color(0xFF662222),
                            ),
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(150.dp)
                                .clickable { onSelectBox(crop.rect, if (isPassed) "Line #${idx + 1}" else "Noise #${idx + 1}") },
                        ) {
                            Column(modifier = Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "#${idx + 1} (${crop.rect.width()}x${crop.rect.height()})",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPassed) Color(0xFF80E8A7) else Color(0xFFEF9A9A),
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (isPassed) "CONFIRMED" else "DISCARDED",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPassed) Color(0xFF00E676) else Color(0xFFFF1744),
                                        softWrap = false,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                                Box(modifier = Modifier.fillMaxWidth().height(65.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                                    if (!crop.cropBitmap.isRecycled) {
                                        Image(bitmap = crop.cropBitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                    }
                                }
                                Text(text = crop.rawText, fontSize = 10.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        ViewMode.CRUNCH_1 -> {
            val allItems = mutableListOf<Pair<Rect, String>>()
            result.crunchOriginalBoxes.forEachIndexed { i, r -> allItems.add(Pair(r, "Conjoined #${i + 1}")) }
            result.crunchSplitBoxes.forEachIndexed { i, r -> allItems.add(Pair(r, "✂️ Lobe #${i + 1}")) }
            LaunchedEffect(highlightedBox) {
                if (highlightedBox != null) {
                    val idx = allItems.indexOfFirst {
                        it.first == highlightedBox ||
                            (it.first.left == highlightedBox.left && it.first.top == highlightedBox.top &&
                             it.first.right == highlightedBox.right && it.first.bottom == highlightedBox.bottom)
                    }
                    if (idx >= 0) listState.animateScrollToItem(idx)
                }
            }
            if (allItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No conjoined bubbles detected", color = Color.Gray, fontSize = 11.sp)
                }
            } else {
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    itemsIndexed(allItems) { _, (rect, label) ->
                        val isSelected = highlightedBox == rect ||
                            (highlightedBox != null && rect.left == highlightedBox.left && rect.top == highlightedBox.top &&
                             rect.right == highlightedBox.right && rect.bottom == highlightedBox.bottom)
                        val isConjoined = label.startsWith("Conjoined")
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF381B1B) else Color(0xFF1E1E24)),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) Color(0xFFFFEA00) else if (isConjoined) Color(0xFFFF5252) else Color(0xFF00E5FF),
                            ),
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(140.dp)
                                .clickable { onSelectBox(rect, label) },
                        ) {
                            Column(modifier = Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isConjoined) Color(0xFFFF8A80) else Color(0xFF80D8FF),
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${rect.width()}x${rect.height()} px",
                                        fontSize = 8.5.sp,
                                        color = Color.Gray,
                                        softWrap = false,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                                Text("(${rect.left}, ${rect.top})", fontSize = 8.5.sp, color = Color.DarkGray)
                            }
                        }
                    }
                }
            }
        }

        ViewMode.CRUNCH_2 -> {
            val splits = result.crunchSplits
            val allItems = mutableListOf<Pair<Rect, String>>()
            splits.forEachIndexed { i, s ->
                s.originalRect?.let { allItems.add(Pair(it, "Split #${i + 1}: ${s.cutStrategy ?: "Laser"}")) }
                s.splitLobeRects.forEachIndexed { li, lr -> allItems.add(Pair(lr, "✂️ Lobe #${li + 1} (S#${i + 1})")) }
                s.clashingLines.forEachIndexed { ci, cr -> allItems.add(Pair(cr, "⚠️ Clash #${ci + 1} (S#${i + 1})")) }
            }
            LaunchedEffect(highlightedBox) {
                if (highlightedBox != null) {
                    val idx = allItems.indexOfFirst {
                        it.first == highlightedBox ||
                            (it.first.left == highlightedBox.left && it.first.top == highlightedBox.top &&
                             it.first.right == highlightedBox.right && it.first.bottom == highlightedBox.bottom)
                    }
                    if (idx >= 0) listState.animateScrollToItem(idx)
                }
            }
            if (splits.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No conjoined bubbles detected", color = Color.Gray, fontSize = 11.sp)
                }
            } else {
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    itemsIndexed(splits) { idx, split ->
                        val rect = split.originalRect ?: Rect()
                        val isSelected = highlightedBox == rect ||
                            (highlightedBox != null && rect.left == highlightedBox.left && rect.top == highlightedBox.top &&
                             rect.right == highlightedBox.right && rect.bottom == highlightedBox.bottom)
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF381B1B) else Color(0xFF1E1E24)),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) Color(0xFFFFEA00) else Color(0xFF76FF03).copy(alpha = 0.6f),
                            ),
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(155.dp)
                                .clickable { onSelectBox(rect, "Split #${idx + 1}") },
                        ) {
                            Column(modifier = Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Split #${idx + 1}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF76FF03),
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${rect.width()}x${rect.height()}",
                                        fontSize = 8.5.sp,
                                        color = Color.Gray,
                                        softWrap = false,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                                Text(
                                    text = split.cutStrategy ?: "Laser Cut",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val aTxt = if (split.crunchPointA != null) "A:(${split.crunchPointA.x},${split.crunchPointA.y})" else ""
                                val bTxt = if (split.crunchPointB != null) "B:(${split.crunchPointB.x},${split.crunchPointB.y})" else ""
                                Text("$aTxt $bTxt", fontSize = 8.sp, color = Color(0xFFFFD54F))
                                val cCount = split.clashingLines.size
                                Text(
                                    if (cCount > 0) "⚠️ $cCount clash obstacles" else "✓ 0 clashes",
                                    fontSize = 8.5.sp,
                                    color = if (cCount > 0) Color(0xFFFF5252) else Color(0xFF00E676),
                                )
                            }
                        }
                    }
                }
            }
        }

        ViewMode.CRUNCH_3 -> {
            val splits = result.crunchSplits
            val allLobes = mutableListOf<Pair<Rect, String>>()
            splits.forEachIndexed { sIdx, s ->
                s.splitLobeRects.forEachIndexed { lIdx, lr ->
                    allLobes.add(Pair(lr, "Lobe #${lIdx + 1} (S#${sIdx + 1})"))
                }
            }
            LaunchedEffect(highlightedBox) {
                if (highlightedBox != null) {
                    val idx = allLobes.indexOfFirst {
                        it.first == highlightedBox ||
                            (it.first.left == highlightedBox.left && it.first.top == highlightedBox.top &&
                             it.first.right == highlightedBox.right && it.first.bottom == highlightedBox.bottom)
                    }
                    if (idx >= 0) listState.animateScrollToItem(idx)
                }
            }
            if (allLobes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No separated lobes", color = Color.Gray, fontSize = 11.sp)
                }
            } else {
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    itemsIndexed(allLobes) { idx, (rect, label) ->
                        val isSelected = highlightedBox == rect
                        val color = if (label.startsWith("Lobe #1")) Color(0xFF00E5FF) else Color(0xFF00E676)
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF1E2833) else Color(0xFF131A22)),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) Color(0xFFFFEA00) else color.copy(alpha = 0.7f),
                            ),
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(140.dp)
                                .clickable { onSelectBox(rect, label) },
                        ) {
                            Column(modifier = Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = color,
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${rect.width()}x${rect.height()}",
                                        fontSize = 8.5.sp,
                                        color = Color.Gray,
                                        softWrap = false,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                                Text("(${rect.left}, ${rect.top})", fontSize = 8.5.sp, color = Color.DarkGray)
                                Text("✂️ Separated Lobe", color = color, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }

        ViewMode.OCR_CROPS -> {
            val crops = result.ocrCrops
            LaunchedEffect(highlightedBox) {
                if (highlightedBox != null) {
                    val idx = crops.indexOfFirst { it.rect == highlightedBox }
                    if (idx >= 0) listState.animateScrollToItem(idx)
                }
            }
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                itemsIndexed(crops) { idx, crop ->
                    val isSelected = highlightedBox == crop.rect
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF2B2B1E) else Color(0xFF1E242B)),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFFFFEA00) else Color(0xFF00E5FF).copy(alpha = 0.5f),
                        ),
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(155.dp)
                            .clickable { onSelectBox(crop.rect, "Chunk #${idx + 1}") },
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Chunk #${idx + 1}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF80D8FF),
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${crop.rect.width()}x${crop.rect.height()}",
                                    fontSize = 8.5.sp,
                                    color = Color.Gray,
                                    softWrap = false,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            Box(modifier = Modifier.fillMaxWidth().height(65.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                                if (!crop.cropBitmap.isRecycled) {
                                    Image(bitmap = crop.cropBitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Text(
                                text = if (crop.rawText.isNotBlank()) crop.rawText else "<empty>",
                                fontSize = 10.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        ViewMode.ORDER_FLOW -> {
            val blocks = result.pageTranslation.blocks
            LaunchedEffect(highlightedBox) {
                if (highlightedBox != null) {
                    val idx = blocks.indexOfFirst {
                        it.x.toInt() == highlightedBox.left && it.y.toInt() == highlightedBox.top
                    }
                    if (idx >= 0) listState.animateScrollToItem(idx)
                }
            }
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                itemsIndexed(blocks) { idx, block ->
                    val blockRect = Rect(block.x.toInt(), block.y.toInt(), (block.x + block.width).toInt(), (block.y + block.height).toInt())
                    val isSelected = highlightedBox == blockRect
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF351E3D) else Color(0xFF221626)),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFE040FB).copy(alpha = 0.5f),
                        ),
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(145.dp)
                            .clickable { onSelectBox(blockRect, "Flow #${idx + 1}") },
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "#${idx + 1}",
                                    color = if (isSelected) Color(0xFFFFEA00) else Color(0xFFE040FB),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${block.width.toInt()}x${block.height.toInt()}",
                                    color = Color.Gray,
                                    fontSize = 8.5.sp,
                                    softWrap = false,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            Text(text = block.text.ifBlank { "(empty)" }, color = Color.White, fontSize = 10.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Manga RTL", color = Color(0xFFCE93D8), fontSize = 8.5.sp)
                        }
                    }
                }
            }
        }

        ViewMode.INPAINTED -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Cleaned canvas (Inpainting complete)", color = Color.Gray, fontSize = 11.sp)
            }
        }

        ViewMode.TRANSLATED -> {
            // Handled separately by QuickInspectFinalVerticalGallery
        }
    }
}

private fun getStepColor(viewMode: ViewMode): Color {
    return when (viewMode) {
        ViewMode.DETECTION_1 -> Color(0xFF00E5FF) // Cyan
        ViewMode.DETECTION_2 -> Color(0xFFFFD54F) // Amber
        ViewMode.LINE_STRIPS -> Color(0xFFFFB300) // Deep Amber
        ViewMode.GROUPING -> Color(0xFF00E676) // Green
        ViewMode.CRUNCH_1 -> Color(0xFFFF5252) // Coral / Red
        ViewMode.CRUNCH_2 -> Color(0xFF76FF03) // Lime / Laser
        ViewMode.CRUNCH_3 -> Color(0xFF00E5FF) // Cyan / Emerald
        ViewMode.OCR_CROPS -> Color(0xFF80D8FF) // Light Blue
        ViewMode.ORDER_FLOW -> Color(0xFFE040FB) // Magenta
        ViewMode.INPAINTED -> Color(0xFF81C784) // Light Green
        ViewMode.TRANSLATED -> Color(0xFFFFEA00) // Gold
    }
}

private fun getStepCountSummary(viewMode: ViewMode, result: TranslationPipelineResult): String {
    return when (viewMode) {
        ViewMode.DETECTION_1 -> "${result.bubbleBoxes.size} Bubbles, ${result.pass1Boxes.ifEmpty { result.detectedBoxes }.size} Boxes"
        ViewMode.DETECTION_2 -> "${result.pass2Crops.size} Probed"
        ViewMode.LINE_STRIPS -> "${result.lineBoxes.ifEmpty { result.detectedBoxes }.size} Lines"
        ViewMode.GROUPING -> {
            val lobesCount = result.groupedBoxes.ifEmpty { result.lineBoxes }.size
            if (result.nonBubbledCrops.isNotEmpty()) "$lobesCount Lobes, ${result.nonBubbledCrops.size} Non-Bubbled"
            else "$lobesCount Lobes"
        }
        ViewMode.CRUNCH_1 -> "${result.crunchSplits.size} Conjoined Bubbles (${result.crunchPointsA.size} A/B)"
        ViewMode.CRUNCH_2 -> "${result.crunchSplits.size} Laser Seams Inspected"
        ViewMode.CRUNCH_3 -> "${result.crunchSplitBoxes.size} Separated Lobes"
        ViewMode.OCR_CROPS -> "${result.ocrCrops.size} Chunks"
        ViewMode.ORDER_FLOW -> "${result.pageTranslation.blocks.size} Blocks"
        ViewMode.INPAINTED -> "Inpainted"
        ViewMode.TRANSLATED -> "${result.pageTranslation.blocks.size} Blocks"
    }
}
