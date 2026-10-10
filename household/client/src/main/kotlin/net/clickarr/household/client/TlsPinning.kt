package net.clickarr.household.client

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/** SHA-256 of the certificate's SubjectPublicKeyInfo, lowercase hex: the device fingerprint used everywhere. */
fun spkiFingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }

/**
 * Trust on first use (ADR 0013). With [expected] set, only that fingerprint is accepted. Without it, the
 * first certificate seen is accepted and reported through [observed], and the pairing proof then binds
 * it, so a device in the middle that presented its own certificate cannot complete pairing.
 */
class TrustOnFirstUse(private val expected: String?) : X509TrustManager {
    @Volatile
    var observed: String? = null
        private set

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val fingerprint = spkiFingerprint(leaf)
        if (expected != null && fingerprint != expected) {
            throw CertificateException("The coordinator's certificate does not match the one this TV paired with")
        }
        observed = fingerprint
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** A client that trusts the coordinator by fingerprint only; hostnames are LAN addresses and mean nothing. */
fun OkHttpClient.pinnedTo(trust: TrustOnFirstUse): OkHttpClient {
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    return newBuilder().sslSocketFactory(ctx.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
}
