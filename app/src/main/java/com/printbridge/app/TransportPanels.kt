package com.printbridge.app

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.printbridge.bluetooth.BluetoothDeviceInfo
import com.printbridge.bluetooth.BluetoothDeviceRepository
import com.printbridge.bluetooth.BluetoothPermissions
import com.printbridge.bluetooth.BluetoothSppPrinterTransport
import com.printbridge.bluetooth.BluetoothVirtualPrinterServer
import com.printbridge.core.PrintJob
import com.printbridge.core.PrintJobState
import com.printbridge.core.PrintQueue
import com.printbridge.core.PrinterProfile
import com.printbridge.core.PrinterTransport
import com.printbridge.core.TransportType
import com.printbridge.drivers.WatermarkComposer
import com.printbridge.transport.FakePrinterTransport
import com.printbridge.transport.NetworkPrinterCandidate
import com.printbridge.transport.NetworkPrinterDiscovery
import com.printbridge.transport.TcpPrinterTransport
import com.printbridge.usb.UsbPrinterDeviceInfo
import com.printbridge.usb.UsbPrinterRepository
import com.printbridge.usb.UsbPrinterTransport
import kotlinx.coroutines.launch

@Composable
internal fun TransportPicker(selected: TransportType, onSelected: (TransportType) -> Unit) {
    // Two rows of two: four buttons in a single row truncate their labels on a 1080px phone
    // ("Blue", "Net"), which was confirmed on a Galaxy S25.
    val transports = listOf(TransportType.USB, TransportType.BLUETOOTH_SPP, TransportType.TCP, TransportType.FAKE)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        transports.chunked(2).forEach { rowTransports ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTransports.forEach { transport ->
                    Button(onClick = { onSelected(transport) }, modifier = Modifier.weight(1f)) {
                        Text(
                            if (selected == transport) "✓ ${transport.label()}" else transport.label(),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun FakePanel(
    generated: ByteArray,
    profile: PrinterProfile,
    licenseStore: LicenseStore,
    onStatus: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var copies by remember { mutableIntStateOf(1) }
    Text("ЛОКАЛЬНАЯ ПРОВЕРКА", style = MaterialTheme.typography.titleMedium)
    Text("Отправка пройдет в память приложения. Это удобно для проверки chunks и байтов без принтера.")
    CopiesSelector(copies, licenseStore) { copies = it }
    Button(onClick = {
        scope.launch {
            if (generated.isEmpty()) {
                onStatus("Сначала сформируйте задание")
                return@launch
            }
            val fake = FakePrinterTransport("ready-job")
            val queue = PrintQueue(fake)
            val payload = WatermarkComposer.compose(generated, profile, licenseStore.watermarkTextFor(profile))
            val effective = licenseStore.allowedCopies(copies)
            var lastState = PrintJobState.QUEUED
            var lastError: String? = null
            repeat(effective) { index ->
                val job = queue.enqueue(
                    PrintJob(source = "Локальная проверка ${index + 1}", printerProfileId = profile.id),
                    profile,
                    payload
                )
                lastState = job.state
                lastError = job.lastError
                if (job.state != PrintJobState.COMPLETED) return@repeat
            }
            val capture = fake.capture()
            onStatus(
                "Локальная проверка: $lastState" +
                    (lastError?.let { ": $it" } ?: "") +
                    "\nКопий: $effective" +
                    "\nОтправлено байт: ${capture.totalBytes}" +
                    "\nЧастей: ${capture.writes.size}"
            )
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("ПРОВЕРИТЬ БЕЗ ПРИНТЕРА") }
}

/**
 * Copies selector. The free entitlement caps this at one sheet per job, and the reason is stated
 * rather than silently clamping the value.
 */
@Composable
internal fun CopiesSelector(
    copies: Int,
    licenseStore: LicenseStore,
    onCopiesChange: (Int) -> Unit
) {
    val maxCopies = licenseStore.allowedCopies(LicenseStore.MAX_SELECTABLE_COPIES)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Копий:")
        Button(onClick = { onCopiesChange((copies - 1).coerceAtLeast(1)) }, enabled = copies > 1) { Text("−") }
        Text(copies.toString(), style = MaterialTheme.typography.titleMedium)
        Button(onClick = { onCopiesChange((copies + 1).coerceAtMost(maxCopies)) }, enabled = copies < maxCopies) { Text("+") }
    }
    if (maxCopies == LicenseStore.FREE_SHEETS_PER_JOB) {
        Text(
            "Бесплатная версия печатает один лист за задание. Больше копий — в платной версии.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
internal fun UsbPanel(
    generated: ByteArray,
    profile: PrinterProfile,
    licenseStore: LicenseStore,
    requestPermission: (UsbDevice, (Boolean) -> Unit) -> Unit,
    onProfileUpdated: (PrinterProfile) -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copies by remember { mutableIntStateOf(1) }
    val usbManager = remember(context) { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    val repository = remember(usbManager) { UsbPrinterRepository(usbManager) }
    var devices by remember { mutableStateOf(emptyList<UsbPrinterDeviceInfo>()) }

    fun printTo(device: UsbDevice) {
        scope.launch {
            onStatus("USB: печать ${profile.protocol}, ${generated.size} байт")
            val transport = UsbPrinterTransport(
                usbManager,
                device,
                (profile.writeTimeoutMs ?: DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS).toInt()
            )
            val result = printBatch(
                transport = transport,
                profile = profile,
                payload = WatermarkComposer.compose(generated, profile, licenseStore.watermarkTextFor(profile)),
                copies = licenseStore.allowedCopies(copies),
                sourceName = "USB"
            )
            onStatus("USB: ${result.describe()}")
        }
    }

    Text("USB", style = MaterialTheme.typography.titleMedium)
    Text(
        if (profile.usbVid != null && profile.usbPid != null) {
            "Устройство в профиле: VID ${profile.usbVid} / PID ${profile.usbPid}"
        } else {
            "USB-принтер в профиле не выбран"
        }
    )
    Button(onClick = {
        devices = runCatching { repository.devices() }.getOrElse {
            onStatus("USB: не удалось прочитать устройства: ${it.message}")
            emptyList()
        }
        onStatus(if (devices.isEmpty()) "USB: принтеры с bulk endpoint не найдены" else "USB: найдено ${devices.size} устройств")
    }, modifier = Modifier.fillMaxWidth()) { Text("НАЙТИ USB-ПРИНТЕРЫ") }
    devices.forEach { device ->
        Button(onClick = {
            val updated = profile.copy(
                usbVid = device.vendorId,
                usbPid = device.productId,
                transportType = TransportType.USB
            )
            onProfileUpdated(updated)
            onStatus("USB: сохранено VID ${device.vendorId} / PID ${device.productId}")
        }, modifier = Modifier.fillMaxWidth()) {
            Text("${device.deviceName}  VID ${device.vendorId} / PID ${device.productId}")
        }
    }
    Button(onClick = {
        if (generated.isEmpty()) {
            onStatus("Сначала сформируйте задание")
            return@Button
        }
        val device = usbManager.findDevice(profile)
        if (device == null) {
            onStatus("USB: выбранное устройство не найдено. Нажмите поиск и выберите принтер")
            return@Button
        }
        if (!usbManager.hasPermission(device)) {
            onStatus("USB: требуется системное разрешение")
            requestPermission(device) { granted ->
                if (granted) printTo(device) else onStatus("USB: пользователь отказал в доступе к устройству")
            }
            return@Button
        }
        printTo(device)
    }, modifier = Modifier.fillMaxWidth()) { Text("ПЕЧАТАТЬ ПО USB") }
}

@Composable
internal fun BluetoothPanel(
    generated: ByteArray,
    profile: PrinterProfile,
    licenseStore: LicenseStore,
    requestPermissions: ((Boolean) -> Unit) -> Unit,
    onProfileUpdated: (PrinterProfile) -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copies by remember { mutableIntStateOf(1) }
    val repository = remember(context) { BluetoothDeviceRepository(context) }
    var devices by remember { mutableStateOf(emptyList<BluetoothDeviceInfo>()) }
    var selected by remember { mutableStateOf<BluetoothDeviceInfo?>(null) }

    Text("BLUETOOTH SPP", style = MaterialTheme.typography.titleMedium)
    Text(
        profile.bluetoothDeviceName?.let { "Устройство в профиле: $it" }
            ?: "Bluetooth-устройство в профиле не выбрано"
    )
    Button(onClick = {
        requestPermissions { granted ->
            if (!granted) {
                onStatus("Разрешение Bluetooth не предоставлено")
            } else {
                devices = runCatching { repository.bondedDevices() }.getOrElse {
                    onStatus("Не удалось получить список Bluetooth-устройств: ${it.message}")
                    emptyList()
                }
                selected = devices.firstOrNull { it.address == profile.bluetoothDeviceAddress }
                onStatus(
                    when {
                        devices.isEmpty() -> "Сопряженные Bluetooth-устройства не найдены"
                        selected != null -> "Сохраненное Bluetooth-устройство найдено"
                        profile.bluetoothDeviceAddress != null -> "Сохраненное устройство не найдено. Выберите другое"
                        else -> "Выберите Bluetooth-принтер"
                    }
                )
            }
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("ВЫБРАТЬ BLUETOOTH-УСТРОЙСТВО") }
    devices.forEach { device ->
        Button(onClick = {
            selected = device
            onProfileUpdated(
                profile.copy(
                    bluetoothDeviceName = device.name,
                    bluetoothDeviceAddress = device.address,
                    transportType = TransportType.BLUETOOTH_SPP
                )
            )
            onStatus("Bluetooth-устройство сохранено в профиль: ${device.name}")
        }, modifier = Modifier.fillMaxWidth()) {
            val saved = profile.bluetoothDeviceAddress == device.address
            Text(if (saved || selected?.address == device.address) "ВЫБРАН: ${device.name}" else device.name)
        }
    }
    CopiesSelector(copies, licenseStore) { copies = it }
    Button(onClick = {
        val address = selected?.address ?: profile.bluetoothDeviceAddress ?: run {
            onStatus("Сначала выберите Bluetooth-устройство")
            return@Button
        }
        if (generated.isEmpty()) {
            onStatus("Сначала сформируйте задание")
            return@Button
        }
        scope.launch {
            onStatus("Bluetooth: подключение к $address, ${profile.protocol}, ${generated.size} байт")
            val transport = BluetoothSppPrinterTransport(
                context,
                address,
                BluetoothSppPrinterTransport.DEFAULT_SERVICE_UUIDS,
                connectTimeoutMs = DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS,
                writeTimeoutMs = profile.writeTimeoutMs ?: DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS
            )
            val result = printBatch(
                transport = transport,
                profile = profile,
                payload = WatermarkComposer.compose(generated, profile, licenseStore.watermarkTextFor(profile)),
                copies = licenseStore.allowedCopies(copies),
                sourceName = "Bluetooth"
            )
            onStatus("Bluetooth: ${result.describe()}")
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("ПЕЧАТАТЬ ПО BLUETOOTH") }
}

@Composable
internal fun TcpPanel(
    generated: ByteArray,
    profile: PrinterProfile,
    licenseStore: LicenseStore,
    onProfileUpdated: (PrinterProfile) -> Unit,
    onStatus: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var copies by remember { mutableIntStateOf(1) }
    var scanning by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(emptyList<NetworkPrinterCandidate>()) }

    Text("TCP/IP", style = MaterialTheme.typography.titleMedium)
    Text(
        profile.networkHost?.let { "Адрес в профиле: $it:${profile.networkPort ?: DEFAULT_NETWORK_PORT}" }
            ?: "Адрес в профиле не задан"
    )
    Button(onClick = {
        scope.launch {
            scanning = true
            onStatus("TCP: ищу сетевые принтеры и PrintBridge Windows Bridge")
            candidates = runCatching { NetworkPrinterDiscovery().discoverLocalSubnets() }
                .onFailure { onStatus("TCP: поиск не удался: ${it.message}") }
                .getOrDefault(emptyList())
            onStatus(if (candidates.isEmpty()) "TCP: сетевые принтеры не найдены" else "TCP: найдено ${candidates.size} адресов")
            scanning = false
        }
    }, modifier = Modifier.fillMaxWidth()) {
        Text(if (scanning) "ПОИСК..." else "НАЙТИ СЕТЕВЫЕ ПРИНТЕРЫ")
    }
    candidates.forEach { candidate ->
        Button(onClick = {
            val updated = profile.copy(
                networkHost = candidate.host,
                networkPort = candidate.port,
                networkConnectTimeoutMs = profile.networkConnectTimeoutMs ?: DEFAULT_NETWORK_CONNECT_TIMEOUT_MS,
                networkWriteTimeoutMs = profile.networkWriteTimeoutMs ?: DEFAULT_NETWORK_WRITE_TIMEOUT_MS,
                transportType = TransportType.TCP
            )
            onProfileUpdated(updated)
            onStatus("TCP: сохранен ${candidate.host}:${candidate.port} (${candidate.protocolHint})")
        }, modifier = Modifier.fillMaxWidth()) {
            Text("${candidate.host}:${candidate.port} ${candidate.protocolHint}")
        }
    }
    CopiesSelector(copies, licenseStore) { copies = it }
    Button(onClick = {
        val host = profile.networkHost?.trim().orEmpty()
        if (host.isBlank()) {
            onStatus("В профиле не задан TCP host")
            return@Button
        }
        if (generated.isEmpty()) {
            onStatus("Сначала сформируйте задание")
            return@Button
        }
        val port = profile.networkPort ?: DEFAULT_NETWORK_PORT
        val connectTimeout = profile.networkConnectTimeoutMs ?: DEFAULT_NETWORK_CONNECT_TIMEOUT_MS
        val writeTimeout = profile.networkWriteTimeoutMs ?: DEFAULT_NETWORK_WRITE_TIMEOUT_MS
        scope.launch {
            onStatus("TCP: подключение к $host:$port, ${profile.protocol}, ${generated.size} байт")
            val transport = TcpPrinterTransport(host, port, connectTimeout, writeTimeout)
            val result = printBatch(
                transport = transport,
                profile = profile,
                payload = WatermarkComposer.compose(generated, profile, licenseStore.watermarkTextFor(profile)),
                copies = licenseStore.allowedCopies(copies),
                sourceName = "TCP"
            )
            onStatus("TCP: ${result.describe()}")
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("ПЕЧАТАТЬ ПО TCP") }
}

/** Outcome of sending one or more sheets, kept small so the UI can print a single line. */
internal data class BatchPrintResult(val sent: Int, val attempted: Int, val state: PrintJobState, val error: String?) {
    fun describe(): String = buildString {
        append("отправлено $sent из $attempted, статус $state")
        error?.let { append(": $it") }
    }
}

/**
 * Sends one payload per entry in [payloads], one queue job per sheet.
 *
 * Each sheet is a separate job so a thermal printer cuts between them and each gets its own
 * connection and completion state. A failure stops the batch instead of queueing the rest into a
 * link or printer that is already gone.
 */
internal suspend fun printSheets(
    transport: PrinterTransport,
    profile: PrinterProfile,
    payloads: List<ByteArray>,
    sourceName: String
): BatchPrintResult {
    val queue = PrintQueue(transport)
    val total = payloads.size.coerceAtLeast(1)
    var sent = 0
    var state = PrintJobState.QUEUED
    var error: String? = null
    payloads.forEachIndexed { index, payload ->
        val job = queue.enqueue(
            PrintJob(source = "$sourceName ${index + 1}/$total", printerProfileId = profile.id),
            profile,
            payload
        )
        state = job.state
        error = job.lastError
        if (job.state != PrintJobState.COMPLETED) return BatchPrintResult(sent + 1, total, state, error)
        sent++
    }
    return BatchPrintResult(sent, total, state, error)
}

/** Convenience wrapper for the ready-job path: the same payload printed [copies] times. */
internal suspend fun printBatch(
    transport: PrinterTransport,
    profile: PrinterProfile,
    payload: ByteArray,
    copies: Int,
    sourceName: String
): BatchPrintResult = printSheets(transport, profile, List(copies.coerceAtLeast(1)) { payload }, sourceName)

internal fun TransportType.label(): String = when (this) {
    TransportType.USB -> "USB"
    TransportType.BLUETOOTH_SPP -> "Bluetooth"
    TransportType.TCP -> "Network"
    TransportType.FAKE -> "Test"
    TransportType.BLUETOOTH_BLE -> "BLE"
}

internal fun UsbManager.findDevice(profile: PrinterProfile): UsbDevice? =
    deviceList.values.firstOrNull { device ->
        device.vendorId == profile.usbVid && device.productId == profile.usbPid
    }

@Composable
internal fun VirtualPrinterPanel(
    requestPermissions: ((Boolean) -> Unit) -> Unit,
    server: BluetoothVirtualPrinterServer,
    serverStatus: String,
    onServerStatusChange: (String) -> Unit
) {
    Text("VIRTUAL PRINTER", style = MaterialTheme.typography.titleMedium)
    Button(onClick = {
        requestPermissions { granted ->
            if (granted) {
                server.start()
                onServerStatusChange("Virtual Bluetooth printer ожидает SPP-подключение")
            } else {
                onServerStatusChange("Для сервера нужно разрешение Bluetooth")
            }
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("ЗАПУСТИТЬ VIRTUAL PRINTER") }
    Button(onClick = { server.stop(); onServerStatusChange("Сервер выключен") }, modifier = Modifier.fillMaxWidth()) { Text("ОСТАНОВИТЬ VIRTUAL PRINTER") }
    Text(serverStatus)
}
