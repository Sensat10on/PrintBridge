package com.printbridge.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.util.Log
import com.printbridge.core.PrintBridgeError
import com.printbridge.simulator.PrintBridgeSimulator
import com.printbridge.simulator.SimulatorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

class BluetoothVirtualPrinterServer(
    private val context: Context,
    private val onJob: (SimulatorJob) -> Unit,
    private val onError: (PrintBridgeError) -> Unit
) {
    private val tag = "PBVirtualPrinter"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val simulator = PrintBridgeSimulator()
    private var serverSocket: BluetoothServerSocket? = null
    private var serverJob: Job? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (serverJob?.isActive == true) return
        if (!BluetoothPermissions.hasConnectPermission(context)) {
            onError(PrintBridgeError.BluetoothPermissionDenied())
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            onError(PrintBridgeError.BluetoothUnavailable())
            return
        }
        serverJob = scope.launch {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                    "PrintBridge Virtual Printer",
                    BluetoothSppPrinterTransport.SPP_UUID
                )
                while (isActive) {
                    serverSocket?.accept()?.use { socket ->
                        Log.i(tag, "Accepted SPP client ${socket.remoteDevice?.address ?: "unknown"}")
                        val bytes = readClientBytes(socket.inputStream)
                        Log.i(tag, "Read ${bytes.size} bytes from SPP client")
                        if (bytes.isNotEmpty()) {
                            val job = simulator.accept(bytes)
                            withContext(Dispatchers.Main) { onJob(job) }
                        } else {
                            Log.w(tag, "Ignoring empty SPP payload")
                        }
                    }
                }
            } catch (error: Exception) {
                if (serverJob?.isCancelled != true) {
                    withContext(Dispatchers.Main) {
                        onError(PrintBridgeError.BluetoothConnectionFailed("virtual-printer", error))
                    }
                }
            } finally {
                serverSocket?.close()
                serverSocket = null
            }
        }
    }

    private fun readClientBytes(input: java.io.InputStream): ByteArray = ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(4096)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: IOException) {
                break
            }
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        serverSocket?.close()
        serverSocket = null
    }

    fun close() {
        stop()
        scope.cancel()
    }
}
