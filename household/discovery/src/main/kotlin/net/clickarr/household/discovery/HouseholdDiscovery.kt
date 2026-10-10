package net.clickarr.household.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.ArrayDeque
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import net.clickarr.core.common.Log

/** A coordinator seen on the LAN (proposal 12.2 TXT record). */
data class DiscoveredCoordinator(
    val serviceName: String,
    val host: String,
    val port: Int,
    val householdId: String?,
    val householdName: String?,
    val deviceId: String?,
    val fingerprint: String?,
    val protocolVersion: Int?,
    val tls: Boolean = true,
) {
    val baseUrl: String get() = (if (tls) "https" else "http") + "://$host:$port"
}

/**
 * NsdManager advertise and browse for `_clickarr._tcp` (ADR 0012). Resolves one service at a time,
 * because the pre-API-28 resolver cannot run concurrently, and holds a multicast lock while browsing,
 * because several TV builds filter multicast otherwise. Manual address entry is the fallback in the UI.
 */
class HouseholdDiscovery(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var registration: NsdManager.RegistrationListener? = null

    data class Advertisement(
        val serviceName: String,
        val port: Int,
        val householdId: String,
        val householdName: String,
        val deviceId: String,
        val fingerprint: String,
        val role: String,
        val tls: Boolean = true,
    )

    fun advertise(ad: Advertisement) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = ad.serviceName
            serviceType = SERVICE_TYPE
            port = ad.port
            setAttribute("v", PROTOCOL_VERSION_TXT)
            setAttribute("hid", ad.householdId)
            setAttribute("name", ad.householdName.take(MAX_TXT_VALUE))
            setAttribute("did", ad.deviceId)
            setAttribute("role", ad.role)
            setAttribute("pk", ad.fingerprint.take(MAX_TXT_VALUE))
            setAttribute("tls", if (ad.tls) "1" else "0")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = Log.i(TAG) { "advertising as '${i.serviceName}' on ${ad.port}" }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) = Log.w(TAG) { "advertise failed: $code" }
            override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) = Log.w(TAG) { "unregister failed: $code" }
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopAdvertising() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    /** Emits the current list of resolved coordinators whenever it changes. Cancel the flow to stop. */
    @Suppress("DEPRECATION")
    fun browse(): Flow<List<DiscoveredCoordinator>> = callbackFlow {
        val lock = wifi.createMulticastLock("clickarr-discovery").apply { setReferenceCounted(false) }
        lock.acquire()
        val found = LinkedHashMap<String, DiscoveredCoordinator>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun publish() = trySend(found.values.toList())

        fun resolveNext() {
            val next = queue.poll()
            if (next == null) {
                resolving = false
                return
            }
            resolving = true
            nsd.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, code: Int) {
                        Log.w(TAG) { "resolve failed for '${s.serviceName}': $code" }
                        resolveNext()
                    }

                    override fun onServiceResolved(s: NsdServiceInfo) {
                        val host = s.host?.hostAddress
                        if (host != null) {
                            val txt = s.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) }
                            found[s.serviceName] = DiscoveredCoordinator(
                                serviceName = s.serviceName,
                                host = host,
                                port = s.port,
                                householdId = txt["hid"],
                                householdName = txt["name"],
                                deviceId = txt["did"],
                                fingerprint = txt["pk"],
                                protocolVersion = txt["v"]?.toIntOrNull(),
                                tls = txt["tls"] != "0",
                            )
                            publish()
                        }
                        resolveNext()
                    }
                },
            )
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) = Log.d(TAG) { "browsing" }
            override fun onStartDiscoveryFailed(t: String, code: Int) {
                Log.w(TAG) { "browse failed: $code" }
                close()
            }
            override fun onStopDiscoveryFailed(t: String, code: Int) = Unit
            override fun onDiscoveryStopped(t: String) = Unit
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                queue.add(i)
                if (!resolving) resolveNext()
            }
            override fun onServiceLost(i: NsdServiceInfo) {
                if (found.remove(i.serviceName) != null) publish()
            }
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        publish()
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            if (lock.isHeld) lock.release()
        }
    }

    companion object {
        private const val TAG = "Discovery"
        const val SERVICE_TYPE = "_clickarr._tcp."
        private const val PROTOCOL_VERSION_TXT = "1"
        private const val MAX_TXT_VALUE = 64
    }
}
