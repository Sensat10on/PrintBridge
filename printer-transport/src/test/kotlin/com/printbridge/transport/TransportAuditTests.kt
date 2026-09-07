package com.printbridge.transport

import com.printbridge.core.DefaultProfiles
import com.printbridge.core.PrintBridgeError
import com.printbridge.core.PrintJob
import com.printbridge.core.PrintJobState
import com.printbridge.core.PrintQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransportAuditTests {
    @Test fun largeJobIsByteExactAcrossFakeAndTcp() = runTest {
        val bytes = realisticRasterJob()
        val profile = DefaultProfiles.all[0].copy(chunkSize = 257, delayBetweenChunksMs = 0)
        val fake = FakePrinterTransport("large-fake")
        val fakeJob = PrintQueue(fake).enqueue(PrintJob(source = "audit", printerProfileId = profile.id), profile, bytes)
        assertEquals(PrintJobState.COMPLETED, fakeJob.state)
        assertContentEquals(bytes, fake.capture().data)

        ServerSocket(0).use { server ->
            val received = CompletableFuture<ByteArray>()
            Thread {
                server.accept().use { socket -> received.complete(socket.getInputStream().readBytes()) }
            }.apply { isDaemon = true; start() }
            val tcp = TcpPrinterTransport("127.0.0.1", server.localPort)
            val tcpJob = PrintQueue(tcp).enqueue(PrintJob(source = "audit", printerProfileId = profile.id), profile, bytes)
            assertEquals(PrintJobState.COMPLETED, tcpJob.state)
            assertContentEquals(bytes, withTimeout(5_000) { received.get() })
        }
    }

    @Test fun chunkingPreservesExactOrderForSeveralSizes() = runTest {
        val bytes = ByteArray(4_099) { (it % 251).toByte() }
        listOf(3, 17, 64, 257, 1024).forEach { chunkSize ->
            val profile = DefaultProfiles.all[0].copy(chunkSize = chunkSize, delayBetweenChunksMs = 0)
            val fake = FakePrinterTransport("chunks-$chunkSize")
            val job = PrintQueue(fake).enqueue(PrintJob(source = "audit", printerProfileId = profile.id), profile, bytes)
            val capture = fake.capture()
            assertEquals(PrintJobState.COMPLETED, job.state)
            assertEquals(bytes.size, capture.totalBytes)
            assertContentEquals(bytes, capture.data)
            assertTrue(capture.writes.all { it.size <= chunkSize })
        }
    }

    @Test fun faultScenariosExposeDomainErrorsAndNeverComplete() = runTest {
        val profile = DefaultProfiles.all[0].copy(chunkSize = 4, delayBetweenChunksMs = 0)
        val bytes = ByteArray(16) { 7 }
        val scenarios = listOf(
            FaultProfile(connectionTimeout = true) to PrintBridgeError.ConnectionTimeout::class,
            FaultProfile(slowWriteMs = 10, failureAfterBytes = 7) to PrintBridgeError.WriteFailed::class,
            FaultProfile(tinyBufferBytes = 3) to PrintBridgeError.WriteFailed::class,
            FaultProfile(disconnectAfterBytes = 7) to PrintBridgeError.ConnectionLost::class,
            FaultProfile(failureAfterBytes = 7) to PrintBridgeError.WriteFailed::class
        )
        scenarios.forEachIndexed { index, (fault, expected) ->
            val job = PrintQueue(FakePrinterTransport("fault-$index", fault)).enqueue(PrintJob(source = "audit", printerProfileId = profile.id), profile, bytes)
            assertEquals(PrintJobState.FAILED, job.state)
            assertTrue(expected.isInstance(job.domainError), "Expected ${expected.simpleName}, got ${job.domainError}")
        }
    }

    @Test fun queueSerializesTwoJobsWithoutInterleaving() = runTest {
        val profile = DefaultProfiles.all[0].copy(chunkSize = 2, delayBetweenChunksMs = 10)
        val fake = FakePrinterTransport("serialized", FaultProfile(slowWriteMs = 10))
        val queue = PrintQueue(fake)
        val first = async { queue.enqueue(PrintJob(source = "first", printerProfileId = profile.id), profile, "AAAAAA".toByteArray()) }
        val second = async { queue.enqueue(PrintJob(source = "second", printerProfileId = profile.id), profile, "BBBBBB".toByteArray()) }
        assertEquals(PrintJobState.COMPLETED, first.await().state)
        assertEquals(PrintJobState.COMPLETED, second.await().state)
        assertEquals("AAAAAABBBBBB", fake.capture().data.toString(Charsets.US_ASCII))
    }

    @Test fun cancellationDuringMultiChunkJobIsCancelled() = runTest {
        val profile = DefaultProfiles.all[0].copy(chunkSize = 2, delayBetweenChunksMs = 100)
        val queue = PrintQueue(FakePrinterTransport("cancel", FaultProfile(slowWriteMs = 100)))
        val task = launch { queue.enqueue(PrintJob(source = "audit", printerProfileId = profile.id), profile, ByteArray(64)) }
        delay(150)
        task.cancelAndJoin()
        assertEquals(PrintJobState.CANCELLED, queue.currentJob.value?.state)
        assertIs<PrintBridgeError.PrintJobCancelled>(queue.currentJob.value?.domainError)
    }
}

private fun realisticRasterJob(): ByteArray {
    val widthBytes = 128
    val height = 1_024
    val payload = ByteArray(widthBytes * height) { index ->
        val x = index % widthBytes
        val y = index / widthBytes
        when {
            x == 0 || x == widthBytes - 1 || y % 128 == 0 -> 0xff.toByte()
            else -> ((x * 17 + y * 13) and 0xff).toByte()
        }
    }
    return byteArrayOf(0x1d, 0x76, 0x30, 0x00, widthBytes.toByte(), 0x00, 0x00, 0x04) + payload
}
