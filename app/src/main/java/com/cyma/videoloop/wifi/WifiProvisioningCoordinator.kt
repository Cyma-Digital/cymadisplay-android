package com.cyma.videoloop.wifi

import android.os.SystemClock
import android.util.Log
import com.cyma.videoloop.admin.DeviceOwnerManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

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
 * hotspot + captive portal and surfaces the SSID/password + a portal-URL QR
 * through [state] for a corner overlay. There's no WiFi-join QR — the installer
 * joins the network manually off the shown SSID/password, then scans the QR (or
 * types the URL) to open the portal.
 *
 * It watches connectivity and self-manages:
 *  - internet lost  → wait a 15s grace for the WiFi client to (re)connect, then
 *    raise hotspot + portal and publish [ProvisioningState.AwaitingPhone]
 *  - internet gained → tear everything down, back to [ProvisioningState.Idle]
 *  - a session runs at most [SESSION_MAX_MS] (3 min); if it hasn't reached the
 *    internet by then it **stops completely** — hotspot down, overlay [Idle], WiFi
 *    client re-enabled to roam — and stays off until the process restarts (reboot).
 *
 * Sequencing mirrors [WifiScanner]/[SoftApController]/[WifiJoiner]: scan before
 * the hotspot (single radio), tear the hotspot down before joining, re-arm on a
 * failed join (all bounded by the 3-min session deadline).
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

    // Sliding deadline (elapsedRealtime-based) for the current session. Reset to
    // "now + SESSION_MAX_MS" on start and on every credentials submit, so a couple
    // of wrong-password retries don't burn through the window and trip the
    // terminal stop mid-interaction; an abandoned box still goes quiet after
    // SESSION_MAX_MS of no submits.
    @Volatile private var sessionDeadline = 0L

    private fun remainingMs(): Long = sessionDeadline - SystemClock.elapsedRealtime()

    // Set once the 3-min session deadline elapses with no success. Terminal for the
    // process lifetime (app-scoped @Singleton) → provisioning only resumes on reboot.
    private val provisioningStopped = AtomicBoolean(false)

    // Signal from the captive-portal server thread → the session loop. Non-null only
    // while the loop is awaiting a credentials submit for the currently-armed hotspot.
    private var pendingCredentials: CompletableDeferred<Pair<String, String>>? = null

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
        if (provisioningStopped.get()) return
        if (sessionJob?.isActive == true) return
        sessionDeadline = SystemClock.elapsedRealtime() + SESSION_MAX_MS
        sessionJob = scope.launch {
            val finished = runSession(retryAfterFailure)
            if (!finished) {
                // Deadline elapsed with no internet (and no credentials submit to
                // extend it) → stop completely until reboot.
                Log.i(TAG, "provisioning window elapsed — stopping until reboot")
                provisioningStopped.set(true)
                pendingCredentials = null
                teardown()                                       // stops server + hotspot (frees radio)
                runCatching { wifiJoiner.ensureClientEnabled() } // re-enable client to roam known nets
                _state.value = ProvisioningState.Idle            // hides overlay/QR (only Idle hides it)
            }
        }
    }

    /**
     * Background provisioning loop. Each pass first gives the WiFi **client** a
     * [GRACE_MS] window to (re)connect to a known network — the setup hotspot is
     * raised only if the box is still offline after it. The hotspot then stays up
     * until the phone submits credentials. A failed hotspot start or a failed join
     * re-arms; the whole loop is bounded by [sessionDeadline] (started in
     * [startSession] and pushed forward on every credentials submit in
     * [awaitCredentials]), after which this returns `false` and the caller stops
     * provisioning completely until reboot. A successful join validates internet,
     * the connectivity watcher fires online, and [stopSession] cancels this loop.
     *
     * Returns `true` if the loop exited for any reason other than the deadline
     * elapsing (online, needs-permission, or a successful join).
     */
    private suspend fun runSession(retryAfterFailure: Boolean): Boolean {
        var retry = retryAfterFailure
        while (coroutineContext.isActive) {
            if (remainingMs() <= 0) return false

            // 1. Grace window — give the WiFi client a chance to connect before the AP.
            _state.value = ProvisioningState.Preparing

            // Device owner grants silently; otherwise the overlay must ask.
            deviceOwnerManager.grantWifiPermissions()
            if (!deviceOwnerManager.hasWifiRuntimePermissions()) {
                _state.value = ProvisioningState.NeedsPermission(deviceOwnerManager.wifiRuntimePermissions)
                return true
            }

            runCatching { wifiJoiner.ensureClientEnabled() }
            if (connectivityMonitor.awaitValidatedInternet(minOf(GRACE_MS, remainingMs()))) {
                // Came online during the grace window — the watcher idles the overlay.
                _state.value = ProvisioningState.Idle
                return true
            }
            if (remainingMs() <= 0) return false

            // 2. Still offline → scan (before the AP: single radio) and raise the hotspot.
            scanned = runCatching { wifiScanner.scan() }.getOrDefault(emptyList())
            when (val result = softAp.start()) {
                is SoftApResult.Failed -> {
                    _state.value = ProvisioningState.Failed(result.reason)
                    delay(minOf(RETRY_DELAY_MS, remainingMs()))
                    retry = true
                }
                is SoftApResult.Started -> {
                    // 3. Hold the hotspot open until the phone submits — bounded by the
                    //    current deadline, which each submit pushes forward.
                    val creds = awaitCredentials(result, retry) ?: return false
                    val (ssid, password) = creds
                    _state.value = ProvisioningState.Connecting(ssid)
                    // Let the "connecting…" page flush to the phone before we drop the AP.
                    delay(1_200)
                    teardown()
                    val online = runCatching { wifiJoiner.join(ssid, password) }.getOrDefault(false)
                    if (online) {
                        Log.i(TAG, "joined '$ssid' — online (watcher will idle the overlay)")
                        return true
                    }
                    Log.w(TAG, "join to '$ssid' failed — re-arming hotspot")
                    retry = true
                }
            }
        }
        return false
    }

    /**
     * Arms the captive portal, publishes [ProvisioningState.AwaitingPhone], and
     * suspends until the phone submits credentials or the current deadline elapses
     * (returning null). A successful submit — right or wrong password — pushes
     * [sessionDeadline] forward by [SESSION_MAX_MS], so a couple of retries never
     * trip the terminal stop mid-interaction; an abandoned hotspot still times out.
     * The captive server is left running on return — the caller tears it down (via
     * [teardown]) so a "connecting…" page can flush first.
     */
    private suspend fun awaitCredentials(
        result: SoftApResult.Started,
        retry: Boolean,
    ): Pair<String, String>? {
        if (remainingMs() <= 0) return null
        val pending = CompletableDeferred<Pair<String, String>>()
        pendingCredentials = pending
        server = CaptivePortalServer.startOnAvailablePort(
            networksProvider = { scanned },
            onSubmit = { ssid, password -> onCredentialsSubmitted(ssid, password) },
        )
        val port = server?.listeningPort ?: CaptivePortalServer.PREFERRED_PORT
        val apIp = HotspotAddress.awaitApIpv4()
        _state.value = ProvisioningState.AwaitingPhone(
            ssid = result.credentials.ssid,
            passphrase = result.credentials.passphrase,
            portalUrl = HotspotAddress.portalUrl(apIp, port),
            retryAfterFailure = retry,
        )
        val creds = try {
            withTimeoutOrNull(remainingMs()) { pending.await() }
        } finally {
            pendingCredentials = null
        }
        if (creds != null) {
            sessionDeadline = SystemClock.elapsedRealtime() + SESSION_MAX_MS
        }
        return creds
    }

    /** Phone submitted the form (server thread). Hands the creds to the session loop. */
    private fun onCredentialsSubmitted(ssid: String, password: String) {
        scope.launch { pendingCredentials?.complete(ssid to password) }
    }

    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        pendingCredentials = null
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

        /** Grace given to the WiFi client to (re)connect before the setup AP is raised. */
        private const val GRACE_MS = 15_000L

        /** Whole provisioning session lives at most this long; then it stops until reboot. */
        private const val SESSION_MAX_MS = 180_000L

        /** Backoff before retrying after a failed hotspot start. */
        private const val RETRY_DELAY_MS = 20_000L
    }
}
