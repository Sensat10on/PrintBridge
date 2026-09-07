package com.printbridge.simulator

import com.printbridge.core.DefaultProfiles
import com.printbridge.core.PrinterProtocol
import com.printbridge.drivers.EscPosDriver
import com.printbridge.drivers.GoojprtLabelDriver
import com.printbridge.drivers.TsplDriver
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SimulatorTests {
    @Test fun detectsEscPosGeneratedReceipt() {
        val bytes = EscPosDriver().testPage(DefaultProfiles.all[0])
        val detection = ProtocolDetector.detect(bytes)
        assertEquals(PrinterProtocol.ESC_POS, detection.protocol)
        assertTrue(detection.evidence.contains("ESC @"))
    }

    @Test fun detectsTsplGeneratedLabel() {
        val bytes = TsplDriver().testPage(DefaultProfiles.all[2])
        val detection = ProtocolDetector.detect(bytes)
        assertEquals(PrinterProtocol.TSPL, detection.protocol)
        assertTrue(detection.evidence.contains("SIZE"))
    }

    @Test fun detectsGoojprtGeneratedLabel() {
        val bytes = GoojprtLabelDriver().testPage(DefaultProfiles.all[4])
        val detection = ProtocolDetector.detect(bytes)
        assertEquals(PrinterProtocol.GOOJPRT_LABEL, detection.protocol)
        assertTrue(detection.evidence.contains("GOOJPRT page begin"))
    }

    @Test fun simulatorAcceptsBothProtocols() {
        val sim = PrintBridgeSimulator()
        val receipt = sim.accept(EscPosDriver().testPage(DefaultProfiles.all[0]))
        val label = sim.accept(TsplDriver().testPage(DefaultProfiles.all[2]))
        val goojprt = sim.accept(GoojprtLabelDriver().testPage(DefaultProfiles.all[4]))
        assertEquals(PrinterProtocol.ESC_POS, receipt.protocol)
        assertEquals(PrinterProtocol.TSPL, label.protocol)
        assertEquals(PrinterProtocol.GOOJPRT_LABEL, goojprt.protocol)
        assertTrue(receipt.commands > 0)
        assertTrue(label.commands > 0)
        assertTrue(goojprt.commands > 0)
        assertNotNull(receipt.preview.raster)
        assertNotNull(label.preview.raster)
        assertTrue(receipt.preview.lines.any { it.contains("QR") })
        assertTrue(label.preview.lines.any { it.contains("BITMAP") })
        assertContentEquals(EscPosDriver().testPage(DefaultProfiles.all[0]), receipt.rawBytes)
    }

    @Test fun escPosPreviewContainsVisualRasterRows() {
        val parsed = EscPosParser().parse(EscPosDriver().testPage(DefaultProfiles.all[0]))

        assertEquals(emptyList(), parsed.warnings)
        assertNotNull(parsed.preview.raster)
        assertTrue(parsed.preview.lines.any { it.startsWith("[RASTER ") })
        assertTrue(parsed.preview.lines.any { it.contains('#') && it.contains('.') })
    }

    @Test fun tsplPreviewConsumesBitmapPayloadAndRendersVisualRows() {
        val parsed = TsplParser().parse(TsplDriver().testPage(DefaultProfiles.all[2]))

        assertEquals(emptyList(), parsed.warnings)
        assertNotNull(parsed.preview.raster)
        assertTrue(parsed.preview.lines.any { it.startsWith("[BITMAP ") })
        assertTrue(parsed.preview.lines.any { it.contains('#') && it.contains('.') })
    }

    @Test fun goojprtPreviewParsesTextQrBarcodeAndBitmap() {
        val parsed = GoojprtLabelParser().parse(GoojprtLabelDriver().testPage(DefaultProfiles.all[4]))

        assertEquals(emptyList(), parsed.warnings)
        assertNotNull(parsed.preview.raster)
        assertTrue(parsed.preview.lines.any { it.contains("PRINTBRIDGE") })
        assertTrue(parsed.preview.lines.any { it.startsWith("BARCODE ") })
        assertTrue(parsed.preview.lines.any { it.startsWith("QR ") })
        assertTrue(parsed.preview.lines.any { it.contains('#') && it.contains('.') })
    }

    @Test fun parsersReportMalformedAndUnknownCommands() {
        val esc = EscPosParser().parse(byteArrayOf(0x1d, 0x76, 0x30, 0x00, 0x02, 0x00, 0x02, 0x00, 0x7f))
        assertTrue(esc.warnings.any { it.contains("Truncated raster") })

        val tspl = TsplParser().parse("SIZE 10 mm,10 mm\nNOPE 1\nBITMAP 0,0,x,2,0,\nPRINT 1\n".toByteArray())
        assertTrue(tspl.warnings.any { it.contains("Unknown TSPL command") })
        assertTrue(tspl.warnings.any { it.contains("Malformed BITMAP") })

        val goojprt = GoojprtLabelParser().parse(byteArrayOf(0x1a, 0x7f, 0x00, 0x1a, 0x54, 0x01, 0x01))
        assertTrue(goojprt.warnings.any { it.contains("Unknown GOOJPRT command") })
        assertTrue(goojprt.warnings.any { it.contains("Truncated GOOJPRT TEXT") })
    }

    @Test fun simulatorPreservesRawUnknownProtocolBytesAndDiagnostics() {
        val raw = byteArrayOf(0x01, 0x02, 0x03, 'X'.code.toByte())
        val result = PrintBridgeSimulator().accept(raw)
        assertEquals(PrinterProtocol.UNKNOWN, result.protocol)
        assertContentEquals(raw, result.rawBytes)
        assertTrue(result.diagnostics.isNotEmpty())
    }
}
