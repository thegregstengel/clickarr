package net.clickarr.spike.tls

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Fallback path for Spike C: a software key pair and a BouncyCastle-built certificate in an in-memory
 * PKCS12 keystore. If the Keystore-backed path fails on some device, this is what the real
 * implementation would persist (encrypted) instead.
 */
object SoftwareIdentity {
    const val ALIAS = "clickarr-software"
    val password = "clickarr".toCharArray()

    fun create(): Pair<KeyStore, X509Certificate> {
        if (Security.getProvider("BC")?.javaClass?.name != BouncyCastleProvider::class.java.name) {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
        val kp: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            X500Name("CN=Clickarr Device (software)"),
            BigInteger.valueOf(now),
            Date(now - 86_400_000L),
            Date(now + 20L * 365 * 86_400_000L),
            X500Name("CN=Clickarr Device (software)"),
            kp.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry(ALIAS, kp.private, password, arrayOf(cert))
        return ks to cert
    }
}
