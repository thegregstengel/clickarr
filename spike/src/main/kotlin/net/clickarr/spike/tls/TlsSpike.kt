package net.clickarr.spike.tls

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens

/**
 * Spike C. Answers: can a Ktor Netty server on this device serve TLS from a certificate whose private
 * key lives in the Android Keystore, and can a Ktor client connect while pinning only that key?
 * Also runs the software-certificate fallback and a plaintext CIO server for comparison.
 *
 * Pass criterion (docs/spikes.md): "Keystore TLS self-test OK" on Fire OS 6, Fire OS 7/8, and Android TV 12+.
 */
private const val TLS_PORT = 47831
private const val TLS_SW_PORT = 47832
private const val PLAIN_PORT = 47833

@Composable
fun TlsSpike() {
    val log = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf<List<EmbeddedServer<*, *>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    fun say(s: String) {
        log.add(0, s)
    }

    DisposableEffect(Unit) {
        onDispose { running.forEach { runCatching { it.stop(500, 1000) } } }
    }

    Column(
        Modifier.fillMaxSize().background(ClickarrColors.BgBase).padding(ClickarrDimens.SafeArea).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("TLS household server spike", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                scope.launch {
                    runCatching {
                        val (server, fp) = withContext(Dispatchers.IO) {
                            val cert = DeviceIdentity.ensure()
                            val fp = DeviceIdentity.fingerprint(cert)
                            val ks = DeviceIdentity.keyStore()
                            startTls(ks, DeviceIdentity.ALIAS, charArrayOf(), TLS_PORT) to fp
                        }
                        running = running + server
                        say("Keystore TLS server up on $TLS_PORT, fingerprint ${fp.take(16)}...")
                        say(selfTest("https://127.0.0.1:$TLS_PORT/v1/info", fp, "Keystore TLS"))
                    }.onFailure { say("Keystore TLS FAILED: ${it::class.simpleName}: ${it.message}") }
                }
            }) { Text("Start Keystore TLS + self-test") }
            Button(onClick = {
                scope.launch {
                    runCatching {
                        val (server, fp) = withContext(Dispatchers.IO) {
                            val (ks, cert) = SoftwareIdentity.create()
                            startTls(ks, SoftwareIdentity.ALIAS, SoftwareIdentity.password, TLS_SW_PORT) to DeviceIdentity.fingerprint(cert)
                        }
                        running = running + server
                        say("Software TLS server up on $TLS_SW_PORT")
                        say(selfTest("https://127.0.0.1:$TLS_SW_PORT/v1/info", fp, "Software TLS"))
                    }.onFailure { say("Software TLS FAILED: ${it::class.simpleName}: ${it.message}") }
                }
            }) { Text("Start software-cert TLS + self-test") }
            Button(onClick = {
                scope.launch {
                    runCatching {
                        val server = withContext(Dispatchers.IO) {
                            embeddedServer(CIO, port = PLAIN_PORT) { routing { get("/v1/info") { call.respondText("""{"ok":true,"tls":false}""") } } }
                                .also { it.start(wait = false) }
                        }
                        running = running + server
                        say("Plain CIO server up on $PLAIN_PORT")
                        say(selfTest("http://127.0.0.1:$PLAIN_PORT/v1/info", null, "Plain HTTP"))
                    }.onFailure { say("Plain HTTP FAILED: ${it::class.simpleName}: ${it.message}") }
                }
            }) { Text("Start plain HTTP + self-test") }
        }
        Button(onClick = {
            running.forEach { runCatching { it.stop(500, 1000) } }
            running = emptyList()
            say("Stopped all servers")
        }) { Text("Stop servers") }
        log.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = ClickarrColors.TextSecondary) }
    }
}

private fun startTls(ks: KeyStore, alias: String, keyPassword: CharArray, port: Int): EmbeddedServer<*, *> {
    val server = embeddedServer(
        Netty,
        configure = {
            sslConnector(
                keyStore = ks,
                keyAlias = alias,
                keyStorePassword = { keyPassword },
                privateKeyPassword = { keyPassword },
            ) {
                this.port = port
                this.host = "0.0.0.0"
            }
        },
    ) {
        routing { get("/v1/info") { call.respondText("""{"ok":true,"tls":true}""") } }
    }
    server.start(wait = false)
    return server
}

/** Connects with a trust manager that accepts exactly one SPKI fingerprint and nothing else. */
private suspend fun selfTest(url: String, pinnedFingerprint: String?, label: String): String = withContext(Dispatchers.IO) {
    val t0 = SystemClock.elapsedRealtime()
    val client = HttpClient(OkHttp) {
        engine {
            config {
                if (pinnedFingerprint != null) {
                    val tm = PinnedTrustManager(pinnedFingerprint)
                    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
                    sslSocketFactory(ctx.socketFactory, tm)
                    hostnameVerifier { _, _ -> true }
                }
            }
        }
    }
    try {
        val body = client.get(url).bodyAsText()
        "$label self-test OK in ${SystemClock.elapsedRealtime() - t0} ms: $body"
    } catch (e: Exception) {
        "$label self-test FAILED: ${e::class.simpleName}: ${e.message}"
    } finally {
        client.close()
    }
}

private class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw java.security.cert.CertificateException("empty chain")
        val fp = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded).joinToString("") { "%02x".format(it) }
        if (fp != fingerprint) throw java.security.cert.CertificateException("fingerprint mismatch")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
