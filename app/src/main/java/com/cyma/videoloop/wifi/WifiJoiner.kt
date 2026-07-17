package com.cyma.videoloop.wifi

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import com.cyma.videoloop.admin.DeviceOwnerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects the box to a chosen WiFi network and confirms it actually reaches the
 * internet.
 *
 * The join relies on device-owner privilege. On Android 10+ an ordinary app can
 * no longer add a WiFi config and have the system connect to it; a **device
 * owner** still can. The primary path is therefore the classic
 * [WifiManager.addNetwork] → [WifiManager.enableNetwork] → [WifiManager.reconnect]
 * sequence (deprecated for normal apps but still honoured for device owners).
 *
 * If that path is unavailable (not device owner, or `addNetwork` refused) we fall
 * back to [WifiManager.addNetworkSuggestions] on API 29+, which for a device
 * owner is auto-approved and for a normal app is advisory (the system may prompt
 * and decides when to connect).
 *
 * Either way, success is defined as *validated internet within the timeout* — so
 * a wrong password (fails to associate) or a captive AP is correctly reported as
 * a failure.
 */
@Singleton
class WifiJoiner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectivityMonitor: ConnectivityMonitor,
    private val deviceOwnerManager: DeviceOwnerManager,
) {
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Attempts to join [ssid] with [password] and waits up to [timeoutMs] for
     * validated internet. Returns true only when the box is actually online.
     */
    suspend fun join(ssid: String, password: String, timeoutMs: Long = 35_000): Boolean {
        val legacyStarted = tryLegacyJoin(ssid, password)
        if (!legacyStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            trySuggestionJoin(ssid, password)
        }
        return connectivityMonitor.awaitValidatedInternet(timeoutMs)
    }

    /**
     * Device-owner / legacy path. Returns true if a network config was accepted
     * and a connection attempt kicked off (not that internet is up yet).
     *
     * After the setup AP is torn down, wlan0 flips AP→client and wpa_supplicant needs a
     * moment to come back; [addNetwork] transiently returns -1 during that window. So we
     * wait for client mode to be enabled, then retry [addNetwork] a few times.
     */
    @Suppress("DEPRECATION")
    private suspend fun tryLegacyJoin(ssid: String, password: String): Boolean = runCatching {
        awaitWifiClientReady()
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (password.isEmpty()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"$password\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
        }
        var netId = -1
        for (attempt in 1..ADD_NETWORK_ATTEMPTS) {
            netId = wifiManager.addNetwork(config)
            Log.i(TAG, "addNetwork attempt=$attempt netId=$netId")
            if (netId != -1) break
            delay(ADD_NETWORK_RETRY_MS)
        }
        if (netId == -1) {
            Log.w(TAG, "addNetwork refused after $ADD_NETWORK_ATTEMPTS attempts (supplicant not ready / not device owner?)")
            return@runCatching false
        }
        wifiManager.disconnect()
        val enabled = wifiManager.enableNetwork(netId, true)
        wifiManager.reconnect()
        Log.i(TAG, "legacy join netId=$netId enabled=$enabled")
        enabled
    }.getOrElse {
        Log.w(TAG, "legacy join threw", it)
        false
    }

    /**
     * Re-enables the WiFi client radio and waits for it to be ready. Called by the
     * provisioning coordinator after it tears the setup hotspot down, so the box
     * gets a chance to reconnect to a known network before the AP is re-armed.
     */
    suspend fun ensureClientEnabled() = awaitWifiClientReady()

    /** Ensures wifi is back in client ([WIFI_STATE_ENABLED]) mode after AP teardown. */
    @Suppress("DEPRECATION")
    private suspend fun awaitWifiClientReady() {
        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
        }
        var waited = 0L
        while (wifiManager.wifiState != WifiManager.WIFI_STATE_ENABLED && waited < WIFI_READY_TIMEOUT_MS) {
            delay(WIFI_READY_POLL_MS)
            waited += WIFI_READY_POLL_MS
        }
        Log.i(TAG, "wifi client ready: state=${wifiManager.wifiState} waitedMs=$waited")
    }

    /** Suggestion path (API 29+). Auto-approved for a device owner. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun trySuggestionJoin(ssid: String, password: String) {
        runCatching {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
            if (password.isNotEmpty()) builder.setWpa2Passphrase(password)
            builder.setIsAppInteractionRequired(false)
            val suggestion = builder.build()
            // Clear any prior suggestions so a retry with a new password takes effect.
            wifiManager.removeNetworkSuggestions(emptyList())
            val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
            Log.i(TAG, "addNetworkSuggestions status=$status deviceOwner=${deviceOwnerManager.isDeviceOwner()}")
        }.onFailure { Log.w(TAG, "suggestion join threw", it) }
    }

    private companion object {
        private const val TAG = "WifiJoiner"
        private const val WIFI_READY_TIMEOUT_MS = 6_000L
        private const val WIFI_READY_POLL_MS = 300L
        private const val ADD_NETWORK_ATTEMPTS = 4
        private const val ADD_NETWORK_RETRY_MS = 700L
    }
}
