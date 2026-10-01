package com.raen.kisaratranslator.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FormatShapes
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raen.kisaratranslator.data.model.DetectorType
import com.raen.kisaratranslator.data.model.LanguageOption
import com.raen.kisaratranslator.data.model.OcrType
import com.raen.kisaratranslator.data.model.TranslatorType
import com.raen.kisaratranslator.data.service.TranslationService
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelineSettingsSheet(
    service: TranslationService,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val config by service.config.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header Row with Title and Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Pipeline Settings",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White),
                    )
                    Text(
                        text = "Engines, Grouping & Typesetting",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        maxLines = 1,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { service.setConfig(com.raen.kisaratranslator.data.model.PipelineConfig()) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("Reset", fontSize = 11.sp, color = Color(0xFFFF8A80), maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = { service.optimizeMemory() },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFBA68C8))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Free RAM", fontSize = 11.sp, color = Color.White, maxLines = 1)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Pipeline Method Toggle ──
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (config.enhancedPipeline) Color(0xFF1A2E1A) else Color(0xFF282828),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Extension,
                            contentDescription = null,
                            tint = if (config.enhancedPipeline) Color(0xFF69F0AE) else Color(0xFF90A4AE),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Pipeline Method",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = config.effectiveMethod == 9,
                            onClick = { service.setConfig(config.copy(pipelineMethod = 9, enhancedPipeline = false)) },
                            label = { Text("Method 8 v3 — Border Angle 📐", fontSize = 11.sp) },
                        )
                        FilterChip(
                            selected = config.effectiveMethod == 8,
                            onClick = { service.setConfig(config.copy(pipelineMethod = 8, enhancedPipeline = false)) },
                            label = { Text("Method 8 v2 — Laser Cut ⚡", fontSize = 11.sp) },
                        )
                        FilterChip(
                            selected = config.effectiveMethod == 5,
                            onClick = { service.setConfig(config.copy(pipelineMethod = 5, enhancedPipeline = false)) },
                            label = { Text("Method 6 — Whole Bubble 🫧", fontSize = 11.sp) },
                        )
                    }
                    when (config.effectiveMethod) {
                        9 -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Method 8 v3 (Pure Border Angle) · 100% ZERO FALLBACK · Outer perimeter Moore-contour boundary tracing · Concavity notch detection (40°–155°) · Opposite notch pairing · Obstacle-aware laser cut seam · Leaves bubble untouched if no angle notch found",
                                fontSize = 10.sp,
                                color = Color(0xFFFFD600),
                                lineHeight = 14.sp,
                            )
                        }
                        8 -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Method 8 v2 (Laser Cut) · YOLO11n-seg instance masks + High-Res Contour Crunch Detection · Pinpoints Crunch Notch Points A & B · Obstacle-Aware Seam Cut deflecting around text line boundaries · Zero-pixel character slicing",
                                fontSize = 10.sp,
                                color = Color(0xFF76FF03),
                                lineHeight = 14.sp,
                            )
                        }
                        7 -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Method 8 v1 (Watershed) · YOLO11n-seg instance segmentation masks · High-fidelity polygon boundaries · Auto-fallback to Method 7 flood-fill · Distance Transform + Watershed peak splitting",
                                fontSize = 10.sp,
                                color = Color(0xFF00E5FF),
                                lineHeight = 14.sp,
                            )
                        }
                        6 -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Geometric Crunch Splitter · Pixel-accurate bubble shape via luminance flood-fill · Distance Transform peak detection · Erosion + Watershed conjoined bubble split · IoA text association (not centroid) · Narration/SFX preserved in UNASSIGNED queue",
                                fontSize = 10.sp,
                                color = Color(0xFFE040FB),
                                lineHeight = 14.sp,
                            )
                        }
                        5 -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Whole Bubble Detection First · 100% deterministic spatial containment · Conjoined 8-shaped bubble waist splitting · Internal 3-line chunking sweet-spot OCR · Inherits all Method 5 enhancements",
                                fontSize = 10.sp,
                                color = Color(0xFF00E676),
                                lineHeight = 14.sp,
                            )
                        }
                        4 -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Intra-bubble column clustering (lobe separation) · Dynamic MangaOCR sweet-spot fallback (>3 lines fallback to 3-line chunk OCR with Japanese RTL concatenation) · Zero-overlap guarantee · 8-stage deep inspector",
                                fontSize = 10.sp,
                                color = Color(0xFFFF80AB),
                                lineHeight = 14.sp,
                            )
                        }
                        3 -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Full-bubble smart grouping first · Conjoined bubble splitting (isthmus/notch) · Strict non-bubble isolation (panel gutter barriers & tight radius) · Direct multi-line MangaOCR with expanded tokens",
                                fontSize = 10.sp,
                                color = Color(0xFFFFD54F),
                                lineHeight = 14.sp,
                            )
                        }
                        2 -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Two-pass focused scan (contrast/sharpness boost) · Vertical line gap bridging (no missed mid-glyphs) · Full line-strip OCR · Manga RTL order · Smart inpainting",
                                fontSize = 10.sp,
                                color = Color(0xFF80D8FF),
                                lineHeight = 14.sp,
                            )
                        }
                        1 -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Single sensitive scan (0.30) · Softer SFX filter · Raw CTD boxes directly · Hard bubble fence · Centroid inpaint",
                                fontSize = 10.sp,
                                color = Color(0xFF69F0AE),
                                lineHeight = 14.sp,
                            )
                        }
                        else -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Original baseline pipeline — standard DBNet/CTD thresholds with default geometry merge.",
                                fontSize = 10.sp,
                                color = Color.Gray,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Section 1: AI Engines
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Speed, contentDescription = null, tint = Color(0xFF00B0FF), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("1. AI Engine Pipeline", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Speech Balloon & Text Detector", fontSize = 12.sp, color = Color.LightGray)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DetectorType.entries.forEach { det ->
                            FilterChip(
                                selected = config.detector == det,
                                onClick = { service.setConfig(config.copy(detector = det)) },
                                label = { Text(det.displayName, fontSize = 11.sp) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("OCR Character Recognizer", fontSize = 12.sp, color = Color.LightGray)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OcrType.entries.forEach { ocr ->
                            FilterChip(
                                selected = config.ocr == ocr,
                                onClick = { service.setConfig(config.copy(ocr = ocr)) },
                                label = { Text(ocr.displayName, fontSize = 11.sp) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Text Translator Engine", fontSize = 12.sp, color = Color.LightGray)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TranslatorType.entries.forEach { trans ->
                            FilterChip(
                                selected = config.translator == trans,
                                onClick = { service.setConfig(config.copy(translator = trans)) },
                                label = { Text(trans.displayName, fontSize = 11.sp) },
                            )
                        }
                    }

                    if (config.translator == TranslatorType.SUGOI_ONNX) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Sugoi Decode Mode", fontSize = 11.sp, color = Color.LightGray)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            FilterChip(
                                selected = config.sugoiBeamWidth <= 1,
                                onClick = { service.setConfig(config.copy(sugoiBeamWidth = 1)) },
                                label = { Text("⚡ Fast (Greedy ~1.2s)", fontSize = 11.sp) },
                            )
                            FilterChip(
                                selected = config.sugoiBeamWidth > 1,
                                onClick = { service.setConfig(config.copy(sugoiBeamWidth = 2)) },
                                label = { Text("✨ Quality (Beam 2 ~2.5s)", fontSize = 11.sp) },
                            )
                        }
                    }

                    if (config.translator == TranslatorType.GEMINI_FLASH) {
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = config.geminiApiKey,
                            onValueChange = { service.setConfig(config.copy(geminiApiKey = it)) },
                            label = { Text("Gemini API Key (Google AI Studio - 1,500/day free)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = config.geminiModel,
                            onValueChange = { service.setConfig(config.copy(geminiModel = it)) },
                            label = { Text("Gemini Model (e.g. gemini-1.5-flash, gemini-2.5-flash)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (config.translator == TranslatorType.GROQ_LLAMA) {
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = config.groqApiKey,
                            onValueChange = { service.setConfig(config.copy(groqApiKey = it)) },
                            label = { Text("Groq API Key (console.groq.com - 14,400/day free)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = config.groqModel,
                            onValueChange = { service.setConfig(config.copy(groqModel = it)) },
                            label = { Text("Groq Model (e.g. llama-3.1-8b-instant, llama-3.3-70b-versatile)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (config.translator == TranslatorType.DEEPL) {
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = config.deeplApiKey,
                            onValueChange = { service.setConfig(config.copy(deeplApiKey = it)) },
                            label = { Text("DeepL API Key (Free 500k chars/mo)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (config.translator == TranslatorType.MICROSOFT_AZURE) {
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = config.azureApiKey,
                            onValueChange = { service.setConfig(config.copy(azureApiKey = it)) },
                            label = { Text("Azure API Key (Free 2M chars/mo)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = config.azureRegion,
                            onValueChange = { service.setConfig(config.copy(azureRegion = it)) },
                            label = { Text("Azure Region (default: global)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (config.translator == TranslatorType.QWEN_0_5B) {
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = config.qwenEndpoint,
                            onValueChange = { service.setConfig(config.copy(qwenEndpoint = it)) },
                            label = { Text("PC/Local Endpoint (Blank = On-Device Offline)", fontSize = 11.sp) },
                            placeholder = { Text("e.g. http://192.168.1.5:11434/v1", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = config.qwenApiKey,
                            onValueChange = { service.setConfig(config.copy(qwenApiKey = it)) },
                            label = { Text("Endpoint Auth Token / API Key (Optional)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section 2: Languages
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Translate, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("2. Languages", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Source Language (Manga)", fontSize = 12.sp, color = Color.LightGray)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LanguageOption.SOURCE_LANGUAGES.forEach { lang ->
                            FilterChip(
                                selected = config.sourceLang == lang.code,
                                onClick = { service.setConfig(config.copy(sourceLang = lang.code)) },
                                label = { Text(lang.displayName, fontSize = 11.sp) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Target Language (Translation)", fontSize = 12.sp, color = Color.LightGray)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LanguageOption.TARGET_LANGUAGES.forEach { lang ->
                            FilterChip(
                                selected = config.targetLang == lang.code,
                                onClick = { service.setConfig(config.copy(targetLang = lang.code)) },
                                label = { Text(lang.displayName, fontSize = 11.sp) },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section 3: Bubble Grouping & Reading Order
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Extension, contentDescription = null, tint = Color(0xFFFFAB00), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("3. Speech Bubble Grouping", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        }
                        Switch(
                            checked = config.bubbleGroupingEnabled,
                            onCheckedChange = { service.setConfig(config.copy(bubbleGroupingEnabled = it)) },
                        )
                    }

                    if (config.bubbleGroupingEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Clustering Algorithm Mode", fontSize = 12.sp, color = Color.LightGray)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = config.assignmentMode == 0,
                                onClick = { service.setConfig(config.copy(assignmentMode = 0)) },
                                label = { Text("Auto / DSU Proximity", fontSize = 11.sp) },
                            )
                            FilterChip(
                                selected = config.assignmentMode == 1,
                                onClick = { service.setConfig(config.copy(assignmentMode = 1)) },
                                label = { Text("Strict Spatial DSU", fontSize = 11.sp) },
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Manga Reading Order Sorting", fontSize = 12.sp, color = Color.LightGray)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = config.lineSortingOrder == 0,
                                onClick = { service.setConfig(config.copy(lineSortingOrder = 0)) },
                                label = { Text("Auto / RTL Manga (Top-Right → Bottom-Left)", fontSize = 11.sp) },
                            )
                            FilterChip(
                                selected = config.lineSortingOrder == 1,
                                onClick = { service.setConfig(config.copy(lineSortingOrder = 1)) },
                                label = { Text("LTR Webtoon (Top-Left → Bottom-Right)", fontSize = 11.sp) },
                            )
                            FilterChip(
                                selected = config.lineSortingOrder == 2,
                                onClick = { service.setConfig(config.copy(lineSortingOrder = 2)) },
                                label = { Text("Disabled (Raw)", fontSize = 11.sp) },
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("OCR Chunking Lines (Method 5 & 6)", fontSize = 12.sp, color = Color.LightGray)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = config.chunkLinesCount == 1,
                                onClick = { service.setConfig(config.copy(chunkLinesCount = 1)) },
                                label = { Text("1 Line (Isolated)", fontSize = 11.sp) },
                            )
                            FilterChip(
                                selected = config.chunkLinesCount == 2,
                                onClick = { service.setConfig(config.copy(chunkLinesCount = 2)) },
                                label = { Text("2 Lines (Default)", fontSize = 11.sp) },
                            )
                            FilterChip(
                                selected = config.chunkLinesCount == 3,
                                onClick = { service.setConfig(config.copy(chunkLinesCount = 3)) },
                                label = { Text("3 Lines (Wide Context)", fontSize = 11.sp) },
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Det 2 Neighbor Probing", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 13.sp)
                                Text("Probes 4-way adjacent boxes around detected speech bubbles", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = config.probeDet2Neighbors,
                                onCheckedChange = { service.setConfig(config.copy(probeDet2Neighbors = it)) },
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Step 3 Orphan Line Probing", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 13.sp)
                                Text("AI noise filter: probes non-bubble text to discard background artifacts", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = config.probeStep3Orphans,
                                onCheckedChange = { service.setConfig(config.copy(probeStep3Orphans = it)) },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section 4: Typesetting & Inpainting
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FormatShapes, contentDescription = null, tint = Color(0xFFE040FB), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("4. Typesetting & Inpainting", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Fill Bubble Background", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 13.sp)
                            Text("Cleans original Japanese text before drawing translation", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = config.fillBubbleBackground,
                            onCheckedChange = { service.setConfig(config.copy(fillBubbleBackground = it)) },
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Font Scale Multiplier", fontSize = 12.sp, color = Color.LightGray)
                        Text("%", fontSize = 12.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = config.textScaleFactor,
                        onValueChange = { service.setConfig(config.copy(textScaleFactor = it)) },
                        valueRange = 0.5f..2.0f,
                        steps = 15,
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keep Screen On (WakeLock)", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 13.sp)
                            Text("Prevents phone sleep during heavy AI processing", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = config.keepScreenOn,
                            onCheckedChange = { service.setConfig(config.copy(keepScreenOn = it)) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section 5: Speed & Hardware Resource Consumption
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Speed, contentDescription = null, tint = Color(0xFFFFD600), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("5. Speed & Resource Control", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        }
                        Text("5 Discrete Levels", fontSize = 11.sp, color = Color.Gray)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Fine-tune hardware resource consumption for lower-end vs high-end devices. Low saves battery and RAM (sequential); High pulls full CPU/RAM concurrency for maximum speed.",
                        fontSize = 11.sp,
                        color = Color.LightGray,
                        lineHeight = 15.sp,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Master Preset Row
                    Text("Master Hardware Profile Preset", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFFD600))
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            Triple(1, "Lowest", Color(0xFF81C784)),
                            Triple(2, "Low", Color(0xFF4FC3F7)),
                            Triple(3, "Normal", Color(0xFFFFB74D)),
                            Triple(4, "High", Color(0xFFFF8A65)),
                            Triple(5, "Highest", Color(0xFFFF5252)),
                        ).forEach { (lvl, label, tintColor) ->
                            val isSelected = config.speedDet1 == lvl &&
                                    config.speedDet2 == lvl &&
                                    config.speedProbeNeighbors == lvl &&
                                    config.speedProbeOrphans == lvl &&
                                    config.speedOcr == lvl &&
                                    config.speedTranslate == lvl

                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    service.setConfig(
                                        config.copy(
                                            speedDet1 = lvl,
                                            speedDet2 = lvl,
                                            speedProbeNeighbors = lvl,
                                            speedProbeOrphans = lvl,
                                            speedOcr = lvl,
                                            speedTranslate = lvl,
                                        )
                                    )
                                },
                                label = {
                                    Text(
                                        label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color.Black else tintColor,
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 1. Detection 1 (Global Scan)
                    SpeedSliderItem(
                        title = "Detection 1: Global Scan",
                        description = "DBNet/CTD segmentation confidence & initial thread allocation",
                        currentLevel = config.speedDet1,
                        onLevelChange = { service.setConfig(config.copy(speedDet1 = it)) },
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2. Detection 2 (Boost Scan)
                    SpeedSliderItem(
                        title = "Detection 2: Boost Scan",
                        description = "Halo mask canvas contrast enhancement & candidate box generation",
                        currentLevel = config.speedDet2,
                        onLevelChange = { service.setConfig(config.copy(speedDet2 = it)) },
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 3. Probing Neighbors (Det 2 Checks)
                    SpeedSliderItem(
                        title = "Probing Neighbors: Det 2 Adjacent",
                        description = "Parallel worker threads & token bounds for 4-way adjacent cell verification",
                        currentLevel = config.speedProbeNeighbors,
                        onLevelChange = { service.setConfig(config.copy(speedProbeNeighbors = it)) },
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 4. Probing Orphans (Step 3 Noise Checks)
                    SpeedSliderItem(
                        title = "Probing Orphans: Step 3 Filter",
                        description = "Concurrency & token limits for probing non-bubbled orphan background text",
                        currentLevel = config.speedProbeOrphans,
                        onLevelChange = { service.setConfig(config.copy(speedProbeOrphans = it)) },
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 5. OCR (MangaOCR Recognition)
                    SpeedSliderItem(
                        title = "OCR: Text Recognition",
                        description = "MangaOCR ONNX intra-op threads, token caps, and coroutine worker concurrency",
                        currentLevel = config.speedOcr,
                        onLevelChange = { service.setConfig(config.copy(speedOcr = it)) },
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 6. Translation (Sugoi / On-Device)
                    SpeedSliderItem(
                        title = "Translate: Sugoi / SLM",
                        description = "Translation worker semaphore concurrency and Marian/ONNX thread scaling",
                        currentLevel = config.speedTranslate,
                        onLevelChange = { service.setConfig(config.copy(speedTranslate = it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedSliderItem(
    title: String,
    description: String,
    currentLevel: Int,
    onLevelChange: (Int) -> Unit,
) {
    val levelNames = listOf("Lowest", "Low", "Normal", "High", "Highest")
    val levelColors = listOf(
        Color(0xFF81C784), // Lowest: Green
        Color(0xFF4FC3F7), // Low: Cyan
        Color(0xFFFFB74D), // Normal: Orange
        Color(0xFFFF8A65), // High: Deep Orange
        Color(0xFFFF5252), // Highest: Red
    )
    val safeIndex = (currentLevel - 1).coerceIn(0, 4)
    val activeLabel = levelNames[safeIndex]
    val activeColor = levelColors[safeIndex]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 13.sp)
                Text(description, fontSize = 11.sp, color = Color.Gray, lineHeight = 14.sp)
            }
            Text(
                text = "$currentLevel · $activeLabel",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = activeColor,
            )
        }

        Slider(
            value = currentLevel.toFloat(),
            onValueChange = { onLevelChange(it.roundToInt().coerceIn(1, 5)) },
            valueRange = 1f..5f,
            steps = 3, // 5 discrete positions: 1, 2, 3, 4, 5
            modifier = Modifier.fillMaxWidth(),
        )
    }
}