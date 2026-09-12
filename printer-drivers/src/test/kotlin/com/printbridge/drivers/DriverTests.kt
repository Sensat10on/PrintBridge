package com.printbridge.drivers

import com.printbridge.core.DefaultProfiles
import java.nio.charset.Charset
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriverTests {
    @Test fun escPosInitializationIsEscAt() {
        val bytes = EscPosDriver().testPage(DefaultProfiles.all[0])
        assertContentEquals(byteArrayOf(0x1b, 0x40), bytes.take(2).toByteArray())
    }

    @Test fun escPosContainsQrRasterAndText() {
        val bytes = EscPosDriver().testPage(DefaultProfiles.all[0])
        assertTrue(bytes.toString(Charsets.UTF_8).contains("PRINTBRIDGE"))
        assertTrue(bytes.containsSequence(0x1b, 0x40))
        assertTrue(bytes.containsSequence(0x1b, 0x61, 0x01, 0x1b, 0x45, 0x01, 0x1d, 0x21, 0x11))
        assertTrue(bytes.containsSequence(*"PRINTBRIDGE\n".toByteArray(Charsets.UTF_8).map { it.toInt() }.toIntArray()))
        assertTrue(bytes.containsSequence(0x1d, 0x28, 0x6b))
        assertTrue(bytes.containsSequence(0x1d, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x43, 0x06))
        assertTrue(bytes.containsSequence(0x1d, 0x76, 0x30, 0x00, 0x08, 0x00, 0x20, 0x00))
        assertTrue(bytes.containsSequence(0x1b, 0x64, 0x03))
    }

    @Test fun escPosCompleteReceiptMatchesGoldenSha256() {
        val bytes = EscPosDriver().testPage(DefaultProfiles.all[0])
        assertEquals("744603abecd0fcd21cbd6fa8a3bc625e473fe13d1be113a865b56144a4410ce6", bytes.sha256())
    }

    @Test fun tsplContainsIndependentGoldenCommands() {
        val bytes = TsplDriver().testPage(DefaultProfiles.all[2])
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("SIZE 100 mm,150 mm"))
        assertTrue(text.contains("GAP 3 mm,0 mm"))
        assertTrue(text.contains("""TEXT 80,60,"0",0,2,2,"PRINTBRIDGE TEST""""))
        assertTrue(text.contains("""BARCODE 80,340,"128",90,1,0,2,2,"PB000001""""))
        assertTrue(text.contains("""QRCODE 80,540,L,5,A,0,"PrintBridge Test""""))
        assertTrue(text.contains("PRINT 1"))
    }

    @Test fun tsplCompleteLabelMatchesGoldenSha256() {
        val bytes = TsplDriver().testPage(DefaultProfiles.all[2])
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("SIZE 100 mm,150 mm\nGAP 3 mm,0 mm\nDENSITY 8\nSPEED 4\nCLS\n"))
        assertTrue(text.contains("BITMAP 600,960,8,64,0,\n"))
        assertEquals("215b74b1440bd2141a14792e001d6df339a8d6742c80759b0e1e50d6274dbf03", bytes.sha256())
    }

    @Test fun goojprtLabelContainsSdkCommandFamilies() {
        val bytes = GoojprtLabelDriver().testPage(DefaultProfiles.all[4])
        assertTrue(bytes.containsSequence(0x1a, 0x5b, 0x01))
        assertTrue(bytes.containsSequence(0x1a, 0x54, 0x01))
        assertTrue(bytes.containsSequence(0x1a, 0x5c, 0x01))
        assertTrue(bytes.containsSequence(0x1a, 0x26, 0x01))
        assertTrue(bytes.containsSequence(0x1a, 0x30, 0x00))
        assertTrue(bytes.containsSequence(0x1a, 0x31, 0x00))
        assertTrue(bytes.containsSequence(0x1a, 0x21, 0x01))
        assertTrue(bytes.containsSequence(0x1a, 0x5d, 0x00))
        assertTrue(bytes.containsSequence(0x1a, 0x4f, 0x01, 0x01))
    }

    @Test fun tsplNonAsciiTextIsEncodedWithTheRequestedCodePageInsteadOfBeingReplaced() {
        val latin = TsplDriver().build(DefaultProfiles.all[2]) { text(80, 60, "Größe Ærø") }
        assertTrue(
            latin.toString(Charsets.ISO_8859_1).contains("Größe Ærø"),
            "Latin-1 characters must round-trip through the default TSPL code page"
        )
        assertFalse(latin.toString(Charsets.ISO_8859_1).contains("?"), "No character may degrade to '?'")

        // Consumer label printers are single-code-page; the caller picks the page for Cyrillic.
        val cyrillic = TsplDriver().build(DefaultProfiles.all[2], charset = Charset.forName("windows-1251")) {
            text(80, 60, "Этикетка")
        }
        assertTrue(cyrillic.toString(Charset.forName("windows-1251")).contains("Этикетка"))
    }

    @Test fun tsplPayloadCannotInjectCommandsOrBreakStringLiterals() {
        val bytes = TsplDriver().build(DefaultProfiles.all[2]) {
            text(80, 60, "bad\"\nPRINT 99\n\"value")
            qrcode(80, 100, "plain-backslash")
        }
        val commandLines = bytes.toString(Charsets.ISO_8859_1).split('\n').filter { it.isNotBlank() }

        // The injected text stays inside one literal: newlines became spaces, so "PRINT 99"
        // can no longer start its own command line.
        assertEquals(2, commandLines.size, "A payload must not add TSPL lines")
        assertTrue(commandLines.none { it.startsWith("PRINT 99") }, "Injected command must not become a TSPL line")
        assertEquals("TEXT 80,60,\"0\",0,1,1,\"bad\\\" PRINT 99 \\\"value\"", commandLines[0])
        assertEquals("QRCODE 80,100,L,5,A,0,\"plain-backslash\"", commandLines[1])
    }

    @Test fun tsplControlCharactersAndLongPayloadsAreBounded() {
        assertEquals("a b c d", TsplText.escape("a\u0000b\tc\rd"))
        assertEquals("say \\\"hi\\\"", TsplText.escape("say \"hi\""))
        assertTrue(TsplText.escape("x".repeat(10_000)).count { it.isISOControl() } == 0)
        assertTrue(TsplText.escape("y".repeat(10_000)).length <= TsplText.MAX_LINE_CHARS + 8)
    }

    @Test fun goojprtCompleteLabelMatchesGoldenSha256() {
        val bytes = GoojprtLabelDriver().testPage(DefaultProfiles.all[4])
        assertEquals("1e42e9eb8907ac2339d22ddc7ead68c226be7383d445da65fe716b4cb7f2c7ca", bytes.sha256())
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private fun ByteArray.containsSequence(vararg values: Int): Boolean {
    val needle = values.map { it.toByte() }.toByteArray()
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return true
    }
    return false
}
