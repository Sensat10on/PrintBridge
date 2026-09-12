package com.printbridge.app

import android.content.Context
import androidx.core.content.edit
import com.printbridge.drivers.WatermarkComposer

/**
 * Free / paid split for PrintBridge.
 *
 * Two rules describe the entitlement, and both live here so every print path agrees:
 *
 * 1. **One sheet per job.** The free version prints a single sheet — the first page of a
 *    multi-page document, one copy of an image or template. Printing the rest of a document is
 *    what the license unlocks.
 * 2. **Every unlicensed print carries a watermark**, applied by `WatermarkComposer` on the way to
 *    the transport. Previews always show the real job, and a licensed build produces output
 *    identical to the plain drivers.
 *
 * Both rules are enforced only in the `paid` build. The `full` test build is compiled with
 * `FREE_TIER_ENFORCED = false` and behaves as if the license were always present, so testers get
 * every feature without a purchase. This is a build-time flag rather than a runtime switch on
 * purpose: a shipped APK must not be able to turn the limits off.
 *
 * Purchase handling is deliberately a local entitlement only: Google Play Billing is not wired up
 * yet, so [grantLicense] exists for development. The storage format is a single boolean on purpose
 * so a real Billing client can be dropped in later without changing callers: replace
 * [isLicensed] with a Billing query and call [grantLicense] on `PURCHASED`/`RESTORED`.
 */
class LicenseStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * A promo code grants the same entitlement a purchase does, so it is consulted here instead of
     * forking the free/paid rules. Promo storage lives in [PromoActivationStore] and stays separate
     * so a promo can be withdrawn without touching a purchase.
     */
    private val promos = PromoActivationStore(context)

    /** True when the user owns the watermark-free, unlimited version, by purchase or by promo. */
    fun isLicensed(): Boolean = isLicensedFor(BuildConfig.FREE_TIER_ENFORCED)

    /**
     * Entitlement under an explicit policy. [enforced] mirrors `FREE_TIER_ENFORCED`: when the
     * free tier is not enforced (the `full` test build) the license is always present.
     */
    fun isLicensedFor(enforced: Boolean): Boolean =
        !enforced || preferences.getBoolean(KEY_LICENSED, false) || promos.isActivated()

    /**
     * Watermark text for the current entitlement, or null when the job must be printed unmarked.
     */
    fun watermarkText(configured: String? = null): String? =
        watermarkTextFor(configured, BuildConfig.FREE_TIER_ENFORCED)

    /** Watermark text under an explicit policy; see [isLicensedFor]. */
    fun watermarkTextFor(configured: String?, enforced: Boolean): String? =
        if (isLicensedFor(enforced)) null else (configured ?: WatermarkComposer.DEFAULT_TEXT)

    /** How many sheets one print command may produce under the current entitlement. */
    fun maxSheetsPerJob(): Int = maxSheetsPerJobFor(BuildConfig.FREE_TIER_ENFORCED)

    /** Sheet limit under an explicit policy; see [isLicensedFor]. */
    fun maxSheetsPerJobFor(enforced: Boolean): Int =
        if (isLicensedFor(enforced)) Int.MAX_VALUE else FREE_SHEETS_PER_JOB

    fun grantLicense() {
        preferences.edit { putBoolean(KEY_LICENSED, true) }
    }

    fun revokeLicense() {
        preferences.edit { remove(KEY_LICENSED) }
    }

    companion object {
        const val PREFERENCES_NAME = "printbridge_license"
        const val KEY_LICENSED = "watermark_removed"

        /** The free version prints exactly one sheet per job. */
        const val FREE_SHEETS_PER_JOB = 1

        /** Upper bound for the copies selector, independent of the entitlement. */
        const val MAX_SELECTABLE_COPIES = 20

        /** Placeholder product id; replace together with the real Billing integration. */
        const val PRODUCT_WATERMARK_REMOVAL = "printbridge.remove_watermark"
    }
}

/**
 * Pages that a single print command may produce for [document].
 *
 * Used both to render the page selector and to enforce the limit before sending, so the UI can
 * never offer a page that would be refused.
 */
internal fun LicenseStore.allowedPages(
    document: PrintableDocument,
    enforced: Boolean = BuildConfig.FREE_TIER_ENFORCED
): List<Int> {
    val available = document.pages.indices.toList()
    if (available.isEmpty()) return emptyList()
    return available.take(maxSheetsPerJobFor(enforced))
}

/** Copies that a single print command may produce for a ready-job template. */
internal fun LicenseStore.allowedCopies(
    requested: Int,
    enforced: Boolean = BuildConfig.FREE_TIER_ENFORCED
): Int = requested.coerceIn(1, LicenseStore.MAX_SELECTABLE_COPIES).coerceAtMost(maxSheetsPerJobFor(enforced))

/** True when the document has more pages than the entitlement will print. */
internal fun LicenseStore.isPageLimitReached(
    document: PrintableDocument,
    enforced: Boolean = BuildConfig.FREE_TIER_ENFORCED
): Boolean = document.pages.size > maxSheetsPerJobFor(enforced)

/**
 * Watermark text to apply for [profile] given the current entitlement, or null when the job must
 * be printed unmarked. Print paths call this instead of reading the store directly so the rule
 * lives in one place.
 */
internal fun LicenseStore.watermarkTextFor(
    profile: com.printbridge.core.PrinterProfile,
    enforced: Boolean = BuildConfig.FREE_TIER_ENFORCED
): String? = watermarkTextFor(profile.watermarkText, enforced)
