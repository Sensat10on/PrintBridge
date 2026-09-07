package com.printbridge.drivers

import com.printbridge.core.MonoBitmap
import com.printbridge.core.PrinterDriver
import com.printbridge.core.PrinterProfile
import com.printbridge.core.PrinterProtocol
import com.printbridge.core.mmToDots
import java.io.ByteArrayOutputStream

class GoojprtLabelDriver : PrinterDriver {
    override val protocol = PrinterProtocol.GOOJPRT_LABEL

    override fun testPage(profile: PrinterProfile): ByteArray = build(profile) {
        val width = mmToDots(profile.paperWidthMm, profile.dpi).coerceAtMost(384)
        val height = mmToDots(profile.paperHeightMm ?: 40f, profile.dpi).coerceAtMost(936)
        pageBegin(0, 0, width, height)
        text(24, 24, "PRINTBRIDGE", font = 32, widthScale = 1, heightScale = 1)
        line(20, 70, width - 20, 70)
        text(24, 92, "GOOJPRT LABEL")
        text(24, 130, "SKU: PB000001")
        barcode(24, 172, "PB000001", height = 72)
        qr(250, 100, "PrintBridge GOOJPRT")
        box(18, 18, width - 18, height - 18)
        bitmap(250, 250, checkerBitmap(64, 64))
        pageEnd()
        pagePrint(1)
    }

    fun build(profile: PrinterProfile, block: GoojprtLabelBuilder.() -> Unit): ByteArray =
        GoojprtLabelBuilder().apply(block).toByteArray()
}

class GoojprtLabelBuilder {
    private val out = ByteArrayOutputStream()

    fun pageBegin(startX: Int, startY: Int, width: Int, height: Int, rotate: Int = 0) {
        bytes(0x1a, 0x5b, 0x01)
        little16(startX)
        little16(startY)
        little16(width)
        little16(height)
        bytes(rotate and 0xff)
    }

    fun pageEnd() = bytes(0x1a, 0x5d, 0x00)

    fun pagePrint(copies: Int) = bytes(0x1a, 0x4f, 0x01, copies.coerceIn(1, 255))

    fun text(
        x: Int,
        y: Int,
        value: String,
        font: Int = 24,
        bold: Boolean = false,
        underline: Boolean = false,
        inverse: Boolean = false,
        strike: Boolean = false,
        rotation: Int = 0,
        widthScale: Int = 0,
        heightScale: Int = 0
    ) {
        bytes(0x1a, 0x54, 0x01)
        little16(x)
        little16(y)
        little16(font)
        little16(labelStyle(bold, underline, inverse, strike, rotation, widthScale, heightScale))
        zeroTerminated(value)
    }

    fun line(startX: Int, startY: Int, endX: Int, endY: Int, width: Int = 2, black: Boolean = true) {
        bytes(0x1a, 0x5c, 0x01)
        little16(startX)
        little16(startY)
        little16(endX)
        little16(endY)
        little16(width)
        bytes(if (black) 1 else 0)
    }

    fun box(left: Int, top: Int, right: Int, bottom: Int, borderWidth: Int = 2, black: Boolean = true) {
        bytes(0x1a, 0x26, 0x01)
        little16(left)
        little16(top)
        little16(right)
        little16(bottom)
        little16(borderWidth)
        bytes(if (black) 1 else 0)
    }

    fun rect(left: Int, top: Int, right: Int, bottom: Int, black: Boolean = true) {
        bytes(0x1a, 0x2a, 0x00)
        little16(left)
        little16(top)
        little16(right)
        little16(bottom)
        bytes(if (black) 1 else 0)
    }

    fun barcode(x: Int, y: Int, value: String, type: Int = 8, height: Int = 80, unitWidth: Int = 2, rotation: Int = 0) {
        bytes(0x1a, 0x30, 0x00)
        little16(x)
        little16(y)
        bytes(type.coerceIn(0, 29), height.coerceIn(1, 255), unitWidth.coerceIn(1, 4), rotation.coerceIn(0, 3))
        zeroTerminated(value)
    }

    fun qr(x: Int, y: Int, value: String, version: Int = 0, ecc: Int = 2, unitWidth: Int = 4, rotation: Int = 0) {
        bytes(0x1a, 0x31, 0x00, version.coerceIn(0, 20), ecc.coerceIn(1, 4))
        little16(x)
        little16(y)
        bytes(unitWidth.coerceIn(1, 4), rotation.coerceIn(0, 3))
        zeroTerminated(value)
    }

    fun bitmap(x: Int, y: Int, bitmap: MonoBitmap, style: Int = 0) {
        val bytesPerRow = (bitmap.width + 7) / 8
        bytes(0x1a, 0x21, 0x01)
        little16(x)
        little16(y)
        little16(bytesPerRow * 8)
        little16(bitmap.height)
        little16(style)
        for (row in 0 until bitmap.height) {
            for (col in 0 until bytesPerRow) {
                var b = 0
                for (bit in 0..7) {
                    val px = col * 8 + bit
                    if (px < bitmap.width && bitmap[px, row]) b = b or (0x80 shr bit)
                }
                out.write(b)
            }
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun labelStyle(bold: Boolean, underline: Boolean, inverse: Boolean, strike: Boolean, rotation: Int, widthScale: Int, heightScale: Int): Int {
        var style = 0
        if (bold) style = style or 0x01
        if (underline) style = style or 0x02
        if (inverse) style = style or 0x04
        if (strike) style = style or 0x08
        style = style or ((rotation.coerceIn(0, 3) and 0x03) shl 4)
        style = style or ((widthScale.coerceIn(0, 15) and 0x0f) shl 8)
        style = style or ((heightScale.coerceIn(0, 15) and 0x0f) shl 12)
        return style
    }

    private fun zeroTerminated(value: String) {
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write(0)
    }

    private fun little16(value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
    }

    private fun bytes(vararg values: Int) = values.forEach { out.write(it and 0xff) }
}

private fun checkerBitmap(width: Int, height: Int): MonoBitmap =
    MonoBitmap(width, height, BooleanArray(width * height) { i -> ((i % width) / 8 + (i / width) / 8) % 2 == 0 })
