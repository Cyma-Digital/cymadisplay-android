package com.cyma.videoloop.wifi

import android.util.Log
import com.cyma.videoloop.admin.DeviceOwnerManager
import com.cyma.videoloop.util.wifiQrPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Overlay-facing state of background WiFi provisioning. [Idle] means "show
 * nothing" (either online, or not provisioning); every other state renders a
 * small overlay on top of the always-running content.
 */
sealed interface ProvisioningState {
    data object Idle : ProvisioningState
    data object Preparing : ProvisioningState
    data class NeedsPermission(val permissions: List<String>) : ProvisioningState
    data class AwaitingPhone(
        val qrPayload: String,
        val ssid: String,
        val passphrase: String?,
        val portalUrl: String?,
        val retryAfterFailure: Boolean,
    ) : ProvisioningState
    data class Connecting(val ssid: String) : ProvisioningState
    data class Failed(val message: String) : ProvisioningState
}

/**
 * App-scoped driver of the WiFi-setup flow. Unlike a screen ViewModel this lives
 * for the whole process, so provisioning runs **in the background without ever
 * interrupting playback**: content keeps rendering while this raises the setup
 * hotspot + captive portal and surfaces a QR through [state] for a corner overlay.
 *
 * It watches connectivity and self-manages:
 *  - internet lost  → raise hotspot + portal, publish [ProvisioningState.AwaitingPhone]
 *  - internet gained → tear everything down, back to [ProvisioningState.Idle]
 *
 * Sequencing mirrors [WifiScanner]/[SoftApController]/[WifiJoiner]: scan before
 * the hotspot (single radio), tear the hotspot down before joining, re-arm on a
 * failed join.
 */
@Singleton
class WifiProvisioningCoordinator @Inject constructor(
    private val softAp: SoftApController,
    private val wifiScanner: WifiScanner,
    private val wifiJoiner: WifiJoiner,
    private val deviceOwnerManager: DeviceOwnerManager,
    private val connectivityMonitor: ConnectivityMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<ProvisioningState>(ProvisioningState.Idle)
    val state: StateFlow<ProvisioningState> = _state.asStateFlow()

    private var watcherStarted = false
    private var sessionJob: Job? = null
    private var server: CaptivePortalServer? = null

    @Volatile private var scanned: List<ScannedNetwork> = emptyList()
    private val submitting = AtomicBoolean(false)
    private val rescanning = AtomicBoolean(false)

    /** Start watching connectivity. Idempotent — safe to call from every onCreate. */
    fun ensureRunning() {
        if (watcherStarted) return
        watcherStarted = true
        scope.launch {
            connectivityMonitor.validatedInternetFlow().distinctUntilChanged().collect { online ->
                Log.i(TAG, "connectivity online=$online")
                if (online) stopSession() else startSession(retryAfterFailure = false)
            }
        }
    }

    /** Called by the overlay once it has obtained the runtime permissions (non-DO path). */
    fun onPermissionsGranted() = startSession(retryAfterFailure = false)

    private fun startSession(retryAfterFailure: Boolean) {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch { runSession(retryAfterFailure) }
    }

    private suspend fun runSession(retryAfterFailure: Boolean) {
        _state.value = ProvisioningState.Preparing
        submitting.set(false)

        // Device owner grants silently; otherwise the overlay must ask.
        deviceOwnerManager.grantWifiPermissions()
        if (!deviceOwnerManager.hasWifiRuntimePermissions()) {
            _state.value = ProvisioningState.NeedsPermission(deviceOwnerManager.wifiRuntimePermissions)
            return
        }

        // Scan first — a single-radio box can't scan while its AP is up.
        scanned = runCatching { wifiScanner.scan() }.getOrDefault(emptyList())

        when (val result = softAp.start()) {
            is SoftApResult.Failed -> {
                _state.value = ProvisioningState.Failed(result.reason)
                scheduleRetry()
            }
            is SoftApResult.Started -> {
                server = CaptivePortalServer.startOnAvailablePort(
                    networksProvider = { scanned },
                    onRescanRequested = { rescan() },
                    onSubmit = { ssid, password -> onCredentialsSubmitted(ssid, password) },
                )
                val port = server?.listeningPort ?: CaptivePortalServer.PREFERRED_PORT
                val apIp = HotspotAddress.awaitApIpv4()
                _state.value = ProvisioningState.AwaitingPhone(
                    qrPayload = wifiQrPayload(result.credentials.ssid, result.credentials.passphrase),
                    ssid = result.credentials.ssid,
                    passphrase = result.credentials.passphrase,
                    portalUrl = HotspotAddress.portalUrl(apIp, port),
                    retryAfterFailure = retryAfterFailure,
                )
            }
        }
    }

    /** Portal "Rescan" link (server thread). Refreshes the cached list best-effort. */
    private fun rescan() {
        if (!rescanning.compareAndSet(false, true)) return
        scope.launch {
            scanned = runCatching { wifiScanner.scan() }.getOrDefault(scanned)
            rescanning.set(false)
        }
    }

    /** Phone submitted the form (server thread). */
    private fun onCredentialsSubmitted(ssid: String, password: String) {
        if (!submitting.compareAndSet(false, true)) return
        scope.launch {
            _state.value = ProvisioningState.Connecting(ssid)
            // Let the "connecting…" page flush to the phone before we drop the AP.
            delay(1_200)
            teardown()

            val online = runCatching { wifiJoiner.join(ssid, password) }.getOrDefault(false)
            if (online) {
                Log.i(TAG, "joined '$ssid' — online (connectivity watcher will idle the overlay)")
                // The connectivity watcher sees validated internet and calls stopSession().
            } else {
                Log.w(TAG, "join to '$ssid' failed — re-arming hotspot")
                startSession(retryAfterFailure = true)
            }
        }
    }

    /** Retry a failed hotspot after a delay, as long as we're still offline. */
    private fun scheduleRetry() {
        scope.launch {
            delay(RETRY_DELAY_MS)
            if (_state.value is ProvisioningState.Failed) startSession(retryAfterFailure = true)
        }
    }

    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        teardown()
        _state.value = ProvisioningState.Idle
    }

    private fun teardown() {
        server?.let { runCatching { it.stop() } }
        server = null
        softAp.stop()
    }

    private companion object {
        private const val TAG = "WifiProvisioning"
        private const val RETRY_DELAY_MS = 20_000L
    }
}
