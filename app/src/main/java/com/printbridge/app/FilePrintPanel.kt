package com.printbridge.app

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.printbridge.core.PrintJob
import com.printbridge.core.PrintQueue
import com.printbridge.core.PrinterProfile
import com.printbridge.core.PrintBridgeError
import com.printbridge.core.PrinterTransport
import com.printbridge.bluetooth.BluetoothSppPrinterTransport
import com.printbridge.transport.FakePrinterTransport
import com.printbridge.transport.TcpPrinterTransport
import com.printbridge.usb.UsbPrinterTransport
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.Context
import kotlinx.coroutines.launch

/**
 * Prints the contents of a file chosen by the user instead of the built-in templates.
 *
 * Supported inputs: images (PNG/JPEG/WebP/BMP/GIF), PDF (every page) and plain text/CSV.
 * The transport is the one selected in the profile, so this panel reuses the same queued,
 * watermark-aware path as the ready-job screen.
 */
@Composable
internal fun FilePrintPanel(
    profile: PrinterProfile,
    licenseStore: LicenseStore,
    requestUsbPermission: (UsbDevice, (Boolean) -> Unit) -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<PrintableDocument?>(null) }
    var selectedPage by remember { mutableStateOf(0) }
    var pickedName by remember { mutableStateOf<String?>(null) }

    fun load(uri: Uri, declaredMime: String?) {
        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "файл"
        pickedName = name
        document = null
        selectedPage = 0
        onStatus("Файл: $name — читаю...")
        // The picker does not report the MIME type back reliably, so fall back to the file name.
        runCatching {
            when (FilePrintPipeline.sourceFor(declaredMime, name) ?: FilePrintPipeline.sourceFor(context.contentResolver.getType(uri), name)) {
                DocumentSource.IMAGE -> FilePrintPipeline.readImage(context, uri, profile, name)
                DocumentSource.PDF -> FilePrintPipeline.readPdf(context, uri, profile, name)
                DocumentSource.TEXT -> FilePrintPipeline.readText(context, uri, name)
                null -> throw FilePrintException("Тип файла не поддерживается. Нужны изображение, PDF или текст.")
            }
        }.onSuccess { loaded ->
            document = loaded
            onStatus(describe(loaded))
        }.onFailure { error ->
            onStatus(error.message ?: "Не удалось прочитать файл")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri, null)
    }

    Text("ПЕЧАТЬ ФАЙЛА", style = MaterialTheme.typography.titleMedium)
    Text("Изображение, PDF или текстовый файл. Отправится через выбранный способ печати.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
            Text("ИЗОБРАЖЕНИЕ")
        }
        Button(onClick = { picker.launch(arrayOf("application/pdf")) }, modifier = Modifier.weight(1f)) {
            Text("PDF")
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { picker.launch(arrayOf("text/*")) }, modifier = Modifier.weight(1f)) {
            Text("ТЕКСТ")
        }
        Button(onClick = { picker.launch(PICKER_MIME_TYPES) }, modifier = Modifier.weight(1f)) {
            Text("ЛЮБОЙ ФАЙЛ")
        }
    }

    val loaded = document
    if (loaded != null) {
        Text("$pickedName — ${describe(loaded)}")
        if (loaded.pages.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { selectedPage = (selectedPage - 1).coerceAtLeast(0) },
                    enabled = selectedPage > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("← СТРАНИЦА") }
                Button(
                    onClick = { selectedPage = (selectedPage + 1).coerceAtMost(loaded.pages.size - 1) },
                    enabled = selectedPage < loaded.pages.size - 1,
                    modifier = Modifier.weight(1f)
                ) { Text("СТРАНИЦА →") }
            }
            Text("Страница ${selectedPage + 1} из ${loaded.pages.size}")
        }
    }

    Button(
        onClick = {
            val current = document
            if (current == null) {
                onStatus("Сначала выберите файл")
                return@Button
            }
            val payload = composePrintableDocument(
                profile = profile,
                document = current,
                watermarkText = licenseStore.watermarkTextFor(profile),
                page = current.pages.getOrNull(selectedPage)
            )
            if (payload.isEmpty()) {
                onStatus("Нечего печатать: профиль ${profile.protocol} не поддерживает этот тип документа")
                return@Button
            }
            scope.launch {
                onStatus("Файл: печать ${current.sourceName}, ${payload.size} байт, ${profile.transportType}")
                val result = printViaProfile(context, profile, payload, requestUsbPermission)
                onStatus("Файл: ${result.state}${result.lastError?.let { ": $it" } ?: ""}")
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("ПЕЧАТАТЬ ФАЙЛ") }

    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        val loadedDocument = document
        Text(
            when {
                loadedDocument == null && pickedName == null -> "Файл не выбран"
                loadedDocument == null -> "Файл $pickedName не прочитан"
                else -> "$pickedName\n${describe(loadedDocument)}"
            },
            modifier = Modifier.padding(12.dp)
        )
    }
}

private val PICKER_MIME_TYPES = arrayOf("image/*", "application/pdf", "text/*", "text/csv")

private fun describe(document: PrintableDocument): String = when {
    document.isRaster -> "готово: ${document.pages.size} стр., ${document.pages.first().width}x${document.pages.first().height} точек"
    document.text != null -> "готово: ${document.text!!.length} символов"
    else -> "пусто"
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

/** Sends [payload] through whichever transport the profile selects. */
@Suppress("UNUSED_PARAMETER")
private suspend fun printViaProfile(
    context: Context,
    profile: PrinterProfile,
    payload: ByteArray,
    requestUsbPermission: (UsbDevice, (Boolean) -> Unit) -> Unit
): PrintJob = when (profile.transportType) {
    com.printbridge.core.TransportType.FAKE ->
        PrintQueue(FakePrinterTransport("file-print")).enqueue(
            PrintJob(source = "Файл", printerProfileId = profile.id), profile, payload
        )

    com.printbridge.core.TransportType.TCP -> {
        val host = profile.networkHost?.trim().orEmpty()
        if (host.isBlank()) {
            failedJob(profile, PrintBridgeError.InvalidPrinterProfile("В профиле не задан TCP host"))
        } else {
            PrintQueue(
                TcpPrinterTransport(
                    host,
                    profile.networkPort ?: DEFAULT_NETWORK_PORT,
                    profile.networkConnectTimeoutMs ?: DEFAULT_NETWORK_CONNECT_TIMEOUT_MS,
                    profile.networkWriteTimeoutMs ?: DEFAULT_NETWORK_WRITE_TIMEOUT_MS
                )
            ).enqueue(PrintJob(source = "Файл TCP", printerProfileId = profile.id), profile, payload)
        }
    }

    com.printbridge.core.TransportType.BLUETOOTH_SPP -> {
        val address = profile.bluetoothDeviceAddress
        if (address.isNullOrBlank()) {
            failedJob(profile, PrintBridgeError.BluetoothDeviceNotFound("не выбран в профиле"))
        } else {
            PrintQueue(
                BluetoothSppPrinterTransport(
                    context,
                    address,
                    BluetoothSppPrinterTransport.DEFAULT_SERVICE_UUIDS,
                    connectTimeoutMs = DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS,
                    writeTimeoutMs = profile.writeTimeoutMs ?: DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS
                )
            ).enqueue(PrintJob(source = "Файл Bluetooth", printerProfileId = profile.id), profile, payload)
        }
    }

    com.printbridge.core.TransportType.USB -> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.findDevice(profile)
        when {
            device == null -> failedJob(profile, PrintBridgeError.InvalidPrinterProfile("USB-принтер не выбран в профиле"))
            !manager.hasPermission(device) -> failedJob(profile, PrintBridgeError.InvalidPrinterProfile("Нет разрешения USB"))
            else -> PrintQueue(
                UsbPrinterTransport(manager, device, (profile.writeTimeoutMs ?: DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS).toInt())
            ).enqueue(PrintJob(source = "Файл USB", printerProfileId = profile.id), profile, payload)
        }
    }

    com.printbridge.core.TransportType.BLUETOOTH_BLE ->
        failedJob(profile, PrintBridgeError.UnsupportedProtocol(profile.protocol))
}

private fun failedJob(profile: PrinterProfile, error: PrintBridgeError): PrintJob =
    PrintJob(
        source = "Файл",
        printerProfileId = profile.id,
        state = com.printbridge.core.PrintJobState.FAILED,
        lastError = error.message,
        domainError = error
    )
