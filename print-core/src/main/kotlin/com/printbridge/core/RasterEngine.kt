package com.printbridge.core

data class MonoBitmap(val width: Int, val height: Int, val pixels: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = pixels[y * width + x]
}

data class RasterOptions(
    val rotate90: Boolean = false,
    val invert: Boolean = false,
    val threshold: Int = 160,
    val dither: Boolean = false
)

object RasterEngine {
    fun targetWidthDots(profile: PrinterProfile): Int = mmToDots(profile.paperWidthMm, profile.dpi)

    fun fromGrayscale(width: Int, height: Int, gray: ByteArray, options: RasterOptions = RasterOptions()): MonoBitmap {
        require(gray.size == width * height) { "Grayscale buffer size must match dimensions" }
        val outWidth = if (options.rotate90) height else width
        val outHeight = if (options.rotate90) width else height
        val out = BooleanArray(outWidth * outHeight)
        for (y in 0 until height) for (x in 0 until width) {
            val src = gray[y * width + x].toInt() and 0xff
            val black = if (options.invert) src > options.threshold else src < options.threshold
            val dx = if (options.rotate90) height - 1 - y else x
            val dy = if (options.rotate90) x else y
            out[dy * outWidth + dx] = black
        }
        return MonoBitmap(outWidth, outHeight, out)
    }
}
