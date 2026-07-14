package com.cyma.videoloop.wifi

import android.os.SystemClock
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.delay

/**
 * Resolves the box's own IPv4 address on the setup-hotspot interface, so the UI
 * can show the installer a URL to type if captive-portal auto-open doesn't fire.
 */
object HotspotAddress {

    private const val TAG = "HotspotAddress"

    /** e.g. `http://192.168.43.1` or `http://192.168.43.1:8080`. Null if [ip] is null. */
    fun portalUrl(ip: String?, port: Int): String? {
        if (ip == null) return null
        return if (port == 80) "http://$ip" else "http://$ip:$port"
    }

    /**
     * Polls for the AP interface IPv4 for up to [timeoutMs]. Right after the legacy AP
     * reports ENABLED, wlan0 is still being re-IP'd to the gateway (e.g. 192.168.43.1),
     * so a single snapshot returns null — poll until it appears.
     */
    suspend fun awaitApIpv4(timeoutMs: Long = 6_000): String? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        var ip: String? = findApIpv4()
        while (ip == null && SystemClock.elapsedRealtime() < end) {
            delay(300)
            ip = findApIpv4()
        }
        if (ip == null) Log.w(TAG, "no AP IPv4 found within ${timeoutMs}ms")
        return ip
    }

    private fun findApIpv4(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull().orEmpty()
        // Prefer AP-like interface names, then any site-local IPv4.
        val candidates = interfaces
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface -> iface.inetAddresses.toList().map { iface.name to it } }
            .filter { (_, addr) -> addr is Inet4Address && addr.isSiteLocalAddress }

        val apLike = candidates.firstOrNull { (name, _) ->
            name.contains("ap", ignoreCase = true) ||
                name.contains("softap", ignoreCase = true) ||
                name.startsWith("wlan")
        }
        return (apLike ?: candidates.firstOrNull())?.second?.hostAddress
    }
}
