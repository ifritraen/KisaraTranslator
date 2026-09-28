package com.raen.kisaratranslator.core.wakelock

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages CPU WakeLock and foreground screen keep-alive for uninterrupted
 * ONNX neural inference and batch translation runs.
 */
class WakeLockManager(private val context: Context) {

    companion object {
        private const val TAG = "WakeLockManager"
        private const val LOCK_TAG_BASE = "KisaraTranslator:InferenceLock"
        private const val DEFAULT_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes safety limit
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val lockRefCounter = AtomicInteger(0)

    private val _isLockHeld = MutableStateFlow(false)
    val isLockHeld: StateFlow<Boolean> = _isLockHeld.asStateFlow()

    @Synchronized
    fun acquire(reason: String = "inference", timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$LOCK_TAG_BASE:$reason",
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(timeoutMs)
            lockRefCounter.incrementAndGet()
            _isLockHeld.value = true
            Log.d(TAG, "WakeLock acquired for: $reason (active refs: ${lockRefCounter.get()})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    @Synchronized
    fun release(reason: String = "inference") {
        try {
            val refs = lockRefCounter.decrementAndGet()
            if (refs <= 0) {
                lockRefCounter.set(0)
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                _isLockHeld.value = false
                Log.d(TAG, "WakeLock completely released after: $reason")
            } else {
                Log.d(TAG, "WakeLock ref decreased for: $reason (remaining: $refs)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }

    /**
     * Executes the suspending [block] while safely guaranteeing WakeLock acquisition and release.
     */
    suspend fun <T> withWakeLock(
        reason: String = "task",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        block: suspend () -> T,
    ): T {
        acquire(reason, timeoutMs)
        return try {
            block()
        } finally {
            release(reason)
        }
    }
}
