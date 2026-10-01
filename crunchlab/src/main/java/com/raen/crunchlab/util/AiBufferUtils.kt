package com.raen.crunchlab.util

import ai.onnxruntime.OrtSession
import android.util.Log
import com.raen.crunchlab.engine.profile.HardwareDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Utility for allocating native-order Direct FloatBuffers and high-performance
 * hardware-accelerated OrtSession configurations for Android mobile inference.
 */
object AiBufferUtils {

    fun allocateDirectFloatBuffer(capacity: Int): FloatBuffer {
        return ByteBuffer.allocateDirect(capacity * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    /**
     * Creates an OrtSession.SessionOptions configured for the specified thread count
     * and hardware execution delegate:
     * - Configurable threads matching selected ResourceProfile (Low=3, Mid=4, High=6)
     * - Configurable hardware execution delegate (XNNPACK, NNAPI, standard CPU)
     * - Disables persistent BFCArena pre-allocation and memory hoard (addCPU(false))
     * - Disables static execution plan memory pattern caching (setMemoryPatternOptimization(false))
     * - Disables thread spinning to prevent thermal throttling
     * Caller MUST close the returned options after creating the session.
     */
    fun createSessionOptions(
        threads: Int = 4,
        delegate: HardwareDelegate = HardwareDelegate.XNNPACK,
    ): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val safeThreads = threads.coerceIn(1, numCores)

        opts.setIntraOpNumThreads(safeThreads)
        opts.setInterOpNumThreads(1)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        opts.setMemoryPatternOptimization(false)

        when (delegate) {
            HardwareDelegate.XNNPACK -> {
                try {
                    val method = opts.javaClass.getMethod("addXnnpack", Map::class.java)
                    method.invoke(opts, mapOf("num_threads" to safeThreads.toString()))
                } catch (e: Throwable) {
                    Log.w("AiBufferUtils", "Could not configure XNNPACK: ${e.message}")
                }
            }
            HardwareDelegate.NNAPI -> {
                var nnapiAdded = false
                try {
                    val method = try {
                        opts.javaClass.getMethod("addNnapi")
                    } catch (_: NoSuchMethodException) {
                        opts.javaClass.getMethod("addNnapi", Int::class.javaPrimitiveType)
                    }
                    if (method.parameterCount == 0) {
                        method.invoke(opts)
                    } else {
                        method.invoke(opts, 0)
                    }
                    nnapiAdded = true
                } catch (e: Throwable) {
                    Log.w("AiBufferUtils", "NNAPI delegate not available, falling back to XNNPACK: ${e.message}")
                }
                if (!nnapiAdded) {
                    try {
                        val method = opts.javaClass.getMethod("addXnnpack", Map::class.java)
                        method.invoke(opts, mapOf("num_threads" to safeThreads.toString()))
                    } catch (_: Throwable) {}
                }
            }
            HardwareDelegate.CPU -> {
                // Baseline sequential CPU without XNNPACK delegate
            }
        }

        opts.addCPU(false)
        try {
            opts.addConfigEntry("session.use_memory_arena", "0")
            opts.addConfigEntry("session.use_device_allocator_for_initializers", "1")
            opts.addConfigEntry("session.arena_extend_strategy", "kSameAsRequested")
            opts.addConfigEntry("session.intra_op.allow_spinning", "0")
        } catch (_: Throwable) {}
        return opts
    }
}
