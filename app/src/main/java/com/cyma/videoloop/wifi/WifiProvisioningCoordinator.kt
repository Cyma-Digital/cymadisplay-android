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

    /**
     * A join attempt has been made and we're waiting to see whether it reached the
     * internet. Distinct from [Preparing] (which means "about to raise the hotspot"):
     * a join that validates *just* after [WifiJoiner]'s own timeout lands here, and
     * mislabelling it as Preparing made a working connection look like a restart.
     */
    data object Verifying : ProvisioningState
    data class NeedsPermission(val permissions: List<String>) : ProvisioningState
    data class AwaitingPhone(
        val ssid: String,
        val passphrase: String?,
        val portalUrl: String?,
        /**
         * Why the previous attempt failed, or null on the first arming. Carries the
         * reason rather than a bare flag: "wrong password" and "joined the AP but it
         * has no internet" need different advice, and showing the former for the
         * latter sends the installer chasing the wrong problem.
         */
        val retryReason: String?,
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
 * **Provisioning is boot-only.** Exactly one session is started, from
 * [ensureRunning] at process start (i.e. right after the box powers up):
 *  - first a [GRACE_MS] (1 min) window in which the WiFi client can (re)connect to
 *    an already-configured network — Android needs a while to do that after boot,
 *    so a box that comes up online never raises the hotspot at all. The overlay
 *    stays [ProvisioningState.Idle] (nothing shown) for the whole grace window
 *  - still offline after it → raise hotspot + portal and publish
 *    [ProvisioningState.AwaitingPhone]
 *  - internet gained at any point → tear everything down, back to
 *    [ProvisioningState.Idle]
 *  - the session runs at most [SESSION_MAX_MS] (5 min); if it hasn't reached the
 *    internet by then it **stops completely** — hotspot down, overlay [Idle], WiFi
 *    client re-enabled to roam — and stays off until the process restarts (reboot).
 *
 * Losing internet *later*, while the box is already running, deliberately does
 * **not** raise the hotspot — the connectivity watcher only reacts to coming
 * online (to tear down). Recovery from a mid-run outage is a reboot.
 *
 * Sequencing mirrors [WifiScanner]/[SoftApController]/[WifiJoiner]: scan before
 * the hotspot (single radio), tear the hotspot down before joining, re-arm on a
 * failed join (all bounded by the session deadline).
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

    // Set once the session deadline elapses with no success, or once the box is online.
    // Terminal for the process lifetime (app-scoped @Singleton) → provisioning only
    // resumes on reboot.
    private val provisioningStopped = AtomicBoolean(false)

    // Signal from the captive-portal server thread → the session loop. Non-null only
    // while the loop is awaiting a credentials submit for the currently-armed hotspot.
    private var pendingCredentials: CompletableDeferred<Pair<String, String>>? = null

    /**
     * Starts the single boot provisioning session and the connectivity watcher.
     * Idempotent — safe to call from every onCreate.
     */
    fun ensureRunning() {
        if (watcherStarted) return
        watcherStarted = true
        // Boot-only: one session, started here at process start. Its first phase is the
        // GRACE_MS window, so an already-configured box that connects on its own never
        // raises the hotspot.
        startSession(retryAfterFailure = false)
        scope.launch {
            connectivityMonitor.validatedInternetFlow().distinctUntilChanged().collect { online ->
                Log.i(TAG, "connectivity online=$online")
                // Only coming *online* is reactive (tear everything down). Going offline
                // later must NOT raise the hotspot — it comes up at boot only.
                if (online) stopSession()
            }
        }
    }

    /** Called by the overlay once it has obtained the runtime permissions (non-DO path). */
    fun onPermissionsGranted() = startSession(retryAfterFailure = false)

    private fun startSession(retryAfterFailure: Boolean) {
        if (provisioningStopped.get()) return
        if (sessionJob?.isActive == true) return
        // Grace window is on top of the AP window, so the hotspot really is reachable
        // for SESSION_MAX_MS and not SESSION_MAX_MS minus the grace.
        sessionDeadline = SystemClock.elapsedRealtime() + GRACE_MS + SESSION_MAX_MS
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
        var retry: String? = if (retryAfterFailure) RETRY_GENERIC else null
        while (coroutineContext.isActive) {
            if (remainingMs() <= 0) return false

            // 1. Grace window — give the WiFi client a chance to connect before the AP.
            //    Stay silent (Idle) through it on the first pass: a box that connects on
            //    its own must never flash a WiFi-setup overlay at boot. On a retry pass
            //    a join was already attempted, so this window is really "did it work?" —
            //    Verifying, not Preparing.
            _state.value = if (retry != null) ProvisioningState.Verifying else ProvisioningState.Idle

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
            _state.value = ProvisioningState.Preparing
            scanned = runCatching { wifiScanner.scan() }.getOrDefault(emptyList())
            when (val result = softAp.start()) {
                is SoftApResult.Failed -> {
                    _state.value = ProvisioningState.Failed(result.reason)
                    delay(minOf(RETRY_DELAY_MS, remainingMs()))
                    retry = RETRY_GENERIC
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
                    // Verifying is published from inside the join, the moment supplicant
                    // actually associates — before that we're still connecting.
                    val joinResult = runCatching {
                        wifiJoiner.join(ssid, password) { _state.value = ProvisioningState.Verifying }
                    }.getOrElse {
                        Log.w(TAG, "join to '$ssid' threw", it)
                        JoinResult.NotAssociated
                    }
                    when (joinResult) {
                        JoinResult.Online -> {
                            Log.i(TAG, "joined '$ssid' — online (watcher will idle the overlay)")
                            return true
                        }
                        JoinResult.AssociatedNoInternet -> {
                            Log.w(TAG, "'$ssid' joined but has no internet — re-arming hotspot")
                            retry = "Conectou a \"$ssid\", mas essa rede não tem internet. " +
                                "Verifique o roteador ou escolha outra rede."
                        }
                        JoinResult.NotAssociated -> {
                            Log.w(TAG, "join to '$ssid' never associated — re-arming hotspot")
                            retry = RETRY_GENERIC
                        }
                    }
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
        retryReason: String?,
    ): Pair<String, String>? {
        if (remainingMs() <= 0) return null
        val pending = CompletableDeferred<Pair<String, String>>()
        pendingCredentials = pending
        server = CaptivePortalServer.startOnAvailablePort(
            networksProvider = { scanned },
            onSubmit = { ssid, password -> onCredentialsSubmitted(ssid, password) },
        )
        val port = server?.listeningPort ?: CaptivePortalServer.PORTAL_PORT
        val apIp = HotspotAddress.awaitApIpv4()
        _state.value = ProvisioningState.AwaitingPhone(
            ssid = result.credentials.ssid,
            passphrase = result.credentials.passphrase,
            portalUrl = HotspotAddress.portalUrl(apIp, port),
            retryReason = retryReason,
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

    /**
     * Online → nothing left to provision. Terminal for the process lifetime: the box
     * is configured, and a later outage must not bring the hotspot back (boot-only).
     */
    private fun stopSession() {
        provisioningStopped.set(true)
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

        /**
         * Grace given to the WiFi client to (re)connect before the setup AP is raised.
         * 1 min: after a cold boot Android can take a good while to associate with an
         * already-configured network, and raising the AP earlier would steal the radio.
         */
        private const val GRACE_MS = 60_000L

        /** Whole provisioning session lives at most this long; then it stops until reboot. */
        private const val SESSION_MAX_MS = 300_000L

        /** Backoff before retrying after a failed hotspot start. */
        private const val RETRY_DELAY_MS = 20_000L

        /** Shown when the box never associated — by far the most common cause is a typo'd password. */
        private const val RETRY_GENERIC =
            "Não foi possível conectar — senha incorreta? Tente de novo."
    }
}
