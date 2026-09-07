package com.printbridge.transport

import com.printbridge.core.DefaultProfiles
import com.printbridge.core.PrintJob
import com.printbridge.core.PrintJobState
import com.printbridge.core.PrintQueue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransportTests {
    @Test fun fakeTransportCapturesChunkedWrites() = runTest {
        val profile = DefaultProfiles.all[0].copy(chunkSize = 3, delayBetweenChunksMs = 0)
        val fake = FakePrinterTransport("chunk-test")
        val job = PrintQueue(fake).enqueue(PrintJob(source = "unit", printerProfileId = profile.id), profile, "abcdefg".toByteArray())
        val capture = fake.capture()
        assertEquals(PrintJobState.COMPLETED, job.state)
        assertEquals(listOf(3, 3, 1), capture.writes.map { it.size })
        assertEquals("abcdefg", capture.data.toString(Charsets.UTF_8))
    }

    @Test fun tinyBufferFailureIsReported() = runTest {
        val profile = DefaultProfiles.all[0].copy(chunkSize = 10, delayBetweenChunksMs = 0)
        val fake = FakePrinterTransport("tiny", FaultProfile(tinyBufferBytes = 4))
        val job = PrintQueue(fake).enqueue(PrintJob(source = "unit", printerProfileId = profile.id), profile, "abcdef".toByteArray())
        assertEquals(PrintJobState.FAILED, job.state)
        assertTrue(job.lastError!!.contains("Write failed"))
    }

    @Test fun disconnectAfterNBytesFailsJob() = runTest {
        val profile = DefaultProfiles.all[0].copy(chunkSize = 3, delayBetweenChunksMs = 0)
        val fake = FakePrinterTransport("disconnect", FaultProfile(disconnectAfterBytes = 4))
        val job = PrintQueue(fake).enqueue(PrintJob(source = "unit", printerProfileId = profile.id), profile, "abcdef".toByteArray())
        assertEquals(PrintJobState.FAILED, job.state)
    }

    @Test fun largeJobPreservesBytesAcrossChunks() = runTest {
        val profile = DefaultProfiles.all[0].copy(chunkSize = 4096, delayBetweenChunksMs = 0)
        val data = ByteArray(128 * 1024) { (it % 251).toByte() }
        val fake = FakePrinterTransport("large")

        val job = PrintQueue(fake).enqueue(PrintJob(source = "unit", printerProfileId = profile.id), profile, data)
        val capture = fake.capture()

        assertEquals(PrintJobState.COMPLETED, job.state)
        assertEquals(data.size, capture.totalBytes)
        assertTrue(capture.writes.size > 1)
        assertTrue(data.contentEquals(capture.data))
    }

    @Test fun injectedConnectTimeoutFailsJob() = runTest {
        val profile = DefaultProfiles.all[0]
        val fake = FakePrinterTransport("timeout", FaultProfile(connectionTimeout = true))
        val job = PrintQueue(fake).enqueue(PrintJob(source = "unit", printerProfileId = profile.id), profile, byteArrayOf(1))
        assertEquals(PrintJobState.FAILED, job.state)
        assertTrue(job.lastError!!.contains("timed out"))
    }

    @Test fun slowPrinterStillCompletesWhenNoTimeoutIsInjected() = runTest {
        val profile = DefaultProfiles.all[0].copy(chunkSize = 2, delayBetweenChunksMs = 0)
        val fake = FakePrinterTransport("slow", FaultProfile(slowConnectMs = 1, slowWriteMs = 1))
        val job = PrintQueue(fake).enqueue(PrintJob(source = "unit", printerProfileId = profile.id), profile, "abcd".toByteArray())
        assertEquals(PrintJobState.COMPLETED, job.state)
    }

    @Test fun tcpTransportWritesToLoopbackServer() = runBlocking {
        val payload = "tcp-print".toByteArray()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val ready = CountDownLatch(1)
        val received = ArrayBlockingQueue<ByteArray>(1)
        val failures = ArrayBlockingQueue<Throwable>(1)
        val thread = Thread {
            ready.countDown()
            server.use {
                try {
                    val socket = it.accept()
                    socket.use {
                        val buffer = ByteArray(payload.size)
                        var offset = 0
                        while (offset < buffer.size) {
                            val read = socket.getInputStream().read(buffer, offset, buffer.size - offset)
                            if (read < 0) break
                            offset += read
                        }
                        received.put(buffer.copyOf(offset))
                    }
                } catch (error: Throwable) {
                    failures.put(error)
                }
            }
        }.also { it.start() }
        assertTrue(ready.await(1, TimeUnit.SECONDS))

        val transport = TcpPrinterTransport("127.0.0.1", server.localPort)
        transport.connect()
        transport.write(payload)
        transport.disconnect()
        thread.join(6000)

        failures.peek()?.let { throw it }
        assertTrue(payload.contentEquals(received.poll(1, TimeUnit.SECONDS)))
    }

    @Test fun networkDiscoveryUsesCommonSharedPrinterPortsAndValidatesSubnetPrefix() = runTest {
        assertEquals(listOf(9191, 9100, 631, 515), NetworkPrinterDiscovery.DEFAULT_PRINTER_PORTS)
        assertFailsWith<IllegalArgumentException> {
            NetworkPrinterDiscovery().discoverSubnet("192.168.1")
        }
    }
}
