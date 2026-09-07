package com.printbridge.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.printbridge.core.ConnectionState
import com.printbridge.core.PrintBridgeError
import com.printbridge.core.PrinterTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class UsbPrinterDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val interfaceIndex: Int,
    val endpointAddress: Int
)

data class UsbBulkTarget(
    val usbInterface: UsbInterface,
    val endpoint: UsbEndpoint
)

class UsbPrinterRepository(private val usbManager: UsbManager) {
    fun devices(): List<UsbPrinterDeviceInfo> =
        usbManager.deviceList.values.mapNotNull { device ->
            val target = UsbEndpointSelector.findWritableBulkEndpoint(device) ?: return@mapNotNull null
            UsbPrinterDeviceInfo(
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                interfaceIndex = target.usbInterface.id,
                endpointAddress = target.endpoint.address
            )
        }.sortedWith(compareBy({ it.vendorId }, { it.productId }, { it.deviceName }))
}

object UsbEndpointSelector {
    fun findWritableBulkEndpoint(device: UsbDevice): UsbBulkTarget? {
        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT
                ) {
                    return UsbBulkTarget(usbInterface, endpoint)
                }
            }
        }
        return null
    }
}

class UsbPrinterTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val writeTimeoutMs: Int = 5000
) : PrinterTransport {
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = mutableState
    private var connection: UsbDeviceConnection? = null
    private var target: UsbBulkTarget? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        mutableState.value = ConnectionState.CONNECTING
        if (!usbManager.hasPermission(device)) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.InvalidPrinterProfile("USB permission is not granted for ${device.deviceName}")
        }
        val selected = UsbEndpointSelector.findWritableBulkEndpoint(device)
            ?: throw PrintBridgeError.InvalidPrinterProfile("USB device has no writable bulk endpoint")
        val opened = usbManager.openDevice(device)
            ?: throw PrintBridgeError.ConnectionLost()
        if (!opened.claimInterface(selected.usbInterface, true)) {
            opened.close()
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.ConnectionLost()
        }
        connection = opened
        target = selected
        mutableState.value = ConnectionState.CONNECTED
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { target?.usbInterface?.let { connection?.releaseInterface(it) } }
        connection?.close()
        connection = null
        target = null
        mutableState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val activeConnection = connection ?: throw PrintBridgeError.ConnectionLost()
        val activeEndpoint = target?.endpoint ?: throw PrintBridgeError.ConnectionLost()
        val written = activeConnection.bulkTransfer(activeEndpoint, data, data.size, writeTimeoutMs)
        if (written != data.size) {
            mutableState.value = ConnectionState.ERROR
            throw PrintBridgeError.WriteFailed(IllegalStateException("USB wrote $written of ${data.size} bytes"))
        }
    }
}
