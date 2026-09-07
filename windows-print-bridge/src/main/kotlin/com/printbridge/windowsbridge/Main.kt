package com.printbridge.windowsbridge

fun main(args: Array<String>) {
    if (args.contains("--help")) {
        printHelp()
        return
    }
    if (args.contains("--list")) {
        val printers = WindowsPrinterQueue().listPrinters()
        if (printers.isEmpty()) {
            println("No Windows printers are installed")
        } else {
            printers.forEach { printer ->
                println("${if (printer.isDefault) "*" else " "} ${printer.name}")
            }
        }
        return
    }

    val config = BridgeArgs.parse(args)
    RawTcpBridgeServer(config).serveForever()
}

private fun printHelp() {
    println(
        """
        PrintBridge Windows Print Bridge

        Usage:
          .\gradlew.bat :windows-print-bridge:run --args="--list"
          .\gradlew.bat :windows-print-bridge:run --args="--printer ""Printer Name"" --port 9191"

        Android should print to this Windows PC over TCP port 9191.
        The bridge forwards raw bytes into the selected Windows print queue.
        """.trimIndent()
    )
}
