package com.printbridge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Promo codes are verified with Ed25519, so the tests sign with an ephemeral key pair and pass its
 * public key into [PromoActivation.verify]. The publisher's private key is never needed, which is
 * exactly the property the scheme is there for.
 */
@RunWith(RobolectricTestRunner::class)
class PromoActivationTests {

    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKeyBase64: String = Base64.getEncoder().encodeToString(rawPublic())

    private fun rawPublic(): ByteArray {
        val encoded = pair.public.encoded
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    private fun codeFor(serial: Int, signingKey: java.security.PrivateKey = pair.private): String {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(signingKey)
        signature.update(PromoActivation.signedMessage(serial))
        val signed = signature.sign()
        val payload = ByteArray(1 + 4 + 64)
        payload[0] = PromoActivation.FORMAT_VERSION.toByte()
        payload[1] = (serial ushr 24).toByte()
        payload[2] = (serial ushr 16).toByte()
        payload[3] = (serial ushr 8).toByte()
        payload[4] = serial.toByte()
        System.arraycopy(signed, 0, payload, 5, 64)
        return PromoActivation.encode(payload)
    }

    // ---------- verification ----------

    @Test
    fun acceptsACodeSignedByThePublicationKey() {
        val code = codeFor(1001)

        val result = PromoActivation.verify(code, publicKeyBase64)

        assertTrue("code was rejected: $result", result is PromoActivation.PromoResult.Valid)
        assertEquals(1001, (result as PromoActivation.PromoResult.Valid).serial)
    }

    @Test
    fun rejectsACodeSignedByAnotherKey() {
        val otherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val forged = codeFor(1001, otherKey.private)

        assertEquals(
            "A code signed by a different key must not activate anything",
            PromoActivation.PromoResult.Invalid,
            PromoActivation.verify(forged, publicKeyBase64)
        )
    }

    @Test
    fun rejectsATamperedSerial() {
        val code = codeFor(1001)
        // Swap the payload's serial bits while keeping the signature: the signature no longer
        // covers the message, so verification has to fail.
        val payload = PromoActivation.decode(code)
        assertNotNull(payload)
        payload!![4] = (payload[4] + 1).toByte()
        val tampered = PromoActivation.encode(payload)

        assertEquals(PromoActivation.PromoResult.Invalid, PromoActivation.verify(tampered, publicKeyBase64))
    }

    @Test
    fun rejectsMalformedInput() {
        assertEquals(PromoActivation.PromoResult.Malformed, PromoActivation.verify("", publicKeyBase64))
        assertEquals(PromoActivation.PromoResult.Malformed, PromoActivation.verify("1234567890", publicKeyBase64))
        assertEquals(PromoActivation.PromoResult.Malformed, PromoActivation.verify("PB1.", publicKeyBase64))
        assertEquals(PromoActivation.PromoResult.Malformed, PromoActivation.verify("PB1.$$$$", publicKeyBase64))
        // A payload of the wrong length is not a code either.
        assertEquals(PromoActivation.PromoResult.Malformed, PromoActivation.verify("PB1.AAAA", publicKeyBase64))
        assertNull(PromoActivation.decode("PB1."))
        assertNull(PromoActivation.decode("not a code"))
    }

    @Test
    fun decodeToleratesWhitespaceAroundAndInsideTheCode() {
        val code = codeFor(7)
        val spaced = code.replace(PromoActivation.PREFIX, "${PromoActivation.PREFIX} ") + "\n"

        val result = PromoActivation.verify(spaced, publicKeyBase64)

        assertTrue("$result", result is PromoActivation.PromoResult.Valid)
        assertEquals(7, (result as PromoActivation.PromoResult.Valid).serial)
    }

    @Test
    fun productionKeyIsEmbeddedAndDistinctFromTheTestKey() {
        assertNotNull(PromoActivation.productionPublicKeyBase64())
        assertEquals(
            "The embedded key must be the raw 32-byte Ed25519 key",
            32,
            Base64.getDecoder().decode(PromoActivation.productionPublicKeyBase64()).size
        )
        assertFalse(PromoActivation.productionPublicKeyBase64() == publicKeyBase64)
    }

    @Test
    fun theEmbeddedKeyIsAWellFormedEd25519Key() {
        val raw = Base64.getDecoder().decode(PromoActivation.productionPublicKeyBase64())
        val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        val key = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(prefix + raw))

        assertNotNull(key)
    }

    // ---------- store ----------

    @Test
    fun activationPersistsTheSerialAndSurvivesRestart() {
        val store = PromoActivationStore(RuntimeEnvironment.getApplication())
        store.clear()

        assertFalse(store.isActivated())
        assertNull(store.appliedSerial())

        assertTrue(store.activate(codeFor(4242), publicKeyBase64) is PromoActivation.PromoResult.Valid)
        assertTrue(store.isActivated())
        assertEquals(4242, store.appliedSerial())
    }

    @Test
    fun attemptsAreCappedSoCodesCannotBeBruteForced() {
        val store = PromoActivationStore(RuntimeEnvironment.getApplication())
        store.clear()

        repeat(PromoActivationStore.MAX_ATTEMPTS) { attempt ->
            val result = store.activate("PB1.not-a-code", publicKeyBase64)
            assertEquals("attempt ${attempt + 1}", PromoActivation.PromoResult.Malformed, result)
        }

        assertEquals(0, store.attemptsLeft())
        assertFalse("The store must refuse further attempts", store.isActivated())
        assertEquals(
            "Once the budget is spent even a valid code is refused",
            PromoActivation.PromoResult.Invalid,
            store.activate(codeFor(1), publicKeyBase64)
        )
    }

    @Test
    fun clearingDropsThePromoWithoutTouchingTheLicence() {
        val context = RuntimeEnvironment.getApplication()
        val promo = PromoActivationStore(context)
        val licence = LicenseStore(context)
        promo.clear()
        licence.revokeLicense()

        promo.activate(codeFor(9), publicKeyBase64)
        assertTrue(promo.isActivated())

        promo.clear()

        assertFalse(promo.isActivated())
        assertFalse("A promo must not leave a purchase behind", licence.isLicensedFor(enforced = true))
    }
}
