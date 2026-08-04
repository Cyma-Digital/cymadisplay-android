package com.cyma.videoloop.wifi

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.cyma.videoloop.admin.DeviceOwnerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a join attempt — the two failure modes need different advice on screen. */
sealed interface JoinResult {
    /** Associated *and* internet-validated. The only success. */
    data object Online : JoinResult

    /** Associated with the AP, but no validated internet: dead upstream or captive AP. */
    data object AssociatedNoInternet : JoinResult

    /** Never associated: wrong password, or the AP is out of range. */
    data object NotAssociated : JoinResult
}

/**
 * Connects the box to a chosen WiFi network and confirms it actually reaches the
 * internet.
 *
 * The join relies on device-owner privilege. On Android 10+ an ordinary app can
 * no longer add a WiFi config and have the system connect to it; a **device
 * owner** still can. The primary path is therefore the classic
 * [WifiManager.addNetwork] → [WifiManager.enableNetwork] sequence (deprecated for
 * normal apps but still honoured for device owners).
 *
 * If that path is unavailable (not device owner, or `addNetwork` refused) we fall
 * back to [WifiManager.addNetworkSuggestions] on API 29+, which for a device
 * owner is auto-approved and for a normal app is advisory (the system may prompt
 * and decides when to connect).
 *
 * **`enableNetwork` does not start a connection, and `reconnect()` cannot.** It only
 * marks the config enabled; the connection itself is kicked off by the framework's
 * `WifiConnectivityManager` on its next *connectivity scan*, whose back-off is
 * 20/40/80 s — measured 81 s of dead air on an API 29 TX3 box. [WifiManager.reconnect]
 * is the call that would normally force it, but for apps targeting Q+ it is a
 * documented no-op ("will always fail and return false"); the device-owner exemption
 * covers `addNetwork`/`enableNetwork`/`setWifiEnabled`, not `reconnect`. So this class
 * *nudges* the framework into scanning instead, escalating only as far as needed:
 * `startScan()` → another `startScan()` → a full client-radio restart. Everything after
 * association is fast (DHCP ~1 s, internet validation ~1 s), so association is the only
 * thing worth optimising.
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
     * Attempts to join [ssid] with [password]: configures the network, nudges the
     * framework until supplicant associates, then waits for validated internet.
     * [onAssociated] fires the moment association is confirmed, so the caller can move
     * its UI from "connecting" to "verifying internet" at the truthful moment.
     */
    suspend fun join(
        ssid: String,
        password: String,
        onAssociated: () -> Unit = {},
    ): JoinResult {
        val started = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - started

        val legacyStarted = tryLegacyJoin(ssid, password)
        if (!legacyStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            trySuggestionJoin(ssid, password)
        }

        if (!awaitAssociationWithNudges(ssid)) {
            Log.w(TAG, "join '$ssid': never associated after ${elapsed()}ms — wrong password or out of range")
            return JoinResult.NotAssociated
        }
        Log.i(TAG, "join '$ssid': associated after ${elapsed()}ms — awaiting internet validation")
        onAssociated()

        if (!connectivityMonitor.awaitValidatedInternet(VALIDATION_TIMEOUT_MS)) {
            Log.w(TAG, "join '$ssid': associated but no validated internet after ${elapsed()}ms")
            return JoinResult.AssociatedNoInternet
        }
        Log.i(TAG, "join '$ssid': online after ${elapsed()}ms")
        return JoinResult.Online
    }

    /**
     * Waits for supplicant to associate with [ssid], escalating the nudge each time a
     * window expires. Tiers are cheapest-first: a scan is what makes the framework
     * evaluate the network we just enabled, and only if two scans don't take do we
     * bounce the radio.
     *
     * Q throttles foreground `startScan()` to 4 calls per 2 min; this uses at most 3.
     */
    private suspend fun awaitAssociationWithNudges(ssid: String): Boolean {
        // Tier 1 — reconnect() (a no-op on Q+, honoured pre-Q) + a scan.
        nudgeReconnect()
        startScan("tier1")
        if (awaitAssociation(ssid, ASSOC_NUDGE_MS)) return true

        // Tier 2 — one more scan; the first can land while the AP teardown still has
        // the radio, in which case it produces nothing.
        Log.i(TAG, "not associated after ${ASSOC_NUDGE_MS}ms — rescanning")
        startScan("tier2")
        if (awaitAssociation(ssid, ASSOC_RESCAN_MS)) return true

        // Tier 3 — restart the client radio. Heavy-handed, but a fresh client start
        // forces a scan + auto-connect pass on BSPs that ignore the above.
        Log.i(TAG, "still not associated — restarting wifi client")
        restartWifiClient()
        startScan("tier3")
        return awaitAssociation(ssid, ASSOC_RADIO_RESTART_MS)
    }

    /**
     * Polls [WifiManager.getConnectionInfo] for an SSID match — the supplicant-level
     * "we're on the right AP" signal, which lands before DHCP and before validation.
     * That's what separates a wrong password (never associates) from a dead upstream
     * (associates, never validates).
     */
    @Suppress("DEPRECATION")
    private suspend fun awaitAssociation(ssid: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = runCatching { wifiManager.connectionInfo?.ssid?.stripQuotes() }.getOrNull()
            if (current != null && current == ssid) return true
            delay(ASSOC_POLL_MS)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun nudgeReconnect() {
        runCatching {
            val accepted = wifiManager.reconnect()
            Log.i(TAG, "reconnect() accepted=$accepted (expected false on API 29+)")
        }
    }

    @Suppress("DEPRECATION")
    private fun startScan(tier: String) {
        runCatching {
            val accepted = wifiManager.startScan()
            Log.i(TAG, "startScan($tier) accepted=$accepted")
        }.onFailure { Log.w(TAG, "startScan($tier) threw", it) }
    }

    /**
     * Off→on bounce of the client radio. `setWifiEnabled` always fails for apps
     * targeting Q+ **except** for device/profile owners, which these boxes are — so
     * this tier quietly does nothing on a non-provisioned box, which is why it's last.
     */
    @Suppress("DEPRECATION")
    private suspend fun restartWifiClient() {
        runCatching {
            val off = wifiManager.setWifiEnabled(false)
            delay(RADIO_BOUNCE_MS)
            val on = wifiManager.setWifiEnabled(true)
            Log.i(TAG, "wifi client bounce: off=$off on=$on deviceOwner=${deviceOwnerManager.isDeviceOwner()}")
        }.onFailure { Log.w(TAG, "wifi client bounce threw", it) }
        awaitWifiClientReady()
    }

    /**
     * Device-owner / legacy path. Returns true if a network config was accepted
     * (not that a connection has started — see the class kdoc; nothing here initiates
     * one, the nudges in [awaitAssociationWithNudges] do).
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

        /** Association window after the first scan nudge. */
        private const val ASSOC_NUDGE_MS = 15_000L

        /** Association window after a second scan. */
        private const val ASSOC_RESCAN_MS = 12_000L

        /** Association window after the client radio has been bounced. */
        private const val ASSOC_RADIO_RESTART_MS = 20_000L

        private const val ASSOC_POLL_MS = 500L

        /** Gap between off and on when bouncing the client radio. */
        private const val RADIO_BOUNCE_MS = 1_500L

        /** Post-association wait for validated internet. Measured cost: ~1 s (DHCP) + ~1 s (probe). */
        private const val VALIDATION_TIMEOUT_MS = 25_000L
    }
}
