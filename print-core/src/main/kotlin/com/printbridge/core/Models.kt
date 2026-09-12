package com.printbridge.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

enum class PrinterProtocol { ESC_POS, TSPL, GOOJPRT_LABEL, ZPL, CPCL, EPL, UNKNOWN }
enum class TransportType { FAKE, TCP, BLUETOOTH_SPP, BLUETOOTH_BLE, USB }
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, BUSY, ERROR }
enum class Alignment { LEFT, CENTER, RIGHT }
enum class PrintJobState { QUEUED, CONNECTING, PROCESSING, PRINTING, COMPLETED, FAILED, CANCELLED }

data class PrinterProfile(
    val id: String,
    val displayName: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val alternativeNames: List<String> = emptyList(),
    val bluetoothNamePatterns: List<String> = emptyList(),
    val bluetoothDeviceName: String? = null,
    val bluetoothDeviceAddress: String? = null,
    val networkHost: String? = null,
    val networkPort: Int? = null,
    val networkConnectTimeoutMs: Int? = null,
    val networkWriteTimeoutMs: Int? = null,
    val protocol: PrinterProtocol,
    val dpi: Int,
    val paperWidthMm: Float,
    val paperHeightMm: Float? = null,
    val gapMm: Float? = null,
    val transportType: TransportType,
    val supportsCut: Boolean,
    val supportsStatus: Boolean,
    val chunkSize: Int? = null,
    val delayBetweenChunksMs: Long? = null,
    val writeTimeoutMs: Long? = null,
    val usbVid: Int? = null,
    val usbPid: Int? = null,
    val knownQuirks: List<String> = emptyList(),
    val verified: Boolean = true,
    val notes: String? = null,
    /** Text stamped on every job while the app is unlicensed; null means "use the default". */
    val watermarkText: String? = null
)

object DefaultProfiles {
    val all = listOf(
        PrinterProfile("generic-escpos-58-203", "Стандартный ESC/POS 58 мм - 203 DPI", protocol = PrinterProtocol.ESC_POS, dpi = 203, paperWidthMm = 58f, transportType = TransportType.FAKE, supportsCut = false, supportsStatus = false, chunkSize = 512, delayBetweenChunksMs = 10, notes = "Используется только проверенный общий набор команд."),
        PrinterProfile("generic-escpos-80-203", "Стандартный ESC/POS 80 мм - 203 DPI", protocol = PrinterProtocol.ESC_POS, dpi = 203, paperWidthMm = 80f, transportType = TransportType.FAKE, supportsCut = true, supportsStatus = false, chunkSize = 1024, delayBetweenChunksMs = 10),
        PrinterProfile("generic-tspl-label-203", "Стандартный TSPL для этикеток - 203 DPI", protocol = PrinterProtocol.TSPL, dpi = 203, paperWidthMm = 100f, paperHeightMm = 150f, gapMm = 3f, transportType = TransportType.FAKE, supportsCut = false, supportsStatus = false, chunkSize = 1024, delayBetweenChunksMs = 10),
        PrinterProfile("generic-tspl-label-300", "Стандартный TSPL для этикеток - 300 DPI", protocol = PrinterProtocol.TSPL, dpi = 300, paperWidthMm = 100f, paperHeightMm = 150f, gapMm = 3f, transportType = TransportType.FAKE, supportsCut = false, supportsStatus = false, chunkSize = 1024, delayBetweenChunksMs = 10),
        PrinterProfile(
            id = "goojprt-mtp-label-58-203",
            displayName = "GOOJPRT/MTP бинарные этикетки 58 мм - 203 DPI",
            manufacturer = "GOOJPRT",
            model = "MTP-2 / MTP-II",
            alternativeNames = listOf("MTP-2", "MTP-II", "POS-Q1", "POS-Q2"),
            bluetoothNamePatterns = listOf("MTP", "GOOJPRT", "Q1", "Q2"),
            protocol = PrinterProtocol.GOOJPRT_LABEL,
            dpi = 203,
            paperWidthMm = 58f,
            paperHeightMm = 40f,
            transportType = TransportType.FAKE,
            supportsCut = false,
            supportsStatus = false,
            chunkSize = 64,
            delayBetweenChunksMs = 10,
            knownQuirks = listOf("RFCOMM channel 1 fallback", "bitmap writes use small flow-control chunks"),
            verified = false,
            notes = "Подготовлено по GOOJPRT MTP-2 SDK: label-команды начинаются с 0x1A, это не TSPL."
        )
    )
}

fun mmToDots(mm: Float, dpi: Int): Int = (mm / 25.4f * dpi).roundToInt()

sealed class PrintBridgeError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ConnectionTimeout : PrintBridgeError("Connection timed out")
    class ConnectionLost : PrintBridgeError("Connection lost")
    class NetworkPrinterUnreachable(host: String, port: Int, cause: Throwable? = null) : PrintBridgeError("Network printer unreachable: $host:$port", cause)
    class UnsupportedProtocol(protocol: PrinterProtocol) : PrintBridgeError("Unsupported protocol: $protocol")
    class InvalidPrinterProfile(reason: String) : PrintBridgeError("Invalid printer profile: $reason")
    class WriteFailed(cause: Throwable) : PrintBridgeError("Write failed", cause)
    class PrinterBusy : PrintBridgeError("Printer busy")
    class PaperOut : PrintBridgeError("Paper out")
    class CoverOpen : PrintBridgeError("Cover open")
    class BluetoothUnavailable(reason: String = "Bluetooth is unavailable") : PrintBridgeError(reason)
    class BluetoothPermissionDenied : PrintBridgeError("Bluetooth permission denied")
    class BluetoothDeviceNotFound(address: String) : PrintBridgeError("Bluetooth device not found: $address")
    class BluetoothConnectionFailed(address: String, cause: Throwable? = null) : PrintBridgeError("Bluetooth connection failed: $address", cause)
    class PrintJobCancelled : PrintBridgeError("Print job cancelled")
}

data class PrintJob(
    val id: String = UUID.randomUUID().toString(),
    val source: String,
    val printerProfileId: String,
    val copies: Int = 1,
    val createdAt: Instant = Instant.now(),
    val state: PrintJobState = PrintJobState.QUEUED,
    val progress: Float = 0f,
    val lastError: String? = null,
    val domainError: PrintBridgeError? = null
)

interface PrinterTransport {
    val state: StateFlow<ConnectionState>
    suspend fun connect()
    suspend fun disconnect()
    suspend fun write(data: ByteArray)
}

interface PrinterDriver {
    val protocol: PrinterProtocol
    fun testPage(profile: PrinterProfile): ByteArray
}

class PrintQueue(
    private val transport: PrinterTransport,
    private val chunker: WriteChunker = WriteChunker()
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<PrintJob?>(null)
    val currentJob: StateFlow<PrintJob?> = state

    suspend fun enqueue(job: PrintJob, profile: PrinterProfile, bytes: ByteArray): PrintJob = mutex.withLock {
        var current = job.copy(state = PrintJobState.CONNECTING)
        state.value = current
        return try {
            transport.connect()
            current = current.copy(state = PrintJobState.PRINTING, progress = 0.1f)
            state.value = current
            chunker.writeChunked(bytes, profile, transport) { sent, total ->
                state.value = current.copy(progress = sent.toFloat() / total.coerceAtLeast(1))
            }
            transport.disconnect()
            current.copy(state = PrintJobState.COMPLETED, progress = 1f).also { state.value = it }
        } catch (cancelled: CancellationException) {
            transport.disconnect()
            val domainError = PrintBridgeError.PrintJobCancelled()
            job.copy(state = PrintJobState.CANCELLED, lastError = domainError.message, domainError = domainError).also { state.value = it }
        } catch (error: Throwable) {
            transport.disconnect()
            val domainError = error as? PrintBridgeError ?: PrintBridgeError.WriteFailed(error)
            job.copy(state = PrintJobState.FAILED, lastError = domainError.message, domainError = domainError).also { state.value = it }
        }
    }
}

class WriteChunker(
    private val defaultChunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val defaultDelayMs: Long = 0
) {
    suspend fun writeChunked(data: ByteArray, profile: PrinterProfile, transport: PrinterTransport, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        // Clamped on both sides: a profile can come from persisted JSON, and an absurd
        // chunkSize used to overflow `offset + chunkSize` and fail the job with an
        // IndexOutOfBoundsException instead of a domain error.
        val chunkSize = (profile.chunkSize ?: defaultChunkSize).coerceIn(1, MAX_CHUNK_SIZE)
        val delayMs = (profile.delayBetweenChunksMs ?: defaultDelayMs).coerceAtLeast(0)
        var offset = 0
        while (offset < data.size) {
            val end = (offset + chunkSize).coerceAtMost(data.size)
            transport.write(data.copyOfRange(offset, end))
            offset = end
            onProgress(offset, data.size)
            if (delayMs > 0 && offset < data.size) delay(delayMs)
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE: Int = 1024
        const val MAX_CHUNK_SIZE: Int = 64 * 1024
    }
}
