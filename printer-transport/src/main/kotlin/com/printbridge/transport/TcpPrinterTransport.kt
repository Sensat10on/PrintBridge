package com.printbridge.transport

import com.printbridge.core.ConnectionState
import com.printbridge.core.PrintBridgeError
import com.printbridge.core.PrinterTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

class TcpPrinterTransport(
    private val host: String,
    private val port: Int = 9100,
    private val connectTimeoutMs: Int = 5000,
    private val writeTimeoutMs: Int = 5000
) : PrinterTransport {
    init {
        require(host.isNotBlank()) { "Host is required" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
    }

    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = mutableState
    private var socket: SocketChannel? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        mutableState.value = ConnectionState.CONNECTING
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val channel = SocketChannel.open()
                channel.configureBlocking(false)
                channel.connect(InetSocketAddress(host, port))
                val deadline = System.nanoTime() + connectTimeoutMs * 1_000_000L
                while (!channel.finishConnect()) {
                    if (System.nanoTime() >= deadline) throw SocketTimeoutException("Connection timed out")
                    Thread.sleep(5)
                }
                socket = channel
                mutableState.value = ConnectionState.CONNECTED
                return@withContext
            } catch (error: SocketTimeoutException) {
                socket?.close()
                socket = null
                lastError = error
            } catch (error: Exception) {
                socket?.close()
                socket = null
                lastError = error
                mutableState.value = ConnectionState.ERROR
                throw PrintBridgeError.NetworkPrinterUnreachable(host, port, error)
            }
            if (attempt < 2) delay(50)
        }
        mutableState.value = ConnectionState.ERROR
        throw PrintBridgeError.NetworkPrinterUnreachable(host, port, lastError)
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        socket?.close()
        socket = null
        // Keep a terminal error state visible to the caller instead of masking a failed
        // connect/write with a clean DISCONNECTED.
        if (mutableState.value != ConnectionState.ERROR) {
            mutableState.value = ConnectionState.DISCONNECTED
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val active = socket ?: throw PrintBridgeError.ConnectionLost()
            active.writeWithTimeout(data, writeTimeoutMs)
        } catch (error: PrintBridgeError) {
            mutableState.value = ConnectionState.ERROR
            throw error
        } catch (error: Exception) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.WriteFailed(error)
        }
    }
}

private fun SocketChannel.writeWithTimeout(data: ByteArray, timeoutMs: Int) {
    Selector.open().use { selector ->
        configureBlocking(false)
        register(selector, SelectionKey.OP_WRITE)
        val buffer = ByteBuffer.wrap(data)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (buffer.hasRemaining()) {
            if (write(buffer) > 0) continue
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
            if (remainingMs <= 0 || selector.select(remainingMs) == 0) {
                throw SocketTimeoutException("TCP write timed out after ${timeoutMs}ms")
            }
            selector.selectedKeys().clear()
        }
    }
}
