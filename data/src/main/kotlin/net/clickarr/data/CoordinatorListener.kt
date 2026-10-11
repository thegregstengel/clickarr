package net.clickarr.data

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.clickarr.core.common.Log
import net.clickarr.household.coordinator.Coordinator
import net.clickarr.household.coordinator.TlsFrontDoor
import net.clickarr.household.coordinator.coordinatorRoutes
import net.clickarr.household.discovery.DeviceIdentity
import net.clickarr.household.protocol.DEFAULT_PORT

/** Brings the coordinator's engine up behind the TLS front door (ADR 0013). */
internal object CoordinatorListener {
    private const val TAG = "Household"
    private const val CONNECTOR_TIMEOUT_MS = 5_000L

    class Listening(val server: EmbeddedServer<*, *>, val door: TlsFrontDoor?, val port: Int, val tls: Boolean)

    /**
     * TLS on the LAN port in front of a loopback-only engine. Plain HTTP on the LAN port only if the device
     * cannot start TLS at all, which spike C is meant to rule out; the role and the advertisement say which.
     */
    suspend fun start(c: Coordinator): Listening? {
        val secure = runCatching {
            val engine = startServer(c, "127.0.0.1", 0)
            val backendPort = enginePort(engine) ?: run { engine.stop(0, 0); error("engine port unknown") }
            val ssl = TlsFrontDoor.sslContext(DeviceIdentity.keyStore().also { DeviceIdentity.certificate() }, DeviceIdentity.ALIAS, null)
            val door = TlsFrontDoor(ssl, backendPort)
            val port = runCatching { door.start(DEFAULT_PORT) }.getOrElse { door.start(0) }
            Listening(engine, door, port, tls = true)
        }.onFailure { Log.w(TAG, it) { "TLS front door failed; the household stays off rather than run in the clear" } }.getOrNull()
        // No plain-HTTP fallback: a coordinator the LAN can impersonate or read is worse than none (security review 2026-10-10).
        return secure
    }

    private suspend fun enginePort(engine: EmbeddedServer<*, *>): Int? = withTimeoutOrNull(CONNECTOR_TIMEOUT_MS) {
        withContext(Dispatchers.IO) { engine.engine.resolvedConnectors().firstOrNull()?.port }
    }

    /** Suspending start so no thread blocks inside a coroutine; returns once the engine is accepting. */
    private suspend fun startServer(c: Coordinator, host: String, port: Int): EmbeddedServer<*, *> {
        val s = embeddedServer(CIO, port = port, host = host) { coordinatorRoutes(c) }
        Log.d(TAG) { "starting engine on $host:$port" }
        withContext(Dispatchers.IO) { s.startSuspend(wait = false) }
        Log.d(TAG) { "engine up" }
        return s
    }
}
