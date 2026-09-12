package com.printbridge.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.printbridge.core.DefaultProfiles
import com.printbridge.core.PrinterProtocol
import com.printbridge.core.RasterOptions
import com.printbridge.drivers.WatermarkComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.charset.Charset

@RunWith(RobolectricTestRunner::class)
class FilePrintTests {

    // ---------- entitlement ----------

    // The `paid` build enforces the free tier; the `full` test build unlocks everything. Both
    // policies are asserted explicitly so the same suite is meaningful in either flavour.

    @Test
    fun enforcingBuildWatermarksUntilALicenceIsGranted() {
        val store = LicenseStore(RuntimeEnvironment.getApplication())
        val profile = DefaultProfiles.all.first()

        assertFalse("A fresh install is the free version", store.isLicensedFor(enforced = true))
        assertEquals(WatermarkComposer.DEFAULT_TEXT, store.watermarkTextFor(profile, enforced = true))

        store.grantLicense()
        assertTrue(store.isLicensedFor(enforced = true))
        assertNull("A licensed build must not stamp anything", store.watermarkTextFor(profile, enforced = true))

        store.revokeLicense()
        assertFalse(store.isLicensedFor(enforced = true))
        assertEquals(WatermarkComposer.DEFAULT_TEXT, store.watermarkTextFor(profile, enforced = true))
    }

    @Test
    fun fullTestBuildIsUnlockedWithoutALicence() {
        val store = LicenseStore(RuntimeEnvironment.getApplication())
        val profile = DefaultProfiles.all.first()
        store.revokeLicense()

        assertTrue("The full build always behaves as licensed", store.isLicensedFor(enforced = false))
        assertNull("No watermark in the full build", store.watermarkTextFor(profile, enforced = false))
        assertEquals(Int.MAX_VALUE, store.maxSheetsPerJobFor(enforced = false))
        assertEquals(listOf(0, 1, 2), store.allowedPages(pagedDocument(3), enforced = false))
        assertEquals(
            LicenseStore.MAX_SELECTABLE_COPIES,
            store.allowedCopies(LicenseStore.MAX_SELECTABLE_COPIES, enforced = false)
        )
    }

    @Test
    fun compiledFlavourMatchesItsBuildConfigFlag() {
        val store = LicenseStore(RuntimeEnvironment.getApplication())

        assertEquals(
            "isLicensed() must follow BuildConfig.FREE_TIER_ENFORCED",
            !BuildConfig.FREE_TIER_ENFORCED,
            store.isLicensed()
        )
    }

    @Test
    fun watermarkUsesTheProfileSpecificTextWhenSet() {
        val store = LicenseStore(RuntimeEnvironment.getApplication())
        val profile = DefaultProfiles.all.first().copy(watermarkText = "ПРОБНАЯ ВЕРСИЯ")

        assertEquals("ПРОБНАЯ ВЕРСИЯ", store.watermarkTextFor(profile, enforced = true))
    }

    // ---------- sheet limit ----------

    private fun pagedDocument(pageCount: Int): PrintableDocument {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val page = FilePrintPipeline.toPrintable(bitmap)
        bitmap.recycle()
        return PrintableDocument(sourceName = "doc.pdf", pages = List(pageCount) { page })
    }

    @Test
    fun freeVersionPrintsOnlyTheFirstSheetOfADocument() {
        val store = LicenseStore(RuntimeEnvironment.getApplication())
        val document = pagedDocument(5)

        assertEquals(listOf(0), store.allowedPages(document, enforced = true))
        assertTrue(store.isPageLimitReached(document, enforced = true))

        store.grantLicense()
        assertEquals(listOf(0, 1, 2, 3, 4), store.allowedPages(document, enforced = true))
        assertFalse(store.isPageLimitReached(document, enforced = true))
    }

    @Test
    fun singlePageDocumentIsNotLimited() {
        val store = LicenseStore(RuntimeEnvironment.getApplication())

        assertEquals(listOf(0), store.allowedPages(pagedDocument(1), enforced = true))
        assertFalse(store.isPageLimitReached(pagedDocument(1), enforced = true))
    }

    @Test
    fun documentWithoutPagesAllowsNothing() {
        val store = LicenseStore(RuntimeEnvironment.getApplication())

        assertTrue(store.allowedPages(PrintableDocument(sourceName = "empty", text = ""), enforced = true).isEmpty())
    }

    @Test
    fun freeVersionPrintsOneCopyAndPaidVersionPrintsTheRequestedAmount() {
        val store = LicenseStore(RuntimeEnvironment.getApplication())

        assertEquals(1, store.allowedCopies(5, enforced = true))
        assertEquals(1, store.maxSheetsPerJobFor(enforced = true))

        store.grantLicense()
        assertEquals(5, store.allowedCopies(5, enforced = true))
        assertEquals(
            "The selector cap applies even when licensed",
            LicenseStore.MAX_SELECTABLE_COPIES,
            store.allowedCopies(999, enforced = true)
        )
        assertEquals("At least one sheet is always printed", 1, store.allowedCopies(0, enforced = true))
    }

    // ---------- document selection ----------

    @Test
    fun sourceIsChosenFromMimeTypeOrFileName() {
        assertEquals(DocumentSource.IMAGE, FilePrintPipeline.sourceFor("image/png", null))
        assertEquals(DocumentSource.PDF, FilePrintPipeline.sourceFor("application/pdf", null))
        assertEquals(DocumentSource.TEXT, FilePrintPipeline.sourceFor("text/csv", null))
        assertEquals(DocumentSource.IMAGE, FilePrintPipeline.sourceFor(null, "photo.JPEG"))
        assertEquals(DocumentSource.PDF, FilePrintPipeline.sourceFor(null, "label.pdf"))
        assertEquals(DocumentSource.TEXT, FilePrintPipeline.sourceFor(null, "report.csv"))
        assertNull(FilePrintPipeline.sourceFor("application/zip", "archive.zip"))
    }

    // ---------- text encoding ----------

    @Test
    fun utf8AndLegacyCyrillicAreDecodedCorrectly() {
        val utf8 = "Привет, мир".toByteArray(Charsets.UTF_8)
        assertEquals("Привет, мир", TextEncoding.decode(utf8))

        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8
        assertEquals("Привет, мир", TextEncoding.decode(bom))

        val legacy = "Привет, мир".toByteArray(Charset.forName("windows-1251"))
        assertEquals("Windows-1251 must be the fallback", "Привет, мир", TextEncoding.decode(legacy))

        assertEquals("", TextEncoding.decode(ByteArray(0)))
    }

    @Test
    fun printerEncodingFollowsTheProtocol() {
        val escPos = DefaultProfiles.all.first { it.protocol == PrinterProtocol.ESC_POS }
        val tspl = DefaultProfiles.all.first { it.protocol == PrinterProtocol.TSPL }

        assertEquals(TextEncoding.UTF8, TextEncoding.forPrinter(escPos))
        assertEquals(TextEncoding.WINDOWS_1251, TextEncoding.forPrinter(tspl))
    }

    // ---------- image rasterisation ----------

    @Test
    fun sampleSizeNeverShrinksBelowTheTargetWidth() {
        assertEquals(1, FilePrintPipeline.calculateSampleSize(384, 384))
        assertEquals(1, FilePrintPipeline.calculateSampleSize(400, 384))
        assertEquals(2, FilePrintPipeline.calculateSampleSize(800, 384))
        assertEquals(4, FilePrintPipeline.calculateSampleSize(1600, 384))
        listOf(400, 800, 1600, 4032).forEach { source ->
            val sample = FilePrintPipeline.calculateSampleSize(source, 384)
            assertTrue("source=$source sample=$sample", source / sample >= 384)
        }
    }

    @Test
    fun imageIsScaledToPrinterWidthAndReducedToOneBit() {
        val bitmap = blackLeftHalf(200, 100)

        val mono = FilePrintPipeline.toPrintable(bitmap, RasterOptions(targetWidthDots = 100))

        assertEquals("Output must be scaled to the printer width", 100, mono.width)
        assertEquals("Aspect ratio must be preserved", 50, mono.height)
        assertTrue("Left edge must be black", mono[2, 25])
        assertFalse("Right edge must stay white", mono[97, 25])
        bitmap.recycle()
    }

    @Test
    fun fullyWhiteImageProducesNoBlackDots() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        val mono = FilePrintPipeline.toPrintable(bitmap)

        assertEquals(0, mono.pixels.count { it })
        bitmap.recycle()
    }

    @Test
    fun transparentPixelsAreTreatedAsPaperNotInk() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)

        val mono = FilePrintPipeline.toPrintable(bitmap)

        assertEquals("Transparency must not print as a black block", 0, mono.pixels.count { it })
        bitmap.recycle()
    }

    // ---------- document to printer bytes ----------

    @Test
    fun rasterPageIsSentThroughEveryProtocol() {
        val bitmap = blackLeftHalf(200, 100)
        val page = FilePrintPipeline.toPrintable(bitmap, RasterOptions(targetWidthDots = 100))
        bitmap.recycle()
        val document = PrintableDocument(sourceName = "test.png", pages = listOf(page))
        val profiles = listOf(
            DefaultProfiles.all.first { it.protocol == PrinterProtocol.ESC_POS },
            DefaultProfiles.all.first { it.protocol == PrinterProtocol.TSPL },
            DefaultProfiles.all.first { it.protocol == PrinterProtocol.GOOJPRT_LABEL }
        )

        profiles.forEach { profile ->
            val bytes = composePrintableDocument(profile, document, watermarkText = null)
            val marker = "${profile.protocol} must produce raster bytes"
            assertTrue(marker, bytes.size > 32)
        }
    }

    @Test
    fun freeBuildWatermarksFileJobsToo() {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val page = FilePrintPipeline.toPrintable(bitmap, RasterOptions(targetWidthDots = 64))
        bitmap.recycle()
        val document = PrintableDocument(sourceName = "test.png", pages = listOf(page))
        val profile = DefaultProfiles.all.first { it.protocol == PrinterProtocol.ESC_POS }

        val licensed = composePrintableDocument(profile, document, watermarkText = null)
        val free = composePrintableDocument(profile, document, watermarkText = WatermarkComposer.DEFAULT_TEXT)

        assertTrue("The free build must add the mark", free.size > licensed.size)
        assertTrue(free.toString(Charsets.UTF_8).contains(WatermarkComposer.DEFAULT_TEXT))
    }

    @Test
    fun textDocumentIsWrappedAndPrinted() {
        val document = PrintableDocument(sourceName = "report.csv", text = "col1,col2\nПривет,мир")
        val escPos = DefaultProfiles.all.first { it.protocol == PrinterProtocol.ESC_POS }

        val bytes = composePrintableDocument(escPos, document, watermarkText = null)

        assertTrue(bytes.isNotEmpty())
        assertTrue(bytes.toString(Charsets.UTF_8).contains("col1,col2"))
    }

    @Test
    fun readImageFromContentUriProducesAPage() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = blackLeftHalf(120, 60)
        val file = File(context.cacheDir, "sample.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val document = FilePrintPipeline.readImage(context, Uri.fromFile(file), DefaultProfiles.all.first(), "sample.png")

        assertEquals(1, document.pages.size)
        assertEquals("sample.png", document.sourceName)
        assertTrue("Something must be black after thresholding", document.pages.first().pixels.any { it })
    }

    @Test
    fun unreadableUriFailsWithAUserFacingMessage() {
        val context = RuntimeEnvironment.getApplication()
        val missing = Uri.fromFile(File(context.cacheDir, "does-not-exist.png"))

        val error = runCatching {
            FilePrintPipeline.readImage(context, missing, DefaultProfiles.all.first(), "missing.png")
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue("unexpected error: $error", error is FilePrintException)
    }

    private fun blackLeftHalf(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (x in 0 until width / 2) for (y in 0 until height) bitmap.setPixel(x, y, Color.BLACK)
        return bitmap
    }
}
