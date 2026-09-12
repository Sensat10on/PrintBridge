package com.printbridge.app

import android.content.Context
import androidx.core.content.edit
import com.printbridge.drivers.WatermarkComposer

/**
 * Free / paid split for PrintBridge.
 *
 * Business rule: **without a license every printed job carries a watermark**. The mark is applied
 * by `WatermarkComposer` on the way to the transport, so previews always show the real job and a
 * licensed build produces output identical to the plain drivers.
 *
 * Purchase handling is deliberately a local entitlement only: Google Play Billing is not wired up
 * yet, so [grantLicense] exists for development. The storage format is a single boolean on purpose
 * so a real Billing client can be dropped in later without changing callers: replace
 * [isLicensed] with a Billing query and call [grantLicense] on `PURCHASED`/`RESTORED`.
 */
class LicenseStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** True when the user owns the watermark-free version. */
    fun isLicensed(): Boolean = preferences.getBoolean(KEY_LICENSED, false)

    /**
     * Watermark text for the current entitlement, or null when the job must be printed unmarked.
     */
    fun watermarkText(configured: String? = null): String? =
        if (isLicensed()) null else (configured ?: WatermarkComposer.DEFAULT_TEXT)

    fun grantLicense() {
        preferences.edit { putBoolean(KEY_LICENSED, true) }
    }

    fun revokeLicense() {
        preferences.edit { remove(KEY_LICENSED) }
    }

    companion object {
        const val PREFERENCES_NAME = "printbridge_license"
        const val KEY_LICENSED = "watermark_removed"

        /** Placeholder product id; replace together with the real Billing integration. */
        const val PRODUCT_WATERMARK_REMOVAL = "printbridge.remove_watermark"
    }
}

/**
 * Watermark text to apply for [profile] given the current entitlement, or null when the job must
 * be printed unmarked. Print paths call this instead of reading the store directly so the rule
 * lives in one place.
 */
internal fun LicenseStore.watermarkTextFor(profile: com.printbridge.core.PrinterProfile): String? =
    watermarkText(profile.watermarkText)
