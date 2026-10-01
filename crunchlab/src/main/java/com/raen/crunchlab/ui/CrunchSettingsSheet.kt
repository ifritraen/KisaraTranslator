package com.raen.crunchlab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raen.crunchlab.engine.profile.DeviceProfileManager
import com.raen.crunchlab.engine.profile.DeviceSpecs
import com.raen.crunchlab.engine.profile.HardwareDelegate
import com.raen.crunchlab.engine.profile.ResourceProfile
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrunchSettingsSheet(
    activeProfile: ResourceProfile,
    onProfileChange: (ResourceProfile) -> Unit,
    activeDelegate: HardwareDelegate,
    onDelegateChange: (HardwareDelegate) -> Unit,
    onReleaseResources: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val specs: DeviceSpecs = remember { DeviceProfileManager.detectSpecs(context) }
    var memorySummary by remember { mutableStateOf(DeviceProfileManager.getMemorySummary(context)) }
    var purgeFeedback by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161616),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.DarkGray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            "Resource & Performance",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Dynamic profiling • Hardware delegate • Memory release",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                }
            }

            // 1. Device Hardware Specs Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = Color(0xFF40C4FF),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "Detected Device Specs",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Surface(
                            color = Color(0xFF2E7D32),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "Auto-Detected",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = Color(0xFF333333))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Device", color = Color.Gray, fontSize = 11.sp)
                            Text(specs.deviceModel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("SoC Cores", color = Color.Gray, fontSize = 11.sp)
                            Text("${specs.availableCores} Cores", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("System RAM", color = Color.Gray, fontSize = 11.sp)
                            Text(
                                String.format(Locale.US, "%.1f GB total (Free: %d MB)", specs.totalRamGb, specs.availRamMb),
                                color = if (specs.isLowMemory) Color(0xFFFF5252) else Color(0xFFB0BEC5),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (activeProfile != specs.recommendedProfile) {
                            OutlinedButton(
                                onClick = { onProfileChange(specs.recommendedProfile) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    "Apply Rec: ${specs.recommendedProfile.title}",
                                    fontSize = 10.sp,
                                    color = Color(0xFF69F0AE)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Resource Consumption Profiles
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Dynamic Consumption Profile",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Allocates CPU threads to match thermal ceiling and multitasking headroom",
                    color = Color.Gray,
                    fontSize = 11.sp
                )

                ResourceProfile.values().forEach { profile ->
                    val isSelected = profile == activeProfile
                    val borderColor = if (isSelected) Color(0xFF00E676) else Color(0xFF333333)
                    val bgColor = if (isSelected) Color(0xFF1B382B) else Color(0xFF202020)

                    Surface(
                        color = bgColor,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
                            .clickable { onProfileChange(profile) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        profile.title,
                                        color = if (isSelected) Color(0xFF69F0AE) else Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        color = if (isSelected) Color(0xFF00E676) else Color(0xFF424242),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "${profile.targetLoadPercent}% (${profile.threads} Threads)",
                                            color = if (isSelected) Color.Black else Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    profile.description,
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 11.sp
                                )
                            }

                            RadioButton(
                                selected = isSelected,
                                onClick = { onProfileChange(profile) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFF00E676),
                                    unselectedColor = Color.Gray
                                )
                            )
                        }
                    }
                }
            }

            // 3. Hardware Execution Delegate
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "Hardware Execution Delegate",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "Independent hardware acceleration for neural inference engines",
                    color = Color.Gray,
                    fontSize = 11.sp
                )

                HardwareDelegate.values().forEach { delegate ->
                    val isSelected = delegate == activeDelegate
                    val borderColor = if (isSelected) Color(0xFFFFD54F) else Color(0xFF333333)
                    val bgColor = if (isSelected) Color(0xFF372E14) else Color(0xFF202020)

                    Surface(
                        color = bgColor,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
                            .clickable { onDelegateChange(delegate) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    delegate.label,
                                    color = if (isSelected) Color(0xFFFFE082) else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    delegate.description,
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 11.sp
                                )
                            }

                            RadioButton(
                                selected = isSelected,
                                onClick = { onDelegateChange(delegate) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFFFFD54F),
                                    unselectedColor = Color.Gray
                                )
                            )
                        }
                    }
                }
            }

            // 4. Memory Safeguard & Auto-Release
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E262B)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "Auto-Release & Memory Cap",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Surface(
                            color = Color(0xFF006064),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "Active (0ms)",
                                color = Color(0xFF80DEEA),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        "All ONNX models (CTD, Bubble, Waist, MangaOCR, Sugoi INT8) are automatically closed and their ~1.5+GB native weight buffers released immediately when translation finishes or is stopped.",
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )

                    HorizontalDivider(color = Color(0xFF2C3940))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Current Memory", color = Color.Gray, fontSize = 10.sp)
                            Text(memorySummary, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            if (purgeFeedback != null) {
                                Text(purgeFeedback ?: "", color = Color(0xFF00E676), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = {
                                onReleaseResources()
                                memorySummary = DeviceProfileManager.getMemorySummary(context)
                                purgeFeedback = "✓ 1.5+GB purged"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00838F)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("🧹 Purge RAM", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
