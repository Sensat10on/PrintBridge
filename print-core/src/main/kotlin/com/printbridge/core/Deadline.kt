package com.printbridge.core

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

/**
 * Runs a blocking JVM operation (Bluetooth RFCOMM connect/OutputStream, USB bulk transfer, ...)
 * under a deadline.
 *
 * These helpers block a thread and do not observe coroutine cancellation, so the operation runs
 * on a dedicated daemon thread and the deadline is enforced by [withTimeout]. On timeout the
 * worker thread is shut down; if the operation is still blocked in native IO, the caller must
 * additionally abort the resource (close the socket) to release the thread.
 *
 * @param onTimeout invoked before the timeout exception is thrown, so callers can close sockets.
 * @throws TimeoutCancellationException when the deadline expires.
 */
suspend fun <T> withIoDeadline(
    timeoutMs: Long,
    workerName: String,
    onTimeout: () -> Unit = {},
    action: () -> T
): T {
    if (timeoutMs <= 0) return runInterruptible(Dispatchers.IO, action)
    val worker = AtomicReference<ExecutorService?>()
    try {
        return withTimeout(timeoutMs) {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, workerName).apply { isDaemon = true }
            }
            worker.set(executor)
            runInterruptible(Dispatchers.IO) { action() }
                .also { executor.shutdown() }
        }
    } catch (timeout: TimeoutCancellationException) {
        onTimeout()
        throw timeout
    } finally {
        worker.get()?.shutdownNow()
    }
}
