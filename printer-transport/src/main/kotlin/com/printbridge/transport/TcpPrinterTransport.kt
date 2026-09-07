package com.printbridge.transport

import com.printbridge.core.ConnectionState
import com.printbridge.core.PrintBridgeError
import com.printbridge.core.PrinterTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket

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
    private var socket: Socket? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        mutableState.value = ConnectionState.CONNECTING
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                socket = Socket().apply {
                    soTimeout = writeTimeoutMs
                    connect(InetSocketAddress(host, port), connectTimeoutMs)
                }
                mutableState.value = ConnectionState.CONNECTED
                return@withContext
            } catch (error: BindException) {
                socket?.close()
                socket = null
                lastError = error
                try {
                    socket = Socket(host, port).apply { soTimeout = writeTimeoutMs }
                    mutableState.value = ConnectionState.CONNECTED
                    return@withContext
                } catch (fallbackError: Exception) {
                    socket?.close()
                    socket = null
                    lastError = fallbackError
                    if (attempt == 2) {
                        mutableState.value = ConnectionState.ERROR
                        throw PrintBridgeError.NetworkPrinterUnreachable(host, port, fallbackError)
                    }
                    delay(50)
                }
            } catch (error: Exception) {
                socket?.close()
                socket = null
                lastError = error
                mutableState.value = ConnectionState.ERROR
                throw PrintBridgeError.NetworkPrinterUnreachable(host, port, error)
            }
        }
        mutableState.value = ConnectionState.ERROR
        throw PrintBridgeError.NetworkPrinterUnreachable(host, port, lastError)
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        socket?.close()
        socket = null
        mutableState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val active = socket ?: throw PrintBridgeError.ConnectionLost()
            active.getOutputStream().write(data)
            active.getOutputStream().flush()
        } catch (error: PrintBridgeError) {
            mutableState.value = ConnectionState.ERROR
            throw error
        } catch (error: Exception) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.WriteFailed(error)
        }
    }
}
