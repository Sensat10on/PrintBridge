package com.printbridge.core

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeadlineTests {
    @Test fun blockingWorkWithinTheDeadlineReturnsItsResult() = runBlocking {
        val result = withIoDeadline(5_000, "test-worker") {
            Thread.sleep(20)
            "done"
        }

        assertEquals("done", result)
    }

    @Test fun blockingWorkPastTheDeadlineIsAbandonedAndReported() = runBlocking {
        var aborted = false
        var workerFinished = false

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(5_000) {
                withIoDeadline(100, "test-worker-slow", onTimeout = { aborted = true }) {
                    // Simulates a stalled RFCOMM write: not interruptible by Thread.interrupt().
                    Thread.sleep(1_500)
                    workerFinished = true
                }
            }
        }

        assertTrue(aborted, "onTimeout must run so the caller can close the socket")
        assertFalse(workerFinished, "The call must not have waited for the blocking work to finish")
    }

    @Test fun workerFailureIsPropagatedToTheCaller() = runBlocking {
        val failure = assertFailsWith<IllegalStateException> {
            withIoDeadline(5_000, "test-worker-failure") {
                throw IllegalStateException("bulk transfer failed")
            }
        }

        assertEquals("bulk transfer failed", failure.message)
    }

    @Test fun nonPositiveDeadlineRunsInlineWithoutATimeout() = runBlocking {
        val result = withIoDeadline(0, "test-worker-inline") { "immediate" }

        assertEquals("immediate", result)
    }

    @Test fun chunkerClampsAbsurdChunkSizesInsteadOfOverflowing() = runBlocking {
        val transport = RecordingTransport()
        val profile = DefaultProfiles.all[0].copy(chunkSize = Int.MAX_VALUE, delayBetweenChunksMs = 0)
        val data = ByteArray(WriteChunker.MAX_CHUNK_SIZE + 10) { (it % 251).toByte() }

        val job = PrintQueue(transport).enqueue(
            PrintJob(source = "unit", printerProfileId = profile.id),
            profile,
            data
        )

        assertEquals(PrintJobState.COMPLETED, job.state)
        assertEquals(data.size, transport.totalBytes)
        assertTrue(transport.writes.all { it.size <= WriteChunker.MAX_CHUNK_SIZE })
    }

    @Test fun chunkerTreatsNegativeDelayAsNoDelay() = runBlocking {
        val transport = RecordingTransport()
        val profile = DefaultProfiles.all[0].copy(chunkSize = 2, delayBetweenChunksMs = -5)

        val job = PrintQueue(transport).enqueue(
            PrintJob(source = "unit", printerProfileId = profile.id),
            profile,
            byteArrayOf(1, 2, 3)
        )

        assertEquals(PrintJobState.COMPLETED, job.state)
        assertEquals(listOf(2, 1), transport.writes.map { it.size })
    }

    @Test fun transportKeepsErrorStateAfterFailedConnect() = runBlocking {
        val failingTransport = object : PrinterTransport {
            override val state = MutableStateFlow(ConnectionState.DISCONNECTED)
            override suspend fun connect() {
                state.value = ConnectionState.ERROR
                throw PrintBridgeError.ConnectionLost()
            }
            override suspend fun disconnect() {
                if (state.value != ConnectionState.ERROR) state.value = ConnectionState.DISCONNECTED
            }
            override suspend fun write(data: ByteArray) = Unit
        }

        val profile = DefaultProfiles.all[0]
        val job = PrintQueue(failingTransport).enqueue(
            PrintJob(source = "unit", printerProfileId = profile.id),
            profile,
            byteArrayOf(1)
        )

        assertEquals(PrintJobState.FAILED, job.state)
        assertEquals(ConnectionState.ERROR, failingTransport.state.value)
    }
}

/** Minimal recording transport, so print-core tests stay independent of printer-transport. */
private class RecordingTransport : PrinterTransport {
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state = mutableState
    val writes = mutableListOf<ByteArray>()
    val totalBytes: Int get() = writes.sumOf { it.size }

    override suspend fun connect() {
        mutableState.value = ConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        mutableState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun write(data: ByteArray) {
        writes += data.copyOf()
    }
}
