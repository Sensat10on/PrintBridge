package com.printbridge.drivers

import com.printbridge.core.DefaultProfiles
import com.printbridge.core.PrinterProtocol
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextDocumentPrinterTests {
    private val escPos = DefaultProfiles.all.first { it.protocol == PrinterProtocol.ESC_POS }
    private val tspl = DefaultProfiles.all.first { it.protocol == PrinterProtocol.TSPL }

    @Test fun wrapsAtWordBoundariesWithoutLosingCharacters() {
        val text = "alpha beta gamma delta epsilon"
        val lines = TextDocumentPrinter.wrap(text, 12)

        assertTrue(lines.all { it.length <= 12 }, "Every line must respect the column limit: $lines")
        assertEquals(text.replace(" ", ""), lines.joinToString("").replace(" ", ""))
    }

    @Test fun longWordsAreSplitRatherThanDropped() {
        val lines = TextDocumentPrinter.wrap("X".repeat(25), 10)

        assertEquals(listOf("XXXXXXXXXX", "XXXXXXXXXX", "XXXXX"), lines)
    }

    @Test fun honoursHardLineBreaksAndTabs() {
        val lines = TextDocumentPrinter.wrap("a\tb\r\nc\rd", 20)

        assertEquals(listOf("a    b", "c", "d"), lines)
    }

    @Test fun emptyLinesArePreserved() {
        assertEquals(listOf("first", "", "second"), TextDocumentPrinter.wrap("first\n\nsecond", 20))
    }

    @Test fun columnsFollowPaperWidth() {
        val narrow = escPos.copy(paperWidthMm = 58f)
        val wide = escPos.copy(paperWidthMm = 80f)

        assertEquals(TextDocumentPrinter.DEFAULT_COLUMNS_58MM, TextDocumentPrinter.columnsFor(narrow))
        assertEquals(TextDocumentPrinter.DEFAULT_COLUMNS_80MM, TextDocumentPrinter.columnsFor(wide))
    }

    @Test fun escPosDocumentIsWrappedAndTerminated() {
        val text = "The quick brown fox jumps over the lazy dog"
        val bytes = TextDocumentPrinter.composeEscPos(escPos, text, columns = 16)
        val initialize = byteArrayOf(0x1b, 0x40)
        val feed = byteArrayOf(0x1b, 0x64, 0x03)

        assertContentEquals(initialize, bytes.copyOfRange(0, 2), "Document must start with ESC @")
        assertContentEquals(feed, bytes.copyOfRange(bytes.size - feed.size, bytes.size), "Document must end with a feed")

        // The body is: ESC a 0 (left align) followed by one LF-terminated line per wrap.
        val body = bytes.copyOfRange(initialize.size, bytes.size - feed.size).toString(Charsets.UTF_8)
        val printedLines = body.replace(Regex("\u001b.\\u0000"), "").split("\n").filter { it.isNotEmpty() }
        assertTrue(printedLines.all { it.length <= 16 }, "Printed lines must fit the column limit: $printedLines")
        assertEquals(
            TextDocumentPrinter.wrap(text, 16).filter { it.isNotEmpty() },
            printedLines,
            "Printed lines must match the wrapper output"
        )
    }

    @Test fun escPosDocumentWithCutEndsWithCutCommand() {
        val withCut = escPos.copy(supportsCut = true)
        val bytes = TextDocumentPrinter.composeEscPos(withCut, "hello")
        val suffix = byteArrayOf(0x1d, 0x56, 0x00)

        assertContentEquals(suffix, bytes.copyOfRange(bytes.size - suffix.size, bytes.size))
    }

    @Test fun legacyCodePageIsAppliedWhenRequested() {
        val bytes = TextDocumentPrinter.composeEscPos(escPos, "Привет", columns = 32, charset = Charset.forName("windows-1251"))
        val decoded = bytes.toString(Charset.forName("windows-1251"))

        assertTrue(decoded.contains("Привет"), "windows-1251 text must round-trip")
    }

    @Test fun tsplDocumentStaysInsideTheLabelAndKeepsPrintLast() {
        val bytes = TextDocumentPrinter.composeTspl(tspl, "line one\nline two", columns = 32)
        val text = bytes.toString(Charsets.ISO_8859_1)

        assertTrue(text.contains("""TEXT 20,20,"0",0,1,1,"line one""""), "First line must be placed at the margin")
        assertTrue(text.contains("""TEXT 20,50,"0",0,1,1,"line two""""), "Subsequent lines step down by the line height")
        assertTrue(text.endsWith("PRINT 1\n"), "PRINT must remain the last command")
    }

    @Test fun tsplDocumentTruncatesInsteadOfOverflowingALabel() {
        val longText = (1..100).joinToString("\n") { "line $it" }
        val bytes = TextDocumentPrinter.composeTspl(tspl, longText, columns = 32)
        val text = bytes.toString(Charsets.ISO_8859_1)

        val renderedLines = text.split("\n").count { it.startsWith("TEXT ") }
        assertEquals(TextDocumentPrinter.DEFAULT_TS_PL_LINES_PER_LABEL, renderedLines)
    }

    @Test fun goojprtDocumentIsWrappedInsidePageBoundaries() {
        val goojprt = DefaultProfiles.all.first { it.protocol == PrinterProtocol.GOOJPRT_LABEL }
        val bytes = TextDocumentPrinter.composeGoojprt(goojprt, "hello label")
        val text = bytes.toString(Charsets.UTF_8)

        assertTrue(text.contains("hello label"))
        assertTrue(bytes.indexOfPageBegin() < bytes.indexOfSequence(byteArrayOf(0x1a, 0x5d, 0x00)))
    }
}

private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
    outer@ for (index in 0..(size - needle.size)) {
        for (offset in needle.indices) {
            if (this[index + offset] != needle[offset]) continue@outer
        }
        return index
    }
    return -1
}

private fun ByteArray.indexOfPageBegin(): Int = indexOfSequence(byteArrayOf(0x1a, 0x5b, 0x01))
