package com.raen.crunchlab.engine.profile

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Dynamic resource consumption profiles.
 * low = 30% (3 threads)
 * mid = 55% (4 threads)
 * high = 80% (6 threads)
 */
enum class ResourceProfile(
    val title: String,
    val targetLoadPercent: Int,
    val threads: Int,
    val badge: String,
    val description: String,
) {
    LOW(
        title = "Low",
        targetLoadPercent = 30,
        threads = 3,
        badge = "30% • 3T",
        description = "Eco & cool operation. Safe for background multitasking and battery saving."
    ),
    MID(
        title = "Mid",
        targetLoadPercent = 55,
        threads = 4,
        badge = "55% • 4T",
        description = "Optimal speed and thermal balance. Recommended for sustained reading."
    ),
    HIGH(
        title = "High",
        targetLoadPercent = 80,
        threads = 6,
        badge = "80% • 6T",
        description = "Maximum throughput burst mode. Utilizes performance cluster cores."
    );

    companion object {
        fun fromThreads(threads: Int): ResourceProfile = when (threads) {
            in 0..3 -> LOW
            in 4..5 -> MID
            else -> HIGH
        }
    }
}

/**
 * Hardware execution delegates for ONNX Runtime inference.
 * Independent of resource profiles (Low/Mid/High).
 */
enum class HardwareDelegate(
    val label: String,
    val shortLabel: String,
    val description: String,
) {
    XNNPACK(
        label = "CPU (XNNPACK)",
        shortLabel = "XNNPACK",
        description = "High-efficiency multi-threaded ARM NEON / FP32 kernel acceleration."
    ),
    NNAPI(
        label = "NNAPI (NPU / GPU)",
        shortLabel = "NNAPI",
        description = "Android Neural Networks API delegate (offloads to NPU / DSP / GPU where available)."
    ),
    CPU(
        label = "CPU (Standard)",
        shortLabel = "Standard",
        description = "Baseline sequential CPU without specialized XNNPACK thread pool."
    )
}

/**
 * Snapshot of detected device hardware capabilities.
 */
data class DeviceSpecs(
    val totalRamGb: Float,
    val availRamMb: Long,
    val isLowMemory: Boolean,
    val availableCores: Int,
    val recommendedProfile: ResourceProfile,
    val deviceModel: String,
    val socSummary: String,
)

object DeviceProfileManager {

    fun detectSpecs(context: Context): DeviceSpecs {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val totalRamGb = memInfo.totalMem / (1024f * 1024f * 1024f)
        val availRamMb = memInfo.availMem / (1024L * 1024L)
        val isLowMem = memInfo.lowMemory

        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        val recommended = when {
            totalRamGb <= 4.5f || cores <= 4 -> ResourceProfile.LOW
            totalRamGb >= 10.0f && cores >= 8 -> ResourceProfile.HIGH
            else -> ResourceProfile.MID
        }

        val model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val soc = "${Build.HARDWARE} (${cores} Cores)"

        return DeviceSpecs(
            totalRamGb = totalRamGb,
            availRamMb = availRamMb,
            isLowMemory = isLowMem,
            availableCores = cores,
            recommendedProfile = recommended,
            deviceModel = model,
            socSummary = soc,
        )
    }

    fun getMemorySummary(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024L * 1024L)
        val totalGb = String.format(java.util.Locale.US, "%.1f", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
        return "RAM: ${availMb}MB free / ${totalGb}GB total"
    }
}
