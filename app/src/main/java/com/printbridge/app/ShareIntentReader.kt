package com.printbridge.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log

/**
 * A document handed to PrintBridge by another application.
 *
 * Only the URI is carried, never the bytes: a shared PDF can be tens of megabytes and the read
 * has to happen under the existing size limit in [FilePrintPipeline] instead of in the intent
 * handler.
 */
data class SharedDocument(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val fromText: Boolean = false
) {
    val isText: Boolean get() = fromText
}

/**
 * Reads `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intents.
 *
 * The app accepts any MIME type, so this is the entry point for "print this" from a gallery, a file
 * manager, Chrome or a chat application. When several files are shared only the first is taken,
 * which is reported to the user rather than silently dropping the rest.
 */
object ShareIntentReader {
    private const val TAG = "PBShare"

    /** Extras this app understands; anything else is ignored rather than guessed at. */
    fun read(context: Context, intent: Intent?): SharedDocument? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null

        val uris = streamUris(intent)
        if (uris.isNotEmpty()) {
            if (uris.size > 1) {
                Log.i(TAG, "Received ${uris.size} files, printing the first one")
            }
            val uri = uris.first()
            return SharedDocument(
                uri = uri,
                displayName = displayName(context, uri) ?: uri.lastPathSegment ?: "shared-file",
                mimeType = intent.type ?: context.contentResolver.getType(uri)
            )
        }

        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank()) {
            return SharedDocument(
                uri = Uri.EMPTY,
                displayName = "shared-text.txt",
                mimeType = "text/plain",
                fromText = true
            )
        }
        return null
    }

    /** Text shared alongside an image is ignored: the attachment wins. */
    fun sharedText(intent: Intent?): String? {
        if (intent == null) return null
        if (streamUris(intent).isNotEmpty()) return null
        return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    }

    private fun streamUris(intent: Intent): List<Uri> {
        val single = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
        if (single != null) return listOf(single)

        val many = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        return many.orEmpty()
    }

    /** Display name from the content provider, so the UI shows a real file name. */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
