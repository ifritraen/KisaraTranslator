package com.raen.crunchlab.util

import ai.onnxruntime.OrtSession
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
     * Creates an OrtSession.SessionOptions configured for maximum mobile inference speed:
     * - Disables persistent BFCArena pre-allocation and memory hoard (addCPU(false))
     * - Disables static execution plan memory pattern caching (setMemoryPatternOptimization(false))
     * - Enables hardware XNNPACK / ARM NEON acceleration
     * - Intra-op multi-threading matched to performance cores
     * - Disables thread spinning to prevent thermal throttling
     * Caller MUST close the returned options after creating the session.
     */
    fun createSessionOptions(threads: Int = 4): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(threads.coerceAtLeast(1))
        opts.setInterOpNumThreads(1)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        opts.setMemoryPatternOptimization(false)
        try {
            val method = opts.javaClass.getMethod("addXnnpack", Map::class.java)
            method.invoke(opts, mapOf("num_threads" to threads.toString()))
        } catch (_: Throwable) {}
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
