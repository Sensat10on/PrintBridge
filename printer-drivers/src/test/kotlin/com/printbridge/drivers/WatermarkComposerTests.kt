package com.printbridge.drivers

import com.printbridge.core.DefaultProfiles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatermarkComposerTests {
    private val escPos = DefaultProfiles.all.first { it.protocol == com.printbridge.core.PrinterProtocol.ESC_POS && it.supportsCut }
    private val tspl = DefaultProfiles.all.first { it.protocol == com.printbridge.core.PrinterProtocol.TSPL }
    private val goojprt = DefaultProfiles.all.first { it.protocol == com.printbridge.core.PrinterProtocol.GOOJPRT_LABEL }

    @Test fun licensedBuildReturnsDriverBytesUntouched() {
        val original = EscPosDriver().testPage(escPos)

        val composed = WatermarkComposer.compose(original, escPos, watermarkText = null)

        assertContentEquals(original, composed, "A licensed build must not alter the payload at all")
    }

    @Test fun blankWatermarkTextIsTreatedAsLicensed() {
        val original = EscPosDriver().testPage(escPos)

        assertContentEquals(original, WatermarkComposer.compose(original, escPos, watermarkText = "  "))
    }

    @Test fun emptyPayloadIsReturnedAsIs() {
        assertContentEquals(ByteArray(0), WatermarkComposer.compose(ByteArray(0), escPos, "PRINTBRIDGE FREE"))
    }

    @Test fun escPosWatermarkPrecedesFeedAndCut() {
        val original = EscPosDriver().testPage(escPos)
        val composed = WatermarkComposer.compose(original, escPos, "PRINTBRIDGE FREE")

        assertTrue(composed.size > original.size, "The mark must add bytes")
        assertTrue(
            composed.toString(Charsets.UTF_8).contains("PRINTBRIDGE FREE"),
            "The watermark text must be present in the ESC/POS output"
        )
        // The job must still end with feed + cut, i.e. the mark sits on the document.
        val suffix = byteArrayOf(0x1b, 0x64, 0x03, 0x1d, 0x56, 0x00)
        assertContentEquals(suffix, composed.copyOfRange(composed.size - suffix.size, composed.size))

        val withoutMark = WatermarkComposer.compose(original, escPos, null)
        assertContentEquals(original, withoutMark)
    }

    @Test fun tsplWatermarkStaysBeforeThePrintCommand() {
        val original = TsplDriver().testPage(tspl)
        val composed = WatermarkComposer.compose(original, tspl, "PRINTBRIDGE FREE")
        val text = composed.toString(Charsets.ISO_8859_1)

        assertTrue(text.contains("PRINTBRIDGE FREE"), "The watermark must be rendered as TSPL text")
        val markIndex = text.indexOf("PRINTBRIDGE FREE")
        val printIndex = text.lastIndexOf("PRINT ")
        assertTrue(markIndex < printIndex, "PRINT must remain the last command in a TSPL job")
        assertTrue(text.startsWith("SIZE "), "The label header must stay first")
        assertTrue(text.endsWith("PRINT 1\n"), "The job must end with the original print command")
    }

    @Test fun goojprtWatermarkStaysInsideThePageBoundaries() {
        val original = GoojprtLabelDriver().testPage(goojprt)
        val composed = WatermarkComposer.compose(original, goojprt, "PRINTBRIDGE FREE")
        val text = composed.toString(Charsets.UTF_8)

        assertTrue(text.contains("PRINTBRIDGE FREE"), "The watermark must be rendered as GOOJPRT text")
        val markIndex = composed.indexOfSequence(byteArrayOf(0x1a, 0x54, 0x01), "PRINTBRIDGE FREE".toByteArray(Charsets.UTF_8))
        val pageEnd = composed.indexOfSequence(byteArrayOf(0x1a, 0x5d, 0x00))
        val pagePrint = composed.indexOfSequence(byteArrayOf(0x1a, 0x4f, 0x01))
        assertTrue(markIndex in 0 until pageEnd, "The mark must be drawn before the page is closed")
        assertTrue(pageEnd < pagePrint, "Page end must still precede page print")
    }

    @Test fun unknownProtocolIsLeftUntouched() {
        val payload = "raw bytes without a known signature".toByteArray()
        val unknown = DefaultProfiles.all.first().copy(protocol = com.printbridge.core.PrinterProtocol.ZPL)

        assertContentEquals(payload, WatermarkComposer.compose(payload, unknown, "PRINTBRIDGE FREE"))
    }

    @Test fun escPosWithoutCutStillGetsTheMarkBeforeFeed() {
        val noCut = DefaultProfiles.all.first { it.transportType == com.printbridge.core.TransportType.FAKE }.copy(supportsCut = false)
        val original = EscPosDriver().testPage(noCut)
        val composed = WatermarkComposer.compose(original, noCut, "PRINTBRIDGE FREE")

        assertTrue(composed.toString(Charsets.UTF_8).contains("PRINTBRIDGE FREE"))
        val feed = byteArrayOf(0x1b, 0x64, 0x03)
        assertContentEquals(feed, composed.copyOfRange(composed.size - feed.size, composed.size))
    }
}

private fun ByteArray.indexOfSequence(needle: ByteArray, anchor: ByteArray? = null): Int {
    outer@ for (index in 0..(size - needle.size)) {
        for (offset in needle.indices) {
            if (this[index + offset] != needle[offset]) continue@outer
        }
        if (anchor != null) {
            val textStart = index + needle.size + 8 // x, y, font, style are 2 bytes each
            if (textStart + anchor.size > size) return -1
            for (offset in anchor.indices) {
                if (this[textStart + offset] != anchor[offset]) continue@outer
            }
        }
        return index
    }
    return -1
}
