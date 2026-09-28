package com.raen.kisaratranslator.data.monitor

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.raen.kisaratranslator.data.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

data class ResourceMetrics(
    val cpuPercent: Int = 0,
    val gpuPercent: Int = 0,
    val gpuClockMhz: Int = 0,
    val ramAppMb: Long = 0,
    val ramMaxMb: Long = 0,
    val isRootActive: Boolean = false,
)

object ResourceMonitor {
    init {
        try {
            System.loadLibrary("kisara_native")
        } catch (e: Throwable) {
            AppLogger.error("Failed to load kisara_native library", e)
        }
    }

    @JvmStatic
    private external fun nativePurgeHeap(): Boolean

    private val _metrics = MutableStateFlow(ResourceMetrics())
    val metrics: StateFlow<ResourceMetrics> = _metrics.asStateFlow()

    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // CPU sampling state
    private var lastCpuTime: Long = 0L
    private var lastSampleTime: Long = 0L
    private val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // Root shell process keep-alive for zero-latency sysfs reading
    private var rootProcess: java.lang.Process? = null
    private var rootWriter: java.io.BufferedWriter? = null
    private var rootReader: BufferedReader? = null
    private var hasRoot = false

    fun start() {
        if (monitorJob != null) return

        initRootShell()

        lastCpuTime = Process.getElapsedCpuTime()
        lastSampleTime = SystemClock.uptimeMillis()

        monitorJob = scope.launch {
            while (isActive) {
                try {
                    val sample = sampleMetrics()
                    _metrics.value = sample
                } catch (_: Exception) {}
                delay(1000)
            }
        }
    }

    private fun initRootShell() {
        try {
            val p = java.lang.ProcessBuilder("su").start()
            val w = java.io.BufferedWriter(java.io.OutputStreamWriter(p.outputStream))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            w.write("id\n")
            w.flush()
            val line = r.readLine()
            if (line != null && line.contains("uid=0")) {
                hasRoot = true
                rootProcess = p
                rootWriter = w
                rootReader = r
                AppLogger.info("ResourceMonitor: Root shell initialized successfully (uid=0)")
            } else {
                p.destroy()
            }
        } catch (_: Exception) {
            hasRoot = false
        }
    }

    private fun sampleMetrics(): ResourceMetrics {
        // 1. Memory Calculation
        val runtime = Runtime.getRuntime()
        val heapAllocated = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val nativeAllocated = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val ramAppMb = heapAllocated + nativeAllocated
        val ramMaxMb = runtime.maxMemory() / (1024 * 1024)

        // 2. CPU Calculation
        val nowSample = SystemClock.uptimeMillis()
        val nowCpu = Process.getElapsedCpuTime()
        val timeDelta = nowSample - lastSampleTime
        val cpuDelta = nowCpu - lastCpuTime

        var cpuPct = 0
        if (timeDelta > 0) {
            cpuPct = ((cpuDelta.toFloat() / (timeDelta * numCores)) * 100f).toInt().coerceIn(0, 100)
        }
        lastSampleTime = nowSample
        lastCpuTime = nowCpu

        // 3. GPU Calculation (Mali/Adreno sysfs via root)
        var gpuPct = 0
        var gpuMhz = 0

        if (hasRoot && rootWriter != null && rootReader != null) {
            try {
                rootWriter!!.write("cat /sys/kernel/gpu/gpu_busy 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null; cat /sys/kernel/gpu/gpu_clock 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null; echo '---END---'\n")
                rootWriter!!.flush()

                var line: String?
                var step = 0
                while (rootReader!!.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed == "---END---") break
                    if (step == 0) {
                        val digits = trimmed.replace("%", "").trim().toIntOrNull()
                        if (digits != null) gpuPct = digits
                        step++
                    } else if (step == 1) {
                        val khz = trimmed.toIntOrNull()
                        if (khz != null) gpuMhz = if (khz > 100000) khz / 1000 else khz
                        step++
                    }
                }
            } catch (_: Exception) {
                initRootShell()
            }
        }

        return ResourceMetrics(
            cpuPercent = cpuPct,
            gpuPercent = gpuPct.coerceIn(0, 100),
            gpuClockMhz = gpuMhz,
            ramAppMb = ramAppMb,
            ramMaxMb = ramMaxMb,
            isRootActive = hasRoot,
        )
    }

    fun optimizeRam(
        context: android.content.Context? = null,
        externalJavaBefore: Long? = null,
        externalNativeBefore: Long? = null,
    ) {
        val runtime = Runtime.getRuntime()
        val javaBefore = externalJavaBefore ?: ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
        val nativeBefore = externalNativeBefore ?: (Debug.getNativeHeapAllocatedSize() / (1024 * 1024))

        System.runFinalization()
        System.gc()
        runtime.runFinalization()
        runtime.gc()

        // 1. Force Android system memory trim on ART and Graphics pipeline
        try {
            (context?.applicationContext as? android.app.Application)?.onTrimMemory(
                android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            )
        } catch (_: Exception) {}

        // 2. VMRuntime trimHeap if available on ART
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntimeMethod = vmRuntimeClass.getMethod("getRuntime")
            val vmRuntime = getRuntimeMethod.invoke(null)
            val trimHeapMethod = vmRuntimeClass.getMethod("trimHeap")
            trimHeapMethod.invoke(vmRuntime)
        } catch (_: Throwable) {}

        // 3. Purge Android Scudo native heap cache directly via libc mallopt
        try {
            nativePurgeHeap()
        } catch (_: Throwable) {}

        val javaAfter = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val nativeAfter = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        AppLogger.info("RAM Optimization: Java [${javaBefore}MB -> ${javaAfter}MB], Native [${nativeBefore}MB -> ${nativeAfter}MB]")

        // 3. Immediately update metrics so UI reflects the memory drop without 1s delay
        try {
            _metrics.value = sampleMetrics()
        } catch (_: Exception) {}
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        try {
            rootWriter?.write("exit\n")
            rootWriter?.flush()
            rootProcess?.destroy()
        } catch (_: Exception) {}
        rootProcess = null
        rootWriter = null
        rootReader = null
    }
}
