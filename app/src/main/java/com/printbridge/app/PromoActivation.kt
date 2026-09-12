package com.printbridge.app

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Promo codes.
 *
 * A promo is **not** a payment path and does not replace Google Play Billing: it is a signed
 * offline code a publisher hands out (bundles, support cases, giveaways) and it unlocks exactly
 * what the licence unlocks. The entitlement model itself is unchanged.
 *
 * Format: `PB1.<base64url>` where the payload is a version byte, a 4-byte serial and a full
 * Ed25519 signature over `version || serial`. The public key is compiled into the app, so the
 * private key never leaves the publisher's machine and a code cannot be forged without it.
 * Base64 keeps byte alignment, so no padding bits have to be invented; codes are meant to be
 * pasted, not typed.
 *
 * Notes worth keeping in mind:
 *
 * - Verification uses the platform Ed25519 provider, which exists from API 33. On older devices
 *   [PromoActivation.verify] reports [PromoResult.UnsupportedPlatform] instead of silently
 *   rejecting a valid code.
 * - The applied serial is recorded, so a code can be recognised in a support request and cannot
 *   stack entitlements, and the number of attempts is capped in [PromoActivationStore] so codes
 *   cannot be brute forced from the UI.
 * - The public key below matches `build/devtools/KeyTool.java`; the private key is intentionally
 *   not in the repository, which means losing it means issuing a new key pair in a new build.
 */
object PromoActivation {
    /** Version byte of the current format; bump only together with the encoder tool. */
    const val FORMAT_VERSION: Int = 1

    /** Start of every code, so a pasted string is recognisable as a promo. */
    const val PREFIX: String = "PB1."

    private const val SERIAL_BYTES = 4
    private const val SIGNATURE_BYTES = 64
    private const val PAYLOAD_BYTES = 1 + SERIAL_BYTES + SIGNATURE_BYTES

    /**
     * Raw 32-byte Ed25519 public key, base64. Generated with `build/devtools/KeyTool.java`.
     */
    private const val PUBLIC_KEY_BASE64 = "KfE/6Byg5IkmozwmhasYnDXV2Bd1VVrXeLUtF43cwLI="


    sealed interface PromoResult {
        /** Code is valid; [serial] identifies it. */
        data class Valid(val serial: Int) : PromoResult

        /** Code is well formed but the signature does not match. */
        data object Invalid : PromoResult

        /** Empty or malformed input — not even a code. */
        data object Malformed : PromoResult

        /** The platform has no Ed25519 provider (Android 12 and older). */
        data object UnsupportedPlatform : PromoResult
    }

    /**
     * Verifies [code] against [publicKeyBase64], which defaults to the key compiled into the app.
     *
     * The parameter exists so tests can sign with an ephemeral key pair instead of needing the
     * publisher's private key; production callers never pass it.
     */
    fun verify(code: String, publicKeyBase64: String = PUBLIC_KEY_BASE64): PromoResult {
        val payload = decode(code) ?: return PromoResult.Malformed
        if (payload[0].toInt() != FORMAT_VERSION) return PromoResult.Malformed
        val serial = serialOf(payload)
        return when (verifySignature(payload, serial, publicKeyBase64)) {
            SignatureStatus.OK -> PromoResult.Valid(serial)
            SignatureStatus.BAD -> PromoResult.Invalid
            SignatureStatus.UNSUPPORTED -> PromoResult.UnsupportedPlatform
        }
    }

    private enum class SignatureStatus { OK, BAD, UNSUPPORTED }

    private fun verifySignature(payload: ByteArray, serial: Int, publicKeyBase64: String): SignatureStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return SignatureStatus.UNSUPPORTED
        return try {
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey(publicKeyBase64))
            verifier.update(signedMessage(serial))
            if (verifier.verify(payload.copyOfRange(5, PAYLOAD_BYTES))) SignatureStatus.OK else SignatureStatus.BAD
        } catch (_: Exception) {
            // A malformed signature makes verify() throw on some providers; that is still a
            // rejection, but a missing provider must be reported differently.
            if (hasProvider()) SignatureStatus.BAD else SignatureStatus.UNSUPPORTED
        }
    }

    private fun hasProvider(): Boolean = runCatching { Signature.getInstance("Ed25519") }.isSuccess

    /** Message the publisher signs: version byte followed by the serial. */
    internal fun signedMessage(serial: Int): ByteArray =
        byteArrayOf(
            FORMAT_VERSION.toByte(),
            (serial ushr 24).toByte(),
            (serial ushr 16).toByte(),
            (serial ushr 8).toByte(),
            serial.toByte()
        )

    internal fun serialOf(payload: ByteArray): Int =
        ((payload[1].toInt() and 0xff) shl 24) or
            ((payload[2].toInt() and 0xff) shl 16) or
            ((payload[3].toInt() and 0xff) shl 8) or
            (payload[4].toInt() and 0xff)

    private fun publicKey(publicKeyBase64: String): PublicKey {
        val raw = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        // Wrap the raw 32 bytes into the X.509 SubjectPublicKeyInfo envelope Ed25519 expects.
        val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(prefix + raw))
    }

    /** Base64 of the raw public key a code must be signed with; exposed for tooling and tests. */
    fun productionPublicKeyBase64(): String = PUBLIC_KEY_BASE64

    /** Decodes `PB1.<base64url>` into the binary payload; null when it is not a well formed code. */
    internal fun decode(code: String): ByteArray? {
        val trimmed = code.trim()
        if (!trimmed.uppercase().startsWith(PREFIX)) return null
        val body = trimmed.substring(PREFIX.length).filterNot { it.isWhitespace() }
        if (body.isEmpty()) return null
        val bytes = try {
            Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size != PAYLOAD_BYTES) return null
        return bytes
    }

    /** Encodes a payload the way the publisher's tool does; used by tests and the generator. */
    internal fun encode(payload: ByteArray): String {
        require(payload.size == PAYLOAD_BYTES) { "Payload must be $PAYLOAD_BYTES bytes" }
        val body = Base64.encodeToString(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return PREFIX + body
    }
}

/**
 * Stores the promo entitlement next to the licence flag.
 *
 * A promo unlocks the same thing a purchase does, so [LicenseStore] consults this store and the
 * free/paid rules stay untouched.
 */
class PromoActivationStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isActivated(): Boolean = preferences.getBoolean(KEY_ACTIVATED, false)

    /** Serial of the applied code, or null when none was applied. */
    fun appliedSerial(): Int? = preferences.getInt(KEY_SERIAL, NO_SERIAL).takeIf { it != NO_SERIAL }

    fun attemptsLeft(): Int = (MAX_ATTEMPTS - preferences.getInt(KEY_ATTEMPTS, 0)).coerceAtLeast(0)

    /**
     * Verifies and applies [code]. Returns the outcome so the caller can show a precise message.
     *
     * [publicKeyBase64] defaults to the key compiled into the app; tests pass an ephemeral key.
     */
    fun activate(
        code: String,
        publicKeyBase64: String = PromoActivation.productionPublicKeyBase64()
    ): PromoActivation.PromoResult {
        if (attemptsLeft() == 0) return PromoActivation.PromoResult.Invalid
        return when (val result = PromoActivation.verify(code, publicKeyBase64)) {
            is PromoActivation.PromoResult.Valid -> {
                preferences.edit {
                    putBoolean(KEY_ACTIVATED, true)
                    putInt(KEY_SERIAL, result.serial)
                    remove(KEY_ATTEMPTS)
                }
                result
            }
            else -> {
                preferences.edit { putInt(KEY_ATTEMPTS, preferences.getInt(KEY_ATTEMPTS, 0) + 1) }
                result
            }
        }
    }

    /** Development helper: drops the promo entitlement without touching a purchase. */
    fun clear() {
        preferences.edit {
            remove(KEY_ACTIVATED)
            remove(KEY_SERIAL)
            remove(KEY_ATTEMPTS)
        }
    }

    companion object {
        const val PREFERENCES_NAME = "printbridge_promo"

        private const val KEY_ACTIVATED = "activated"
        private const val KEY_SERIAL = "serial"
        private const val KEY_ATTEMPTS = "attempts"
        private const val NO_SERIAL = -1

        /** Caps guessing on a single device. */
        const val MAX_ATTEMPTS = 10
    }
}
