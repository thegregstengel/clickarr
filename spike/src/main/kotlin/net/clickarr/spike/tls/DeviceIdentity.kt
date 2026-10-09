package net.clickarr.spike.tls

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Generates the per-device EC P-256 key and self-signed certificate in the Android Keystore
 * (proposal 13.3). The Keystore signs the certificate itself, so no BouncyCastle is needed on this path.
 */
object DeviceIdentity {
    const val ALIAS = "clickarr-device-spike"
    private const val KEYSTORE = "AndroidKeyStore"

    fun ensure(): X509Certificate {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
                .setCertificateSubject(X500Principal("CN=Clickarr Device"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 20L * 365 * 86_400_000L))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        }
        return ks.getCertificate(ALIAS) as X509Certificate
    }

    fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** SHA-256 over the SubjectPublicKeyInfo, the value the household protocol pins. */
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }

    fun delete() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
    }
}
