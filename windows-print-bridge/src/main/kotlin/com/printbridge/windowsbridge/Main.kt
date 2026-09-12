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
          .\gradlew.bat :windows-print-bridge:run --args="--bind 127.0.0.1 --port 9191"

        Options:
          --list              List installed Windows print queues and exit
          --bind <address>    Interface to listen on (default 0.0.0.0). Prefer 127.0.0.1:
                              the bridge is a raw TCP port with no authentication.
          --port <number>     Listening port (default 9191)
          --printer <name>    Windows print queue name (default: system default queue)
          --max-job-bytes <n> Reject jobs larger than n bytes (default 8388608)
          --help              Show this message

        Android should print to this Windows PC over TCP port 9191.
        The bridge forwards raw bytes into the selected Windows print queue.
        """.trimIndent()
    )
}
