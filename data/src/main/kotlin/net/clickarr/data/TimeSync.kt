package net.clickarr.data

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import net.clickarr.core.common.AppTime
import net.clickarr.core.common.Log

/**
 * Keeps [AppTime] in step with Settings: the chosen zone and, when automatic time is on, an SNTP check
 * against public time servers at start and every few hours. A TV stick with a clock a minute off would
 * otherwise be a minute off on every channel.
 */
@Singleton
class TimeSync @Inject constructor(private val prefs: DevicePrefs) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            launch { prefs.timeZoneId.collect { AppTime.zone = zoneOrDevice(it) } }
            prefs.autoTime.collect { auto ->
                if (!auto) {
                    AppTime.networkOffsetMs = 0L
                    return@collect
                }
                while (true) {
                    syncNow()
                    delay(INTERVAL)
                }
            }
        }
    }

    /** One SNTP exchange; returns the measured offset or null when no server answered. */
    suspend fun syncNow(): Long? {
        val offset = plausibleOffset(SERVERS.mapNotNull { host -> runCatching { sntpOffsetMs(host) }.getOrNull() })
        if (offset != null) {
            AppTime.networkOffsetMs = offset
            Log.i(TAG) { "network time offset ${offset / 1000.0} s" }
        } else {
            Log.w(TAG) { "no time server answered" }
        }
        return offset
    }

    suspend fun isAuto(): Boolean = prefs.autoTime.first()

    /**
     * A single answer within an hour of the TV's clock is taken as is. A bigger correction (a TV that has
     * never set its clock) needs two servers that agree, so one spoofed reply cannot move the schedule by a day.
     */
    private fun plausibleOffset(offsets: List<Long>): Long? {
        offsets.firstOrNull { kotlin.math.abs(it) <= MAX_OFFSET_MS }?.let { return it }
        if (offsets.size < 2) return null
        return offsets[0].takeIf { kotlin.math.abs(offsets[0] - offsets[1]) <= AGREEMENT_MS }
    }

    private fun zoneOrDevice(id: String?): TimeZone =
        id?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.currentSystemDefault()

    /** RFC 4330: offset = ((t1 - t0) + (t2 - t3)) / 2 with the server's receive and transmit stamps. */
    private suspend fun sntpOffsetMs(host: String): Long = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            val address = InetAddress.getByName(host)
            // Connected, so a datagram from anyone else on the LAN is dropped by the kernel.
            socket.connect(address, NTP_PORT)
            val buffer = ByteArray(PACKET)
            buffer[0] = 0x1B // LI = 0, version 3, mode 3 (client)
            val t0 = System.currentTimeMillis()
            writeTimestamp(buffer, TRANSMIT_OFFSET, t0)
            val sent = buffer.copyOfRange(TRANSMIT_OFFSET, TRANSMIT_OFFSET + STAMP_BYTES)
            socket.send(DatagramPacket(buffer, buffer.size, address, NTP_PORT))
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val t3 = System.currentTimeMillis()
            // The server echoes our transmit stamp as its originate stamp; a reply that does not is not ours.
            val echoed = buffer.copyOfRange(ORIGINATE_OFFSET, ORIGINATE_OFFSET + STAMP_BYTES)
            check(echoed.contentEquals(sent)) { "unexpected NTP reply" }
            val t1 = timestamp(buffer, RECEIVE_OFFSET)
            val t2 = timestamp(buffer, TRANSMIT_OFFSET)
            ((t1 - t0) + (t2 - t3)) / 2
        }
    }

    private fun writeTimestamp(b: ByteArray, at: Int, epochMs: Long) {
        val seconds = epochMs / 1000 + NTP_EPOCH_OFFSET
        val fraction = ((epochMs % 1000) shl 32) / 1000
        for (i in 0 until 4) b[at + i] = (seconds shr (8 * (3 - i))).toByte()
        for (i in 0 until 4) b[at + 4 + i] = (fraction shr (8 * (3 - i))).toByte()
    }

    private fun timestamp(b: ByteArray, at: Int): Long {
        var seconds = 0L
        for (i in 0 until 4) seconds = (seconds shl 8) or (b[at + i].toLong() and 0xFF)
        var fraction = 0L
        for (i in 4 until 8) fraction = (fraction shl 8) or (b[at + i].toLong() and 0xFF)
        return (seconds - NTP_EPOCH_OFFSET) * 1000 + (fraction * 1000 shr 32)
    }

    companion object {
        private const val TAG = "TimeSync"
        private val SERVERS = listOf("time.google.com", "pool.ntp.org")
        private val INTERVAL = 6.hours
        private const val TIMEOUT_MS = 3_000
        private const val PACKET = 48
        private const val NTP_PORT = 123
        private const val ORIGINATE_OFFSET = 24
        private const val RECEIVE_OFFSET = 32
        private const val STAMP_BYTES = 8

        /** Beyond this the reply is more likely a spoof or a broken server than a TV clock that far off. */
        private const val MAX_OFFSET_MS = 60L * 60 * 1000
        private const val AGREEMENT_MS = 5_000L
        private const val TRANSMIT_OFFSET = 40
        private const val NTP_EPOCH_OFFSET = 2_208_988_800L
    }
}
