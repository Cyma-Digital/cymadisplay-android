package com.cyma.videoloop.data.metrics

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cyma.videoloop.BuildConfig
import com.cyma.videoloop.data.api.AccessPointDto
import com.cyma.videoloop.data.api.GeolocateRequestDto
import com.cyma.videoloop.data.api.MetricsApi
import com.cyma.videoloop.wifi.ConnectivityMonitor
import com.cyma.videoloop.wifi.WifiScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_LAT = doublePreferencesKey("geo_latitude")
private val KEY_LNG = doublePreferencesKey("geo_longitude")
private val KEY_TIMESTAMP = stringPreferencesKey("geo_timestamp")

/** A resolved position, as reported in the metrics payload. */
data class GeoFix(val latitude: Double, val longitude: Double, val timestamp: String)

/**
 * Physical position of the box, resolved by WiFi trilateration through Google's
 * Geolocation API — the Android port of the Pi fleet's `geolocation.py`.
 *
 * **Boot-only, exactly like the Pi's one-shot `geolocation.service`.** There's no
 * GPS on a signage box and it never moves, so [resolveOnBoot] runs once per process
 * start and the result is cached in DataStore forever after; [cachedFix] is what
 * every metrics report puts on the wire. A box that fails all its attempts keeps
 * reporting the previous boot's fix (or nulls, if it never had one) and does not
 * try again until it reboots — the same "recovery is a reboot" contract the WiFi
 * provisioning flow uses.
 *
 * This bounds Google Geolocation spend at **≤ [MAX_ATTEMPTS] calls per boot**
 * (normally 1). A per-tick refresh would have burned ~3 calls every 5 min on any
 * box Google can't locate.
 *
 * The resolve **waits for validated internet** before scanning. A single-radio box
 * in AP mode can't scan for client networks, and validated internet implies the
 * setup hotspot is already down — so this can never fight
 * [com.cyma.videoloop.wifi.WifiProvisioningCoordinator] for the radio.
 */
@Singleton
class GeoLocationRepository @Inject constructor(
    private val api: MetricsApi,
    private val wifiScanner: WifiScanner,
    private val connectivityMonitor: ConnectivityMonitor,
    private val dataStore: DataStore<Preferences>,
) {
    private val refreshLock = Mutex()

    /** One resolve per process, success or not — see the class kdoc. */
    private val attempted = AtomicBoolean(false)

    /** Last resolved fix, or null if none has ever succeeded on this box. */
    suspend fun cachedFix(): GeoFix? {
        val prefs = dataStore.data.first()
        val lat = prefs[KEY_LAT] ?: return null
        val lng = prefs[KEY_LNG] ?: return null
        val ts = prefs[KEY_TIMESTAMP] ?: return null
        return GeoFix(lat, lng, ts)
    }

    /**
     * Resolves the box's position once for this process, on the same retry ladder as
     * `geolocation.py` (3 attempts, 10 s → 20 s → 40 s). Subsequent calls are no-ops
     * whatever the outcome, so a box Google can't locate never retries until reboot.
     * Never throws.
     */
    suspend fun resolveOnBoot() {
        if (!attempted.compareAndSet(false, true)) return
        if (BuildConfig.GEOLOCATION_API_KEY.isEmpty()) {
            // Built without a key in `.env` — don't burn attempts on calls that 400.
            Log.i(TAG, "no geolocation API key in this build; reporting null coordinates")
            return
        }
        refreshLock.withLock {
            // The box may still be associating right after a cold boot (~17 s on the
            // TX3); a scan before that returns nothing useful.
            if (!connectivityMonitor.awaitValidatedInternet(ONLINE_WAIT_MS)) {
                Log.i(TAG, "no internet within ${ONLINE_WAIT_MS}ms; keeping cached fix until reboot")
                return
            }

            var backoffMs = BACKOFF_START_MS
            repeat(MAX_ATTEMPTS) { attempt ->
                val result = runCatching { resolveOnce() }
                result.getOrNull()?.let { fix ->
                    persist(fix)
                    Log.i(TAG, "fix resolved: ${fix.latitude},${fix.longitude} (attempt ${attempt + 1})")
                    return
                }
                Log.w(TAG, "geolocate attempt ${attempt + 1} failed", result.exceptionOrNull())
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                }
            }
            Log.w(TAG, "giving up after $MAX_ATTEMPTS attempts; no retry until reboot")
        }
    }

    private suspend fun resolveOnce(): GeoFix {
        val accessPoints = wifiScanner.scanAccessPoints().map {
            AccessPointDto(macAddress = it.bssid, signalStrength = it.rssi, channel = it.channel)
        }
        // Google needs at least two APs to trilaterate; considerIp=false means a
        // single-AP request usually 404s rather than returning a useless IP guess.
        require(accessPoints.size >= MIN_ACCESS_POINTS) {
            "only ${accessPoints.size} access points visible"
        }
        val response = api.geolocate(
            url = "$GEOLOCATE_URL${BuildConfig.GEOLOCATION_API_KEY}",
            body = GeolocateRequestDto(wifiAccessPoints = accessPoints),
        )
        return GeoFix(
            latitude = response.location.lat,
            longitude = response.location.lng,
            timestamp = iso8601(System.currentTimeMillis()),
        )
    }

    private suspend fun persist(fix: GeoFix) {
        dataStore.edit { prefs ->
            prefs[KEY_LAT] = fix.latitude
            prefs[KEY_LNG] = fix.longitude
            prefs[KEY_TIMESTAMP] = fix.timestamp
        }
    }

    private fun iso8601(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(epochMs))

    private companion object {
        private const val TAG = "GeoLocation"
        private const val GEOLOCATE_URL =
            "https://www.googleapis.com/geolocation/v1/geolocate?key="
        private const val MAX_ATTEMPTS = 3
        private const val ONLINE_WAIT_MS = 120_000L
        private const val BACKOFF_START_MS = 10_000L
        private const val BACKOFF_MAX_MS = 300_000L
        private const val MIN_ACCESS_POINTS = 2
    }
}
