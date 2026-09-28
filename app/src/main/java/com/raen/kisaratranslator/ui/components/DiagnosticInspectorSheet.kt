package com.raen.kisaratranslator.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raen.kisaratranslator.data.model.LogEntry
import com.raen.kisaratranslator.data.model.TranslationReport
import com.raen.kisaratranslator.data.service.TranslationStepDiagnostics
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticInspectorSheet(
    diagnostics: TranslationStepDiagnostics?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val logList = remember { mutableStateListOf<LogEntry>().apply { addAll(TranslationReport.logs) } }

    LaunchedEffect(Unit) {
        TranslationReport.logFlow.collectLatest { entry ->
            logList.add(entry)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Translation Diagnostics & Logs",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Step Timing Breakdown Cards
            if (diagnostics != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF282828),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Execution Timing Breakdown",
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF00E676),
                            fontSize = 13.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TimingItem("Detection", "${diagnostics.detectionTimeMs} ms")
                            TimingItem("OCR Crops", "${diagnostics.ocrTimeMs} ms")
                            TimingItem("Grouping", "${diagnostics.groupingTimeMs} ms")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TimingItem("Translation", "${diagnostics.translationTimeMs} ms")
                            TimingItem("Rendering", "${diagnostics.renderingTimeMs} ms")
                            TimingItem("Total Time", "${diagnostics.totalTimeMs} ms", highlight = true)
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TimingItem("Detected Boxes", "${diagnostics.detectedBoxesCount}")
                            TimingItem("Final Blocks", "${diagnostics.finalBlocksCount}")
                            TimingItem("Status", "SUCCESS", highlight = true)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Action buttons: Copy all logs & Clear
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = {
                        val text = logList.joinToString("\n") { "[${it.timestamp}] [${it.level}] [${it.tag}] ${it.message}" }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Translation Diagnostics", text))
                        Toast.makeText(context, "Copied ${logList.size} log lines to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF333333)),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Logs", fontSize = 12.sp, color = Color.White)
                }

                OutlinedButton(
                    onClick = {
                        TranslationReport.clear()
                        logList.clear()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Logs", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Log Console
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Black,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            ) {
                if (logList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                        Text("No logs recorded yet.", color = Color.Gray, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                    ) {
                        items(logList.reversed()) { log ->
                            val color = when (log.level) {
                                "ERROR" -> Color(0xFFFF5252)
                                "WARN" -> Color(0xFFFFAB00)
                                else -> Color(0xFFB0BEC5)
                            }
                            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    text = "[${log.timestamp}] [${log.tag}] ${log.message}",
                                    color = color,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                )
                                if (log.throwable != null) {
                                    Text(
                                        text = log.throwable.stackTraceToString().take(400),
                                        color = Color(0xFFFF8A80),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
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
private fun TimingItem(label: String, value: String, highlight: Boolean = false) {
    Column {
        Text(text = label, fontSize = 10.sp, color = Color.Gray)
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) Color(0xFF00E676) else Color.White,
        )
    }
}
