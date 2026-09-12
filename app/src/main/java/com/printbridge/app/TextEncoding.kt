package com.printbridge.app

import java.nio.charset.Charset

/**
 * Which byte encoding user documents are written in.
 *
 * Thermal printers expect one code page per job, and files in the wild are not always UTF-8.
 * Detection is intentionally simple and predictable: a BOM wins, otherwise strict UTF-8
 * decoding is attempted and a failure falls back to Windows-1251, which is the common legacy
 * encoding for Russian text.
 */
enum class TextEncoding(val displayName: String, val charset: Charset) {
    UTF8("UTF-8", Charsets.UTF_8),
    WINDOWS_1251("Windows-1251", Charset.forName("windows-1251"));

    companion object {
        private val utf8Strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)

        /** Encoding used by the printer; the driver receives these bytes and must not re-encode. */
        fun forPrinter(profile: com.printbridge.core.PrinterProfile): TextEncoding =
            if (profile.protocol == com.printbridge.core.PrinterProtocol.TSPL) WINDOWS_1251 else UTF8

        fun decode(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
            return try {
                // Strict validation first: a successful decode means the bytes really are UTF-8.
                utf8Strict.decode(java.nio.ByteBuffer.wrap(bytes))
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                String(bytes, WINDOWS_1251.charset)
            }
        }
    }
}
