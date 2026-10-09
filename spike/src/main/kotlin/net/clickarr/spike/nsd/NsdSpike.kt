package net.clickarr.spike.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.util.ArrayDeque
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens

/**
 * Spike D. Advertises _clickarr._tcp with a TXT record and browses for others, resolving one at a
 * time (the pre-API-28 NsdManager cannot resolve concurrently). Holds a multicast lock while browsing.
 *
 * Pass criterion (docs/spikes.md): a Fire TV and an Android TV device each see the other within 10 s.
 */
private const val SERVICE_TYPE = "_clickarr._tcp."

@Composable
fun NsdSpike() {
    val context = LocalContext.current
    val log = remember { mutableStateListOf<String>() }
    val controller = remember { NsdController(context) { log.add(0, it) } }
    DisposableEffect(Unit) { onDispose { controller.stopAll() } }

    Column(
        Modifier.fillMaxSize().background(ClickarrColors.BgBase).padding(ClickarrDimens.SafeArea).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("LAN discovery spike", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { controller.advertise() }) { Text("Advertise") }
            Button(onClick = { controller.browse() }) { Text("Browse") }
            Button(onClick = { controller.stopAll() }) { Text("Stop") }
        }
        log.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = ClickarrColors.TextSecondary) }
    }
}

private class NsdController(private val context: Context, private val say: (String) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val lock = wifi.createMulticastLock("clickarr-nsd").apply { setReferenceCounted(false) }
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun advertise() {
        if (registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "Clickarr ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            port = 47831
            setAttribute("v", "1")
            setAttribute("role", "coordinator")
            setAttribute("did", "spike-${Build.MODEL.hashCode().toUInt()}")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = say("Registered as '${i.serviceName}'")
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) = say("Registration failed: $code")
            override fun onServiceUnregistered(i: NsdServiceInfo) = say("Unregistered")
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) = say("Unregister failed: $code")
        }
        registration = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun browse() {
        if (discovery != null) return
        lock.acquire()
        say("Multicast lock held; browsing $SERVICE_TYPE")
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) = say("Discovery started")
            override fun onStartDiscoveryFailed(t: String, code: Int) = say("Discovery start failed: $code")
            override fun onStopDiscoveryFailed(t: String, code: Int) = say("Discovery stop failed: $code")
            override fun onDiscoveryStopped(t: String) = say("Discovery stopped")
            override fun onServiceFound(i: NsdServiceInfo) {
                say("Found '${i.serviceName}'")
                enqueueResolve(i)
            }
            override fun onServiceLost(i: NsdServiceInfo) = say("Lost '${i.serviceName}'")
        }
        discovery = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Suppress("DEPRECATION")
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.add(info)
        if (resolving) return
        resolving = true
        fun next() {
            val i = resolveQueue.poll()
            if (i == null) {
                resolving = false
                return
            }
            nsd.resolveService(
                i,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, code: Int) {
                        say("Resolve failed for '${s.serviceName}': $code")
                        next()
                    }

                    override fun onServiceResolved(s: NsdServiceInfo) {
                        val txt = s.attributes.entries.joinToString(" ") { "${it.key}=${it.value?.toString(Charsets.UTF_8)}" }
                        say("Resolved '${s.serviceName}' at ${s.host?.hostAddress}:${s.port} [$txt]")
                        next()
                    }
                },
            )
        }
        next()
    }

    fun stopAll() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        if (lock.isHeld) lock.release()
    }
}
