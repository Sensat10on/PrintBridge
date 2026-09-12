package com.printbridge.app

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.printbridge.bluetooth.BluetoothSppPrinterTransport
import com.printbridge.core.PrintBridgeError
import com.printbridge.core.PrintJob
import com.printbridge.core.PrintJobState
import com.printbridge.core.PrintQueue
import com.printbridge.core.PrinterProfile
import com.printbridge.core.PrinterTransport
import com.printbridge.core.TransportType
import com.printbridge.transport.FakePrinterTransport
import com.printbridge.transport.TcpPrinterTransport
import com.printbridge.usb.UsbPrinterTransport
import kotlinx.coroutines.launch

/**
 * Prints the contents of a file chosen by the user instead of the built-in templates.
 *
 * Supported inputs: images (PNG/JPEG/WebP/BMP/GIF), PDF (page by page) and plain text/CSV. The
 * transport is the one selected in the profile, so this panel reuses the same queued,
 * watermark-aware path as the ready-job screen.
 *
 * Under the free entitlement only the first sheet of a job can be sent; the page selector is built
 * from [LicenseStore.allowedPages] so it never offers a page that the print command would refuse.
 */
@Composable
internal fun FilePrintPanel(
    profile: PrinterProfile,
    licenseStore: LicenseStore,
    shared: SharedPrintPayload? = null,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<PrintableDocument?>(null) }
    var selectedPages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var loadedFrom by remember { mutableStateOf<FileSource?>(null) }
    // Declared before the picker so the callback can reference it; rememberUpdatedState keeps the
    // lambda pointing at the current one instead of the first composition.
    var loadInto: (Uri, String?) -> Unit = { _, _ -> }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadInto(uri, null)
    }

    fun applyResult(result: Result<PrintableDocument>, name: String, source: FileSource) {
        result.onSuccess { loaded ->
            document = loaded
            selectedPages = licenseStore.allowedPages(loaded).toSet()
            loadedFrom = source
            pickedName = name
            onStatus(describe(loaded, licenseStore))
        }.onFailure { error ->
            document = null
            selectedPages = emptySet()
            loadedFrom = null
            pickedName = name
            onStatus(error.message ?: "Не удалось прочитать файл")
        }
    }

    loadInto = { uri, declaredMime ->
        val name = ShareIntentReader.displayName(context, uri) ?: uri.lastPathSegment ?: "файл"
        onStatus("Файл: $name — читаю...")
        // The picker does not report the MIME type back reliably, so fall back to the file name.
        val mime = declaredMime ?: context.contentResolver.getType(uri)
        applyResult(
            runCatching {
                when (FilePrintPipeline.sourceFor(mime, name)) {
                    DocumentSource.IMAGE -> FilePrintPipeline.readImage(context, uri, profile, name)
                    DocumentSource.PDF -> FilePrintPipeline.readPdf(context, uri, profile, name)
                    DocumentSource.TEXT -> FilePrintPipeline.readText(context, uri, name)
                    null -> throw FilePrintException("Тип файла не поддерживается. Нужны изображение, PDF или текст.")
                }
            },
            name,
            FileSource.Shared
        )
    }

    // A document handed over by another application is loaded as soon as the section is reached.
    LaunchedEffect(shared) {
        val payload = shared ?: return@LaunchedEffect
        val name = payload.name
        applyResult(
            runCatching {
                if (payload.text != null) {
                    if (payload.text.isBlank()) throw FilePrintException("Передан пустой текст")
                    PrintableDocument(sourceName = name, text = payload.text)
                } else {
                    val uri = payload.uri ?: throw FilePrintException("Не удалось получить файл")
                    when (FilePrintPipeline.sourceFor(payload.mimeType, name)) {
                        DocumentSource.IMAGE -> FilePrintPipeline.readImage(context, uri, profile, name)
                        DocumentSource.PDF -> FilePrintPipeline.readPdf(context, uri, profile, name)
                        DocumentSource.TEXT -> FilePrintPipeline.readText(context, uri, name)
                        null -> throw FilePrintException("Тип файла не поддерживается. Нужны изображение, PDF или текст.")
                    }
                }
            },
            name,
            FileSource.External
        )
    }

    // The section title lives in the screen picker above, so this heading describes the step.
    Text("ВЫБОР ФАЙЛА", style = MaterialTheme.typography.titleMedium)
    Text("Изображение, PDF или текстовый файл. Отправится через выбранный способ печати.")
    if (shared != null) {
        Text(
            "Получено из другого приложения: ${shared.name}",
            style = MaterialTheme.typography.bodySmall
        )
    }
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
        Text("$pickedName — ${describe(loaded, licenseStore)}")
        if (loaded.pages.size > 1) {
            val allowed = licenseStore.allowedPages(loaded).toSet()
            Text("Страницы для печати (${selectedPages.size} из ${loaded.pages.size}):")
            loaded.pages.indices.forEach { index ->
                val permitted = index in allowed
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Checkbox(
                        checked = index in selectedPages,
                        enabled = permitted,
                        onCheckedChange = { checked ->
                            selectedPages = if (checked) selectedPages + index else selectedPages - index
                        }
                    )
                    Text(
                        if (permitted) {
                            "Страница ${index + 1}"
                        } else {
                            "Страница ${index + 1} — только в платной версии"
                        }
                    )
                }
            }
            if (licenseStore.isPageLimitReached(loaded)) {
                Text(
                    "Бесплатная версия печатает один лист за задание. " +
                        "Остальные страницы станут доступны после покупки.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    Button(
        onClick = {
            val current = document
            if (current == null) {
                onStatus("Сначала выберите файл")
                return@Button
            }
            val pages = selectedPages.sorted().filter { it in current.pages.indices }
            if (pages.isEmpty()) {
                onStatus("Не выбрано ни одной страницы")
                return@Button
            }
            scope.launch {
                val watermark = licenseStore.watermarkTextFor(profile)
                val payloads = pages.mapNotNull { index ->
                    val bytes = composePrintableDocument(
                        profile = profile,
                        document = current,
                        watermarkText = watermark,
                        page = current.pages.getOrNull(index)
                    )
                    bytes.takeIf { it.isNotEmpty() }
                }
                if (payloads.isEmpty()) {
                    onStatus("Нечего печатать: профиль ${profile.protocol} не поддерживает этот тип документа")
                    return@launch
                }
                onStatus(
                    "Файл: печать ${current.sourceName}, листов ${payloads.size}, " +
                        "первый ${payloads.first().size} байт, ${profile.transportType}"
                )
                val transport = resolvePrintTransport(context, profile)
                if (transport == null) {
                    onStatus("Файл: ${describeTargetError(profile)}")
                    return@launch
                }
                val result = printSheets(
                    transport = transport,
                    profile = profile,
                    payloads = payloads,
                    sourceName = "Файл"
                )
                onStatus("Файл: ${result.describe()}")
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("ПЕЧАТАТЬ ФАЙЛ") }

    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Text(
            when {
                loaded == null && pickedName == null -> "Файл не выбран"
                loaded == null -> "Файл $pickedName не прочитан"
                else -> "$pickedName\n${describe(loaded, licenseStore)}"
            },
            modifier = Modifier.padding(12.dp)
        )
    }
}

private val PICKER_MIME_TYPES = arrayOf("image/*", "application/pdf", "text/*", "text/csv")

/** Why a transport could not be built, phrased for the status line. */
private fun describeTargetError(profile: PrinterProfile): String = when (profile.transportType) {
    TransportType.TCP -> "в профиле не задан TCP host"
    TransportType.BLUETOOTH_SPP -> "Bluetooth-устройство не выбрано в профиле"
    TransportType.USB -> "USB-принтер не выбран в профиле или нет разрешения"
    else -> "транспорт ${profile.transportType} не поддерживается"
}

/**
 * Builds the transport for the profile, or null when the profile is not ready to print.
 *
 * The device is resolved once per batch so every page of a document goes to the same printer.
 */
internal fun resolvePrintTransport(context: Context, profile: PrinterProfile): PrinterTransport? =
    when (profile.transportType) {
        TransportType.FAKE -> FakePrinterTransport("file-print")

        TransportType.TCP -> profile.networkHost?.trim()?.takeIf { it.isNotBlank() }?.let { host ->
            TcpPrinterTransport(
                host,
                profile.networkPort ?: DEFAULT_NETWORK_PORT,
                profile.networkConnectTimeoutMs ?: DEFAULT_NETWORK_CONNECT_TIMEOUT_MS,
                profile.networkWriteTimeoutMs ?: DEFAULT_NETWORK_WRITE_TIMEOUT_MS
            )
        }

        TransportType.BLUETOOTH_SPP -> profile.bluetoothDeviceAddress?.takeIf { it.isNotBlank() }?.let { address ->
            BluetoothSppPrinterTransport(
                context,
                address,
                BluetoothSppPrinterTransport.DEFAULT_SERVICE_UUIDS,
                connectTimeoutMs = DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS,
                writeTimeoutMs = profile.writeTimeoutMs ?: DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS
            )
        }

        TransportType.USB -> {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = manager.findDevice(profile)
            if (device == null || !manager.hasPermission(device)) {
                null
            } else {
                UsbPrinterTransport(manager, device, (profile.writeTimeoutMs ?: DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS).toInt())
            }
        }

        TransportType.BLUETOOTH_BLE -> null
    }

/** Where the loaded document came from, so the user knows what is about to be printed. */
internal enum class FileSource(val label: String) {
    Picked("выбран в приложении"),
    Shared("выбран в приложении"),
    External("получен из другого приложения")
}

private fun describe(document: PrintableDocument, licenseStore: LicenseStore): String = when {
    document.isRaster -> {
        val first = document.pages.first()
        val limit = if (licenseStore.isPageLimitReached(document)) {
            ", бесплатно доступна 1 из ${document.pages.size}"
        } else {
            ""
        }
        "готово: ${document.pages.size} стр., ${first.width}x${first.height} точек$limit"
    }
    document.text != null -> "готово: ${document.text!!.length} символов"
    else -> "пусто"
}


