package com.raen.kisaratranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.raen.kisaratranslator.core.wakelock.WakeLockManager
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.service.BatchTranslationService
import com.raen.kisaratranslator.data.service.TranslationService
import com.raen.kisaratranslator.ui.components.DiagnosticInspectorSheet
import com.raen.kisaratranslator.ui.components.LogConsoleWindow
import com.raen.kisaratranslator.ui.components.PipelineSettingsSheet
import com.raen.kisaratranslator.ui.components.QuickInspectContainer
import com.raen.kisaratranslator.ui.components.ResourceBar
import com.raen.kisaratranslator.ui.screens.BatchFolderScreen
import com.raen.kisaratranslator.ui.screens.ModelManagerScreen
import com.raen.kisaratranslator.ui.screens.SigmaBenchmarkScreen
import com.raen.kisaratranslator.ui.screens.SinglePageScreen

@Composable
fun MainScreen(
    translationService: TranslationService,
    batchService: BatchTranslationService,
    modelManager: TranslationModelManager,
    wakeLockManager: WakeLockManager,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }

    val lastResult by translationService.translationResult.collectAsState()

    Scaffold(
        containerColor = Color(0xFF121212),
        // TopBar removed for clean edge-to-edge experience (matching KisaraColorizer)
        topBar = {},
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                // Quick Inspect Contextual Step Galleries (Expandable directly over ResourceBar)
                if (lastResult != null) {
                    QuickInspectContainer(
                        translationService = translationService,
                        result = lastResult!!,
                    )
                }

                // 1. Ultra-slim Live Resource Monitor (CPU %, GPU %, RAM MB) directly above LogConsoleWindow
                ResourceBar(
                    onFreeRamClick = { translationService.optimizeMemory() },
                )

                // 2. Engine Logs Console Window
                LogConsoleWindow()

                // 3. Ultra-slim Bottom Navigation Bar (46.dp height, pure icons)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Tab 0: Single Page
                        IconButton(
                            onClick = { selectedTab = 0 },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Translate,
                                contentDescription = "Single Page",
                                tint = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        // Tab 1: Batch Folder
                        IconButton(
                            onClick = { selectedTab = 1 },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderCopy,
                                contentDescription = "Batch Folder",
                                tint = if (selectedTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        // Tab 2: AI Models
                        IconButton(
                            onClick = { selectedTab = 2 },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Extension,
                                contentDescription = "AI Models",
                                tint = if (selectedTab == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        // Tab 3: Sigma Benchmark
                        IconButton(
                            onClick = { selectedTab = 3 },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Compare,
                                contentDescription = "Sigma Benchmark",
                                tint = if (selectedTab == 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        // Diagnostics Sheet Trigger
                        IconButton(
                            onClick = { showDiagnosticsSheet = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Analytics,
                                contentDescription = "Diagnostics",
                                tint = if (lastResult != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        // Pipeline Settings & Tuning Trigger
                        IconButton(
                            onClick = { showSettingsSheet = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = "Pipeline Settings & Tuning",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 8.dp)
                .padding(bottom = paddingValues.calculateBottomPadding()),
        ) {
            when (selectedTab) {
                0 -> SinglePageScreen(
                    translationService = translationService,
                    onOpenSettings = { showSettingsSheet = true },
                )
                1 -> BatchFolderScreen(batchService = batchService, wakeLockManager = wakeLockManager)
                2 -> ModelManagerScreen(modelManager = modelManager)
                3 -> SigmaBenchmarkScreen(translationService = translationService)
            }
        }
    }

    if (showSettingsSheet) {
        PipelineSettingsSheet(
            service = translationService,
            onDismissRequest = { showSettingsSheet = false },
        )
    }

    if (showDiagnosticsSheet) {
        DiagnosticInspectorSheet(
            diagnostics = lastResult?.diagnostics,
            onDismiss = { showDiagnosticsSheet = false },
        )
    }
}
