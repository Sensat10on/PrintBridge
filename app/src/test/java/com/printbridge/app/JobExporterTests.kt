package com.printbridge.app

import android.graphics.Color
import com.printbridge.core.MonoBitmap
import com.printbridge.core.DefaultProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class JobExporterTests {

    private fun page(width: Int, height: Int, block: (Int, Int) -> Boolean): MonoBitmap =
        MonoBitmap(width, height, BooleanArray(width * height) { index -> block(index % width, index / width) })

    private fun rasterDocument(pages: Int = 1) = PrintableDocument(
        sourceName = "receipt.png",
        pages = List(pages) { page(4, 2) { x, _ -> x == 0 } }
    )

    // ---------- formats ----------

    @Test
    fun formatsFollowTheDocumentShape() {
        assertEquals(
            listOf(ExportFormat.PDF, ExportFormat.RAW),
            JobExporter.formatsFor(rasterDocument())
        )
        assertEquals(
            listOf(ExportFormat.RAW, ExportFormat.TEXT),
            JobExporter.formatsFor(PrintableDocument(sourceName = "notes.txt", text = "hello"))
        )
    }

    @Test
    fun suggestedNameKeepsTheStemAndSwapsTheExtension() {
        assertEquals("receipt.pdf", JobExporter.suggestedName("receipt.png", ExportFormat.PDF))
        assertEquals("report.bin", JobExporter.suggestedName("report.csv", ExportFormat.RAW))
        assertEquals("no-extension.txt", JobExporter.suggestedName("no-extension", ExportFormat.TEXT))
    }

    @Test
    fun suggestedNameSanitisesUnsafeCharacters() {
        val name = JobExporter.suggestedName("этикетка №1 (копия).png", ExportFormat.PDF)

        assertTrue("unexpected name: $name", name.matches(Regex("[A-Za-z0-9._-]+")))
        assertTrue(name.endsWith(".pdf"))
    }

    @Test
    fun suggestedNameFallsBackForAnEmptySource() {
        assertEquals("printbridge.pdf", JobExporter.suggestedName("", ExportFormat.PDF))
    }

    // ---------- raster to bitmap ----------

    @Test
    fun bitmapScalesEachDotIntoASquareBlock() {
        val source = page(2, 2) { x, y -> x == 0 && y == 0 }

        val bitmap = JobExporter.toBitmap(source, pixelsPerDot = 3)

        assertEquals(6, bitmap.width)
        assertEquals(6, bitmap.height)
        // The single black dot becomes a 3x3 block in the top-left corner.
        assertEquals(Color.BLACK, bitmap.getPixel(0, 0))
        assertEquals(Color.BLACK, bitmap.getPixel(2, 2))
        assertEquals(Color.WHITE, bitmap.getPixel(3, 0))
        assertEquals(Color.WHITE, bitmap.getPixel(0, 3))
        bitmap.recycle()
    }

    @Test
    fun bitmapWithoutBlackDotsIsEntirelyWhite() {
        val bitmap = JobExporter.toBitmap(page(3, 3) { _, _ -> false }, pixelsPerDot = 2)

        assertEquals(0, (0 until bitmap.width).sumOf { x -> (0 until bitmap.height).count { y -> bitmap.getPixel(x, y) != Color.WHITE } })
        bitmap.recycle()
    }

    // ---------- PDF ----------

    @Test
    fun everySheetBecomesItsOwnPdfPageAtTheScaledSize() {
        val pages = listOf(page(4, 2) { _, _ -> true }, page(10, 5) { _, _ -> false })

        val geometry = JobExporter.pageGeometry(pages, pixelsPerDot = 2)

        assertEquals("one page per sheet", 2, geometry.size)
        assertEquals(8 to 4, geometry[0])
        assertEquals(20 to 10, geometry[1])
    }

    @Test
    fun pageScaleIsClampedToASaneRange() {
        val pages = listOf(page(3, 1) { _, _ -> true })

        assertEquals(listOf(3 to 1), JobExporter.pageGeometry(pages, pixelsPerDot = 0))
        assertEquals(listOf(24 to 8), JobExporter.pageGeometry(pages, pixelsPerDot = 99))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pdfExportRefusesADocumentWithoutPages() {
        JobExporter.writePdf(ByteArrayOutputStream(), emptyList(), pixelsPerDot = 1)
    }

    // ---------- raw and text payloads ----------

    @Test
    fun rawExportWritesThePrinterBytesUntouched() {
        val document = rasterDocument()
        val payload = byteArrayOf(0x1b, 0x40, 0x01, 0x02)

        val written = writeToTempFile(document, ExportFormat.RAW, payload)

        assertTrue(payload.contentEquals(written))
    }

    @Test
    fun textExportWritesTheDocumentText() {
        val document = PrintableDocument(sourceName = "notes.txt", text = "Привет, мир")

        val written = writeToTempFile(document, ExportFormat.TEXT, printerBytes = byteArrayOf(1, 2, 3))

        assertEquals("Привет, мир", String(written, Charsets.UTF_8))
    }

    private fun writeToTempFile(
        document: PrintableDocument,
        format: ExportFormat,
        printerBytes: ByteArray
    ): ByteArray {
        val context = RuntimeEnvironmentHolder.application
        val file = java.io.File(context.cacheDir, "export-${format.name.lowercase()}.out")
        file.delete()
        val uri = android.net.Uri.fromFile(file)

        JobExporter.write(context, uri, document, format, printerBytes)

        return file.readBytes()
    }

    // ---------- resume reporting ----------

    @Test
    fun partialResultsExposeWhatIsLeftToSend() {
        val completed = BatchPrintResult(sent = 3, attempted = 3, state = com.printbridge.core.PrintJobState.COMPLETED, error = null)
        val stopped = BatchPrintResult(
            sent = 2,
            attempted = 5,
            state = com.printbridge.core.PrintJobState.FAILED,
            error = "boom",
            sentBeforeFailure = 2,
            failedAt = 2
        )

        assertEquals(false, completed.isPartial)
        assertEquals(null, completed.resumeHint())

        assertEquals(true, stopped.isPartial)
        assertEquals(3, stopped.remaining)
        assertTrue(stopped.resumeHint()!!.contains("с 3-го"))
    }

    @Test
    fun aFailureOnTheLastSheetLeavesNothingToResume() {
        val stopped = BatchPrintResult(
            sent = 4,
            attempted = 5,
            state = com.printbridge.core.PrintJobState.FAILED,
            error = "boom",
            sentBeforeFailure = 4,
            failedAt = 4
        )

        assertEquals(true, stopped.isPartial)
        assertEquals(1, stopped.remaining)
    }

    @Test
    fun profilesUsedByTheExportTestsExist() {
        assertTrue(DefaultProfiles.all.isNotEmpty())
    }
}

/** Robolectric application handle, kept out of the test bodies for readability. */
private object RuntimeEnvironmentHolder {
    val application: android.content.Context get() = org.robolectric.RuntimeEnvironment.getApplication()
}
