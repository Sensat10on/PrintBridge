package com.printbridge.drivers

import com.printbridge.core.Alignment
import com.printbridge.core.MonoBitmap
import com.printbridge.core.PrinterDriver
import com.printbridge.core.PrinterProfile
import com.printbridge.core.PrinterProtocol
import java.io.ByteArrayOutputStream

class EscPosDriver : PrinterDriver {
    override val protocol = PrinterProtocol.ESC_POS

    override fun testPage(profile: PrinterProfile): ByteArray = build(profile) {
        initialize()
        align(Alignment.CENTER)
        bold(true)
        scale(2, 2)
        textLine("PRINTBRIDGE")
        scale(1, 1)
        bold(false)
        textLine("")
        textLine("Printer Test")
        textLine("------------------------")
        align(Alignment.LEFT)
        leftRight("Product A", "12.50", 24)
        leftRight("Product B", "8.20", 24)
        textLine("")
        bold(true)
        leftRight("TOTAL", "20.70", 24)
        bold(false)
        textLine("")
        align(Alignment.CENTER)
        qr("PrintBridge ESC/POS TEST")
        raster(checkerBitmap(64, 32))
        textLine("ESC/POS TEST")
        textLine("------------------------")
        feed(3)
        if (profile.supportsCut) cut()
    }

    fun build(profile: PrinterProfile, block: EscPosBuilder.() -> Unit): ByteArray =
        EscPosBuilder(profile).apply(block).toByteArray()
}

class EscPosBuilder(private val profile: PrinterProfile) {
    private val out = ByteArrayOutputStream()
    fun initialize() { bytes(0x1b, 0x40) }
    fun align(alignment: Alignment) = bytes(0x1b, 0x61, when (alignment) { Alignment.LEFT -> 0; Alignment.CENTER -> 1; Alignment.RIGHT -> 2 })
    fun bold(enabled: Boolean) = bytes(0x1b, 0x45, if (enabled) 1 else 0)
    fun scale(width: Int, height: Int) = bytes(0x1d, 0x21, ((width - 1).coerceIn(0, 7) shl 4) or (height - 1).coerceIn(0, 7))
    fun textLine(value: String) { out.write(value.toByteArray(Charsets.UTF_8)); bytes(0x0a) }
    fun leftRight(left: String, right: String, columns: Int) = textLine(left + " ".repeat((columns - left.length - right.length).coerceAtLeast(1)) + right)
    fun feed(lines: Int) = bytes(0x1b, 0x64, lines.coerceIn(0, 255))
    fun cut() = bytes(0x1d, 0x56, 0x00)
    fun qr(value: String) {
        val data = value.toByteArray(Charsets.UTF_8)
        bytes(0x1d, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x43, 0x06)
        bytes(0x1d, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x45, 0x31)
        val len = data.size + 3
        bytes(0x1d, 0x28, 0x6b, len and 0xff, (len shr 8) and 0xff, 0x31, 0x50, 0x30)
        out.write(data)
        bytes(0x1d, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x51, 0x30)
        bytes(0x0a)
    }
    fun raster(bitmap: MonoBitmap) {
        val bytesPerRow = (bitmap.width + 7) / 8
        bytes(0x1d, 0x76, 0x30, 0x00, bytesPerRow and 0xff, bytesPerRow shr 8, bitmap.height and 0xff, bitmap.height shr 8)
        for (y in 0 until bitmap.height) {
            for (xByte in 0 until bytesPerRow) {
                var b = 0
                for (bit in 0..7) {
                    val x = xByte * 8 + bit
                    if (x < bitmap.width && bitmap[x, y]) b = b or (0x80 shr bit)
                }
                out.write(b)
            }
        }
        bytes(0x0a)
    }
    fun toByteArray(): ByteArray = out.toByteArray()
    private fun bytes(vararg values: Int) = values.forEach { out.write(it) }
}

private fun checkerBitmap(width: Int, height: Int): MonoBitmap =
    MonoBitmap(width, height, BooleanArray(width * height) { i -> ((i % width) / 8 + (i / width) / 8) % 2 == 0 })
