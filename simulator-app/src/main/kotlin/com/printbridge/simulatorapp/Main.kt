package com.printbridge.simulatorapp

import com.printbridge.simulator.PrintBridgeSimulator
import java.net.ServerSocket
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    val bind = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 9100
    val simulator = PrintBridgeSimulator()
    ServerSocket(port, 10, java.net.InetAddress.getByName(bind)).use { server ->
        println("PrintBridge Simulator listening on ${server.inetAddress.hostAddress}:$port")
        while (true) {
            val socket = server.accept()
            thread {
                socket.use {
                    val data = it.getInputStream().readBytes()
                    val job = simulator.accept(data)
                    println("Job #${job.id} protocol=${job.protocol} received=${job.receivedBytes} commands=${job.commands} warnings=${job.warnings}")
                    job.preview.lines.take(12).forEach(::println)
                }
            }
        }
    }
}
