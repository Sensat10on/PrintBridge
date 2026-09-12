package com.printbridge.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.printbridge.core.MonoBitmap
import com.printbridge.core.PrinterProfile
import com.printbridge.core.RasterEngine
import com.printbridge.core.RasterOptions
import java.io.ByteArrayOutputStream

/**
 * Content of a user-selected file, ready to be turned into printer bytes.
 *
 * `pages` is used for images (one page) and PDFs (one page each); `text` is used for plain text
 * and CSV. Exactly one of the two is populated.
 */
data class PrintableDocument(
    val sourceName: String,
    val pages: List<MonoBitmap> = emptyList(),
    val text: String? = null
) {
    val isRaster: Boolean get() = pages.isNotEmpty()
}

/** Raised for user-visible failures; the message is shown in the UI as-is. */
class FilePrintException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a file chosen with `ActivityResultContracts.OpenDocument` into printable content.
 *
 * Images and PDF pages are reduced to a 1-bit [MonoBitmap] sized to the printer width; text is
 * kept as a string so the driver can wrap it to the printer's column count. Everything here is
 * Android-specific, which is why it lives in the app module rather than in `print-core`.
 */
object FilePrintPipeline {
    const val MAX_OUTPUT_DOTS_HEIGHT: Int = 8_000
    const val DEFAULT_TEXT_LIMIT_BYTES: Int = 512 * 1024

    /** Picks the pipeline for a document; returns null for unsupported types. */
    fun sourceFor(mimeType: String?, fileName: String?): DocumentSource? {
        val mime = mimeType?.lowercase().orEmpty()
        val name = fileName?.lowercase().orEmpty()
        return when {
            mime.startsWith("image/") || name.matchesImageExtension() -> DocumentSource.IMAGE
            mime == "application/pdf" || name.endsWith(".pdf") -> DocumentSource.PDF
            mime.startsWith("text/") || name.endsWith(".txt") || name.endsWith(".csv") -> DocumentSource.TEXT
            else -> null
        }
    }

    fun readImage(
        context: Context,
        uri: Uri,
        profile: PrinterProfile,
        sourceName: String,
        options: RasterOptions = RasterOptions()
    ): PrintableDocument {
        val bitmap = try {
            decodeScaledBitmap(context, uri, RasterEngine.targetWidthDots(profile))
        } catch (error: FilePrintException) {
            throw error
        } catch (error: Exception) {
            throw FilePrintException("Не удалось прочитать изображение: ${error.message ?: "неизвестная ошибка"}", error)
        } ?: throw FilePrintException("Не удалось прочитать изображение: формат не поддерживается")
        return try {
            PrintableDocument(sourceName = sourceName, pages = listOf(toPrintable(bitmap, options)))
        } finally {
            bitmap.recycle()
        }
    }

    fun readPdf(
        context: Context,
        uri: Uri,
        profile: PrinterProfile,
        sourceName: String,
        options: RasterOptions = RasterOptions(),
        maxPages: Int = 50
    ): PrintableDocument {
        val descriptor = openDescriptor(context, uri)
        descriptor.use { pfd ->
            return try {
                PdfRenderer(pfd).use { renderer ->
                    val pageCount = minOf(renderer.pageCount, maxPages)
                    if (pageCount == 0) throw FilePrintException("PDF не содержит страниц")
                    val pages = ArrayList<MonoBitmap>(pageCount)
                    for (index in 0 until pageCount) {
                        renderer.openPage(index).use { page ->
                            pages += renderPage(page, profile, options)
                        }
                    }
                    PrintableDocument(sourceName = sourceName, pages = pages)
                }
            } catch (error: FilePrintException) {
                throw error
            } catch (error: Exception) {
                throw FilePrintException("Не удалось прочитать PDF: ${error.message}", error)
            }
        }
    }

    fun readText(
        context: Context,
        uri: Uri,
        sourceName: String,
        limitBytes: Int = DEFAULT_TEXT_LIMIT_BYTES
    ): PrintableDocument {
        val bytes = readBounded(context, uri, limitBytes)
        if (bytes.isEmpty()) throw FilePrintException("Файл пуст")
        return PrintableDocument(sourceName = sourceName, text = TextEncoding.decode(bytes))
    }

    private fun renderPage(
        page: PdfRenderer.Page,
        profile: PrinterProfile,
        options: RasterOptions
    ): MonoBitmap {
        val targetWidth = RasterEngine.targetWidthDots(profile)
        val scale = targetWidth.toFloat() / page.width.toFloat()
        val targetHeight = (page.height * scale).toInt()
            .coerceIn(1, MAX_OUTPUT_DOTS_HEIGHT)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            toPrintable(bitmap, options)
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes with `inSampleSize` so a phone camera photo never inflates to full resolution. */
    private fun decodeScaledBitmap(context: Context, uri: Uri, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = calculateSampleSize(bounds.outWidth, targetWidth)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openStream(context, uri).use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        }
    }

    /** Wraps provider failures so the UI always sees a readable message instead of an IOException. */
    private fun openStream(context: Context, uri: Uri): java.io.InputStream =
        try {
            context.contentResolver.openInputStream(uri)
                ?: throw FilePrintException("Не удалось открыть файл")
        } catch (error: FilePrintException) {
            throw error
        } catch (error: Exception) {
            throw FilePrintException("Не удалось прочитать файл: ${error.message ?: "нет доступа"}", error)
        }

    /** Largest power-of-two sample size whose result is still at least [targetWidth] wide. */
    internal fun calculateSampleSize(sourceWidth: Int, targetWidth: Int): Int {
        if (targetWidth <= 0 || sourceWidth <= targetWidth) return 1
        var sampleSize = 1
        while (sourceWidth / (sampleSize * 2) >= targetWidth) sampleSize *= 2
        return sampleSize
    }

    /** Scales to the printer width and reduces to 1 bit. */
    internal fun toPrintable(bitmap: Bitmap, options: RasterOptions = RasterOptions()): MonoBitmap {
        val scaled = scaleToWidth(bitmap, options)
        return try {
            val luminance = IntArray(scaled.width * scaled.height)
            scaled.getPixels(luminance, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            for (index in luminance.indices) {
                val pixel = luminance[index]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                // Transparent pixels come back as 0; treat them as white paper, not black ink.
                val alpha = (pixel ushr 24) and 0xff
                luminance[index] = if (alpha == 0) 255 else ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
            }
            RasterEngine.fromGrayscaleInts(scaled.width, scaled.height, luminance, options)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun scaleToWidth(bitmap: Bitmap, options: RasterOptions): Bitmap {
        val targetWidth = options.targetWidthDots ?: return bitmap
        if (bitmap.width <= targetWidth) return bitmap
        val targetHeight = (bitmap.height.toLong() * targetWidth / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun openDescriptor(context: Context, uri: Uri): ParcelFileDescriptor =
        try {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw FilePrintException("Не удалось открыть файл")
        } catch (error: FilePrintException) {
            throw error
        } catch (error: Exception) {
            throw FilePrintException("Нет доступа к файлу: ${error.message}", error)
        }

    private fun readBounded(context: Context, uri: Uri, limitBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        openStream(context, uri).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > limitBytes) {
                    throw FilePrintException("Текстовый файл больше ${limitBytes / 1024} КБ")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun String.matchesImageExtension(): Boolean =
        endsWith(".png") || endsWith(".jpg") || endsWith(".jpeg") || endsWith(".webp") || endsWith(".bmp") || endsWith(".gif")
}

enum class DocumentSource { IMAGE, PDF, TEXT }
