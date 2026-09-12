package com.printbridge.core

data class MonoBitmap(val width: Int, val height: Int, val pixels: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = pixels[y * width + x]
}

data class RasterOptions(
    val rotate90: Boolean = false,
    val invert: Boolean = false,
    val threshold: Int = 160,
    val dither: Boolean = false,
    /**
     * When set, callers that start from a full-size source (a photo, a rendered PDF page)
     * scale it down to this many dots before thresholding. Null means "use as-is".
     */
    val targetWidthDots: Int? = null
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

    /**
     * Same conversion as [fromGrayscale] but reading luminance from an int buffer.
     *
     * A rendered PDF page at 203 dpi is several megabytes; keeping a second copy as a
     * ByteArray on top of the source bitmap and the resulting MonoBitmap is what makes
     * large jobs fail on a phone. Callers that already have per-pixel luminance in an
     * IntArray (0..255) should use this overload instead.
     */
    fun fromGrayscaleInts(width: Int, height: Int, gray: IntArray, options: RasterOptions = RasterOptions()): MonoBitmap {
        require(gray.size == width * height) { "Grayscale buffer size must match dimensions" }
        val outWidth = if (options.rotate90) height else width
        val outHeight = if (options.rotate90) width else height
        val out = BooleanArray(outWidth * outHeight)
        for (y in 0 until height) for (x in 0 until width) {
            val src = gray[y * width + x]
            val black = if (options.invert) src > options.threshold else src < options.threshold
            val dx = if (options.rotate90) height - 1 - y else x
            val dy = if (options.rotate90) x else y
            out[dy * outWidth + dx] = black
        }
        return MonoBitmap(outWidth, outHeight, out)
    }

    /**
     * Packs a bitmap into the 1-bit-per-pixel, most-significant-bit-first raster form used by
     * ESC/POS `GS v 0`, TSPL `BITMAP` and GOOJPRT bitmap commands.
     */
    fun toPackedBits(bitmap: MonoBitmap): ByteArray {
        val bytesPerRow = (bitmap.width + 7) / 8
        val packed = ByteArray(bytesPerRow * bitmap.height)
        for (y in 0 until bitmap.height) {
            for (xByte in 0 until bytesPerRow) {
                var value = 0
                for (bit in 0..7) {
                    val x = xByte * 8 + bit
                    if (x < bitmap.width && bitmap[x, y]) value = value or (0x80 shr bit)
                }
                packed[y * bytesPerRow + xByte] = value.toByte()
            }
        }
        return packed
    }
}
