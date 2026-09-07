package com.printbridge.simulator

import com.printbridge.core.PrinterProtocol
import com.printbridge.core.MonoBitmap

enum class Confidence { LOW, MEDIUM, HIGH }

data class DetectionResult(val protocol: PrinterProtocol, val confidence: Confidence, val evidence: List<String>)
data class ParsedCommand(val name: String, val detail: String = "", val rawHex: String = "")
data class ParseResult(val protocol: PrinterProtocol, val commands: List<ParsedCommand>, val warnings: List<String>, val preview: VirtualPreview)
data class VirtualPreview(val title: String, val lines: List<String>, val raster: MonoBitmap? = null, val widthDots: Int? = null, val heightDots: Int? = null, val dpi: Int? = null)
data class SimulatorJob(val id: Int, val receivedBytes: Int, val protocol: PrinterProtocol, val commands: Int, val warnings: Int, val preview: VirtualPreview, val rawBytes: ByteArray, val diagnostics: List<String>)

object ProtocolDetector {
    fun detect(data: ByteArray): DetectionResult {
        val ascii = data.toString(Charsets.ISO_8859_1).uppercase()
        val evidence = mutableListOf<String>()
        listOf("SIZE", "GAP", "CLS", "TEXT", "PRINT").forEach { if (ascii.contains(it)) evidence += it }
        if (evidence.contains("SIZE") && evidence.contains("CLS") && evidence.contains("PRINT")) {
            return DetectionResult(PrinterProtocol.TSPL, Confidence.HIGH, evidence)
        }
        val escEvidence = mutableListOf<String>()
        if (data.indexOf(byteArrayOf(0x1b, 0x40)) >= 0) escEvidence += "ESC @"
        if (data.indexOf(byteArrayOf(0x1d, 0x28, 0x6b)) >= 0) escEvidence += "GS ( k QR"
        if (data.indexOf(byteArrayOf(0x1d, 0x76, 0x30)) >= 0) escEvidence += "GS v 0 raster"
        if (escEvidence.isNotEmpty()) return DetectionResult(PrinterProtocol.ESC_POS, if (escEvidence.size > 1) Confidence.HIGH else Confidence.MEDIUM, escEvidence)
        val goojprtEvidence = mutableListOf<String>()
        if (data.indexOf(byteArrayOf(0x1a, 0x5b, 0x01)) >= 0) goojprtEvidence += "GOOJPRT page begin"
        if (data.indexOf(byteArrayOf(0x1a, 0x4f, 0x01)) >= 0) goojprtEvidence += "GOOJPRT page print"
        if (data.indexOf(byteArrayOf(0x1a, 0x54, 0x01)) >= 0) goojprtEvidence += "GOOJPRT text"
        if (goojprtEvidence.isNotEmpty()) return DetectionResult(PrinterProtocol.GOOJPRT_LABEL, if (goojprtEvidence.size > 1) Confidence.HIGH else Confidence.MEDIUM, goojprtEvidence)
        return DetectionResult(PrinterProtocol.UNKNOWN, Confidence.LOW, emptyList())
    }
}

class EscPosParser {
    fun parse(data: ByteArray): ParseResult {
        val commands = mutableListOf<ParsedCommand>()
        val warnings = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val text = StringBuilder()
        var i = 0
        fun flushText() { if (text.isNotEmpty()) { lines += text.toString(); commands += ParsedCommand("TEXT", text.toString()); text.clear() } }
        while (i < data.size) {
            val b = data[i].toInt() and 0xff
            when {
                b == 0x0a -> { flushText(); commands += ParsedCommand("LF"); i++ }
                b == 0x1b && i + 1 < data.size && data[i + 1].toInt() == 0x40 -> { flushText(); commands += ParsedCommand("INITIALIZE", "ESC @"); i += 2 }
                b == 0x1b && i + 2 < data.size && data[i + 1].toInt() == 0x61 -> { flushText(); commands += ParsedCommand("ALIGN", data[i + 2].toString()); i += 3 }
                b == 0x1b && i + 2 < data.size && data[i + 1].toInt() == 0x45 -> { flushText(); commands += ParsedCommand("BOLD", data[i + 2].toString()); i += 3 }
                b == 0x1b && i + 2 < data.size && data[i + 1].toInt() == 0x64 -> { flushText(); commands += ParsedCommand("FEED", data[i + 2].toString()); i += 3 }
                b == 0x1d && i + 2 < data.size && data[i + 1].toInt() == 0x21 -> { flushText(); commands += ParsedCommand("SCALE", data[i + 2].toString()); i += 3 }
                b == 0x1d && i + 2 < data.size && data[i + 1].toInt() == 0x56 -> { flushText(); commands += ParsedCommand("CUT", data[i + 2].toString()); i += 3 }
                b == 0x1d && i + 7 < data.size && data[i + 1].toInt() == 0x76 -> {
                    flushText()
                    val widthBytes = (data[i + 4].toInt() and 0xff) + ((data[i + 5].toInt() and 0xff) shl 8)
                    val height = (data[i + 6].toInt() and 0xff) + ((data[i + 7].toInt() and 0xff) shl 8)
                    val payload = widthBytes * height
                    if (i + 8 + payload > data.size) {
                        warnings += "Truncated raster at $i"
                        i = data.size
                        continue
                    }
                    commands += ParsedCommand("RASTER", "${widthBytes * 8}x$height")
                    lines += "[RASTER ${widthBytes * 8}x$height]"
                    lines += renderPackedRaster(data, i + 8, widthBytes, height)
                    i += 8 + payload
                }
                b == 0x1d && i + 7 < data.size && data[i + 1].toInt() == 0x28 && data[i + 2].toInt() == 0x6b -> {
                    flushText()
                    val length = (data[i + 3].toInt() and 0xff) + ((data[i + 4].toInt() and 0xff) shl 8)
                    val end = i + 5 + length
                    if (end > data.size) {
                        warnings += "Truncated QR command at $i"
                        i = data.size
                    } else {
                        commands += ParsedCommand("QR", "GS ( k")
                        if (data[i + 5].toInt() == 0x31 && data[i + 6].toInt() == 0x51) lines += "[QR]"
                        i = end
                    }
                }
                b in 32..126 -> { text.append(b.toChar()); i++ }
                else -> { warnings += "Unknown byte ${"%02X".format(b)} at $i"; i++ }
            }
        }
        flushText()
        return ParseResult(PrinterProtocol.ESC_POS, commands, warnings, VirtualPreview("Virtual Receipt", lines, extractEscRaster(data)))
    }
}

class TsplParser {
    fun parse(data: ByteArray): ParseResult {
        val commands = mutableListOf<ParsedCommand>()
        val warnings = mutableListOf<String>()
        val preview = mutableListOf<String>()
        var raster: MonoBitmap? = null
        var offset = 0
        while (offset < data.size) {
            val lineEnd = data.indexOf(0x0a, offset).let { if (it < 0) data.size else it }
            val line = data.copyOfRange(offset, lineEnd).toString(Charsets.ISO_8859_1).trim()
            offset = (lineEnd + 1).coerceAtMost(data.size)
            if (line.isEmpty()) continue
            val name = line.takeWhile { !it.isWhitespace() }.uppercase()
            when (name) {
                "SIZE", "GAP", "CLS", "DENSITY", "SPEED", "TEXT", "BARCODE", "QRCODE", "BOX", "PRINT" -> {
                    commands += ParsedCommand(name, line)
                    if (name in setOf("TEXT", "BARCODE", "QRCODE", "BOX")) preview += line
                }
                "BITMAP" -> {
                    val fields = line.removePrefix("BITMAP").trim().split(',').map { it.trim() }
                    val widthBytes = fields.getOrNull(2)?.toIntOrNull()
                    val height = fields.getOrNull(3)?.toIntOrNull()
                    if (widthBytes == null || height == null || widthBytes <= 0 || height <= 0) {
                        warnings += "Malformed BITMAP command: $line"
                    } else {
                        val length = widthBytes * height
                        if (offset + length > data.size) {
                            warnings += "Truncated BITMAP payload: expected $length bytes"
                            offset = data.size
                        } else {
                            val payload = data.copyOfRange(offset, offset + length)
                            raster = bitmapFromPacked(payload, widthBytes, height)
                            commands += ParsedCommand("BITMAP", line)
                            preview += "[BITMAP ${widthBytes * 8}x$height]"
                            preview += renderPackedRaster(payload, 0, widthBytes, height)
                            offset += length
                            if (offset < data.size && data[offset] == '\n'.code.toByte()) offset++
                        }
                    }
                }
                else -> warnings += "Unknown TSPL command: $line"
            }
        }
        return ParseResult(PrinterProtocol.TSPL, commands, warnings, VirtualPreview("Virtual Label", preview, raster))
    }
}

class GoojprtLabelParser {
    fun parse(data: ByteArray): ParseResult {
        val commands = mutableListOf<ParsedCommand>()
        val warnings = mutableListOf<String>()
        val preview = mutableListOf<String>()
        var raster: MonoBitmap? = null
        var offset = 0

        fun requireBytes(count: Int, name: String): Boolean {
            if (offset + count <= data.size) return true
            warnings += "Truncated GOOJPRT $name command at $offset"
            offset = data.size
            return false
        }

        while (offset < data.size) {
            if (data[offset] != 0x1a.toByte()) {
                warnings += "Unknown GOOJPRT byte ${"%02X".format(data[offset].toInt() and 0xff)} at $offset"
                offset++
                continue
            }

            if (!requireBytes(3, "header")) continue
            val start = offset
            val op = data[offset + 1].toInt() and 0xff
            val mode = data[offset + 2].toInt() and 0xff
            offset += 3
            when (op) {
                0x5b -> {
                    if (!requireBytes(9, "PAGE_BEGIN")) continue
                    val x = data.readLe16(offset)
                    val y = data.readLe16(offset + 2)
                    val width = data.readLe16(offset + 4)
                    val height = data.readLe16(offset + 6)
                    val rotate = data[offset + 8].toInt() and 0xff
                    commands += ParsedCommand("PAGE_BEGIN", "${width}x$height at $x,$y rotate=$rotate", data.hex(start, 12))
                    preview += "PAGE ${width}x$height"
                    offset += 9
                }
                0x5d -> {
                    commands += ParsedCommand("PAGE_END", "mode=$mode", data.hex(start, 3))
                }
                0x4f -> {
                    if (!requireBytes(1, "PAGE_PRINT")) continue
                    val copies = data[offset].toInt() and 0xff
                    commands += ParsedCommand("PAGE_PRINT", "copies=$copies", data.hex(start, 4))
                    preview += "PRINT $copies"
                    offset += 1
                }
                0x54 -> {
                    if (!requireBytes(8, "TEXT")) continue
                    val x = data.readLe16(offset)
                    val y = data.readLe16(offset + 2)
                    val font = data.readLe16(offset + 4)
                    val style = data.readLe16(offset + 6)
                    offset += 8
                    val textEnd = data.indexOf(0, offset)
                    if (textEnd < 0) {
                        warnings += "Unterminated GOOJPRT TEXT at $start"
                        offset = data.size
                    } else {
                        val text = data.copyOfRange(offset, textEnd).toString(Charsets.UTF_8)
                        commands += ParsedCommand("TEXT", "$x,$y font=$font style=$style $text", data.hex(start, textEnd - start + 1))
                        preview += "TEXT $x,$y: $text"
                        offset = textEnd + 1
                    }
                }
                0x5c -> {
                    if (!requireBytes(11, "LINE")) continue
                    commands += ParsedCommand("LINE", "${data.readLe16(offset)},${data.readLe16(offset + 2)}-${data.readLe16(offset + 4)},${data.readLe16(offset + 6)}", data.hex(start, 14))
                    preview += "LINE"
                    offset += 11
                }
                0x26 -> {
                    if (!requireBytes(11, "BOX")) continue
                    commands += ParsedCommand("BOX", "${data.readLe16(offset)},${data.readLe16(offset + 2)}-${data.readLe16(offset + 4)},${data.readLe16(offset + 6)}", data.hex(start, 14))
                    preview += "BOX"
                    offset += 11
                }
                0x2a -> {
                    if (!requireBytes(9, "RECT")) continue
                    commands += ParsedCommand("RECT", "${data.readLe16(offset)},${data.readLe16(offset + 2)}-${data.readLe16(offset + 4)},${data.readLe16(offset + 6)}", data.hex(start, 12))
                    preview += "RECT"
                    offset += 9
                }
                0x30 -> {
                    if (!requireBytes(8, "BARCODE")) continue
                    val x = data.readLe16(offset)
                    val y = data.readLe16(offset + 2)
                    val type = data[offset + 4].toInt() and 0xff
                    val height = data[offset + 5].toInt() and 0xff
                    offset += 8
                    val textEnd = data.indexOf(0, offset)
                    if (textEnd < 0) {
                        warnings += "Unterminated GOOJPRT BARCODE at $start"
                        offset = data.size
                    } else {
                        val text = data.copyOfRange(offset, textEnd).toString(Charsets.UTF_8)
                        commands += ParsedCommand("BARCODE", "$x,$y type=$type height=$height $text", data.hex(start, textEnd - start + 1))
                        preview += "BARCODE $x,$y: $text"
                        offset = textEnd + 1
                    }
                }
                0x31 -> {
                    if (!requireBytes(8, "QR")) continue
                    val version = data[offset].toInt() and 0xff
                    val ecc = data[offset + 1].toInt() and 0xff
                    val x = data.readLe16(offset + 2)
                    val y = data.readLe16(offset + 4)
                    offset += 8
                    val textEnd = data.indexOf(0, offset)
                    if (textEnd < 0) {
                        warnings += "Unterminated GOOJPRT QR at $start"
                        offset = data.size
                    } else {
                        val text = data.copyOfRange(offset, textEnd).toString(Charsets.UTF_8)
                        commands += ParsedCommand("QR", "$x,$y version=$version ecc=$ecc $text", data.hex(start, textEnd - start + 1))
                        preview += "QR $x,$y: $text"
                        offset = textEnd + 1
                    }
                }
                0x21 -> {
                    if (!requireBytes(10, "BITMAP")) continue
                    val x = data.readLe16(offset)
                    val y = data.readLe16(offset + 2)
                    val width = data.readLe16(offset + 4)
                    val height = data.readLe16(offset + 6)
                    val payloadLength = ((width + 7) / 8) * height
                    offset += 10
                    if (offset + payloadLength > data.size) {
                        warnings += "Truncated GOOJPRT BITMAP payload: expected $payloadLength bytes"
                        offset = data.size
                    } else {
                        val widthBytes = (width + 7) / 8
                        val payload = data.copyOfRange(offset, offset + payloadLength)
                        raster = bitmapFromPacked(payload, widthBytes, height)
                        commands += ParsedCommand("BITMAP", "$x,$y ${width}x$height", data.hex(start, 13 + payloadLength))
                        preview += "BITMAP $x,$y ${width}x$height"
                        preview += renderPackedRaster(payload, 0, widthBytes, height)
                        offset += payloadLength
                    }
                }
                else -> {
                    warnings += "Unknown GOOJPRT command 1A ${"%02X".format(op)} ${"%02X".format(mode)} at $start"
                }
            }
        }
        return ParseResult(PrinterProtocol.GOOJPRT_LABEL, commands, warnings, VirtualPreview("Virtual GOOJPRT Label", preview, raster))
    }
}

class PrintBridgeSimulator {
    private var nextId = 1
    fun accept(data: ByteArray, override: PrinterProtocol? = null): SimulatorJob {
        val detected = ProtocolDetector.detect(data)
        val protocol = override ?: detected.protocol
        val parsed = when (protocol) {
            PrinterProtocol.ESC_POS -> EscPosParser().parse(data)
            PrinterProtocol.TSPL -> TsplParser().parse(data)
            PrinterProtocol.GOOJPRT_LABEL -> GoojprtLabelParser().parse(data)
            else -> ParseResult(PrinterProtocol.UNKNOWN, emptyList(), listOf("Unknown protocol"), VirtualPreview("Unknown", emptyList()))
        }
        return SimulatorJob(nextId++, data.size, protocol, parsed.commands.size, parsed.warnings.size, parsed.preview, data.copyOf(), parsed.warnings)
    }
}

private fun extractEscRaster(data: ByteArray): MonoBitmap? {
    var index = 0
    while (index + 7 < data.size) {
        if (data[index] == 0x1d.toByte() && data[index + 1] == 0x76.toByte()) {
            val widthBytes = (data[index + 4].toInt() and 0xff) + ((data[index + 5].toInt() and 0xff) shl 8)
            val height = (data[index + 6].toInt() and 0xff) + ((data[index + 7].toInt() and 0xff) shl 8)
            val length = widthBytes * height
            if (index + 8 + length <= data.size) return bitmapFromPacked(data.copyOfRange(index + 8, index + 8 + length), widthBytes, height)
        }
        index++
    }
    return null
}

private fun bitmapFromPacked(data: ByteArray, widthBytes: Int, height: Int): MonoBitmap =
    MonoBitmap(widthBytes * 8, height, BooleanArray(widthBytes * 8 * height) { pixel ->
        val y = pixel / (widthBytes * 8)
        val x = pixel % (widthBytes * 8)
        data[y * widthBytes + x / 8].toInt() and (0x80 shr (x % 8)) != 0
    })

private fun renderPackedRaster(data: ByteArray, start: Int, widthBytes: Int, height: Int): List<String> =
    (0 until height).map { y ->
        buildString {
            for (xByte in 0 until widthBytes) {
                val value = data[start + y * widthBytes + xByte].toInt() and 0xff
                for (bit in 0..7) append(if ((value and (0x80 shr bit)) != 0) '#' else '.')
            }
        }
    }

private fun ByteArray.indexOf(needle: ByteArray): Int {
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

private fun ByteArray.indexOf(value: Int, startIndex: Int): Int {
    for (index in startIndex until size) if ((this[index].toInt() and 0xff) == value) return index
    return -1
}

private fun ByteArray.readLe16(offset: Int): Int =
    (this[offset].toInt() and 0xff) + ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.hex(offset: Int, count: Int): String =
    copyOfRange(offset, (offset + count).coerceAtMost(size)).joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
