package com.printbridge.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.printbridge.core.MonoBitmap
import java.io.OutputStream

/** What a job is exported as. */
enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    /** Rendered pages, for archiving or sending to a PDF-capable printer driver. */
    PDF("PDF (страницы)", "pdf", "application/pdf"),

    /** The exact bytes that would go to the printer; useful for debugging and for raw queues. */
    RAW("Сырые байты принтера", "bin", "application/octet-stream"),

    /** The document text as it was read, for text and CSV sources. */
    TEXT("Текст документа", "txt", "text/plain")
}

/**
 * Writes a print job to a location the user picks.
 *
 * The destination comes from the Storage Access Framework (`CreateDocument`), so the user chooses
 * both folder and file name and the app needs no storage permission. Nothing is written into a
 * fixed folder on the user's behalf.
 */
object JobExporter {
    /** Dots per PDF pixel: one dot is one 1/203 inch, so two pixels keep the PDF readable. */
    const val DEFAULT_PIXELS_PER_DOT: Int = 2

    /** Formats that make sense for a document of this shape. */
    fun formatsFor(document: PrintableDocument): List<ExportFormat> = when {
        document.isRaster -> listOf(ExportFormat.PDF, ExportFormat.RAW)
        document.text != null -> listOf(ExportFormat.RAW, ExportFormat.TEXT)
        else -> listOf(ExportFormat.RAW)
    }

    /** Default file name for the save dialog, derived from the source and the format. */
    fun suggestedName(sourceName: String, format: ExportFormat): String {
        val base = sourceName.substringBeforeLast('.', sourceName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "printbridge" }
        return "$base.${format.extension}"
    }

    /** Writes [payload] to [target]. Throws [FilePrintException] with a readable message. */
    fun write(
        context: Context,
        target: Uri,
        document: PrintableDocument,
        format: ExportFormat,
        printerBytes: ByteArray,
        pixelsPerDot: Int = DEFAULT_PIXELS_PER_DOT
    ) {
        try {
            context.contentResolver.openOutputStream(target, "w")?.use { output ->
                when (format) {
                    ExportFormat.RAW -> output.write(printerBytes)
                    ExportFormat.TEXT -> output.write((document.text ?: "").toByteArray(Charsets.UTF_8))
                    ExportFormat.PDF -> writePdf(output, document.pages, pixelsPerDot)
                }
            } ?: throw FilePrintException("Не удалось открыть выбранный файл для записи")
        } catch (error: FilePrintException) {
            throw error
        } catch (error: Exception) {
            throw FilePrintException("Не удалось сохранить файл: ${error.message ?: "неизвестная ошибка"}", error)
        }
    }

    /** Renders [pages] into a one-page-per-sheet PDF. */
    internal fun writePdf(output: OutputStream, pages: List<MonoBitmap>, pixelsPerDot: Int) {
        require(pages.isNotEmpty()) { "A document without pages cannot be exported as PDF" }
        val pdf = PdfDocument()
        try {
            pages.forEach { page ->
                val bitmap = toBitmap(page, pixelsPerDot)
                try {
                    val info = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
                    val pdfPage = pdf.startPage(info)
                    try {
                        pdfPage.canvas.drawColor(Color.WHITE)
                        pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    } finally {
                        pdf.finishPage(pdfPage)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            pdf.writeTo(output)
        } finally {
            pdf.close()
        }
    }

    /**
     * Page geometry for [pages]: the pixel size of every sheet at [pixelsPerDot].
     *
     * Split out from [writePdf] because `android.graphics.pdf.PdfDocument` has no Robolectric
     * shadow, so the deterministic part — one page per sheet at the right size — is what unit tests
     * can assert; the actual PDF bytes are verified on a device.
     */
    internal fun pageGeometry(pages: List<MonoBitmap>, pixelsPerDot: Int = DEFAULT_PIXELS_PER_DOT): List<Pair<Int, Int>> {
        val scale = pixelsPerDot.coerceIn(1, 8)
        return pages.map { (it.width * scale).coerceAtLeast(1) to (it.height * scale).coerceAtLeast(1) }
    }

    /** One black pixel per printed dot, scaled up so the PDF stays legible at print size. */
    internal fun toBitmap(page: MonoBitmap, pixelsPerDot: Int = DEFAULT_PIXELS_PER_DOT): Bitmap {
        val scale = pixelsPerDot.coerceIn(1, 8)
        val width = (page.width * scale).coerceAtLeast(1)
        val height = (page.height * scale).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { Color.WHITE }
        for (y in 0 until page.height) {
            for (x in 0 until page.width) {
                if (!page[x, y]) continue
                for (dy in 0 until scale) {
                    val row = (y * scale + dy) * width
                    for (dx in 0 until scale) {
                        pixels[row + x * scale + dx] = Color.BLACK
                    }
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
