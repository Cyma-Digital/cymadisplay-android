package com.cyma.videoloop.data.metrics

import android.util.Log
import com.cyma.videoloop.data.api.MetricsApi
import com.cyma.videoloop.wifi.ConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped loop that POSTs device health to the metrics backend every
 * [INTERVAL_MS], the Android counterpart of the Pi fleet's `upload_stats.py`
 * (`time.sleep(300)`).
 *
 * An in-app loop rather than a `WorkManager` worker: this is a kiosk app that is
 * always in the foreground, and WorkManager's 15-min floor for periodic work
 * can't hit the 5-min cadence the dashboard expects.
 *
 * Fire-and-forget by design — a failed POST is logged and retried on the next
 * tick, exactly like the Pi's `try/except`. Nothing here touches UI state, so
 * playback is never affected.
 *
 * The position is resolved **once per boot** off this class's [start] (see
 * [GeoLocationRepository]); every report then just reads the cached fix, so the
 * tick never spends a Google Geolocation call.
 */
@Singleton
class MetricsReporter @Inject constructor(
    private val api: MetricsApi,
    private val collector: MetricsCollector,
    private val geoLocationRepository: GeoLocationRepository,
    private val connectivityMonitor: ConnectivityMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    /** Starts the reporting loop. Safe to call more than once; only the first starts it. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Boot-only, in its own coroutine: the position is resolved once per process
        // (see GeoLocationRepository) and must not delay the first report.
        scope.launch { runCatching { geoLocationRepository.resolveOnBoot() } }
        scope.launch {
            while (isActive) {
                report()
                delay(INTERVAL_MS)
            }
        }
    }

    private suspend fun report() {
        // A cold-booted box takes a while to associate (~17 s measured on the TX3),
        // so the very first tick would otherwise always fail and the first datapoint
        // would land 5 min in. Returns immediately when already online.
        connectivityMonitor.awaitValidatedInternet(ONLINE_WAIT_MS)

        runCatching { api.sendMetrics(collector.collect()) }
            .onSuccess { Log.d(TAG, "metrics posted") }
            .onFailure { Log.w(TAG, "metrics post failed", it) }
    }

    private companion object {
        private const val TAG = "MetricsReporter"

        /** 5 min — matches `upload_stats.py`'s `time.sleep(300)`. */
        private const val INTERVAL_MS = 5L * 60 * 1000

        /** How long a tick waits for internet before posting anyway. */
        private const val ONLINE_WAIT_MS = 60_000L
    }
}
