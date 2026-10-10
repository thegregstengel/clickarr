package net.clickarr.household.discovery

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * This install's EC P-256 key and self-signed certificate in the Android Keystore (proposal 13.3).
 * The SHA-256 of the SubjectPublicKeyInfo is the device fingerprint used in pairing proofs and TLS
 * pinning. The key never leaves the device.
 */
object DeviceIdentity {
    const val ALIAS = "clickarr-device"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val VALID_YEARS = 20L

    fun certificate(): X509Certificate {
        val ks = keyStore()
        if (!ks.containsAlias(ALIAS)) {
            generate()
        } else if (!signsForTls(ks)) {
            // An early build made the key without DIGEST_NONE; TLS cannot sign with it. A new key means a new
            // fingerprint, so any household this device was in has to be joined again.
            ks.deleteEntry(ALIAS)
            generate()
        }
        return ks.getCertificate(ALIAS) as X509Certificate
    }

    /** Conscrypt hands the key a precomputed digest to sign, which the Keystore only allows with DIGEST_NONE. */
    private fun signsForTls(ks: KeyStore): Boolean = runCatching {
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        KeyProperties.DIGEST_NONE in info.digests
    }.getOrDefault(false)

    fun fingerprint(): String = fingerprint(certificate())

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }

    fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun generate() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // DIGEST_NONE is what TLS needs: the stack digests the handshake itself and asks the key to sign the result.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
            .setCertificateSubject(X500Principal("CN=Clickarr Device"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - DAY_MS))
            .setCertificateNotAfter(Date(now + VALID_YEARS * 365 * DAY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
    }

    private const val DAY_MS = 86_400_000L
}
