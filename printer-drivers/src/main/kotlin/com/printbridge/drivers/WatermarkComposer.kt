package com.printbridge.drivers

import com.printbridge.core.Alignment
import com.printbridge.core.PrinterProfile

/**
 * Free / paid split: until a license is purchased, every printed job is marked.
 *
 * [compose] takes the exact bytes a driver produced and inserts the watermark for the
 * protocol it recognises, leaving the payload byte-for-byte identical otherwise. When
 * [watermarkText] is null (licensed build) the input is returned untouched, so the licensed
 * path and the plain driver output are indistinguishable.
 *
 * The watermark is applied to the byte stream rather than to each builder so that every
 * existing caller — including the ready-job templates and the file-print pipeline — is
 * covered without a second code path.
 */
object WatermarkComposer {
    const val DEFAULT_TEXT: String = "PRINTBRIDGE FREE"

    fun compose(
        bytes: ByteArray,
        profile: PrinterProfile,
        watermarkText: String? = DEFAULT_TEXT
    ): ByteArray {
        if (watermarkText.isNullOrBlank() || bytes.isEmpty()) return bytes
        return when (profile.protocol) {
            com.printbridge.core.PrinterProtocol.ESC_POS -> stampEscPos(bytes, profile, watermarkText)
            com.printbridge.core.PrinterProtocol.TSPL -> stampTspl(bytes, profile, watermarkText)
            com.printbridge.core.PrinterProtocol.GOOJPRT_LABEL -> stampGoojprt(bytes, profile, watermarkText)
            else -> bytes
        }
    }

    /**
     * ESC/POS jobs end with `ESC d n` (feed) and optionally `GS V 0` (cut). The mark goes in
     * before them so it stays on the same slip instead of on a fresh one.
     */
    private fun stampEscPos(bytes: ByteArray, profile: PrinterProfile, text: String): ByteArray {
        val suffix = escPosTrailingSuffixLength(bytes)
        val splitAt = bytes.size - suffix
        val mark = watermarkEscPos(profile, text)
        return bytes.copyOfRange(0, splitAt) + mark + bytes.copyOfRange(splitAt, bytes.size)
    }

    /** Number of trailing bytes that belong to feed/cut rather than to the document body. */
    private fun escPosTrailingSuffixLength(bytes: ByteArray): Int {
        var end = bytes.size
        // GS V m  (cut)
        if (end >= 3 && bytes[end - 3] == 0x1d.toByte() && bytes[end - 2] == 0x56.toByte()) end -= 3
        // ESC d n (feed)
        if (end >= 3 && bytes[end - 3] == 0x1b.toByte() && bytes[end - 2] == 0x64.toByte()) end -= 3
        return bytes.size - end
    }

    private fun watermarkEscPos(profile: PrinterProfile, text: String): ByteArray =
        EscPosDriver().build(profile) {
            textLine("")
            textLine("------------------------")
            align(Alignment.CENTER)
            bold(true)
            textLine(text)
            bold(false)
            textLine("------------------------")
        }

    /** TSPL jobs end with `PRINT n`, which must stay the last command. */
    private fun stampTspl(bytes: ByteArray, profile: PrinterProfile, text: String): ByteArray {
        val printIndex = lastPrintCommandIndex(bytes) ?: return bytes + watermarkTspl(profile, text)
        return bytes.copyOfRange(0, printIndex) +
            watermarkTspl(profile, text) +
            bytes.copyOfRange(printIndex, bytes.size)
    }

    private fun lastPrintCommandIndex(bytes: ByteArray): Int? {
        val needle = "PRINT ".toByteArray(Charsets.US_ASCII)
        var found: Int? = null
        var index = 0
        while (index <= bytes.size - needle.size) {
            var match = true
            for (offset in needle.indices) {
                if (bytes[index + offset] != needle[offset]) {
                    match = false
                    break
                }
            }
            if (match && (index == 0 || bytes[index - 1] == '\n'.code.toByte())) found = index
            index++
        }
        return found
    }

    private fun watermarkTspl(profile: PrinterProfile, text: String): ByteArray =
        TsplDriver().build(profile) {
            // Kept inside the label area; y is well above the standard 1120-unit box bottom.
            text(80, 1000, text, xMul = 1, yMul = 1)
            box(60, 990, 720, 1040, thickness = 2)
        }

    /** GOOJPRT label jobs end with `1A 5D 00` (page end) followed by `1A 4F 01 copies`. */
    private fun stampGoojprt(bytes: ByteArray, profile: PrinterProfile, text: String): ByteArray {
        val pageEnd = lastSequence(bytes, byteArrayOf(0x1a, 0x5d, 0x00)) ?: return bytes + watermarkGoojprt(profile, text)
        return bytes.copyOfRange(0, pageEnd) +
            watermarkGoojprt(profile, text) +
            bytes.copyOfRange(pageEnd, bytes.size)
    }

    private fun lastSequence(bytes: ByteArray, needle: ByteArray): Int? {
        var found: Int? = null
        outer@ for (index in 0..(bytes.size - needle.size)) {
            for (offset in needle.indices) {
                if (bytes[index + offset] != needle[offset]) continue@outer
            }
            found = index
        }
        return found
    }

    private fun watermarkGoojprt(profile: PrinterProfile, text: String): ByteArray {
        val width = com.printbridge.core.mmToDots(profile.paperWidthMm, profile.dpi).coerceAtMost(384)
        val height = com.printbridge.core.mmToDots(profile.paperHeightMm ?: 40f, profile.dpi).coerceAtMost(936)
        val y = (height - 40).coerceAtLeast(0)
        return GoojprtLabelDriver().build(profile) {
            rect(0, y.coerceAtLeast(0), width, (y + 38).coerceAtMost(height), black = true)
            text(20, y + 8, text, font = 24, inverse = true)
        }
    }
}
