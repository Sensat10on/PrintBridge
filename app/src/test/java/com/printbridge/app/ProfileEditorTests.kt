package com.printbridge.app

import com.printbridge.core.DefaultProfiles
import com.printbridge.core.PrinterProtocol
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Assert.assertTrue

/**
 * The profile form and the ready-job builders are pure functions, but they used to be
 * unreachable from tests because everything lived in one composable file. They are covered
 * here without Robolectric.
 */
class ProfileEditorTests {
    private val profile = DefaultProfiles.all[0]

    private fun fromEditor(
        displayName: String = profile.displayName,
        dpi: String = profile.dpi.toString(),
        paperWidth: String = profile.paperWidthMm.toString(),
        paperHeight: String = profile.paperHeightMm?.toString().orEmpty(),
        chunkSize: String = profile.chunkSize?.toString().orEmpty(),
        delay: String = profile.delayBetweenChunksMs?.toString().orEmpty(),
        writeTimeout: String = DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS.toString(),
        host: String = profile.networkHost.orEmpty(),
        port: String = (profile.networkPort ?: DEFAULT_NETWORK_PORT).toString(),
        connectTimeout: String = DEFAULT_NETWORK_CONNECT_TIMEOUT_MS.toString(),
        networkWriteTimeout: String = DEFAULT_NETWORK_WRITE_TIMEOUT_MS.toString()
    ) = profileFromEditor(
        profile, displayName, dpi, paperWidth, paperHeight, chunkSize, delay,
        writeTimeout, host, port, connectTimeout, networkWriteTimeout
    )

    @Test fun validEditorValuesProduceUpdatedProfile() {
        val updated = assertNotNull(
            fromEditor(
                displayName = "  Мой принтер  ",
                dpi = "300",
                paperWidth = "80",
                chunkSize = "512",
                delay = "25",
                writeTimeout = "7000",
                host = " 192.168.1.50 ",
                port = "9100",
                connectTimeout = "1500",
                networkWriteTimeout = "2500"
            )
        )

        assertEquals("Мой принтер", updated.displayName)
        assertEquals(300, updated.dpi)
        assertEquals(80f, updated.paperWidthMm)
        assertEquals(512, updated.chunkSize)
        assertEquals(25L, updated.delayBetweenChunksMs)
        assertEquals(7000L, updated.writeTimeoutMs)
        assertEquals("192.168.1.50", updated.networkHost)
        assertEquals(9100, updated.networkPort)
        assertEquals(1500, updated.networkConnectTimeoutMs)
        assertEquals(2500, updated.networkWriteTimeoutMs)
    }

    @Test fun commaDecimalSeparatorIsAccepted() {
        val updated = assertNotNull(fromEditor(paperWidth = "57,5"))

        assertEquals(57.5f, updated.paperWidthMm)
    }

    @Test fun outOfRangeValuesAreRejected() {
        assertNull(fromEditor(dpi = "0"), "DPI 0")
        assertNull(fromEditor(dpi = (MAX_DPI + 1).toString()), "DPI above the cap")
        assertNull(fromEditor(dpi = "abc"), "Non-numeric DPI")
        assertNull(fromEditor(paperWidth = "0"), "Zero paper width")
        assertNull(fromEditor(paperWidth = (MAX_PAPER_WIDTH_MM + 1f).toString()), "Paper width above the cap")
        assertNull(fromEditor(chunkSize = "0"), "Zero chunk size")
        assertNull(fromEditor(chunkSize = (MAX_CHUNK_SIZE_BYTES + 1).toString()), "Chunk size above the cap")
        assertNull(fromEditor(delay = "-1"), "Negative delay")
        assertNull(fromEditor(delay = (MAX_DELAY_BETWEEN_CHUNKS_MS + 1).toString()), "Delay above the cap")
        assertNull(fromEditor(port = "0"), "Port 0")
        assertNull(fromEditor(port = "65536"), "Port above 65535")
        assertNull(fromEditor(connectTimeout = "0"), "Zero connect timeout")
        assertNull(fromEditor(connectTimeout = (MAX_TIMEOUT_MS + 1).toString()), "Connect timeout above the cap")
        assertNull(fromEditor(networkWriteTimeout = (MIN_TIMEOUT_MS - 1).toString()), "Write timeout below the floor")
        assertNull(fromEditor(writeTimeout = "0"), "Zero transport write timeout")
        assertNull(fromEditor(writeTimeout = (MAX_TIMEOUT_MS + 1).toString()), "Transport timeout above the cap")
    }

    @Test fun emptyOptionalFieldsClearPreviousValues() {
        val withValues = profile.copy(chunkSize = 64, delayBetweenChunksMs = 10, networkHost = "10.0.0.5")
        val updated = assertNotNull(
            profileFromEditor(
                withValues, withValues.displayName, withValues.dpi.toString(), withValues.paperWidthMm.toString(),
                "", "", "", DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS.toString(), "", "9100",
                DEFAULT_NETWORK_CONNECT_TIMEOUT_MS.toString(), DEFAULT_NETWORK_WRITE_TIMEOUT_MS.toString()
            )
        )

        assertNull(updated.chunkSize)
        assertNull(updated.delayBetweenChunksMs)
        assertNull(updated.networkHost)
    }

    @Test fun labelProtocolsGetAPaperHeightDefaultAndReceiptsDoNot() {
        val tspl = DefaultProfiles.all[2]
        fun fromEditor(source: com.printbridge.core.PrinterProfile, height: String) = profileFromEditor(
            source, source.displayName, source.dpi.toString(), source.paperWidthMm.toString(), height,
            "", "", DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS.toString(), "", DEFAULT_NETWORK_PORT.toString(),
            DEFAULT_NETWORK_CONNECT_TIMEOUT_MS.toString(), DEFAULT_NETWORK_WRITE_TIMEOUT_MS.toString()
        )

        assertEquals(150f, assertNotNull(fromEditor(tspl, "")).paperHeightMm)
        assertEquals(50f, assertNotNull(fromEditor(tspl, "50")).paperHeightMm)
        // Receipt protocols never carry a label height, even if one was typed earlier.
        assertNull(assertNotNull(fromEditor(profile, "")).paperHeightMm)
        assertNull(assertNotNull(fromEditor(profile, "50")).paperHeightMm)
    }

    @Test fun editorValuesRoundTripThroughTheForm() {
        val source = profile.copy(
            displayName = "Round trip",
            dpi = 203,
            paperWidthMm = 58f,
            chunkSize = 128,
            delayBetweenChunksMs = 15,
            writeTimeoutMs = 8000,
            networkHost = "printer.local",
            networkPort = 9100,
            networkConnectTimeoutMs = 2000,
            networkWriteTimeoutMs = 3000
        )

        val values = source.toEditorValues()
        val restored = assertNotNull(
            profileFromEditor(
                source, values.displayName, values.dpi, values.paperWidth, values.paperHeight,
                values.chunkSize, values.delayBetweenChunks, values.writeTimeout, values.networkHost,
                values.networkPort, values.networkConnectTimeout, values.networkWriteTimeout
            )
        )

        assertEquals(source, restored)
    }

    @Test fun readyJobsAreGeneratedForEveryProtocolAndTemplate() {
        val draft = StoredPrintJobDraft()
        val protocols = listOf(
            DefaultProfiles.all[0].copy(protocol = PrinterProtocol.ESC_POS, supportsCut = true),
            DefaultProfiles.all[2].copy(protocol = PrinterProtocol.TSPL),
            DefaultProfiles.all[4].copy(protocol = PrinterProtocol.GOOJPRT_LABEL)
        )

        protocols.forEach { candidate ->
            PrintJobTemplate.entries.forEach { template ->
                val bytes = buildReadyJob(candidate, draft.copy(template = template))
                val marker = "${candidate.protocol}/${template.name}"
                assertTrue("Expected bytes for $marker", bytes.isNotEmpty())
                assertTrue("Expected a real payload for $marker", bytes.size > 16)            }
        }
    }

    @Test fun unsupportedProtocolProducesNoBytesInsteadOfGarbage() {
        val unsupported = DefaultProfiles.all[0].copy(protocol = PrinterProtocol.ZPL)

        assertTrue(buildReadyJob(unsupported, StoredPrintJobDraft()).isEmpty())
    }
}
