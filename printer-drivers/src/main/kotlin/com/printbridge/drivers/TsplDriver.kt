package com.printbridge.drivers

import com.printbridge.core.MonoBitmap
import com.printbridge.core.PrinterDriver
import com.printbridge.core.PrinterProfile
import com.printbridge.core.PrinterProtocol
import java.io.ByteArrayOutputStream

class TsplDriver : PrinterDriver {
    override val protocol = PrinterProtocol.TSPL
    override fun testPage(profile: PrinterProfile): ByteArray = build(profile) {
        size(profile.paperWidthMm, profile.paperHeightMm ?: 150f)
        gap(profile.gapMm ?: 3f)
        density(8)
        speed(4)
        cls()
        text(80, 60, "PRINTBRIDGE TEST", xMul = 2, yMul = 2)
        box(20, 20, 780, 1180)
        text(80, 170, "Product:")
        text(80, 220, "PB-001", xMul = 2, yMul = 2)
        barcode(80, 340, "PB000001")
        qrcode(80, 540, "PrintBridge Test")
        text(80, 820, "DPI:")
        text(220, 820, profile.dpi.toString())
        text(80, 880, "Paper:")
        text(220, 880, "${profile.paperWidthMm.toInt()} x ${(profile.paperHeightMm ?: 150f).toInt()} mm")
        bitmap(600, 960, checkerBitmap(64, 64))
        print(1)
    }

    fun build(profile: PrinterProfile, block: TsplBuilder.() -> Unit): ByteArray =
        TsplBuilder().apply(block).toByteArray()
}

class TsplBuilder {
    private val out = ByteArrayOutputStream()
    fun size(widthMm: Float, heightMm: Float) = line("SIZE ${widthMm.clean()} mm,${heightMm.clean()} mm")
    fun gap(gapMm: Float, offsetMm: Float = 0f) = line("GAP ${gapMm.clean()} mm,${offsetMm.clean()} mm")
    fun density(value: Int) = line("DENSITY ${value.coerceIn(0, 15)}")
    fun speed(value: Int) = line("SPEED ${value.coerceIn(1, 6)}")
    fun cls() = line("CLS")
    fun text(x: Int, y: Int, value: String, font: String = "0", rotation: Int = 0, xMul: Int = 1, yMul: Int = 1) =
        line("""TEXT $x,$y,"$font",$rotation,$xMul,$yMul,"${value.escape()}"""")
    fun barcode(x: Int, y: Int, value: String) = line("""BARCODE $x,$y,"128",90,1,0,2,2,"${value.escape()}"""")
    fun qrcode(x: Int, y: Int, value: String) = line("""QRCODE $x,$y,L,5,A,0,"${value.escape()}"""")
    fun box(x1: Int, y1: Int, x2: Int, y2: Int, thickness: Int = 3) = line("BOX $x1,$y1,$x2,$y2,$thickness")
    fun bitmap(x: Int, y: Int, bitmap: MonoBitmap) {
        val bytesPerRow = (bitmap.width + 7) / 8
        line("BITMAP $x,$y,$bytesPerRow,${bitmap.height},0,")
        for (row in 0 until bitmap.height) for (col in 0 until bytesPerRow) {
            var b = 0
            for (bit in 0..7) {
                val px = col * 8 + bit
                if (px < bitmap.width && bitmap[px, row]) b = b or (0x80 shr bit)
            }
            out.write(b)
        }
        out.write('\n'.code)
    }
    fun print(copies: Int) = line("PRINT ${copies.coerceAtLeast(1)}")
    fun toByteArray(): ByteArray = out.toByteArray()
    private fun line(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)); out.write('\n'.code) }
}

private fun Float.clean(): String = if (this % 1f == 0f) toInt().toString() else toString()
private fun String.escape(): String = replace("\"", "\\\"")
private fun checkerBitmap(width: Int, height: Int): MonoBitmap =
    MonoBitmap(width, height, BooleanArray(width * height) { i -> ((i % width) / 8 + (i / width) / 8) % 2 == 0 })
