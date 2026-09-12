package com.printbridge.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.printbridge.core.ConnectionState
import com.printbridge.core.PrintBridgeError
import com.printbridge.core.PrinterTransport
import com.printbridge.core.withIoDeadline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

data class BluetoothDeviceInfo(val name: String, val address: String)

internal enum class RfcommSecurity { SECURE, INSECURE }

internal val RFCOMM_SECURITY_ORDER = listOf(RfcommSecurity.SECURE, RfcommSecurity.INSECURE)

internal const val GOOJPRT_COMPAT_RFCOMM_CHANNEL = 1

object BluetoothPermissions {
    fun runtimePermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> = if (sdkInt >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        emptyArray()
    }

    fun hasConnectPermission(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

class BluetoothDeviceRepository(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDeviceInfo> {
        check(BluetoothPermissions.hasConnectPermission(context)) { "Bluetooth permission is not granted" }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return adapter.bondedDevices
            .map { BluetoothDeviceInfo(it.name ?: "Unknown device", it.address) }
            .sortedBy { it.name.lowercase() }
    }
}

class BluetoothSppPrinterTransport(
    private val context: Context,
    private val deviceAddress: String,
    private val serviceUuids: List<UUID> = DEFAULT_SERVICE_UUIDS,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS
) : PrinterTransport {
    constructor(context: Context, deviceAddress: String, serviceUuid: UUID) :
        this(context, deviceAddress, listOf(serviceUuid))

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = mutableState
    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (!BluetoothPermissions.hasConnectPermission(context)) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.BluetoothPermissionDenied()
        }
        val activeAdapter = adapter ?: throw PrintBridgeError.BluetoothUnavailable()
        if (!activeAdapter.isEnabled) throw PrintBridgeError.BluetoothUnavailable("Bluetooth is disabled")
        mutableState.value = ConnectionState.CONNECTING
        try {
            socket?.close()
            activeAdapter.cancelDiscovery()
            val device = activeAdapter.getRemoteDevice(deviceAddress)
            socket = connectToFirstAvailableService(device)
            mutableState.value = ConnectionState.CONNECTED
        } catch (error: TimeoutCancellationException) {
            // A stalled RFCOMM connect() leaves the socket in an unusable state.
            closeQuietly(socket)
            socket = null
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.BluetoothConnectionFailed(
                deviceAddress,
                IOException("Bluetooth connect timed out after ${connectTimeoutMs}ms")
            )
        } catch (error: IllegalArgumentException) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.BluetoothDeviceNotFound(deviceAddress)
        } catch (error: IOException) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.BluetoothConnectionFailed(deviceAddress, error)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectToFirstAvailableService(device: BluetoothDevice): BluetoothSocket {
        var lastError: IOException? = null
        serviceUuids.distinct().forEach { uuid ->
            RFCOMM_SECURITY_ORDER.forEach { security ->
                val candidate = when (security) {
                    RfcommSecurity.SECURE -> device.createRfcommSocketToServiceRecord(uuid)
                    RfcommSecurity.INSECURE -> device.createInsecureRfcommSocketToServiceRecord(uuid)
                }
                try {
                    withIoDeadline(connectTimeoutMs, "printbridge-bt-connect", onTimeout = { closeQuietly(candidate) }) {
                        candidate.connect()
                    }
                    return candidate
                } catch (error: TimeoutCancellationException) {
                    lastError = IOException("RFCOMM connect timed out for $uuid ($security)")
                } catch (error: IOException) {
                    lastError = error
                }
                closeQuietly(candidate)
            }
        }

        try {
            val compatSocket = device.createCompatRfcommSocket(GOOJPRT_COMPAT_RFCOMM_CHANNEL)
            try {
                withIoDeadline(connectTimeoutMs, "printbridge-bt-compat-connect", onTimeout = { closeQuietly(compatSocket) }) {
                    compatSocket.connect()
                }
                return compatSocket
            } catch (error: TimeoutCancellationException) {
                lastError = IOException("RFCOMM channel $GOOJPRT_COMPAT_RFCOMM_CHANNEL connect timed out")
            } catch (error: IOException) {
                lastError = error
            }
            closeQuietly(compatSocket)
        } catch (error: ReflectiveOperationException) {
            lastError = IOException("GOOJPRT RFCOMM channel fallback is unavailable", error)
        }

        throw lastError ?: IOException("No Bluetooth service UUIDs configured")
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        try { socket?.close() } finally {
            socket = null
            if (mutableState.value != ConnectionState.ERROR) {
                mutableState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val active = socket ?: throw PrintBridgeError.ConnectionLost()
        try {
            withIoDeadline(
                writeTimeoutMs,
                "printbridge-bt-write",
                onTimeout = {
                    // OutputStream.write() on a stalled RFCOMM link only unblocks when the
                    // socket is closed, otherwise the worker thread would leak.
                    closeQuietly(active)
                    socket = null
                }
            ) {
                active.outputStream.write(data)
                active.outputStream.flush()
            }
        } catch (error: TimeoutCancellationException) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.WriteFailed(
                IOException("Bluetooth write timed out after ${writeTimeoutMs}ms (${data.size} bytes)")
            )
        } catch (error: IOException) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.ConnectionLost()
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val PRINTBRIDGE_VIRTUAL_PRINTER_UUID: UUID = UUID.fromString("7F4D0D2E-4FB8-4E8F-94F2-86E3ADFCE86F")
        val DEFAULT_SERVICE_UUIDS: List<UUID> = listOf(SPP_UUID, PRINTBRIDGE_VIRTUAL_PRINTER_UUID)

        const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 10_000
        const val DEFAULT_WRITE_TIMEOUT_MS: Long = 10_000
    }
}

private fun closeQuietly(socket: BluetoothSocket?) {
    try {
        socket?.close()
    } catch (_: IOException) {
    }
}

private fun BluetoothDevice.createCompatRfcommSocket(channel: Int): BluetoothSocket {
    val method = javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
    return method.invoke(this, channel) as BluetoothSocket
}
