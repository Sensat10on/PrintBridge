package com.printbridge.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.printbridge.drivers.WatermarkComposer

/**
 * Shows and toggles the free/paid entitlement.
 *
 * Two builds exist: `paid` enforces the free tier and is what ships, `full` is the internal test
 * build compiled with `FREE_TIER_ENFORCED = false`. The panel says which one is running so a
 * tester is never left guessing why nothing is locked.
 *
 * Google Play Billing is not connected yet, so the purchase button explains that and the
 * development buttons flip the local entitlement — the same storage a real Billing client
 * will write to.
 */
@Composable
internal fun LicensePanel(
    licensed: Boolean,
    store: LicenseStore,
    onLicensedChanged: (Boolean) -> Unit
) {
    val freeTierEnforced = BuildConfig.FREE_TIER_ENFORCED

    Text("ЛИЦЕНЗИЯ", style = MaterialTheme.typography.titleMedium)
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    !freeTierEnforced -> "Сборка: полная тестовая — всё открыто"
                    licensed -> "Сборка: платная — метка снята"
                    else -> "Сборка: платная, бесплатный режим — метка и один лист"
                }
            )
            Text(
                when {
                    !freeTierEnforced ->
                        "Тестовая сборка (flavor full): лимит листов и метка отключены на уровне " +
                            "компиляции, покупка не нужна."
                    licensed -> "Печать идёт без метки, документ печатается целиком."
                    else ->
                        "На каждой печати ставится «${WatermarkComposer.DEFAULT_TEXT}» и " +
                            "отправляется один лист за задание. Текст метки меняется в профиле."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (!freeTierEnforced) {
        Text(
            "Это внутренняя сборка для тестов: она не предназначена для распространения.",
            style = MaterialTheme.typography.bodySmall
        )
        return
    }

    Button(
        onClick = { },
        modifier = Modifier.fillMaxWidth(),
        enabled = !licensed
    ) { Text(if (licensed) "УЖЕ КУПЛЕНО" else "КУПИТЬ БЕЗ МЕТКИ") }
    Text(
        "Покупки пока не подключены: Google Play Billing будет добавлен отдельно " +
            "(product id «${LicenseStore.PRODUCT_WATERMARK_REMOVAL}»).",
        style = MaterialTheme.typography.bodySmall
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                store.grantLicense()
                onLicensedChanged(true)
            },
            modifier = Modifier.weight(1f),
            enabled = !licensed
        ) { Text("ВКЛЮЧИТЬ (ТЕСТ)") }
        Button(
            onClick = {
                store.revokeLicense()
                onLicensedChanged(false)
            },
            modifier = Modifier.weight(1f),
            enabled = licensed
        ) { Text("СБРОСИТЬ (ТЕСТ)") }
    }
    Text(
        "Кнопки выше — только для разработки: включают и снимают лицензию локально, " +
            "чтобы проверить оба режима без покупки.",
        style = MaterialTheme.typography.bodySmall
    )
    PromoCodeSection(licensed = licensed, onLicensedChanged = onLicensedChanged)
}

/**
 * Promo code entry. A promo unlocks the same entitlement a purchase does; it is a publisher
 * channel (bundles, support, giveaways), not a payment path, so it lives next to the licence
 * controls and does not replace Billing.
 */
@Composable
private fun PromoCodeSection(licensed: Boolean, onLicensedChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { PromoActivationStore(context) }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val appliedSerial = remember(licensed) { store.appliedSerial() }

    Text("ПРОМО-КОД", style = MaterialTheme.typography.titleMedium)
    Text(
        appliedSerial?.let { "Активирован промо-код №$it." }
            ?: "Промо-код выдаёт издатель. Он открывает те же возможности, что и покупка, и не заменяет её.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Промо-код") },
        singleLine = false,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = {
            val result = store.activate(code)
            isError = result !is PromoActivation.PromoResult.Valid
            status = when (result) {
                is PromoActivation.PromoResult.Valid -> "Промо-код №${result.serial} активирован"
                PromoActivation.PromoResult.Invalid ->
                    if (store.attemptsLeft() == 0) {
                        "Попытки исчерпаны"
                    } else {
                        "Код не подходит. Осталось попыток: ${store.attemptsLeft()}"
                    }
                PromoActivation.PromoResult.Malformed -> "Это не похоже на промо-код"
                PromoActivation.PromoResult.UnsupportedPlatform ->
                    "Промо-коды требуют Android 13 или новее"
            }
            if (result is PromoActivation.PromoResult.Valid) {
                onLicensedChanged(true)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = code.isNotBlank() && store.attemptsLeft() > 0
    ) { Text("ПРИМЕНИТЬ ПРОМО-КОД") }
    if (status.isNotBlank()) {
        Text(
            status,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (appliedSerial != null) {
        Button(
            onClick = {
                store.clear()
                status = "Промо-код снят"
                isError = false
                onLicensedChanged(false)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("СНЯТЬ ПРОМО-КОД (ТЕСТ)") }
    }
}
