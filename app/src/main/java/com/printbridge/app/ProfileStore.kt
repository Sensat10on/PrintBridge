package com.printbridge.app

import android.content.Context
import androidx.core.content.edit
import com.printbridge.core.DefaultProfiles
import com.printbridge.core.PrinterProfile
import com.printbridge.core.PrinterProtocol
import com.printbridge.core.TransportType
import org.json.JSONArray
import org.json.JSONObject

enum class PrintJobTemplate(val label: String) {
    RECEIPT("Чек"),
    PRODUCT_LABEL("Этикетка"),
    QR_LABEL("QR-наклейка"),
    CONNECTION_TEST("Тест связи")
}

data class StoredPrintJobDraft(
    val template: PrintJobTemplate = PrintJobTemplate.RECEIPT,
    val title: String = "PrintBridge",
    val itemName: String = "Product A",
    val itemPrice: String = "12.50",
    val itemCode: String = "PB000001",
    val qrText: String = "https://printbridge.local/job/PB000001"
)

data class StoredPrintJobHistoryItem(
    val id: String,
    val createdAt: Long,
    val profileId: String,
    val protocol: PrinterProtocol,
    val draft: StoredPrintJobDraft
)

class ProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("printbridge_profiles", Context.MODE_PRIVATE)

    fun load(): List<PrinterProfile> = runCatching {
        val raw = preferences.getString(KEY, null) ?: return DefaultProfiles.all
        val values = JSONArray(raw)
        mergeWithDefaults(List(values.length()) { index -> values.getJSONObject(index).toProfile() })
    }.getOrElse { DefaultProfiles.all }

    fun save(profiles: List<PrinterProfile>) {
        val payload = JSONArray().apply { profiles.forEach { put(it.toJson()) } }.toString()
        preferences.edit { putString(KEY, payload) }
    }

    fun loadDraft(): StoredPrintJobDraft = runCatching {
        val raw = preferences.getString(DRAFT_KEY, null) ?: return StoredPrintJobDraft()
        JSONObject(raw).toDraft()
    }.getOrElse { StoredPrintJobDraft() }

    fun saveDraft(draft: StoredPrintJobDraft) {
        preferences.edit { putString(DRAFT_KEY, draft.toJson().toString()) }
    }

    fun loadHistory(): List<StoredPrintJobHistoryItem> = runCatching {
        val raw = preferences.getString(HISTORY_KEY, null) ?: return emptyList()
        val values = JSONArray(raw)
        List(values.length()) { index -> values.getJSONObject(index).toHistoryItem() }
    }.getOrElse { emptyList() }

    fun addHistoryItem(item: StoredPrintJobHistoryItem) {
        val next = (listOf(item) + loadHistory().filterNot { it.draft == item.draft && it.profileId == item.profileId })
            .take(MAX_HISTORY_ITEMS)
        saveHistory(next)
    }

    fun clearHistory() {
        preferences.edit { remove(HISTORY_KEY) }
    }

    private fun PrinterProfile.toJson() = JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        put("manufacturer", manufacturer)
        put("model", model)
        put("alternativeNames", JSONArray(alternativeNames))
        put("bluetoothNamePatterns", JSONArray(bluetoothNamePatterns))
        put("bluetoothDeviceName", bluetoothDeviceName)
        put("bluetoothDeviceAddress", bluetoothDeviceAddress)
        put("networkHost", networkHost)
        put("networkPort", networkPort)
        put("networkConnectTimeoutMs", networkConnectTimeoutMs)
        put("networkWriteTimeoutMs", networkWriteTimeoutMs)
        put("protocol", protocol.name)
        put("dpi", dpi)
        put("paperWidthMm", paperWidthMm.toDouble())
        put("paperHeightMm", paperHeightMm?.toDouble())
        put("gapMm", gapMm?.toDouble())
        put("transportType", transportType.name)
        put("supportsCut", supportsCut)
        put("supportsStatus", supportsStatus)
        put("chunkSize", chunkSize)
        put("delayBetweenChunksMs", delayBetweenChunksMs)
        put("writeTimeoutMs", writeTimeoutMs)
        put("usbVid", usbVid)
        put("usbPid", usbPid)
        put("knownQuirks", JSONArray(knownQuirks))
        put("verified", verified)
        put("notes", notes)
    }

    private fun JSONObject.toProfile() = PrinterProfile(
        id = getString("id"),
        displayName = getString("displayName"),
        manufacturer = optString("manufacturer").takeIf { it.isNotBlank() },
        model = optString("model").takeIf { it.isNotBlank() },
        alternativeNames = optStringArray("alternativeNames"),
        bluetoothNamePatterns = optStringArray("bluetoothNamePatterns"),
        bluetoothDeviceName = optString("bluetoothDeviceName").takeIf { it.isNotBlank() },
        bluetoothDeviceAddress = optString("bluetoothDeviceAddress").takeIf { it.isNotBlank() },
        networkHost = optString("networkHost").takeIf { it.isNotBlank() },
        networkPort = nullableInt("networkPort"),
        networkConnectTimeoutMs = nullableInt("networkConnectTimeoutMs"),
        networkWriteTimeoutMs = nullableInt("networkWriteTimeoutMs"),
        protocol = enumValue("protocol", PrinterProtocol.ESC_POS),
        dpi = getInt("dpi"),
        paperWidthMm = getDouble("paperWidthMm").toFloat(),
        paperHeightMm = nullableDouble("paperHeightMm"),
        gapMm = nullableDouble("gapMm"),
        transportType = enumValue("transportType", TransportType.FAKE),
        supportsCut = getBoolean("supportsCut"),
        supportsStatus = getBoolean("supportsStatus"),
        chunkSize = nullableInt("chunkSize"),
        delayBetweenChunksMs = nullableLong("delayBetweenChunksMs"),
        writeTimeoutMs = nullableLong("writeTimeoutMs"),
        usbVid = nullableInt("usbVid"),
        usbPid = nullableInt("usbPid"),
        knownQuirks = optStringArray("knownQuirks"),
        verified = optBoolean("verified", true),
        notes = optString("notes").takeIf { it.isNotBlank() }
    )

    private fun mergeWithDefaults(saved: List<PrinterProfile>): List<PrinterProfile> {
        val savedById = saved.associateBy { it.id }
        val defaultIds = DefaultProfiles.all.map { it.id }.toSet()
        val mergedDefaults = DefaultProfiles.all.map { default ->
            savedById[default.id]?.let { savedProfile -> default.withSavedRuntimeFields(savedProfile) } ?: default
        }
        return mergedDefaults + saved.filterNot { it.id in defaultIds }
    }

    private fun PrinterProfile.withSavedRuntimeFields(saved: PrinterProfile) = copy(
        displayName = saved.displayName,
        manufacturer = saved.manufacturer ?: manufacturer,
        model = saved.model ?: model,
        alternativeNames = saved.alternativeNames.ifEmpty { alternativeNames },
        bluetoothNamePatterns = saved.bluetoothNamePatterns.ifEmpty { bluetoothNamePatterns },
        bluetoothDeviceName = saved.bluetoothDeviceName,
        bluetoothDeviceAddress = saved.bluetoothDeviceAddress,
        networkHost = saved.networkHost,
        networkPort = saved.networkPort,
        networkConnectTimeoutMs = saved.networkConnectTimeoutMs,
        networkWriteTimeoutMs = saved.networkWriteTimeoutMs,
        protocol = saved.protocol,
        dpi = saved.dpi,
        paperWidthMm = saved.paperWidthMm,
        paperHeightMm = saved.paperHeightMm,
        gapMm = saved.gapMm,
        transportType = saved.transportType,
        supportsCut = saved.supportsCut,
        supportsStatus = saved.supportsStatus,
        chunkSize = saved.chunkSize,
        delayBetweenChunksMs = saved.delayBetweenChunksMs,
        writeTimeoutMs = saved.writeTimeoutMs,
        usbVid = saved.usbVid,
        usbPid = saved.usbPid,
        knownQuirks = saved.knownQuirks.ifEmpty { knownQuirks },
        verified = saved.verified,
        notes = saved.notes ?: notes
    )

    private fun StoredPrintJobDraft.toJson() = JSONObject().apply {
        put("template", template.name)
        put("title", title)
        put("itemName", itemName)
        put("itemPrice", itemPrice)
        put("itemCode", itemCode)
        put("qrText", qrText)
    }

    private fun JSONObject.toDraft() = StoredPrintJobDraft(
        template = runCatching { PrintJobTemplate.valueOf(optString("template", PrintJobTemplate.RECEIPT.name)) }
            .getOrDefault(PrintJobTemplate.RECEIPT),
        title = optString("title", StoredPrintJobDraft().title),
        itemName = optString("itemName", StoredPrintJobDraft().itemName),
        itemPrice = optString("itemPrice", StoredPrintJobDraft().itemPrice),
        itemCode = optString("itemCode", StoredPrintJobDraft().itemCode),
        qrText = optString("qrText", StoredPrintJobDraft().qrText)
    )

    private fun StoredPrintJobHistoryItem.toJson() = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("profileId", profileId)
        put("protocol", protocol.name)
        put("draft", draft.toJson())
    }

    private fun JSONObject.toHistoryItem() = StoredPrintJobHistoryItem(
        id = getString("id"),
        createdAt = getLong("createdAt"),
        profileId = getString("profileId"),
        protocol = PrinterProtocol.valueOf(getString("protocol")),
        draft = getJSONObject("draft").toDraft()
    )

    private fun saveHistory(items: List<StoredPrintJobHistoryItem>) {
        val payload = JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()
        preferences.edit { putString(HISTORY_KEY, payload) }
    }

    private fun JSONObject.nullableDouble(name: String): Float? = if (isNull(name)) null else getDouble(name).toFloat()
    private fun JSONObject.nullableInt(name: String): Int? = if (isNull(name)) null else getInt(name)
    private fun JSONObject.nullableLong(name: String): Long? = if (isNull(name)) null else getLong(name)
    private inline fun <reified T : Enum<T>> JSONObject.enumValue(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(optString(name, fallback.name)) }.getOrDefault(fallback)

    private fun JSONObject.optStringArray(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }
    }

    private companion object {
        const val KEY = "profiles"
        const val DRAFT_KEY = "last_print_job_draft"
        const val HISTORY_KEY = "print_job_history"
        const val MAX_HISTORY_ITEMS = 20
    }
}
