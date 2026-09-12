package com.printbridge.drivers

import com.printbridge.core.Alignment
import com.printbridge.core.PrinterProfile
import java.nio.charset.Charset

/**
 * Prints the contents of a plain-text or CSV file.
 *
 * A document is arbitrary text, so the protocol-specific part is the same problem the drivers
 * already solve: lay the text out into printer-width lines and emit them. Wrapping happens at
 * word boundaries where possible and never loses characters.
 */
object TextDocumentPrinter {
    /** Default printable columns for a 58 mm receipt at the standard 12-column font. */
    const val DEFAULT_COLUMNS_58MM: Int = 32
    const val DEFAULT_COLUMNS_80MM: Int = 48

    fun columnsFor(profile: PrinterProfile): Int = when {
        profile.paperWidthMm >= 76f -> DEFAULT_COLUMNS_80MM
        profile.paperWidthMm >= 57f -> DEFAULT_COLUMNS_58MM
        else -> DEFAULT_COLUMNS_58MM
    }

    /**
     * Wraps [text] into lines of at most [columns] characters.
     *
     * - existing LF, CRLF and lone CR are honoured as hard line breaks;
     * - long words are split rather than dropped;
     * - tabs are expanded to spaces so column alignment survives on the printer.
     */
    fun wrap(text: String, columns: Int): List<String> {
        require(columns > 0) { "Column count must be positive" }
        val lines = mutableListOf<String>()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
        for (rawLine in normalized.split('\n')) {
            if (rawLine.isEmpty()) {
                lines += ""
                continue
            }
            var rest = rawLine
            while (rest.length > columns) {
                val window = rest.substring(0, columns + 1)
                val breakAt = window.lastIndexOf(' ').takeIf { it > 0 } ?: columns
                lines += rest.substring(0, breakAt).trimEnd()
                rest = rest.substring(breakAt).trimStart()
            }
            lines += rest
        }
        return lines
    }

    /** ESC/POS document: initialize, print wrapped lines, feed and optionally cut. */
    fun composeEscPos(
        profile: PrinterProfile,
        text: String,
        columns: Int = columnsFor(profile),
        charset: Charset = Charsets.UTF_8
    ): ByteArray = EscPosDriver().build(profile) {
        initialize()
        align(Alignment.LEFT)
        wrap(text, columns).forEach { line -> textLineEncoded(line, charset) }
        feed(3)
        if (profile.supportsCut) cut()
    }

    /** TSPL document: one label per screen-worth of lines, laid out top to bottom. */
    fun composeTspl(
        profile: PrinterProfile,
        text: String,
        columns: Int = columnsFor(profile),
        linesPerLabel: Int = DEFAULT_TS_PL_LINES_PER_LABEL,
        lineHeight: Int = DEFAULT_TS_PL_LINE_HEIGHT
    ): ByteArray = TsplDriver().build(profile) {
        size(profile.paperWidthMm, profile.paperHeightMm ?: 150f)
        gap(profile.gapMm ?: 3f)
        density(8)
        speed(4)
        cls()
        wrap(text, columns).take(linesPerLabel).forEachIndexed { index, line ->
            text(DEFAULT_TS_PL_MARGIN_X, DEFAULT_TS_PL_MARGIN_Y + index * lineHeight, line)
        }
        print(1)
    }

    /** GOOJPRT label document: same layout rules as TSPL. */
    fun composeGoojprt(
        profile: PrinterProfile,
        text: String,
        columns: Int = columnsFor(profile),
        linesPerLabel: Int = DEFAULT_TS_PL_LINES_PER_LABEL,
        lineHeight: Int = DEFAULT_TS_PL_LINE_HEIGHT
    ): ByteArray = GoojprtLabelDriver().build(profile) {
        val width = com.printbridge.core.mmToDots(profile.paperWidthMm, profile.dpi).coerceAtMost(384)
        val height = com.printbridge.core.mmToDots(profile.paperHeightMm ?: 40f, profile.dpi).coerceAtMost(936)
        pageBegin(0, 0, width, height)
        wrap(text, columns).take(linesPerLabel).forEachIndexed { index, line ->
            text(DEFAULT_TS_PL_MARGIN_X, DEFAULT_TS_PL_MARGIN_Y + index * lineHeight, line, font = 24)
        }
        pageEnd()
        pagePrint(1)
    }

    const val DEFAULT_TS_PL_LINES_PER_LABEL: Int = 20
    const val DEFAULT_TS_PL_LINE_HEIGHT: Int = 30
    const val DEFAULT_TS_PL_MARGIN_X: Int = 20
    const val DEFAULT_TS_PL_MARGIN_Y: Int = 20
}

/**
 * Writes a line with an explicit charset. The ESC/POS builder always uses UTF-8, which is what
 * modern thermal printers expect; devices configured for a legacy code page need the caller to
 * pick one, so the encoding lives here instead of inside the builder.
 */
private fun EscPosBuilder.textLineEncoded(value: String, charset: Charset) {
    if (charset == Charsets.UTF_8) {
        textLine(value)
    } else {
        rawTextLine(value.toByteArray(charset))
    }
}
