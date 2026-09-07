package com.printbridge.bluetooth

import com.printbridge.core.ConnectionState
import com.printbridge.core.DefaultProfiles
import com.printbridge.core.PrintBridgeError
import com.printbridge.core.PrintJob
import com.printbridge.core.PrintJobState
import com.printbridge.core.PrintQueue
import com.printbridge.core.PrinterTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BluetoothTransportContractTests {
    @Test fun standardSppIsPreferredForRealDeviceCompatibility() {
        assertEquals(
            BluetoothSppPrinterTransport.SPP_UUID,
            BluetoothSppPrinterTransport.DEFAULT_SERVICE_UUIDS.first()
        )
        assertTrue(BluetoothSppPrinterTransport.PRINTBRIDGE_VIRTUAL_PRINTER_UUID in BluetoothSppPrinterTransport.DEFAULT_SERVICE_UUIDS)
    }

    @Test fun bothSecureAndInsecureSocketsAreSupportedForSppCompatibility() {
        assertEquals(
            listOf(RfcommSecurity.SECURE, RfcommSecurity.INSECURE),
            RFCOMM_SECURITY_ORDER
        )
    }

    @Test fun goojprtCompatibleRfcommChannelFallbackIsPreserved() {
        assertEquals(1, GOOJPRT_COMPAT_RFCOMM_CHANNEL)
    }

    @Test fun connectionFailureProducesBluetoothDomainErrorAndNeverCompletes() = runTest {
        val transport = FailingSppTransport(connectError = PrintBridgeError.BluetoothConnectionFailed("AA:BB"))
        val profile = DefaultProfiles.all.first()
        val job = PrintQueue(transport).enqueue(PrintJob(source = "test", printerProfileId = profile.id), profile, byteArrayOf(1))

        assertEquals(PrintJobState.FAILED, job.state)
        assertIs<PrintBridgeError.BluetoothConnectionFailed>(job.domainError)
        assertEquals(ConnectionState.DISCONNECTED, transport.state.value)
    }

    @Test fun disconnectDuringMultiChunkWriteNeverCompletes() = runTest {
        val transport = FailingSppTransport(writeError = PrintBridgeError.ConnectionLost())
        val profile = DefaultProfiles.all.first().copy(chunkSize = 1)
        val job = PrintQueue(transport).enqueue(PrintJob(source = "test", printerProfileId = profile.id), profile, byteArrayOf(1, 2, 3))

        assertEquals(PrintJobState.FAILED, job.state)
        assertIs<PrintBridgeError.ConnectionLost>(job.domainError)
        assertEquals(ConnectionState.DISCONNECTED, transport.state.value)
    }
}

private class FailingSppTransport(
    private val connectError: PrintBridgeError? = null,
    private val writeError: PrintBridgeError? = null
) : PrinterTransport {
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = mutableState

    override suspend fun connect() {
        connectError?.let { throw it }
        mutableState.value = ConnectionState.CONNECTED
    }

    override suspend fun disconnect() { mutableState.value = ConnectionState.DISCONNECTED }

    override suspend fun write(data: ByteArray) { writeError?.let { throw it } }
}
