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
import kotlinx.coroutines.Dispatchers
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
    private val serviceUuids: List<UUID> = DEFAULT_SERVICE_UUIDS
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
        } catch (error: IllegalArgumentException) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.BluetoothDeviceNotFound(deviceAddress)
        } catch (error: IOException) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.BluetoothConnectionFailed(deviceAddress, error)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToFirstAvailableService(device: BluetoothDevice): BluetoothSocket {
        var lastError: IOException? = null
        serviceUuids.distinct().forEach { uuid ->
            RFCOMM_SECURITY_ORDER.forEach { security ->
                val candidate = when (security) {
                    RfcommSecurity.SECURE -> device.createRfcommSocketToServiceRecord(uuid)
                    RfcommSecurity.INSECURE -> device.createInsecureRfcommSocketToServiceRecord(uuid)
                }
                try {
                    candidate.connect()
                    return candidate
                } catch (error: IOException) {
                    lastError = error
                    try { candidate.close() } catch (_: IOException) {}
                }
            }
        }

        try {
            val compatSocket = device.createCompatRfcommSocket(GOOJPRT_COMPAT_RFCOMM_CHANNEL)
            try {
                compatSocket.connect()
                return compatSocket
            } catch (error: IOException) {
                lastError = error
                try { compatSocket.close() } catch (_: IOException) {}
            }
        } catch (error: ReflectiveOperationException) {
            lastError = IOException("GOOJPRT RFCOMM channel fallback is unavailable", error)
        }

        throw lastError ?: IOException("No Bluetooth service UUIDs configured")
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        try { socket?.close() } finally {
            socket = null
            mutableState.value = ConnectionState.DISCONNECTED
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val active = socket ?: throw PrintBridgeError.ConnectionLost()
        try {
            active.outputStream.write(data)
            active.outputStream.flush()
        } catch (error: IOException) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.ConnectionLost()
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val PRINTBRIDGE_VIRTUAL_PRINTER_UUID: UUID = UUID.fromString("7F4D0D2E-4FB8-4E8F-94F2-86E3ADFCE86F")
        val DEFAULT_SERVICE_UUIDS: List<UUID> = listOf(SPP_UUID, PRINTBRIDGE_VIRTUAL_PRINTER_UUID)
    }
}

private fun BluetoothDevice.createCompatRfcommSocket(channel: Int): BluetoothSocket {
    val method = javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
    return method.invoke(this, channel) as BluetoothSocket
}
