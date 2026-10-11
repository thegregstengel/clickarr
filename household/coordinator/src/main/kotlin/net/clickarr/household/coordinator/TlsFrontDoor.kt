package net.clickarr.household.coordinator

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

/**
 * TLS termination in front of the coordinator's HTTP engine (ADR 0013). The engine listens on the
 * loopback interface only; this accepts TLS on the LAN port with the device certificate and copies
 * bytes both ways, so HTTP and WebSocket traffic pass through unchanged. It uses the platform's own
 * SSLServerSocket because Netty cannot be dexed below API 26 (spike C, finding C1).
 */
class TlsFrontDoor(private val ssl: SSLContext, private val backendPort: Int) {
    private var serverSocket: SSLServerSocket? = null

    @Volatile
    private var closed = false
    // Bounded: an idle-connection flood from the LAN runs out of slots, not memory. Two threads per bridge.
    private val pool: ExecutorService = ThreadPoolExecutor(
        0, MAX_THREADS, KEEP_ALIVE_S, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, "clickarr-tls").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** The bound port, or -1 before [start]. */
    val port: Int get() = serverSocket?.localPort ?: -1

    /** Binds [port] (0 for any free port) on all interfaces and starts accepting. Returns the bound port. */
    fun start(port: Int): Int {
        val socket = ssl.serverSocketFactory.createServerSocket(port, BACKLOG, InetAddress.getByName("0.0.0.0")) as SSLServerSocket
        socket.enabledProtocols = socket.supportedProtocols.filter { it in MODERN_TLS }.toTypedArray()
        serverSocket = socket
        pool.execute { acceptLoop(socket) }
        return socket.localPort
    }

    fun stop() {
        closed = true
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
    }

    private fun acceptLoop(socket: SSLServerSocket) {
        while (!closed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            runCatching { client.soTimeout = CLIENT_TIMEOUT_MS }
            runCatching { pool.execute { bridge(client) } }.onFailure { runCatching { client.close() } }
        }
    }

    private fun bridge(client: Socket) {
        runCatching {
            Socket("127.0.0.1", backendPort).use { backend ->
                client.use { c ->
                    val upstream = pool.submit { pump(c.getInputStream(), backend.getOutputStream()) }
                    pump(backend.getInputStream(), c.getOutputStream())
                    upstream.cancel(true)
                }
            }
        }
    }

    /** Copies until EOF or error, then closes the destination so the other direction unwinds too. */
    private fun pump(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(BUFFER)
        runCatching {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        }
        runCatching { to.close() }
    }

    companion object {
        private const val MAX_THREADS = 64
        private const val KEEP_ALIVE_S = 60L
        private const val CLIENT_TIMEOUT_MS = 30_000

        private const val BACKLOG = 50
        private const val BUFFER = 16 * 1024
        private val MODERN_TLS = setOf("TLSv1.3", "TLSv1.2")

        /** A server context that always presents [alias] from [keyStore], whatever else the store holds. */
        fun sslContext(keyStore: KeyStore, alias: String, password: CharArray?): SSLContext {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, password)
            val delegate = kmf.keyManagers.filterIsInstance<X509KeyManager>().firstOrNull()
                ?: throw IOException("no X509 key manager for the device certificate")
            return SSLContext.getInstance("TLS").apply { init(arrayOf(FixedAliasKeyManager(delegate, alias)), null, null) }
        }
    }
}

/** Serves one alias for server authentication, when its key type fits the cipher suite being negotiated. */
private class FixedAliasKeyManager(private val delegate: X509KeyManager, private val alias: String) : X509ExtendedKeyManager() {
    private fun serverAlias(keyType: String?): String? =
        alias.takeIf { keyType == null || delegate.getPrivateKey(alias)?.algorithm.equals(keyType, ignoreCase = true) }

    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = serverAlias(keyType)

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<Principal>?, engine: SSLEngine?): String? = serverAlias(keyType)

    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = serverAlias(keyType)?.let { arrayOf(it) }

    override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String? =
        delegate.chooseClientAlias(keyType, issuers, socket)

    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? =
        delegate.getClientAliases(keyType, issuers)

    override fun getCertificateChain(alias: String?) = delegate.getCertificateChain(alias)

    override fun getPrivateKey(alias: String?) = delegate.getPrivateKey(alias)
}
