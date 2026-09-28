package com.raen.kisaratranslator.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raen.kisaratranslator.core.util.ImageUtils
import com.raen.kisaratranslator.data.service.TranslationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ComparisonMode(val label: String) {
    ORIGINAL("Original (Raw)"),
    BASELINE("Before (Baseline)"),
    LATEST("After (Latest)"),
}

@Composable
fun SigmaBenchmarkScreen(
    translationService: TranslationService,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val originalBitmap by translationService.originalBitmap.collectAsState()
    val translationResult by translationService.translationResult.collectAsState()
    val baselineResult by translationService.baselineResult.collectAsState()
    val isProcessing by translationService.isProcessing.collectAsState()
    val currentStep by translationService.currentStep.collectAsState()
    val config by translationService.config.collectAsState()

    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var activeImageIndex by remember { mutableIntStateOf(0) }
    var compMode by remember { mutableStateOf(ComparisonMode.LATEST) }
    var configReloadBanner by remember { mutableStateOf("") }

    // Helper to load and translate an image
    fun loadAndTranslateImage(uri: Uri) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                ImageUtils.decodeBitmapFromUri(context, uri)
            }
            if (bmp != null) {
                if (translationResult != null) {
                    translationService.snapshotBaseline()
                }
                compMode = ComparisonMode.LATEST
                translationService.setOriginalImage(bmp, uri)
                translationService.startPipeline(fromStep = 1)
            }
        }
    }

    // Single Image Picker
    val singleImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUris = listOf(uri)
            activeImageIndex = 0
            loadAndTranslateImage(uri)
        }
    }

    // Multi-Image Picker
    val multiImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            selectedUris = uris
            activeImageIndex = 0
            loadAndTranslateImage(uris[0])
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // 1. Header Card: Sigma Precision Lab Controls
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF1E1E1E),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Compare,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Sigma Precision Benchmark",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp,
                            )
                            Text(
                                text = "PC-First, Mobile-Verify Accuracy Loop",
                                fontSize = 11.sp,
                                color = Color.Gray,
                            )
                        }
                    }

                    // Pick Buttons: Single or Batch
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { singleImagePicker.launch("image/*") },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text("Pick Page", fontSize = 11.sp, color = Color.White)
                        }

                        Button(
                            onClick = { multiImagePicker.launch("image/*") },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Outlined.Collections, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Batch", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Zero-Rebuild ADB Sync Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Engine: ${config.translator.displayName} (Beam: ${config.sugoiBeamWidth})",
                            fontSize = 11.sp,
                            color = Color(0xFF81D4FA),
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = "Detector: ${config.detector.name} | OCR: ${config.ocr.name}",
                            fontSize = 10.sp,
                            color = Color.LightGray,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    // Reload ADB Config Button (Zero-Reinstall!)
                    OutlinedButton(
                        onClick = {
                            val ok = translationService.reloadExternalSigmaConfig()
                            if (ok) {
                                configReloadBanner = "Reloaded sigma_config.json successfully!"
                                Toast.makeText(context, "Loaded sigma_config.json from SD Card", Toast.LENGTH_SHORT).show()
                            } else {
                                configReloadBanner = "No sigma_config.json found on SD Card"
                                Toast.makeText(context, "Push config first: adb push sigma_config.json ...", Toast.LENGTH_LONG).show()
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF00E676))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reload Config", fontSize = 11.sp, color = Color.White)
                    }
                }

                if (configReloadBanner.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = configReloadBanner,
                        fontSize = 10.sp,
                        color = Color(0xFF00E676),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Batch Queue Carousel (if multiple images selected)
        if (selectedUris.size > 1) {
            Text(
                text = "Selected Batch (${selectedUris.size} pages) — Tap to switch",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Gray,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selectedUris.forEachIndexed { idx, uri ->
                    val isSelected = idx == activeImageIndex
                    Card(
                        modifier = Modifier
                            .width(80.dp)
                            .clickable {
                                if (!isProcessing && idx != activeImageIndex) {
                                    activeImageIndex = idx
                                    loadAndTranslateImage(uri)
                                }
                            }
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                            ),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF262626)),
                    ) {
                        Column(
                            modifier = Modifier.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Page ${idx + 1}",
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else Color.Gray,
                            )
                            if (isSelected && isProcessing) {
                                Spacer(modifier = Modifier.height(4.dp))
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // 3. Comparison Mode Selector & Pin Baseline (Scrollable / Adaptive Row)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ComparisonMode.entries.forEach { mode ->
                FilterChip(
                    selected = compMode == mode,
                    onClick = { compMode = mode },
                    label = { Text(mode.label, fontSize = 11.sp) },
                )
            }

            // Snapshot Baseline Button with generous padding
            if (translationResult != null) {
                OutlinedButton(
                    onClick = {
                        translationService.snapshotBaseline()
                        Toast.makeText(context, "Saved as Baseline for comparison", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("Pin Baseline", fontSize = 11.sp, maxLines = 1)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Main Comparison Canvas: Dynamic aspect ratio matching the actual image!
        val currentBmp = originalBitmap
        val cardAspect = if (currentBmp != null && currentBmp.height > 0) {
            (currentBmp.width.toFloat() / currentBmp.height.toFloat()).coerceIn(0.4f, 1.8f)
        } else {
            0.70f
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(cardAspect),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF181818)),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val displayBitmap: Bitmap? = when (compMode) {
                    ComparisonMode.ORIGINAL -> originalBitmap
                    ComparisonMode.BASELINE -> baselineResult?.translatedBitmap ?: originalBitmap
                    ComparisonMode.LATEST -> translationResult?.translatedBitmap ?: originalBitmap
                }

                if (displayBitmap != null) {
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = "Manga Page View",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Collections,
                            contentDescription = null,
                            tint = Color.DarkGray,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Pick a manga page to begin benchmark",
                            color = Color.Gray,
                            fontSize = 13.sp,
                        )
                    }
                }

                // Processing Overlay
                if (isProcessing) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = currentStep.ifBlank { "Translating..." },
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 5. Action Row: Re-Translate with New Config (No Reinstall!)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    if (originalBitmap != null && !isProcessing) {
                        // Snapshot current before re-running
                        if (translationResult != null) {
                            translationService.snapshotBaseline()
                        }
                        translationService.reloadExternalSigmaConfig()
                        translationService.startPipeline(fromStep = 1)
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = originalBitmap != null && !isProcessing,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Re-Translate (Apply Config)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 6. Dialogue Inspector: Japanese Transcript vs. English Translation
        val activeResult = if (compMode == ComparisonMode.BASELINE) baselineResult else translationResult
        val blocks = activeResult?.pageTranslation?.blocks ?: emptyList()

        if (blocks.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Extracted Dialogue & Translations (${blocks.size} blocks)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.White,
                )
                Text(
                    text = "${activeResult?.diagnostics?.totalTimeMs ?: 0}ms total",
                    fontSize = 11.sp,
                    color = Color(0xFF00E676),
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            blocks.forEachIndexed { index, block ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF222222),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "#${index + 1} Speech Bubble",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "${block.width.toInt()}x${block.height.toInt()} at (${block.x.toInt()}, ${block.y.toInt()})",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "🇯🇵 ${block.text}",
                            fontSize = 12.sp,
                            color = Color(0xFFE0E0E0),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "🇬🇧 ${block.translation}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF81D4FA),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
