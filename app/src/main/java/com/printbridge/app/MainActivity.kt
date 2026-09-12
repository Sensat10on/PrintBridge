package com.printbridge.app

import android.os.Bundle
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.printbridge.core.PrinterProfile
import com.printbridge.core.PrinterProtocol
import com.printbridge.core.TransportType
import com.printbridge.core.PrintJob
import com.printbridge.core.PrintQueue
import com.printbridge.drivers.EscPosDriver
import com.printbridge.drivers.GoojprtLabelDriver
import com.printbridge.drivers.TsplDriver
import com.printbridge.transport.FakePrinterTransport
import com.printbridge.bluetooth.BluetoothPermissions
import com.printbridge.bluetooth.BluetoothVirtualPrinterServer
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var bluetoothPermissionCallback: ((Boolean) -> Unit)? = null
    private var usbPermissionCallback: ((Boolean) -> Unit)? = null
    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        bluetoothPermissionCallback?.invoke(results.values.all { it })
        bluetoothPermissionCallback = null
    }
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            usbPermissionCallback?.invoke(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            usbPermissionCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        // ACTION_USB_PERMISSION is an app-private broadcast, so the receiver must not be
        // exported on any API level: an unprotected receiver would let another app spoof a
        // granted USB permission.
        ContextCompat.registerReceiver(
            this,
            usbPermissionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        setContent {
            PrintBridgeApp(
                requestBluetoothPermissions = { callback -> requestBluetoothPermissions(callback) },
                requestUsbPermission = { device, callback -> requestUsbPermission(device, callback) }
            )
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        super.onDestroy()
    }

    private fun requestBluetoothPermissions(onResult: (Boolean) -> Unit) {
        val permissions = BluetoothPermissions.runtimePermissions()
        if (permissions.isEmpty()) onResult(true) else {
            bluetoothPermissionCallback = onResult
            bluetoothPermissionLauncher.launch(permissions)
        }
    }

    private fun requestUsbPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }
        usbPermissionCallback = onResult
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        manager.requestPermission(device, pendingIntent)
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.printbridge.app.USB_PERMISSION"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintBridgeApp(
    requestBluetoothPermissions: (((Boolean) -> Unit) -> Unit)? = null,
    requestUsbPermission: ((UsbDevice, (Boolean) -> Unit) -> Unit)? = null
) {
    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("PRINTBRIDGE") }) }) { padding ->
            val context = LocalContext.current
            val store = remember(context) { ProfileStore(context) }
            val savedDraft = remember(store) { store.loadDraft() }
            var profiles by remember { mutableStateOf(store.load()) }
            var profile by remember { mutableStateOf(profiles.first()) }
            var profileName by remember(profile.id) { mutableStateOf(profile.displayName) }
            var dpiText by remember(profile.id) { mutableStateOf(profile.dpi.toString()) }
            var paperWidthText by remember(profile.id) { mutableStateOf(profile.paperWidthMm.toString()) }
            var paperHeightText by remember(profile.id) { mutableStateOf(profile.paperHeightMm?.toString().orEmpty()) }
            var chunkSizeText by remember(profile.id) { mutableStateOf(profile.chunkSize?.toString().orEmpty()) }
            var delayText by remember(profile.id) { mutableStateOf(profile.delayBetweenChunksMs?.toString().orEmpty()) }
            var writeTimeoutText by remember(profile.id) { mutableStateOf((profile.writeTimeoutMs ?: DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS).toString()) }
            var networkHostText by remember(profile.id) { mutableStateOf(profile.networkHost.orEmpty()) }
            var networkPortText by remember(profile.id) { mutableStateOf((profile.networkPort ?: DEFAULT_NETWORK_PORT).toString()) }
            var networkConnectTimeoutText by remember(profile.id) { mutableStateOf((profile.networkConnectTimeoutMs ?: DEFAULT_NETWORK_CONNECT_TIMEOUT_MS).toString()) }
            var networkWriteTimeoutText by remember(profile.id) { mutableStateOf((profile.networkWriteTimeoutMs ?: DEFAULT_NETWORK_WRITE_TIMEOUT_MS).toString()) }
            var watermarkText by remember(profile.id) { mutableStateOf(profile.watermarkText.orEmpty()) }
            var screenTab by remember { mutableIntStateOf(0) }
            var diagnosticTab by remember { mutableIntStateOf(0) }
            var result by remember { mutableStateOf("Заданий пока нет") }
            var generated by remember { mutableStateOf(ByteArray(0)) }
            var jobTemplate by remember { mutableStateOf(savedDraft.template) }
            var jobTitle by remember { mutableStateOf(savedDraft.title) }
            var itemName by remember { mutableStateOf(savedDraft.itemName) }
            var itemPrice by remember { mutableStateOf(savedDraft.itemPrice) }
            var itemCode by remember { mutableStateOf(savedDraft.itemCode) }
            var qrText by remember { mutableStateOf(savedDraft.qrText) }
            var history by remember { mutableStateOf(store.loadHistory()) }
            var virtualPrinterStatus by remember { mutableStateOf("Сервер выключен") }
            val licenseStore = remember(context) { LicenseStore(context) }
            var licensed by remember { mutableStateOf(licenseStore.isLicensed()) }

            // Single place that mirrors a profile into the profile form, so no field can be
            // missed when the selection, protocol or saved values change.
            fun applyEditor(source: PrinterProfile) {
                val values = source.toEditorValues()
                profileName = values.displayName
                dpiText = values.dpi
                paperWidthText = values.paperWidth
                paperHeightText = values.paperHeight
                chunkSizeText = values.chunkSize
                delayText = values.delayBetweenChunks
                writeTimeoutText = values.writeTimeout
                networkHostText = values.networkHost
                networkPortText = values.networkPort
                networkConnectTimeoutText = values.networkConnectTimeout
                networkWriteTimeoutText = values.networkWriteTimeout
                watermarkText = source.watermarkText.orEmpty()
            }
            val virtualPrinterServer = remember(context) {
                BluetoothVirtualPrinterServer(
                    context,
                    onJob = { job ->
                        virtualPrinterStatus = "Получено: ${job.protocol}, ${job.receivedBytes} байт\n${job.preview.lines.joinToString("\n").take(1200)}"
                    },
                    onError = { error -> virtualPrinterStatus = "Ошибка сервера: ${error.message}" }
                )
            }
            DisposableEffect(virtualPrinterServer) { onDispose { virtualPrinterServer.close() } }
            val scope = rememberCoroutineScope()
            val scrollState = rememberScrollState()

            Column(
                Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize()
                    .simpleVerticalScrollbar(scrollState)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // A dropdown instead of a tab row: four full Russian titles do not fit the tab
                // width on a 1080px phone and were truncated mid-word.
                ScreenSectionPicker(selected = screenTab, onSelected = { screenTab = it })

                when (screenTab) {
                    0 -> {
                        ProfilePicker(profile, profiles) { selected ->
                            profile = selected
                            applyEditor(selected)
                            generated = ByteArray(0)
                            result = "Выбран профиль: ${selected.displayName}"
                        }
                        Text("Протокол: ${profile.protocol}   Бумага: ${profile.paperWidthMm} мм   DPI: ${profile.dpi}")
                        Text("ТИП ЗАДАНИЯ", style = MaterialTheme.typography.titleSmall)
                        PrintTemplatePicker(jobTemplate) {
                            jobTemplate = it
                            generated = ByteArray(0)
                            store.saveDraft(StoredPrintJobDraft(it, jobTitle, itemName, itemPrice, itemCode, qrText))
                        }
                        OutlinedTextField(
                            value = jobTitle,
                            onValueChange = {
                                jobTitle = it
                                generated = ByteArray(0)
                                store.saveDraft(StoredPrintJobDraft(jobTemplate, it, itemName, itemPrice, itemCode, qrText))
                            },
                            label = { Text("Заголовок") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = itemName,
                            onValueChange = {
                                itemName = it
                                generated = ByteArray(0)
                                store.saveDraft(StoredPrintJobDraft(jobTemplate, jobTitle, it, itemPrice, itemCode, qrText))
                            },
                            label = { Text(itemNameLabel(jobTemplate, profile.protocol)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = itemPrice,
                            onValueChange = {
                                itemPrice = it
                                generated = ByteArray(0)
                                store.saveDraft(StoredPrintJobDraft(jobTemplate, jobTitle, itemName, it, itemCode, qrText))
                            },
                            label = { Text(itemPriceLabel(jobTemplate, profile.protocol)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = itemCode,
                            onValueChange = {
                                itemCode = it
                                generated = ByteArray(0)
                                store.saveDraft(StoredPrintJobDraft(jobTemplate, jobTitle, itemName, itemPrice, it, qrText))
                            },
                            label = { Text("Код") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = qrText,
                            onValueChange = {
                                qrText = it
                                generated = ByteArray(0)
                                store.saveDraft(StoredPrintJobDraft(jobTemplate, jobTitle, itemName, itemPrice, itemCode, it))
                            },
                            label = { Text("QR") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = {
                            val draft = StoredPrintJobDraft(jobTemplate, jobTitle, itemName, itemPrice, itemCode, qrText)
                            store.saveDraft(draft)
                            store.addHistoryItem(
                                StoredPrintJobHistoryItem(
                                    id = UUID.randomUUID().toString(),
                                    createdAt = System.currentTimeMillis(),
                                    profileId = profile.id,
                                    protocol = profile.protocol,
                                    draft = draft
                                )
                            )
                            history = store.loadHistory()
                            generated = buildReadyJob(profile, draft)
                            result = inspect(generated, profile.protocol)
                        }, modifier = Modifier.fillMaxWidth()) { Text("СФОРМИРОВАТЬ ЗАДАНИЕ") }
                        Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
                            Text(
                                if (generated.isEmpty()) result else inspect(generated, profile.protocol),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Text("ИСТОРИЯ ЗАДАНИЙ", style = MaterialTheme.typography.titleMedium)
                        if (history.isEmpty()) {
                            Text("История пока пустая")
                        } else {
                            history.take(5).forEach { item ->
                                Button(onClick = {
                                    jobTitle = item.draft.title
                                    jobTemplate = item.draft.template
                                    itemName = item.draft.itemName
                                    itemPrice = item.draft.itemPrice
                                    itemCode = item.draft.itemCode
                                    qrText = item.draft.qrText
                                    store.saveDraft(item.draft)
                                    generated = ByteArray(0)
                                    result = "Задание загружено из истории"
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text("${item.draft.template.label} - ${item.draft.title.ifBlank { "Без названия" }} - ${formatHistoryTime(item.createdAt)}")
                                }
                            }
                            Button(onClick = {
                                store.clearHistory()
                                history = emptyList()
                                result = "История очищена"
                            }, modifier = Modifier.fillMaxWidth()) { Text("ОЧИСТИТЬ ИСТОРИЮ") }
                        }
                        Text("СПОСОБ ПЕЧАТИ", style = MaterialTheme.typography.titleSmall)
                        TransportPicker(profile.transportType) { transport ->
                            val updated = profile.copy(transportType = transport)
                            profiles = profiles.map { if (it.id == updated.id) updated else it }
                            profile = updated
                            store.save(profiles)
                            result = "Выбран способ печати: ${transport.label()}"
                        }
                        when (profile.transportType) {
                            TransportType.USB -> UsbPanel(
                                generated = generated,
                                profile = profile,
                                licenseStore = licenseStore,
                                requestPermission = requestUsbPermission ?: { _, callback -> callback(false) },
                                onProfileUpdated = { updated ->
                                    profiles = profiles.map { if (it.id == updated.id) updated else it }
                                    profile = updated
                                    store.save(profiles)
                                },
                                onStatus = { result = it }
                            )
                            TransportType.BLUETOOTH_SPP -> BluetoothPanel(
                                generated = generated,
                                profile = profile,
                                licenseStore = licenseStore,
                                requestPermissions = requestBluetoothPermissions ?: { _ -> },
                                onProfileUpdated = { updated ->
                                    profiles = profiles.map { if (it.id == updated.id) updated else it }
                                    profile = updated
                                    store.save(profiles)
                                },
                                onStatus = { result = it }
                            )
                            TransportType.TCP -> TcpPanel(
                                generated = generated,
                                profile = profile,
                                licenseStore = licenseStore,
                                onProfileUpdated = { updated ->
                                    profiles = profiles.map { if (it.id == updated.id) updated else it }
                                    profile = updated
                                    applyEditor(updated)
                                    store.save(profiles)
                                },
                                onStatus = { result = it }
                            )
                            else -> FakePanel(
                                generated = generated,
                                profile = profile,
                                licenseStore = licenseStore,
                                onStatus = { result = it }
                            )
                        }
                    }

                    1 -> {
                        ProfilePicker(profile, profiles) { selected ->
                            profile = selected
                            applyEditor(selected)
                            result = "Выбран профиль: ${selected.displayName}"
                        }
                        Text("Способ печати: ${profile.transportType.label()}   Протокол: ${profile.protocol}")
                        FilePrintPanel(
                            profile = profile,
                            licenseStore = licenseStore,
                            onStatus = { result = it }
                        )
                    }

                    2 -> {
                        ProfilePicker(profile, profiles) { selected ->
                            profile = selected
                            applyEditor(selected)
                            generated = ByteArray(0)
                        }
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = { profileName = it },
                            label = { Text("Название профиля") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Параметры печати", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = dpiText,
                            onValueChange = { dpiText = it },
                            label = { Text("Плотность, DPI") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = paperWidthText,
                            onValueChange = { paperWidthText = it },
                            label = { Text("Ширина бумаги, мм") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (profile.protocol == PrinterProtocol.TSPL || profile.protocol == PrinterProtocol.GOOJPRT_LABEL) {
                            OutlinedTextField(
                                value = paperHeightText,
                                onValueChange = { paperHeightText = it },
                                label = { Text("Высота этикетки, мм") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        OutlinedTextField(
                            value = chunkSizeText,
                            onValueChange = { chunkSizeText = it },
                            label = { Text("Размер части, байт") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = delayText,
                            onValueChange = { delayText = it },
                            label = { Text("Пауза между частями, мс") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Сеть", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = networkHostText,
                            onValueChange = { networkHostText = it },
                            label = { Text("IP или hostname") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = networkPortText,
                            onValueChange = { networkPortText = it },
                            label = { Text("TCP порт") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = networkConnectTimeoutText,
                            onValueChange = { networkConnectTimeoutText = it },
                            label = { Text("Таймаут подключения, мс") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = networkWriteTimeoutText,
                            onValueChange = { networkWriteTimeoutText = it },
                            label = { Text("Таймаут записи, мс") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = writeTimeoutText,
                            onValueChange = { writeTimeoutText = it },
                            label = { Text("Таймаут записи USB/Bluetooth, мс") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Лимит бесплатной версии", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = watermarkText,
                            onValueChange = { watermarkText = it },
                            label = { Text("Текст метки (пусто = по умолчанию)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!licensed) {
                            Text("Сейчас активна бесплатная версия: метка ставится на каждую печать.")
                        }
                        Button(onClick = {
                            val edited = profileFromEditor(
                                profile, profileName, dpiText, paperWidthText, paperHeightText, chunkSizeText, delayText,
                                writeTimeoutText, networkHostText, networkPortText, networkConnectTimeoutText, networkWriteTimeoutText
                            )
                            if (edited == null) {
                                result = "Проверьте параметры профиля: положительные числа, порт 1..65535, " +
                                    "таймаут $MIN_TIMEOUT_MS..$MAX_TIMEOUT_MS мс, часть 1..$MAX_CHUNK_SIZE_BYTES байт"
                                return@Button
                            }
                            val updated = edited.copy(watermarkText = watermarkText.trim().takeIf { it.isNotBlank() })
                            profiles = profiles.map { if (it.id == updated.id) updated else it }
                            profile = updated
                            applyEditor(updated)
                            generated = ByteArray(0)
                            store.save(profiles)
                            result = "Профиль сохранен"
                        }, modifier = Modifier.fillMaxWidth()) { Text("СОХРАНИТЬ") }
                        Button(onClick = {
                            val created = profile.copy(
                                id = UUID.randomUUID().toString(),
                                displayName = "Новый профиль ${profile.protocol}",
                                verified = false
                            )
                            profiles = profiles + created
                            profile = created
                            applyEditor(created)
                            generated = ByteArray(0)
                            store.save(profiles)
                            result = "Профиль создан"
                        }, modifier = Modifier.fillMaxWidth()) { Text("СОЗДАТЬ") }
                        Button(onClick = {
                            val duplicate = profile.copy(id = UUID.randomUUID().toString(), displayName = "${profile.displayName} (копия)", verified = false)
                            profiles = profiles + duplicate
                            profile = duplicate
                            applyEditor(duplicate)
                            generated = ByteArray(0)
                            store.save(profiles)
                            result = "Профиль продублирован"
                        }, modifier = Modifier.fillMaxWidth()) { Text("ДУБЛИРОВАТЬ") }
                        Button(onClick = {
                            if (profiles.size == 1) {
                                result = "Нужен хотя бы один профиль"
                            } else {
                                val remaining = profiles.filterNot { it.id == profile.id }
                                profiles = remaining
                                val next = remaining.first()
                                profile = next
                                applyEditor(next)
                                generated = ByteArray(0)
                                store.save(remaining)
                                result = "Профиль удален"
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("УДАЛИТЬ") }
                        Button(onClick = {
                            val updated = profile.copy(protocol = PrinterProtocol.ESC_POS, paperHeightMm = null, gapMm = null)
                            profile = updated
                            applyEditor(updated)
                            generated = ByteArray(0)
                            result = "Выбран ESC/POS. Сохраните профиль"
                        }, modifier = Modifier.fillMaxWidth()) { Text("${if (profile.protocol == PrinterProtocol.ESC_POS) "ВЫБРАН: " else ""}ESC/POS") }
                        Button(onClick = {
                            val updated = profile.copy(
                                protocol = PrinterProtocol.TSPL,
                                paperHeightMm = profile.paperHeightMm ?: 150f,
                                gapMm = profile.gapMm ?: 3f
                            )
                            profile = updated
                            applyEditor(updated)
                            generated = ByteArray(0)
                            result = "Выбран TSPL. Сохраните профиль"
                        }, modifier = Modifier.fillMaxWidth()) { Text("${if (profile.protocol == PrinterProtocol.TSPL) "ВЫБРАН: " else ""}TSPL") }
                        Button(onClick = {
                            val updated = profile.copy(
                                protocol = PrinterProtocol.GOOJPRT_LABEL,
                                paperHeightMm = profile.paperHeightMm ?: 40f,
                                gapMm = null,
                                chunkSize = profile.chunkSize ?: 64,
                                delayBetweenChunksMs = profile.delayBetweenChunksMs ?: 10
                            )
                            profile = updated
                            applyEditor(updated)
                            generated = ByteArray(0)
                            result = "Выбран GOOJPRT Label. Сохраните профиль"
                        }, modifier = Modifier.fillMaxWidth()) { Text("${if (profile.protocol == PrinterProtocol.GOOJPRT_LABEL) "ВЫБРАН: " else ""}GOOJPRT LABEL") }
                    }

                    else -> {
                        LicensePanel(
                            licensed = licensed,
                            store = licenseStore,
                            onLicensedChanged = { isLicensed ->
                                licensed = isLicensed
                                result = if (isLicensed) "Лицензия активирована: метка снята" else "Лицензия сброшена: метка вернётся"
                            }
                        )
                        Button(onClick = {
                            generated = EscPosDriver().testPage(profile.copy(protocol = PrinterProtocol.ESC_POS))
                            result = inspect(generated, PrinterProtocol.ESC_POS)
                        }, modifier = Modifier.fillMaxWidth()) { Text("ТЕСТОВЫЙ ЧЕК") }
                        Button(onClick = {
                            generated = TsplDriver().testPage(profile.copy(protocol = PrinterProtocol.TSPL, paperHeightMm = profile.paperHeightMm ?: 150f))
                            result = inspect(generated, PrinterProtocol.TSPL)
                        }, modifier = Modifier.fillMaxWidth()) { Text("ТЕСТОВАЯ ЭТИКЕТКА") }
                        Button(onClick = {
                            generated = GoojprtLabelDriver().testPage(profile.copy(protocol = PrinterProtocol.GOOJPRT_LABEL, paperHeightMm = profile.paperHeightMm ?: 40f))
                            result = inspect(generated, PrinterProtocol.GOOJPRT_LABEL)
                        }, modifier = Modifier.fillMaxWidth()) { Text("GOOJPRT ЭТИКЕТКА") }
                        Button(onClick = {
                            scope.launch {
                                val fake = FakePrinterTransport("ui-job")
                                val queue = PrintQueue(fake)
                                val job = queue.enqueue(PrintJob(source = "Тестовая лаборатория", printerProfileId = profile.id), profile, generated)
                                val capture = fake.capture()
                                result = "Задание: ${job.state}\nОтправлено байт: ${capture.totalBytes}\nЧастей: ${capture.writes.size}\nHEX:\n${capture.hex.take(1600)}"
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("ИМИТАЦИЯ ПЕЧАТИ") }
                        VirtualPrinterPanel(
                            requestPermissions = requestBluetoothPermissions ?: { _ -> },
                            server = virtualPrinterServer,
                            serverStatus = virtualPrinterStatus,
                            onServerStatusChange = { virtualPrinterStatus = it }
                        )
                        PrimaryTabRow(selectedTabIndex = diagnosticTab) {
                            listOf("ПРОСМОТР", "КОМАНДЫ", "HEX", "RAW").forEachIndexed { index, title ->
                                Tab(selected = diagnosticTab == index, onClick = { diagnosticTab = index }, text = { Text(title, maxLines = 1) })
                            }
                        }
                        Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
                            Text(
                                when (diagnosticTab) {
                                    0 -> result
                                    1 -> inspect(generated, profile.protocol)
                                    2 -> generated.joinToString(" ") { "%02X".format(it) }
                                    else -> generated.toString(Charsets.ISO_8859_1)
                                },
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenSectionPicker(selected: Int, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = SCREEN_SECTIONS[selected.coerceIn(SCREEN_SECTIONS.indices)],
            onValueChange = {},
            readOnly = true,
            label = { Text("Раздел") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SCREEN_SECTIONS.forEachIndexed { index, title ->
                DropdownMenuItem(
                    text = { Text(if (index == selected) "✓ $title" else title) },
                    onClick = {
                        onSelected(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Sections of the main screen, in the order they appear in [ScreenSectionPicker]. */
private val SCREEN_SECTIONS = listOf("Задание", "Печать файла", "Профили", "Диагностика")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilePicker(selected: PrinterProfile, profiles: List<PrinterProfile>, onSelected: (PrinterProfile) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Профиль") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(text = { Text(profile.displayName) }, onClick = { onSelected(profile); expanded = false })
            }
        }
    }
}

@Composable
private fun PrintTemplatePicker(selected: PrintJobTemplate, onSelected: (PrintJobTemplate) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrintJobTemplate.entries.take(2).forEach { template ->
                Button(onClick = { onSelected(template) }, modifier = Modifier.weight(1f)) {
                    Text(if (selected == template) "ВЫБРАН: ${template.label}" else template.label)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrintJobTemplate.entries.drop(2).forEach { template ->
                Button(onClick = { onSelected(template) }, modifier = Modifier.weight(1f)) {
                    Text(if (selected == template) "ВЫБРАН: ${template.label}" else template.label)
                }
            }
        }
    }
}

private fun Modifier.simpleVerticalScrollbar(scrollState: ScrollState): Modifier = drawWithContent {
    drawContent()
    if (scrollState.maxValue <= 0) return@drawWithContent

    val thumbWidth = 4.dp.toPx()
    val thumbPadding = 2.dp.toPx()
    val minThumbHeight = 48.dp.toPx()
    val viewportHeight = size.height
    val contentHeight = viewportHeight + scrollState.maxValue
    val thumbHeight = (viewportHeight * viewportHeight / contentHeight).coerceAtLeast(minThumbHeight)
    val thumbTop = scrollState.value.toFloat() / scrollState.maxValue * (viewportHeight - thumbHeight)

    drawRoundRect(
        color = Color.Gray.copy(alpha = 0.55f),
        topLeft = Offset(size.width - thumbWidth - thumbPadding, thumbTop),
        size = Size(thumbWidth, thumbHeight),
        cornerRadius = CornerRadius(thumbWidth, thumbWidth)
    )
}

