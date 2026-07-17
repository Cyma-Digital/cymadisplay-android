package com.cyma.videoloop.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Three-way network health, surfaced for the on-screen status indicator.
 *
 *  - [OFFLINE]     no active network at all
 *  - [NO_INTERNET] associated with a network but not internet-validated
 *                  (captive portal / dead upstream)
 *  - [ONLINE]      validated internet
 */
enum class NetworkStatus { OFFLINE, NO_INTERNET, ONLINE }

/**
 * Reports whether the box has a *validated* internet connection — i.e. a network
 * that actually reaches the internet, not merely an associated-but-captive WiFi.
 *
 * "Validated" is the signal the routing in [com.cyma.videoloop.MainActivity] and
 * the join step in [WifiJoiner] care about: a box that has associated with a
 * WiFi AP but can't reach the backend is still "offline" for our purposes.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cm: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Snapshot: does an active network currently have validated internet access? */
    fun hasValidatedInternet(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        // API 21–22: no per-network validation signal — best-effort "connected".
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }

    /**
     * Snapshot of the three-way [NetworkStatus]. Same SDK-version split as
     * [hasValidatedInternet]; these are fast local binder calls (no network I/O),
     * safe to call on the main thread / at composition.
     *
     * API 21–22 has no per-network validation signal, so it degrades to
     * ONLINE/OFFLINE only (never NO_INTERNET). Signage boxes run API 23+.
     */
    fun currentStatus(): NetworkStatus {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return NetworkStatus.OFFLINE
            val caps = cm.getNetworkCapabilities(network) ?: return NetworkStatus.OFFLINE
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return NetworkStatus.OFFLINE
            }
            return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                NetworkStatus.ONLINE
            } else {
                NetworkStatus.NO_INTERNET
            }
        }
        @Suppress("DEPRECATION")
        return if (cm.activeNetworkInfo?.isConnected == true) NetworkStatus.ONLINE
        else NetworkStatus.OFFLINE
    }

    /**
     * Cold flow of the three-way [NetworkStatus]. Emits on every connectivity
     * callback **and** on a 5 s heartbeat — the heartbeat catches upstream
     * changes (e.g. router loses WAN) that fire no [ConnectivityManager.NetworkCallback].
     * De-duped at the source via [distinctUntilChanged].
     */
    fun networkStatusFlow(): Flow<NetworkStatus> = callbackFlow {
        trySend(currentStatus())
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Always re-read the active/routed network — the callback may fire for
            // a matched-but-not-active network, whereas the viewer cares about the
            // network the box actually routes through.
            override fun onAvailable(network: Network) { trySend(currentStatus()) }
            override fun onLost(network: Network) { trySend(currentStatus()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(currentStatus())
            }
        }
        val ticker = launch {
            while (isActive) {
                delay(5_000)
                trySend(currentStatus())
            }
        }
        cm.registerNetworkCallback(request, callback)
        awaitClose {
            ticker.cancel()
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    /**
     * Suspends until a validated-internet network appears, or [timeoutMs] elapses.
     * Returns true if internet became reachable within the window.
     */
    suspend fun awaitValidatedInternet(timeoutMs: Long): Boolean {
        if (hasValidatedInternet()) return true
        return withTimeoutOrNull(timeoutMs) {
            validatedInternetFlow().first { it }
        } ?: false
    }

    /** Cold flow of the validated-internet flag, driven by a network callback. */
    fun validatedInternetFlow(): Flow<Boolean> = callbackFlow {
        trySend(hasValidatedInternet())
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(hasValidatedInternet()) }
            override fun onLost(network: Network) { trySend(hasValidatedInternet()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
            }
        }
        cm.registerNetworkCallback(request, callback)
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
}
