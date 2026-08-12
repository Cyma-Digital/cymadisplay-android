package com.cyma.videoloop.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** A nearby WiFi network offered in the captive-portal picker. */
data class ScannedNetwork(val ssid: String, val rssi: Int, val secured: Boolean)

/**
 * One *physical* access point — unlike [ScannedNetwork], which collapses a
 * multi-AP SSID into a single entry. WiFi trilateration needs every BSSID it can
 * see, so this keeps them separate.
 */
data class ScannedAccessPoint(val bssid: String, val rssi: Int, val channel: Int?)

/**
 * Scans for nearby WiFi networks so the captive portal can present a picker.
 *
 * This runs **before** the setup hotspot is started: a single-radio box in AP
 * mode can't reliably scan for client networks, so the coordinator scans first
 * and caches the result. Requires the location permission (self-granted via
 * device-owner privilege upstream).
 */
@Singleton
class WifiScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Triggers a scan and returns the visible networks, strongest first, one
     * entry per SSID. Falls back to the last cached scan results if a fresh scan
     * can't be triggered (Android throttles [WifiManager.startScan]) or times out.
     */
    // Location permission is self-granted upstream via device-owner privilege
    // (see DeviceOwnerManager); the flow won't reach here without it.
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun scan(timeoutMs: Long = 8_000): List<ScannedNetwork> {
        ensureWifiEnabled()
        awaitFreshScan(timeoutMs)
        return runCatching { wifiManager.scanResults }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.SSID.isNotBlank() }
            .groupBy { it.SSID }
            .map { (ssid, results) ->
                val strongest = results.maxBy { it.level }
                ScannedNetwork(
                    ssid = ssid,
                    rssi = strongest.level,
                    secured = strongest.capabilities.isSecured(),
                )
            }
            .sortedByDescending { it.rssi }
    }

    /**
     * Triggers a scan and returns every visible BSSID, strongest first — the input
     * to WiFi trilateration (see [com.cyma.videoloop.data.metrics.GeoLocationRepository]).
     *
     * Same single-radio caveat as [scan]: don't call this while the setup hotspot
     * is up. The caller gates on validated internet, which implies the AP is down.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun scanAccessPoints(timeoutMs: Long = 8_000): List<ScannedAccessPoint> {
        ensureWifiEnabled()
        awaitFreshScan(timeoutMs)
        return runCatching { wifiManager.scanResults }
            .getOrDefault(emptyList())
            .asSequence()
            .mapNotNull { result ->
                val bssid = result.BSSID?.lowercase() ?: return@mapNotNull null
                // Skip junk / hidden entries the way geolocation.py does (len != 17)
                if (bssid.length != 17) return@mapNotNull null
                ScannedAccessPoint(
                    bssid = bssid,
                    rssi = result.level,
                    channel = channelForFrequency(result.frequency),
                )
            }
            .distinctBy { it.bssid }
            .sortedByDescending { it.rssi }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun ensureWifiEnabled() {
        if (!wifiManager.isWifiEnabled) {
            // Non-DO apps can't toggle WiFi on Android 10+; device owners can.
            runCatching { wifiManager.isWifiEnabled = true }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun awaitFreshScan(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                lateinit var receiver: BroadcastReceiver
                val unregister = { runCatching { context.unregisterReceiver(receiver) } }
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        unregister()
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                context.registerReceiver(receiver, filter)
                cont.invokeOnCancellation { unregister() }

                val started = runCatching { wifiManager.startScan() }.getOrDefault(false)
                if (!started) {
                    // Throttled or failed — use whatever cached results exist.
                    Log.i(TAG, "startScan not started; using cached results")
                    unregister()
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private companion object {
        private const val TAG = "WifiScanner"
    }
}

/** Centre frequency (MHz) → 802.11 channel number; null for bands we don't map. */
private fun channelForFrequency(freqMhz: Int): Int? = when (freqMhz) {
    2484 -> 14
    in 2412..2472 -> (freqMhz - 2407) / 5
    in 5000..5900 -> (freqMhz - 5000) / 5
    else -> null
}

private fun String.isSecured(): Boolean =
    listOf("WPA", "WEP", "PSK", "EAP", "SAE").any { contains(it, ignoreCase = true) }
