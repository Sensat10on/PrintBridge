package com.printbridge.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTests {
    @Test fun convertsMillimetersToDots() {
        assertEquals(464, mmToDots(58f, 203))
        assertEquals(800, mmToDots(67.733f, 300))
    }

    @Test fun rasterThresholdAndRotationWork() {
        val mono = RasterEngine.fromGrayscale(2, 1, byteArrayOf(0, 255.toByte()), RasterOptions(rotate90 = true))
        assertEquals(1, mono.width)
        assertEquals(2, mono.height)
        assertTrue(mono[0, 0])
        assertFalse(mono[0, 1])
    }

    @Test fun defaultProfilesHaveRequiredProtocols() {
        assertEquals(5, DefaultProfiles.all.size)
        assertTrue(DefaultProfiles.all.any { it.protocol == PrinterProtocol.ESC_POS && it.paperWidthMm == 58f })
        assertTrue(DefaultProfiles.all.any { it.protocol == PrinterProtocol.TSPL && it.dpi == 300 })
        assertTrue(DefaultProfiles.all.any { it.protocol == PrinterProtocol.GOOJPRT_LABEL && it.chunkSize == 64 })
    }

    @Test fun printQueueReportsCancellationAndDisconnects() = runTest {
        val transport = CancellingTransport()
        val job = PrintQueue(transport).enqueue(
            PrintJob(source = "unit", printerProfileId = DefaultProfiles.all[0].id),
            DefaultProfiles.all[0].copy(chunkSize = 4, delayBetweenChunksMs = 0),
            ByteArray(16) { it.toByte() }
        )

        assertEquals(PrintJobState.CANCELLED, job.state)
        assertTrue(transport.disconnected)
    }
}

private class CancellingTransport : PrinterTransport {
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = mutableState
    var disconnected = false

    override suspend fun connect() {
        mutableState.value = ConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        disconnected = true
        mutableState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun write(data: ByteArray) {
        throw CancellationException("cancelled by test")
    }
}
