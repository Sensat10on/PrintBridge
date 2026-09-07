package com.printbridge.app

import android.os.Bundle
import android.os.Build
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
import com.printbridge.core.DefaultProfiles
import com.printbridge.core.PrinterProfile
import com.printbridge.core.PrinterProtocol
import com.printbridge.core.TransportType
import com.printbridge.core.PrintJob
import com.printbridge.core.PrintQueue
import com.printbridge.drivers.EscPosDriver
import com.printbridge.drivers.GoojprtLabelDriver
import com.printbridge.drivers.TsplDriver
import com.printbridge.simulator.EscPosParser
import com.printbridge.simulator.GoojprtLabelParser
import com.printbridge.simulator.TsplParser
import com.printbridge.transport.FakePrinterTransport
import com.printbridge.transport.NetworkPrinterCandidate
import com.printbridge.transport.NetworkPrinterDiscovery
import com.printbridge.transport.TcpPrinterTransport
import com.printbridge.usb.UsbPrinterDeviceInfo
import com.printbridge.usb.UsbPrinterRepository
import com.printbridge.usb.UsbPrinterTransport
import com.printbridge.bluetooth.BluetoothDeviceInfo
import com.printbridge.bluetooth.BluetoothDeviceRepository
import com.printbridge.bluetooth.BluetoothPermissions
import com.printbridge.bluetooth.BluetoothSppPrinterTransport
import com.printbridge.bluetooth.BluetoothVirtualPrinterServer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }
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
            var networkHostText by remember(profile.id) { mutableStateOf(profile.networkHost.orEmpty()) }
            var networkPortText by remember(profile.id) { mutableStateOf((profile.networkPort ?: 9100).toString()) }
            var networkConnectTimeoutText by remember(profile.id) { mutableStateOf((profile.networkConnectTimeoutMs ?: 5000).toString()) }
            var networkWriteTimeoutText by remember(profile.id) { mutableStateOf((profile.networkWriteTimeoutMs ?: 5000).toString()) }
            var screenTab by remember { mutableStateOf(0) }
            var diagnosticTab by remember { mutableStateOf(0) }
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
                PrimaryTabRow(selectedTabIndex = screenTab) {
                    listOf("ЗАДАНИЕ", "ПРОФИЛИ", "ДИАГНОСТИКА").forEachIndexed { index, title ->
                        Tab(selected = screenTab == index, onClick = { screenTab = index }, text = { Text(title, maxLines = 1) })
                    }
                }

                when (screenTab) {
                    0 -> {
                        Text("ГОТОВОЕ ЗАДАНИЕ", style = MaterialTheme.typography.titleMedium)
                        ProfilePicker(profile, profiles) { selected ->
                            profile = selected
                            profileName = selected.displayName
                            dpiText = selected.dpi.toString()
                            paperWidthText = selected.paperWidthMm.toString()
                            paperHeightText = selected.paperHeightMm?.toString().orEmpty()
                            chunkSizeText = selected.chunkSize?.toString().orEmpty()
                            delayText = selected.delayBetweenChunksMs?.toString().orEmpty()
                            networkHostText = selected.networkHost.orEmpty()
                            networkPortText = (selected.networkPort ?: 9100).toString()
                            networkConnectTimeoutText = (selected.networkConnectTimeoutMs ?: 5000).toString()
                            networkWriteTimeoutText = (selected.networkWriteTimeoutMs ?: 5000).toString()
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
                                onProfileUpdated = { updated ->
                                    profiles = profiles.map { if (it.id == updated.id) updated else it }
                                    profile = updated
                                    networkHostText = updated.networkHost.orEmpty()
                                    networkPortText = (updated.networkPort ?: 9100).toString()
                                    networkConnectTimeoutText = (updated.networkConnectTimeoutMs ?: 5000).toString()
                                    networkWriteTimeoutText = (updated.networkWriteTimeoutMs ?: 5000).toString()
                                    store.save(profiles)
                                },
                                onStatus = { result = it }
                            )
                            else -> FakePanel(
                                generated = generated,
                                profile = profile,
                                onStatus = { result = it }
                            )
                        }
                    }

                    1 -> {
                        Text("ПРОФИЛИ", style = MaterialTheme.typography.titleMedium)
                        ProfilePicker(profile, profiles) { selected ->
                            profile = selected
                            profileName = selected.displayName
                            dpiText = selected.dpi.toString()
                            paperWidthText = selected.paperWidthMm.toString()
                            paperHeightText = selected.paperHeightMm?.toString().orEmpty()
                            chunkSizeText = selected.chunkSize?.toString().orEmpty()
                            delayText = selected.delayBetweenChunksMs?.toString().orEmpty()
                            networkHostText = selected.networkHost.orEmpty()
                            networkPortText = (selected.networkPort ?: 9100).toString()
                            networkConnectTimeoutText = (selected.networkConnectTimeoutMs ?: 5000).toString()
                            networkWriteTimeoutText = (selected.networkWriteTimeoutMs ?: 5000).toString()
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
                        Button(onClick = {
                            val updated = profileFromEditor(
                                profile, profileName, dpiText, paperWidthText, paperHeightText, chunkSizeText, delayText,
                                networkHostText, networkPortText, networkConnectTimeoutText, networkWriteTimeoutText
                            ) ?: run {
                                result = "Проверьте параметры профиля: числа должны быть больше нуля, порт 1..65535"
                                return@Button
                            }
                            profiles = profiles.map { if (it.id == updated.id) updated else it }
                            profile = updated
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
                            profileName = created.displayName
                            dpiText = created.dpi.toString()
                            paperWidthText = created.paperWidthMm.toString()
                            paperHeightText = created.paperHeightMm?.toString().orEmpty()
                            chunkSizeText = created.chunkSize?.toString().orEmpty()
                            delayText = created.delayBetweenChunksMs?.toString().orEmpty()
                            networkHostText = created.networkHost.orEmpty()
                            networkPortText = (created.networkPort ?: 9100).toString()
                            networkConnectTimeoutText = (created.networkConnectTimeoutMs ?: 5000).toString()
                            networkWriteTimeoutText = (created.networkWriteTimeoutMs ?: 5000).toString()
                            generated = ByteArray(0)
                            store.save(profiles)
                            result = "Профиль создан"
                        }, modifier = Modifier.fillMaxWidth()) { Text("СОЗДАТЬ") }
                        Button(onClick = {
                            val duplicate = profile.copy(id = UUID.randomUUID().toString(), displayName = "${profile.displayName} (копия)", verified = false)
                            profiles = profiles + duplicate
                            profile = duplicate
                            profileName = duplicate.displayName
                            dpiText = duplicate.dpi.toString()
                            paperWidthText = duplicate.paperWidthMm.toString()
                            paperHeightText = duplicate.paperHeightMm?.toString().orEmpty()
                            chunkSizeText = duplicate.chunkSize?.toString().orEmpty()
                            delayText = duplicate.delayBetweenChunksMs?.toString().orEmpty()
                            networkHostText = duplicate.networkHost.orEmpty()
                            networkPortText = (duplicate.networkPort ?: 9100).toString()
                            networkConnectTimeoutText = (duplicate.networkConnectTimeoutMs ?: 5000).toString()
                            networkWriteTimeoutText = (duplicate.networkWriteTimeoutMs ?: 5000).toString()
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
                                profile = remaining.first()
                                profileName = profile.displayName
                                dpiText = profile.dpi.toString()
                                paperWidthText = profile.paperWidthMm.toString()
                                paperHeightText = profile.paperHeightMm?.toString().orEmpty()
                                chunkSizeText = profile.chunkSize?.toString().orEmpty()
                                delayText = profile.delayBetweenChunksMs?.toString().orEmpty()
                                networkHostText = profile.networkHost.orEmpty()
                                networkPortText = (profile.networkPort ?: 9100).toString()
                                networkConnectTimeoutText = (profile.networkConnectTimeoutMs ?: 5000).toString()
                                networkWriteTimeoutText = (profile.networkWriteTimeoutMs ?: 5000).toString()
                                generated = ByteArray(0)
                                store.save(remaining)
                                result = "Профиль удален"
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("УДАЛИТЬ") }
                        Button(onClick = {
                            profile = profile.copy(protocol = PrinterProtocol.ESC_POS, paperHeightMm = null, gapMm = null)
                            paperHeightText = ""
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
                            paperHeightText = updated.paperHeightMm.toString()
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
                            paperHeightText = updated.paperHeightMm.toString()
                            chunkSizeText = updated.chunkSize?.toString().orEmpty()
                            delayText = updated.delayBetweenChunksMs?.toString().orEmpty()
                            generated = ByteArray(0)
                            result = "Выбран GOOJPRT Label. Сохраните профиль"
                        }, modifier = Modifier.fillMaxWidth()) { Text("${if (profile.protocol == PrinterProtocol.GOOJPRT_LABEL) "ВЫБРАН: " else ""}GOOJPRT LABEL") }
                    }

                    else -> {
                        Text("ДИАГНОСТИКА", style = MaterialTheme.typography.titleMedium)
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

@Composable
private fun TransportPicker(selected: TransportType, onSelected: (TransportType) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(TransportType.USB, TransportType.BLUETOOTH_SPP, TransportType.TCP, TransportType.FAKE).forEach { transport ->
            Button(onClick = { onSelected(transport) }, modifier = Modifier.weight(1f)) {
                Text(if (selected == transport) "✓ ${transport.label()}" else transport.label(), maxLines = 1)
            }
        }
    }
}

@Composable
private fun FakePanel(
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
private fun UsbPanel(
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
            val transport = UsbPrinterTransport(usbManager, device, (profile.writeTimeoutMs ?: 5000L).toInt())
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
private fun BluetoothPanel(
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
            val transport = BluetoothSppPrinterTransport(context, address)
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
private fun TcpPanel(
    generated: ByteArray,
    profile: PrinterProfile,
    onProfileUpdated: (PrinterProfile) -> Unit,
    onStatus: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf(emptyList<NetworkPrinterCandidate>()) }
    var scanning by remember { mutableStateOf(false) }

    Text("TCP/IP", style = MaterialTheme.typography.titleMedium)
    Text(profile.networkHost?.let { "Адрес в профиле: $it:${profile.networkPort ?: 9100}" } ?: "TCP-адрес в профиле не задан")
    Button(onClick = {
        if (scanning) return@Button
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
                networkConnectTimeoutMs = profile.networkConnectTimeoutMs ?: 5000,
                networkWriteTimeoutMs = profile.networkWriteTimeoutMs ?: 5000,
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
        val port = profile.networkPort ?: 9100
        val connectTimeout = profile.networkConnectTimeoutMs ?: 5000
        val writeTimeout = profile.networkWriteTimeoutMs ?: 5000
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

private fun TransportType.label(): String = when (this) {
    TransportType.USB -> "USB"
    TransportType.BLUETOOTH_SPP -> "Bluetooth"
    TransportType.TCP -> "Network"
    TransportType.FAKE -> "Test"
    TransportType.BLUETOOTH_BLE -> "BLE"
}

private fun UsbManager.findDevice(profile: PrinterProfile): UsbDevice? =
    deviceList.values.firstOrNull { device ->
        device.vendorId == profile.usbVid && device.productId == profile.usbPid
    }

@Composable
private fun VirtualPrinterPanel(
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

private fun itemNameLabel(template: PrintJobTemplate, protocol: PrinterProtocol): String =
    when (template) {
        PrintJobTemplate.RECEIPT -> "Позиция"
        PrintJobTemplate.PRODUCT_LABEL -> "Название товара"
        PrintJobTemplate.QR_LABEL -> "Подпись"
        PrintJobTemplate.CONNECTION_TEST -> if (protocol == PrinterProtocol.TSPL) "Строка на этикетке" else "Строка в чеке"
    }

private fun itemPriceLabel(template: PrintJobTemplate, protocol: PrinterProtocol): String =
    when (template) {
        PrintJobTemplate.RECEIPT -> "Цена"
        PrintJobTemplate.PRODUCT_LABEL -> "Цена"
        PrintJobTemplate.QR_LABEL -> "Описание"
        PrintJobTemplate.CONNECTION_TEST -> if (protocol == PrinterProtocol.TSPL) "Описание" else "Дополнительная строка"
    }

private fun buildReadyJob(profile: PrinterProfile, draft: StoredPrintJobDraft): ByteArray =
    when (profile.protocol) {
        PrinterProtocol.ESC_POS -> when (draft.template) {
            PrintJobTemplate.RECEIPT -> buildReceipt(profile, draft)
            PrintJobTemplate.PRODUCT_LABEL -> buildProductReceipt(profile, draft)
            PrintJobTemplate.QR_LABEL -> buildQrReceipt(profile, draft)
            PrintJobTemplate.CONNECTION_TEST -> EscPosDriver().testPage(profile)
        }
        PrinterProtocol.TSPL -> when (draft.template) {
            PrintJobTemplate.RECEIPT -> buildLabel(profile, draft.copy(title = "Receipt", itemPrice = "Total: ${draft.itemPrice}"))
            PrintJobTemplate.PRODUCT_LABEL -> buildLabel(profile, draft)
            PrintJobTemplate.QR_LABEL -> buildQrLabel(profile, draft)
            PrintJobTemplate.CONNECTION_TEST -> TsplDriver().testPage(profile.copy(paperHeightMm = profile.paperHeightMm ?: 150f))
        }
        PrinterProtocol.GOOJPRT_LABEL -> when (draft.template) {
            PrintJobTemplate.RECEIPT -> buildGoojprtLabel(profile, draft.copy(title = "Receipt", itemPrice = "Total: ${draft.itemPrice}"))
            PrintJobTemplate.PRODUCT_LABEL -> buildGoojprtLabel(profile, draft)
            PrintJobTemplate.QR_LABEL -> buildGoojprtQrLabel(profile, draft)
            PrintJobTemplate.CONNECTION_TEST -> GoojprtLabelDriver().testPage(profile.copy(paperHeightMm = profile.paperHeightMm ?: 40f))
        }
        else -> ByteArray(0)
    }

private fun formatHistoryTime(timestamp: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))

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

private fun buildReceipt(profile: PrinterProfile, draft: StoredPrintJobDraft): ByteArray = EscPosDriver().build(profile) {
    initialize()
    align(com.printbridge.core.Alignment.CENTER)
    bold(true)
    scale(2, 2)
    textLine(draft.title.trim().ifBlank { "Receipt" })
    scale(1, 1)
    bold(false)
    textLine("")
    align(com.printbridge.core.Alignment.LEFT)
    textLine("Code: ${draft.itemCode.cleanValue("PB000001")}")
    textLine("------------------------")
    leftRight(draft.itemName.cleanValue("Item"), draft.itemPrice.cleanValue("0.00"), 24)
    textLine("")
    bold(true)
    leftRight("TOTAL", draft.itemPrice.cleanValue("0.00"), 24)
    bold(false)
    textLine("")
    align(com.printbridge.core.Alignment.CENTER)
    qr(draft.qrText.cleanValue(draft.itemCode.cleanValue("PB000001")))
    feed(3)
    if (profile.supportsCut) cut()
}

private fun buildProductReceipt(profile: PrinterProfile, draft: StoredPrintJobDraft): ByteArray = EscPosDriver().build(profile) {
    initialize()
    align(com.printbridge.core.Alignment.CENTER)
    bold(true)
    textLine(draft.title.cleanValue("Product"))
    bold(false)
    textLine("")
    align(com.printbridge.core.Alignment.LEFT)
    textLine(draft.itemName.cleanValue("Item"))
    textLine("SKU: ${draft.itemCode.cleanValue("PB000001")}")
    textLine("Price: ${draft.itemPrice.cleanValue("0.00")}")
    textLine("")
    align(com.printbridge.core.Alignment.CENTER)
    qr(draft.qrText.cleanValue(draft.itemCode.cleanValue("PB000001")))
    feed(3)
    if (profile.supportsCut) cut()
}

private fun buildQrReceipt(profile: PrinterProfile, draft: StoredPrintJobDraft): ByteArray = EscPosDriver().build(profile) {
    initialize()
    align(com.printbridge.core.Alignment.CENTER)
    bold(true)
    textLine(draft.title.cleanValue("QR"))
    bold(false)
    textLine(draft.itemName.cleanValue("Scan"))
    textLine(draft.itemPrice.cleanValue(""))
    textLine("")
    qr(draft.qrText.cleanValue(draft.itemCode.cleanValue("PB000001")))
    feed(3)
    if (profile.supportsCut) cut()
}

private fun buildLabel(profile: PrinterProfile, draft: StoredPrintJobDraft): ByteArray = TsplDriver().build(profile) {
    size(profile.paperWidthMm, profile.paperHeightMm ?: 150f)
    gap(profile.gapMm ?: 3f)
    density(8)
    speed(4)
    cls()
    box(20, 20, 780, 1120)
    text(80, 60, draft.title.cleanValue("Product"), xMul = 2, yMul = 2)
    text(80, 170, draft.itemName.cleanValue("Item"), xMul = 2, yMul = 2)
    text(80, 270, draft.itemPrice.cleanValue(" "))
    val code = draft.itemCode.cleanValue("PB000001")
    text(80, 350, code)
    barcode(80, 420, code)
    qrcode(80, 610, draft.qrText.cleanValue(code))
    print(1)
}

private fun buildQrLabel(profile: PrinterProfile, draft: StoredPrintJobDraft): ByteArray = TsplDriver().build(profile) {
    size(profile.paperWidthMm, profile.paperHeightMm ?: 150f)
    gap(profile.gapMm ?: 3f)
    density(8)
    speed(4)
    cls()
    box(20, 20, 780, 900)
    text(80, 60, draft.title.cleanValue("QR"), xMul = 2, yMul = 2)
    text(80, 170, draft.itemName.cleanValue("Scan"))
    text(80, 230, draft.itemPrice.cleanValue(" "))
    qrcode(80, 330, draft.qrText.cleanValue(draft.itemCode.cleanValue("PB000001")))
    print(1)
}

private fun buildGoojprtLabel(profile: PrinterProfile, draft: StoredPrintJobDraft): ByteArray = GoojprtLabelDriver().build(profile) {
    val width = com.printbridge.core.mmToDots(profile.paperWidthMm, profile.dpi).coerceAtMost(384)
    val height = com.printbridge.core.mmToDots(profile.paperHeightMm ?: 40f, profile.dpi).coerceAtMost(936)
    val code = draft.itemCode.cleanValue("PB000001")
    pageBegin(0, 0, width, height)
    box(16, 16, width - 16, height - 16)
    text(28, 36, draft.title.cleanValue("Product"), font = 32, bold = true)
    text(28, 92, draft.itemName.cleanValue("Item"), font = 24)
    text(28, 132, draft.itemPrice.cleanValue("0.00"), font = 24)
    text(28, 172, "SKU: $code", font = 24)
    barcode(28, 214, code)
    qr(width - 138, 86, draft.qrText.cleanValue(code))
    pageEnd()
    pagePrint(1)
}

private fun buildGoojprtQrLabel(profile: PrinterProfile, draft: StoredPrintJobDraft): ByteArray = GoojprtLabelDriver().build(profile) {
    val width = com.printbridge.core.mmToDots(profile.paperWidthMm, profile.dpi).coerceAtMost(384)
    val height = com.printbridge.core.mmToDots(profile.paperHeightMm ?: 40f, profile.dpi).coerceAtMost(936)
    pageBegin(0, 0, width, height)
    box(16, 16, width - 16, height - 16)
    text(28, 36, draft.title.cleanValue("QR"), font = 32, bold = true)
    text(28, 92, draft.itemName.cleanValue("Scan"), font = 24)
    text(28, 132, draft.itemPrice.cleanValue(" "), font = 24)
    qr(48, 180, draft.qrText.cleanValue(draft.itemCode.cleanValue("PB000001")), unitWidth = 4)
    pageEnd()
    pagePrint(1)
}

private fun String.cleanValue(default: String): String = trim().ifBlank { default }

private fun inspect(bytes: ByteArray, protocol: PrinterProtocol): String {
    if (bytes.isEmpty()) return "Байты еще не сформированы"
    return when (protocol) {
        PrinterProtocol.ESC_POS -> EscPosParser().parse(bytes).let { "Протокол: ${it.protocol}\nКоманд: ${it.commands.size}\nПредупреждений: ${it.warnings.size}\n\n${it.preview.lines.joinToString("\n")}" }
        PrinterProtocol.TSPL -> TsplParser().parse(bytes).let { "Протокол: ${it.protocol}\nКоманд: ${it.commands.size}\nПредупреждений: ${it.warnings.size}\n\n${it.preview.lines.joinToString("\n")}" }
        PrinterProtocol.GOOJPRT_LABEL -> GoojprtLabelParser().parse(bytes).let { "Протокол: ${it.protocol}\nКоманд: ${it.commands.size}\nПредупреждений: ${it.warnings.size}\n\n${it.preview.lines.joinToString("\n")}" }
        else -> "Предпросмотр не поддерживается"
    }
}

private fun profileFromEditor(
    profile: PrinterProfile,
    displayName: String,
    dpiText: String,
    paperWidthText: String,
    paperHeightText: String,
    chunkSizeText: String,
    delayText: String,
    networkHostText: String,
    networkPortText: String,
    networkConnectTimeoutText: String,
    networkWriteTimeoutText: String
): PrinterProfile? {
    val dpi = dpiText.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val width = paperWidthText.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f } ?: return null
    val height = paperHeightText.replace(',', '.').toFloatOrNull()
    if (height != null && height <= 0f) return null
    val chunkSize = chunkSizeText.toIntOrNull()
    if (chunkSize != null && chunkSize <= 0) return null
    val delay = delayText.toLongOrNull()
    if (delay != null && delay < 0) return null
    val host = networkHostText.trim().takeIf { it.isNotBlank() }
    val port = networkPortText.toIntOrNull() ?: 9100
    if (port !in 1..65535) return null
    val connectTimeout = networkConnectTimeoutText.toIntOrNull() ?: 5000
    if (connectTimeout <= 0) return null
    val writeTimeout = networkWriteTimeoutText.toIntOrNull() ?: 5000
    if (writeTimeout <= 0) return null
    return profile.copy(
        displayName = displayName.trim().ifBlank { profile.displayName },
        dpi = dpi,
        paperWidthMm = width,
        paperHeightMm = when (profile.protocol) {
            PrinterProtocol.TSPL -> height ?: 150f
            PrinterProtocol.GOOJPRT_LABEL -> height ?: 40f
            else -> null
        },
        chunkSize = chunkSize,
        delayBetweenChunksMs = delay,
        networkHost = host,
        networkPort = port,
        networkConnectTimeoutMs = connectTimeout,
        networkWriteTimeoutMs = writeTimeout
    )
}
