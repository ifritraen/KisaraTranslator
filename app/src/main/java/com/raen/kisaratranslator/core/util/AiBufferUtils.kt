package com.raen.kisaratranslator.core.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Utility for allocating native-order Direct FloatBuffers for ONNX Tensor operations.
 */
object AiBufferUtils {
    fun allocateDirectFloatBuffer(capacity: Int): FloatBuffer {
        return ByteBuffer.allocateDirect(capacity * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    /**
     * Creates an OrtSession.SessionOptions configured for minimal mobile RAM footprint:
     * - Disables persistent BFCArena pre-allocation and memory hoard (addCPU(false))
     * - Disables static execution plan memory pattern caching (setMemoryPatternOptimization(false))
     * - Enables hardware XNNPACK / NEON acceleration
     * - Sequential execution mode with specified intra-op thread count
     * Caller MUST close the returned options after creating the session.
     */
    fun createSessionOptions(threads: Int = 2): ai.onnxruntime.OrtSession.SessionOptions {
        val opts = ai.onnxruntime.OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(threads.coerceAtLeast(1))
        opts.setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)
        opts.setExecutionMode(ai.onnxruntime.OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
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
