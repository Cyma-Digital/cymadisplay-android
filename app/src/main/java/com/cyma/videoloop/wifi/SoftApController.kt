package com.cyma.videoloop.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.location.LocationManagerCompat
import com.cyma.videoloop.data.identity.DeviceIdentityRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Credentials of the running setup hotspot, as read from the OS-issued reservation. */
data class SoftApCredentials(val ssid: String, val passphrase: String?)

/** Outcome of trying to raise the setup hotspot, with a human-readable reason on failure. */
sealed interface SoftApResult {
    data class Started(val credentials: SoftApCredentials) : SoftApResult
    data class Failed(val reason: String) : SoftApResult
}

/**
 * Runs the temporary "setup" WiFi hotspot the installer's phone joins.
 *
 * Uses [WifiManager.startLocalOnlyHotspot] (API 26+). The OS chooses the SSID and
 * passphrase; we read them back from the reservation and surface them so the UI
 * can render a `WIFI:` QR code the phone auto-joins.
 *
 * Single-radio boxes cannot host this hotspot *and* be connected as a WiFi client
 * at the same time, so the provisioning coordinator tears the hotspot down
 * (via [stop]) before asking [WifiJoiner] to connect to the chosen network.
 */
@Singleton
class SoftApController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityRepository: DeviceIdentityRepository,
) {
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    // Both paths supported: API 26+ via startLocalOnlyHotspot, API < 26 via the
    // legacy setWifiApEnabled tethering path.
    val isSupported: Boolean get() = true

    /**
     * Whether the device's location master toggle is on. [WifiManager.startLocalOnlyHotspot]
     * fails outright when it's off, even with the location permission held — a
     * common cause of a failed hotspot on freshly-flashed boxes.
     */
    fun isLocationServicesEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching { LocationManagerCompat.isLocationEnabled(lm) }.getOrElse {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                context.contentResolver, Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF,
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    /**
     * Starts the hotspot. API 26+: [startLocalOnlyHotspot] with OS-chosen creds.
     * API < 26: the legacy [WifiManager.setWifiApEnabled] tethering path with our
     * own fixed credentials (see [startLegacyTetherHotspot]). Only the API 26+
     * path consults location services — `setWifiApEnabled` on pre-O doesn't.
     */
    @Suppress("DEPRECATION")
    suspend fun start(): SoftApResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isLocationServicesEnabled()) {
                return SoftApResult.Failed("Turn on Location (device Settings → Location) so the setup hotspot can start.")
            }
            return startLocalOnlyHotspot()
        }
        return startLegacyTetherHotspot()
    }

    @Suppress("DEPRECATION")
    fun stop() {
        reservation?.let { res -> // API 26+ path
            reservation = null
            runCatching { res.close() }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Legacy tether teardown. reservation is always null on this path.
            ApReflection.setWifiApEnabled(wifiManager, null, false)
        }
    }

    // Location permission is self-granted upstream via device-owner privilege
    // (see DeviceOwnerManager); the flow won't reach here without it.
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.O)
    @Suppress("DEPRECATION")
    private suspend fun startLocalOnlyHotspot(): SoftApResult =
        suspendCancellableCoroutine { cont ->
            val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val creds = res.readCredentials()
                    if (cont.isActive) {
                        cont.resume(
                            if (creds != null) SoftApResult.Started(creds)
                            else SoftApResult.Failed("Hotspot started but its credentials couldn't be read."),
                        )
                    }
                }

                override fun onFailed(reason: Int) {
                    Log.w(TAG, "LocalOnlyHotspot failed, reason=$reason")
                    if (cont.isActive) cont.resume(SoftApResult.Failed(reason.toReasonText()))
                }

                override fun onStopped() {
                    Log.i(TAG, "LocalOnlyHotspot stopped")
                }
            }
            try {
                // null handler → delivered on the main looper.
                wifiManager.startLocalOnlyHotspot(callback, null)
            } catch (e: Exception) {
                Log.e(TAG, "startLocalOnlyHotspot threw", e)
                if (cont.isActive) {
                    cont.resume(SoftApResult.Failed("Couldn't start the hotspot: ${e.message ?: e.javaClass.simpleName}"))
                }
            }
            cont.invokeOnCancellation { stop() }
        }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun Int.toReasonText(): String = when (this) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "No available WiFi channel for the hotspot. Try again."
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "This device can't host a hotspot in its current mode."
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "Hotspot/tethering is disabled by policy on this device."
        else -> "Couldn't start the setup hotspot (error $this). This ROM may not support it."
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Suppress("DEPRECATION")
    private fun WifiManager.LocalOnlyHotspotReservation.readCredentials(): SoftApCredentials? {
        // API 30+ exposes SoftApConfiguration; older releases only WifiConfiguration.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cfg = softApConfiguration
            val ssid = cfg.ssid ?: return null
            return SoftApCredentials(ssid = ssid.stripQuotes(), passphrase = cfg.passphrase)
        }
        val cfg = wifiConfiguration ?: return null
        val ssid = cfg.SSID ?: return null
        return SoftApCredentials(ssid = ssid.stripQuotes(), passphrase = cfg.preSharedKey?.stripQuotes())
    }

    // ------------------------------------------------------------------
    // Legacy tethering hotspot (API < 26)
    // ------------------------------------------------------------------
    // startLocalOnlyHotspot only exists on API 26+, so pre-O boxes (e.g. the
    // spoofed MXQ-PRO that reports Android 12 but is really API 24) use the
    // deprecated-but-still-public WifiManager.setWifiApEnabled to raise the
    // classic tethering AP with our own fixed SSID/passphrase.

    /** Terminal AP states we wait for during legacy bring-up. */
    private sealed interface ApState {
        data object Enabled : ApState
        data object Failed : ApState
    }

    @Suppress("DEPRECATION")
    private suspend fun startLegacyTetherHotspot(): SoftApResult {
        val suffix = setupSuffix()
        val ssid = "$HOTSPOT_SSID_PREFIX-$suffix"
        val config = WifiConfiguration().apply {
            // SSID/PSK deliberately UNQUOTED. This Allwinner BSP's softap path writes
            // softap.conf verbatim from these fields and broadcasts the SSID literally
            // (→ quoted SSID on air) + fails to apply a quoted PSK (→ open AP) when they
            // carry the standard WifiConfiguration quote delimiters. The "quoted ASCII"
            // convention is for the stock framework path, which this ROM's vendor softap
            // bypasses (it ignores hostapd.conf entirely).
            SSID = ssid
            preSharedKey = HOTSPOT_PASSPHRASE
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
        }

        // Primary path: PERSIST the WPA2 config via the tether setter, THEN enable off
        // the stored config. setWifiApEnabled(config) on this Allwinner BSP applies the
        // SSID verbatim (incl. quote delimiters) and ignores the PSK → open AP.
        // setWifiApConfiguration normalizes the SSID and applies security — this is what
        // the box's own Settings hotspot UI does, and why a manual WPA2 AP works there.
        val enabled = if (ApReflection.setWifiApConfiguration(wifiManager, config)) {
            Log.i(TAG, "persisted WPA2 config; enabling off stored config")
            tryApEnable(null)
        } else {
            Log.w(TAG, "setWifiApConfiguration unavailable/rejected; falling back to setWifiApEnabled(config)")
            tryApEnable(config)
        }

        if (enabled) {
            val applied = ApReflection.getWifiApConfiguration(wifiManager)
            Log.i(
                TAG,
                "legacy AP enable accepted; ssid=${applied?.SSID} wpaPsk=${applied?.allowedKeyManagement?.get(
                    WifiConfiguration.KeyMgmt.WPA_PSK,
                )} waiting for up: $ssid",
            )
            return waitForApUp(ssid, HOTSPOT_PASSPHRASE)
        }
        Log.w(TAG, "AP enable rejected; trying persisted default config read-back")

        // Deeper fallback: enable off whatever persisted config exists, read creds back.
        if (tryApEnable(null)) {
            val readBack = readApConfig()
            if (readBack != null) {
                return waitForApUp(readBack.ssid, readBack.passphrase)
            }
            // Read blocked too — the phone can't join an unknown SSID. Fail honestly.
            ApReflection.setWifiApEnabled(wifiManager, null, false)
            return SoftApResult.Failed(
                "Hotspot started but its SSID couldn't be read. " +
                    "Run: adb shell appops set ${context.packageName} WRITE_SETTINGS allow",
            )
        }
        return SoftApResult.Failed(
            "Couldn't start the hotspot (WRITE_SETTINGS appop blocked). " +
                "Run: adb shell appops set ${context.packageName} WRITE_SETTINGS allow",
        )
    }

    /** Author the AP with [config] (or the persisted default when null). False on reject/throw. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun tryApEnable(config: WifiConfiguration?): Boolean =
        ApReflection.setWifiApEnabled(wifiManager, config, true)

    /** Best-effort read of the live AP config (needs ACCESS_WIFI_STATE; may be gated behind WRITE_SETTINGS). */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readApConfig(): SoftApCredentials? =
        runCatching {
            val cfg = ApReflection.getWifiApConfiguration(wifiManager) ?: return@runCatching null
            val ssid = cfg.SSID?.stripQuotes() ?: return@runCatching null
            SoftApCredentials(ssid = ssid, passphrase = cfg.preSharedKey?.stripQuotes())
        }.getOrNull()

    private suspend fun waitForApUp(ssid: String, passphrase: String?): SoftApResult =
        when (awaitApState(AP_UP_TIMEOUT_MS)) {
            ApState.Enabled -> {
                Log.i(TAG, "legacy hotspot up: $ssid")
                SoftApResult.Started(SoftApCredentials(ssid = ssid, passphrase = passphrase))
            }
            ApState.Failed -> SoftApResult.Failed("The setup hotspot failed to come up on this ROM.")
            null -> SoftApResult.Failed("The setup hotspot didn't come up in time. Try again.")
        }

    /**
     * Waits for the AP to reach a terminal state (ENABLED/FAILED), or null on timeout.
     *
     * Hybrid: polls `getWifiApState` every 400 ms (robust on BSPs that don't fire the
     * state-changed broadcast or use a non-standard extra key) AND listens for
     * `WIFI_AP_STATE_CHANGED_ACTION` to short-circuit. Diagnostic logging records the
     * raw state on every change, whether reflection resolved, and every broadcast
     * payload — enough to pinpoint a detection failure on an unknown ROM.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun awaitApState(timeoutMs: Long): ApState? = withTimeoutOrNull(timeoutMs) {
        Log.i(TAG, "awaitApState: start (getApState resolved=${ApReflection.canReadState} timeout=${timeoutMs}ms)")
        val result = CompletableDeferred<ApState>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val keys = intent?.extras?.keySet()?.joinToString(",") ?: "<none>"
                val raw = intent?.getIntExtra(ApReflection.EXTRA_AP_STATE, -1) ?: -1
                Log.i(TAG, "awaitApState: broadcast raw=$raw keys=[$keys]")
                apStateToTerminal(raw)?.let { result.complete(it) }
            }
        }
        context.registerReceiver(receiver, IntentFilter(ApReflection.ACTION_AP_STATE_CHANGED))

        val poller = launch {
            var lastRaw = -2
            while (isActive) {
                val raw = ApReflection.getWifiApState(wifiManager)
                if (raw != lastRaw) {
                    Log.i(TAG, "awaitApState: poll rawState=$raw")
                    lastRaw = raw
                }
                val terminal = apStateToTerminal(raw)
                if (terminal != null) {
                    result.complete(terminal)
                    return@launch
                }
                delay(400)
            }
        }
        try {
            result.await()
        } finally {
            poller.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun apStateToTerminal(state: Int): ApState? = when (state) {
        ApReflection.STATE_ENABLED -> ApState.Enabled
        ApReflection.STATE_FAILED -> ApState.Failed
        else -> null
    }

    /** Last 4 chars of the canonical device ID (sha256(ANDROID_ID)[0..9]) — identical to the ID paired with the pairing code. */
    private suspend fun setupSuffix(): String =
        runCatching { identityRepository.getOrCreateDeviceId() }
            .getOrDefault("")
            .takeLast(4)
            .ifBlank { "0000" }

    private companion object {
        private const val TAG = "SoftApController"
        private const val HOTSPOT_SSID_PREFIX = "CymaDisplay"
        private const val HOTSPOT_PASSPHRASE = "cyma102030"
        private const val AP_UP_TIMEOUT_MS = 15_000L
    }
}

/** SSIDs/PSKs are sometimes wrapped in quotes by the framework — normalise. */
internal fun String.stripQuotes(): String =
    if (length >= 2 && startsWith('"') && endsWith('"')) substring(1, length - 1) else this

/**
 * Reflective shim around the tethering-AP APIs. `setWifiApEnabled`,
 * `getWifiApConfiguration`, `getWifiApState`, and the AP-state constants/actions
 * are `@hide` and stripped from the public android.jar stub (compileSdk 34 only
 * exposes `validateSoftApConfiguration`), so they must be invoked reflectively.
 * Safe at runtime on pre-P (no hidden-API blocklist); the legacy path only runs
 * on API < 26 anyway.
 */
@Suppress("DEPRECATION")
private object ApReflection {
    /** `WifiManager.WIFI_AP_STATE_CHANGED_ACTION` */
    const val ACTION_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    /** `WifiManager.EXTRA_WIFI_AP_STATE` */
    const val EXTRA_AP_STATE = "wifi_ap_state"
    const val STATE_ENABLED = 13 // WifiManager.WIFI_AP_STATE_ENABLED
    const val STATE_FAILED = 14  // WifiManager.WIFI_AP_STATE_FAILED

    private val setAp: Method? = runCatching {
        WifiManager::class.java.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
    }.getOrNull()
    private val setApConfig: Method? = runCatching {
        WifiManager::class.java.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
    }.getOrNull()
    private val getApConfig: Method? = runCatching {
        WifiManager::class.java.getMethod("getWifiApConfiguration")
    }.getOrNull()
    private val getApState: Method? = runCatching {
        WifiManager::class.java.getMethod("getWifiApState")
    }.getOrNull()

    /** Whether `getWifiApState` resolved on this ROM (diagnostic). */
    val canReadState: Boolean get() = getApState != null

    /** Persist the tethering AP config (the setter the Settings hotspot UI uses). Normalizes SSID + applies security. */
    fun setWifiApConfiguration(wm: WifiManager, config: WifiConfiguration): Boolean =
        runCatching { setApConfig?.invoke(wm, config) as? Boolean ?: false }.getOrDefault(false)

    fun setWifiApEnabled(wm: WifiManager, config: WifiConfiguration?, enable: Boolean): Boolean =
        runCatching { setAp?.invoke(wm, config, enable) as? Boolean ?: false }.getOrDefault(false)

    fun getWifiApConfiguration(wm: WifiManager): WifiConfiguration? =
        runCatching { getApConfig?.invoke(wm) as? WifiConfiguration }.getOrNull()

    fun getWifiApState(wm: WifiManager): Int =
        runCatching { getApState?.invoke(wm) as? Int ?: -1 }.getOrDefault(-1)
}
