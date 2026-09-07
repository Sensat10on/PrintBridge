package com.printbridge.transport

import com.printbridge.core.ConnectionState
import com.printbridge.core.PrintBridgeError
import com.printbridge.core.PrinterTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.time.Instant

data class FaultProfile(
    val connectionTimeout: Boolean = false,
    val slowConnectMs: Long = 0,
    val slowWriteMs: Long = 0,
    val tinyBufferBytes: Int? = null,
    val failureAfterBytes: Int? = null,
    val disconnectAfterBytes: Int? = null,
    val printerBusy: Boolean = false,
    val paperOut: Boolean = false,
    val coverOpen: Boolean = false
)

data class CapturedWrite(val timestamp: Instant, val data: ByteArray) {
    val size: Int get() = data.size
}

data class CapturedJob(
    val jobId: String,
    val writes: List<CapturedWrite>,
    val startedAt: Instant,
    val endedAt: Instant?
) {
    val totalBytes: Int = writes.sumOf { it.size }
    val data: ByteArray = ByteArrayOutputStream(totalBytes).use { output ->
        writes.forEach { output.write(it.data) }
        output.toByteArray()
    }
    val hex: String = data.joinToString(" ") { "%02X".format(it) }
    val ascii: String = data.map { b -> val c = b.toInt().toChar(); if (c.isISOControl()) '.' else c }.joinToString("")
}

class FakePrinterTransport(
    private val jobId: String = "fake-job",
    private val faults: FaultProfile = FaultProfile()
) : PrinterTransport {
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = mutableState
    private val writes = mutableListOf<CapturedWrite>()
    private var startedAt: Instant = Instant.now()
    private var totalBytes = 0

    override suspend fun connect() {
        mutableState.value = ConnectionState.CONNECTING
        if (faults.slowConnectMs > 0) delay(faults.slowConnectMs)
        if (faults.connectionTimeout) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.ConnectionTimeout()
        }
        when {
            faults.printerBusy -> throw PrintBridgeError.PrinterBusy()
            faults.paperOut -> throw PrintBridgeError.PaperOut()
            faults.coverOpen -> throw PrintBridgeError.CoverOpen()
        }
        startedAt = Instant.now()
        mutableState.value = ConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        mutableState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun write(data: ByteArray) {
        if (mutableState.value != ConnectionState.CONNECTED) throw PrintBridgeError.ConnectionLost()
        faults.tinyBufferBytes?.let { if (data.size > it) throw PrintBridgeError.WriteFailed(IllegalArgumentException("Write exceeds tiny buffer: ${data.size} > $it")) }
        if (faults.slowWriteMs > 0) delay(faults.slowWriteMs)
        val after = totalBytes + data.size
        faults.disconnectAfterBytes?.let {
            if (after > it) {
                mutableState.value = ConnectionState.DISCONNECTED
                throw PrintBridgeError.ConnectionLost()
            }
        }
        faults.failureAfterBytes?.let { if (after > it) throw PrintBridgeError.WriteFailed(IllegalStateException("Injected failure after $it bytes")) }
        writes += CapturedWrite(Instant.now(), data.copyOf())
        totalBytes = after
    }

    fun capture(): CapturedJob = CapturedJob(jobId, writes.toList(), startedAt, Instant.now())
}
