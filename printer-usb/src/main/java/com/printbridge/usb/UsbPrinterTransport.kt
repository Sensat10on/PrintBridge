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
import com.printbridge.core.withIoDeadline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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
    private val writeTimeoutMs: Int = 5000,
    private val maxWriteAttempts: Int = DEFAULT_MAX_WRITE_ATTEMPTS
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
        // Do not overwrite a terminal error state: the caller inspects transport.state
        // after a failed connect/write to distinguish "failed" from "cleanly closed".
        if (mutableState.value != ConnectionState.ERROR) {
            mutableState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Sends [data] in resumable slices. A single bulkTransfer spans one USB transaction, so a
     * short transfer is retried with the remaining bytes instead of failing the whole job on a
     * transient timeout.
     */
    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val activeConnection = connection ?: throw PrintBridgeError.ConnectionLost()
        val activeEndpoint = target?.endpoint ?: throw PrintBridgeError.ConnectionLost()
        if (data.isEmpty()) return@withContext

        var offset = 0
        var attempts = 0
        while (offset < data.size) {
            val sliceSize = (data.size - offset).coerceAtMost(MAX_SLICE_BYTES)
            val slice = data.copyOfRange(offset, offset + sliceSize)
            val written = try {
                withIoDeadline(writeTimeoutMs.toLong(), "printbridge-usb-write") {
                    activeConnection.bulkTransfer(activeEndpoint, slice, slice.size, writeTimeoutMs)
                }
            } catch (timeout: TimeoutCancellationException) {
                mutableState.value = ConnectionState.ERROR
                throw PrintBridgeError.WriteFailed(
                    IllegalStateException(
                        "USB bulk transfer timed out after ${writeTimeoutMs}ms at offset $offset of ${data.size}"
                    )
                )
            }

            if (written > 0) {
                offset += written
                attempts = 0
                continue
            }

            attempts++
            if (attempts >= maxWriteAttempts) {
                mutableState.value = ConnectionState.ERROR
                throw PrintBridgeError.WriteFailed(
                    IllegalStateException(
                        "USB bulk transfer failed after $maxWriteAttempts attempts at offset $offset of ${data.size} " +
                            "(last result: $written)"
                    )
                )
            }
        }
    }

    companion object {
        /** Maximum bytes per bulkTransfer call; keeps retry granularity predictable. */
        const val MAX_SLICE_BYTES: Int = 16 * 1024
        const val DEFAULT_MAX_WRITE_ATTEMPTS: Int = 3
    }
}
