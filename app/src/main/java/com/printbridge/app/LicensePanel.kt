package com.printbridge.app

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
import androidx.compose.ui.Modifier
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
}
