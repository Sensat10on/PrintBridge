package com.printbridge.app

import com.printbridge.core.PrinterProfile
import com.printbridge.core.PrinterProtocol
import com.printbridge.drivers.EscPosDriver
import com.printbridge.drivers.GoojprtLabelDriver
import com.printbridge.drivers.TsplDriver
import com.printbridge.simulator.EscPosParser
import com.printbridge.simulator.GoojprtLabelParser
import com.printbridge.simulator.TsplParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Bounds for the profile editor. Values outside these ranges are rejected by
// profileFromEditor() instead of reaching a transport and failing mid-job.
internal const val DEFAULT_NETWORK_PORT = 9100
internal const val DEFAULT_NETWORK_CONNECT_TIMEOUT_MS = 5000
internal const val DEFAULT_NETWORK_WRITE_TIMEOUT_MS = 5000
internal const val DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS = 10_000L
internal const val MIN_TIMEOUT_MS = 100
internal const val MAX_TIMEOUT_MS = 120_000
internal const val MAX_CHUNK_SIZE_BYTES = 64 * 1024
internal const val MAX_DELAY_BETWEEN_CHUNKS_MS = 60_000L
internal const val MAX_DPI = 2400
internal const val MAX_PAPER_WIDTH_MM = 1000f

/** The values shown in the profile form. */
internal data class ProfileEditorValues(
    val displayName: String,
    val dpi: String,
    val paperWidth: String,
    val paperHeight: String,
    val chunkSize: String,
    val delayBetweenChunks: String,
    val writeTimeout: String,
    val networkHost: String,
    val networkPort: String,
    val networkConnectTimeout: String,
    val networkWriteTimeout: String
)

/** Editor representation of a profile, used by the profile form. */
internal fun PrinterProfile.toEditorValues() = ProfileEditorValues(
    displayName = displayName,
    dpi = dpi.toString(),
    paperWidth = paperWidthMm.toString(),
    paperHeight = paperHeightMm?.toString().orEmpty(),
    chunkSize = chunkSize?.toString().orEmpty(),
    delayBetweenChunks = delayBetweenChunksMs?.toString().orEmpty(),
    writeTimeout = (writeTimeoutMs ?: DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS).toString(),
    networkHost = networkHost.orEmpty(),
    networkPort = (networkPort ?: DEFAULT_NETWORK_PORT).toString(),
    networkConnectTimeout = (networkConnectTimeoutMs ?: DEFAULT_NETWORK_CONNECT_TIMEOUT_MS).toString(),
    networkWriteTimeout = (networkWriteTimeoutMs ?: DEFAULT_NETWORK_WRITE_TIMEOUT_MS).toString()
)

internal fun itemNameLabel(template: PrintJobTemplate, protocol: PrinterProtocol): String =
    when (template) {
        PrintJobTemplate.RECEIPT -> "Позиция"
        PrintJobTemplate.PRODUCT_LABEL -> "Название товара"
        PrintJobTemplate.QR_LABEL -> "Подпись"
        PrintJobTemplate.CONNECTION_TEST -> if (protocol == PrinterProtocol.TSPL) "Строка на этикетке" else "Строка в чеке"
    }

internal fun itemPriceLabel(template: PrintJobTemplate, protocol: PrinterProtocol): String =
    when (template) {
        PrintJobTemplate.RECEIPT -> "Цена"
        PrintJobTemplate.PRODUCT_LABEL -> "Цена"
        PrintJobTemplate.QR_LABEL -> "Описание"
        PrintJobTemplate.CONNECTION_TEST -> if (protocol == PrinterProtocol.TSPL) "Описание" else "Дополнительная строка"
    }

internal fun formatHistoryTime(timestamp: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))

internal fun buildReadyJob(profile: PrinterProfile, draft: StoredPrintJobDraft): ByteArray =
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

internal fun inspect(bytes: ByteArray, protocol: PrinterProtocol): String {
    if (bytes.isEmpty()) return "Байты еще не сформированы"
    return when (protocol) {
        PrinterProtocol.ESC_POS -> EscPosParser().parse(bytes).let { "Протокол: ${it.protocol}\nКоманд: ${it.commands.size}\nПредупреждений: ${it.warnings.size}\n\n${it.preview.lines.joinToString("\n")}" }
        PrinterProtocol.TSPL -> TsplParser().parse(bytes).let { "Протокол: ${it.protocol}\nКоманд: ${it.commands.size}\nПредупреждений: ${it.warnings.size}\n\n${it.preview.lines.joinToString("\n")}" }
        PrinterProtocol.GOOJPRT_LABEL -> GoojprtLabelParser().parse(bytes).let { "Протокол: ${it.protocol}\nКоманд: ${it.commands.size}\nПредупреждений: ${it.warnings.size}\n\n${it.preview.lines.joinToString("\n")}" }
        else -> "Предпросмотр не поддерживается"
    }
}

/**
 * Validates the profile form. Returns `null` when any field is out of range, so the UI reports a
 * message instead of persisting a profile that would fail later inside a transport.
 */
internal fun profileFromEditor(
    profile: PrinterProfile,
    displayName: String,
    dpiText: String,
    paperWidthText: String,
    paperHeightText: String,
    chunkSizeText: String,
    delayText: String,
    writeTimeoutText: String,
    networkHostText: String,
    networkPortText: String,
    networkConnectTimeoutText: String,
    networkWriteTimeoutText: String
): PrinterProfile? {
    val dpi = dpiText.toIntOrNull()?.takeIf { it in 1..MAX_DPI } ?: return null
    val width = paperWidthText.replace(',', '.').toFloatOrNull()?.takeIf { it in 0.1f..MAX_PAPER_WIDTH_MM } ?: return null
    val height = paperHeightText.replace(',', '.').toFloatOrNull()
    if (height != null && height !in 0.1f..MAX_PAPER_WIDTH_MM) return null
    val chunkSize = chunkSizeText.toIntOrNull()
    if (chunkSize != null && chunkSize !in 1..MAX_CHUNK_SIZE_BYTES) return null
    val delay = delayText.toLongOrNull()
    if (delay != null && delay !in 0..MAX_DELAY_BETWEEN_CHUNKS_MS) return null
    val host = networkHostText.trim().takeIf { it.isNotBlank() }
    val port = networkPortText.toIntOrNull() ?: DEFAULT_NETWORK_PORT
    if (port !in 1..65535) return null
    val connectTimeout = networkConnectTimeoutText.toIntOrNull() ?: DEFAULT_NETWORK_CONNECT_TIMEOUT_MS
    if (connectTimeout !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) return null
    val networkWriteTimeout = networkWriteTimeoutText.toIntOrNull() ?: DEFAULT_NETWORK_WRITE_TIMEOUT_MS
    if (networkWriteTimeout !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) return null
    val transportWriteTimeout = writeTimeoutText.toLongOrNull() ?: DEFAULT_TRANSPORT_WRITE_TIMEOUT_MS
    if (transportWriteTimeout !in MIN_TIMEOUT_MS.toLong()..MAX_TIMEOUT_MS.toLong()) return null
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
        writeTimeoutMs = transportWriteTimeout,
        networkHost = host,
        networkPort = port,
        networkConnectTimeoutMs = connectTimeout,
        networkWriteTimeoutMs = networkWriteTimeout
    )
}
