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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.printbridge.bluetooth.BluetoothDeviceInfo
import com.printbridge.bluetooth.BluetoothDeviceRepository
import com.printbridge.bluetooth.BluetoothPermissions
import com.printbridge.bluetooth.BluetoothSppPrinterTransport
import com.printbridge.bluetooth.BluetoothVirtualPrinterServer
import com.printbridge.core.PrintJob
import com.printbridge.core.PrintQueue
import com.printbridge.core.PrinterProfile
import com.printbridge.core.TransportType
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(TransportType.USB, TransportType.BLUETOOTH_SPP, TransportType.TCP, TransportType.FAKE).forEach { transport ->
            Button(onClick = { onSelected(transport) }, modifier = Modifier.weight(1f)) {
                Text(if (selected == transport) "✓ ${transport.label()}" else transport.label(), maxLines = 1)
            }
        }
    }
}

@Composable
internal fun FakePanel(
    generated: ByteArray,
    profile: PrinterProfile,
    onStatus: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    Text("ЛОКАЛЬНАЯ ПРОВЕРКА", style = MaterialTheme.typography.titleMedium)
    Text("Отправка пройдет в память приложения. Это удобно для проверки chunks и байтов без принтера.")
    Button(onClick = {
        scope.launch {
            if (generated.isEmpty()) {
                onStatus("Сначала сформируйте задание")
                return@launch
            }
            val fake = FakePrinterTransport("ready-job")
            val job = PrintQueue(fake).enqueue(PrintJob(source = "Локальная проверка", printerProfileId = profile.id), profile, generated)
            val capture = fake.capture()
            onStatus("Локальная проверка: ${job.state}\nОтправлено байт: ${capture.totalBytes}\nЧастей: ${capture.writes.size}")
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("ПРОВЕРИТЬ БЕЗ ПРИНТЕРА") }
}

@Composable
internal fun UsbPanel(
    generated: ByteArray,
    profile: PrinterProfile,
    requestPermission: (UsbDevice, (Boolean) -> Unit) -> Unit,
    onProfileUpdated: (PrinterProfile) -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
            val job = PrintQueue(transport).enqueue(
                PrintJob(source = "USB", printerProfileId = profile.id),
                profile,
                generated
            )
            onStatus("USB: ${job.state}${job.lastError?.let { ": $it" } ?: ""}")
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
    requestPermissions: ((Boolean) -> Unit) -> Unit,
    onProfileUpdated: (PrinterProfile) -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
            val job = PrintQueue(transport).enqueue(
                PrintJob(source = "Bluetooth SPP", printerProfileId = profile.id),
                profile,
                generated
            )
            onStatus("Bluetooth: ${job.state}${job.lastError?.let { ": $it" } ?: ""}")
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("ПЕЧАТАТЬ ПО BLUETOOTH") }
}

@Composable
internal fun TcpPanel(
    generated: ByteArray,
    profile: PrinterProfile,
    onProfileUpdated: (PrinterProfile) -> Unit,
    onStatus: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
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
            val job = PrintQueue(transport).enqueue(
                PrintJob(source = "TCP/IP", printerProfileId = profile.id),
                profile,
                generated
            )
            onStatus("TCP: ${job.state}${job.lastError?.let { ": $it" } ?: ""}")
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("ПЕЧАТАТЬ ПО TCP") }
}

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
