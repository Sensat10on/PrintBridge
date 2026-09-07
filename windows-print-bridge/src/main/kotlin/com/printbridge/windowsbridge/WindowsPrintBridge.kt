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
data class BridgeConfig(val bind: String = "0.0.0.0", val port: Int = 9191, val printerName: String? = null)

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
    private val queue: WindowsPrinterQueue = WindowsPrinterQueue(config.printerName)
) {
    fun serveForever() {
        ServerSocket(config.port, 50, InetAddress.getByName(config.bind)).use { server ->
            println("PrintBridge Windows bridge listening on ${config.bind}:${config.port}")
            println("Target printer: ${config.printerName ?: "Windows default printer"}")
            while (true) {
                val socket = server.accept()
                Thread { handleClient(socket) }.start()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val remote = client.remoteSocketAddress.toString()
            runCatching {
                val data = client.getInputStream().readBytes()
                println("Received ${data.size} bytes from $remote")
                queue.printRaw(data)
            }.onFailure { error ->
                System.err.println("Failed to handle $remote: ${error.message}")
            }
        }
    }
}

object BridgeArgs {
    fun parse(args: Array<String>): BridgeConfig {
        var bind = "0.0.0.0"
        var port = 9191
        var printer: String? = null
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--bind" -> bind = args.valueAfter(index++)
                "--port" -> port = args.valueAfter(index++).toInt()
                "--printer" -> printer = args.valueAfter(index++)
                else -> error("Unknown argument: ${args[index]}")
            }
            index++
        }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        return BridgeConfig(bind, port, printer)
    }

    private fun Array<String>.valueAfter(index: Int): String {
        require(index + 1 < size) { "Missing value for ${this[index]}" }
        return this[index + 1]
    }
}
