package com.printbridge.app

import android.net.Uri

/**
 * A document that another screen, or another application, wants printed.
 *
 * Only one of [uri] and [text] is set. Bytes are never carried here: a shared PDF can be large, so
 * the read happens in [FilePrintPipeline] under its size limit.
 */
data class SharedPrintPayload(
    val name: String,
    val mimeType: String?,
    val uri: Uri? = null,
    val text: String? = null
)
