package com.lvsmsmch.aichat.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * A thread-safe buffer that collects items and flushes them after a period of inactivity.
 * Uses a periodic checking mechanism rather than per-item timers.
 */
class InactivityBuffer<T>(
    private val inactivityTimeoutMs: Long = 200,
    private val checkIntervalMs: Long = 20,  // How often to check for inactivity
    private val onBatchReady: suspend (items: List<T>) -> Unit,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val buffer = mutableListOf<T>()
    private val mutex = Mutex()
    private var lastItemTimestamp = 0L
    private var checkerJob: Job? = null
    private var isCheckerRunning = false

    /**
     * Adds an item to the buffer and updates the timestamp.
     * Starts the checker if not already running.
     */
    suspend fun add(item: T) {
        mutex.withLock {
            buffer.add(item)
            lastItemTimestamp = System.currentTimeMillis()

            if (!isCheckerRunning) {
                startPeriodicChecker()
            }
        }
    }

    /**
     * Manually flush the buffer regardless of timing.
     */
    suspend fun flushNow() {
        mutex.withLock {
            if (buffer.isNotEmpty()) {
                flush()
            }
        }
    }

    /**
     * Releases resources and flushes any remaining items.
     */
    suspend fun close() {
        stopPeriodicChecker()
        flushNow()
        coroutineScope.cancel()
    }

    private fun startPeriodicChecker() {
        checkerJob = coroutineScope.launch {
            isCheckerRunning = true

            while (isActive) {
                delay(checkIntervalMs)

                val shouldStop = mutex.withLock {
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastItem = currentTime - lastItemTimestamp

                    if (buffer.isNotEmpty() && timeSinceLastItem >= inactivityTimeoutMs) {
                        flush()
                        // Stop checking until new items arrive
                        isCheckerRunning = false
                        true  // Signal to stop the loop
                    } else {
                        false  // Continue the loop
                    }
                }

                if (shouldStop) {
                    break  // Now break is outside the lambda
                }
            }
        }
    }

    private fun stopPeriodicChecker() {
        checkerJob?.cancel()
        checkerJob = null
        isCheckerRunning = false
    }

    private suspend fun flush() {
        if (buffer.isEmpty()) return

        val itemsToProcess = buffer.toList()
        buffer.clear()

        onBatchReady(itemsToProcess)
    }
}