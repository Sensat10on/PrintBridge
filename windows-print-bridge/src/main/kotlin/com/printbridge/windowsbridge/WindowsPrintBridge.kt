package com.printbridge.windowsbridge

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import javax.print.DocFlavor
import javax.print.DocPrintJob
import javax.print.PrintService
import javax.print.PrintServiceLookup
import javax.print.SimpleDoc

data class WindowsPrinterInfo(val name: String, val isDefault: Boolean)
data class BridgeConfig(
    val bind: String = "0.0.0.0",
    val port: Int = 9191,
    val printerName: String? = null,
    val maxJobBytes: Int = 8 * 1024 * 1024
)

class WindowsPrinterQueue(private val printerName: String? = null) {
    fun listPrinters(): List<WindowsPrinterInfo> {
        val defaultName = PrintServiceLookup.lookupDefaultPrintService()?.name
        return PrintServiceLookup.lookupPrintServices(null, null)
            .map { WindowsPrinterInfo(it.name, it.name == defaultName) }
            .sortedWith(compareByDescending<WindowsPrinterInfo> { it.isDefault }.thenBy { it.name.lowercase() })
    }

    fun printRaw(data: ByteArray, jobName: String = "PrintBridge ${Instant.now()}") {
        require(data.isNotEmpty()) { "Print job is empty" }
        val service = selectService()
        val job: DocPrintJob = service.createPrintJob()
        val flavor = DocFlavor.INPUT_STREAM.AUTOSENSE
        ByteArrayInputStream(data).use { input ->
            job.print(SimpleDoc(input, flavor, null), null)
        }
        println("Printed ${data.size} bytes to \"${service.name}\" as \"$jobName\"")
    }

    private fun selectService(): PrintService {
        val services = PrintServiceLookup.lookupPrintServices(null, null).toList()
        printerName?.let { expected ->
            services.firstOrNull { it.name.equals(expected, ignoreCase = true) }?.let { return it }
            services.firstOrNull { it.name.contains(expected, ignoreCase = true) }?.let { return it }
            error("Windows printer queue not found: $expected")
        }
        return PrintServiceLookup.lookupDefaultPrintService()
            ?: services.firstOrNull()
            ?: error("No Windows printers are installed")
    }
}

class RawTcpBridgeServer(
    private val config: BridgeConfig,
    private val queue: WindowsPrinterQueue = WindowsPrinterQueue(config.printerName),
    private val maxJobBytes: Int = config.maxJobBytes,
    private val socketReadTimeoutMs: Int = DEFAULT_SOCKET_READ_TIMEOUT_MS
) {
    fun serveForever() {
        ServerSocket(config.port, 50, InetAddress.getByName(config.bind)).use { server ->
            println("PrintBridge Windows bridge listening on ${config.bind}:${config.port}")
            println("Target printer: ${config.printerName ?: "Windows default printer"}")
            println("Max job size: $maxJobBytes bytes")
            if (config.bind == "0.0.0.0") {
                println("WARNING: the bridge is bound to all interfaces and has no authentication.")
                println("         Use --bind 127.0.0.1 unless LAN clients must reach it.")
            }
            while (true) {
                val socket = server.accept()
                Thread { handleClient(socket) }.apply { isDaemon = true }.start()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val remote = client.remoteSocketAddress.toString()
            runCatching {
                client.soTimeout = socketReadTimeoutMs
                val data = readBounded(client.getInputStream())
                println("Received ${data.size} bytes from $remote")
                queue.printRaw(data)
            }.onFailure { error ->
                System.err.println("Failed to handle $remote: ${error.message}")
            }
        }
    }

    /**
     * Reads a job with a hard cap. The bridge listens on a raw TCP port without
     * authentication, so an unbounded readBytes() would let any LAN peer grow the heap.
     */
    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > maxJobBytes) {
                throw IllegalArgumentException("Print job exceeds the $maxJobBytes byte limit")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        const val DEFAULT_MAX_JOB_BYTES: Int = 8 * 1024 * 1024
        const val DEFAULT_SOCKET_READ_TIMEOUT_MS: Int = 30_000
    }
}

object BridgeArgs {
    fun parse(args: Array<String>): BridgeConfig {
        var bind = "0.0.0.0"
        var port = 9191
        var printer: String? = null
        var maxJobBytes = RawTcpBridgeServer.DEFAULT_MAX_JOB_BYTES
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--bind" -> bind = args.valueAfter(index++)
                "--port" -> port = args.valueAfter(index++).toIntOrNull()
                    ?: error("--port must be a number")
                "--printer" -> printer = args.valueAfter(index++)
                "--max-job-bytes" -> maxJobBytes = args.valueAfter(index++).toIntOrNull()
                    ?: error("--max-job-bytes must be a number")
                else -> error("Unknown argument: ${args[index]}")
            }
            index++
        }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(maxJobBytes in 1..Int.MAX_VALUE) { "Max job size must be positive" }
        return BridgeConfig(bind, port, printer, maxJobBytes)
    }

    private fun Array<String>.valueAfter(index: Int): String {
        require(index + 1 < size) { "Missing value for ${this[index]}" }
        return this[index + 1]
    }
}
