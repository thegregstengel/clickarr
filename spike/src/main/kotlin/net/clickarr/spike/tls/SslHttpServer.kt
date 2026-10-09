package net.clickarr.spike.tls

import java.io.BufferedReader
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * Minimal TLS HTTP/1.1 responder built on the platform's own SSLServerSocket.
 *
 * Why not Ktor Netty: Netty 4.2 and the Ktor 3.2 client use java.lang.invoke and D8 refuses to dex them
 * below API 26 ("Increase the minSdkVersion to 26 or above"). Fire OS 6 is API 25 (ADR 0001), so the
 * spike proves TLS termination from a Keystore-held key with no third-party server at all. If this
 * works, the production coordinator can run Ktor CIO behind an in-process TLS acceptor like this one,
 * or on NanoHTTPD's HTTPS mode. If minSdk moves to 26, Netty becomes an option again.
 */
class SslHttpServer(keyStore: KeyStore, keyPassword: CharArray?, private val port: Int) {
    private val serverSocket: SSLServerSocket
    @Volatile private var closed = false

    init {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, keyPassword)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        serverSocket = ctx.serverSocketFactory.createServerSocket(port) as SSLServerSocket
    }

    fun start() {
        thread(name = "clickarr-tls-$port", isDaemon = true) {
            while (!closed) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: Exception) {
                    if (closed) return@thread else continue
                }
                thread(isDaemon = true) { handle(socket) }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val reader: BufferedReader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {
                val header = reader.readLine()
                if (header.isNullOrEmpty()) break
            }
            val ok = requestLine.startsWith("GET /v1/info")
            val body = if (ok) """{"ok":true,"tls":true,"server":"SSLServerSocket"}""" else """{"error":"not found"}"""
            val status = if (ok) "200 OK" else "404 Not Found"
            val response = "HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n" +
                "Connection: close\r\n\r\n$body"
            s.getOutputStream().apply {
                write(response.toByteArray())
                flush()
            }
        }
    }

    fun stop() {
        closed = true
        runCatching { serverSocket.close() }
    }
}
