package com.cyma.videoloop.data.metrics

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.HardwarePropertiesManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.cyma.videoloop.data.api.DeviceMetricsDto
import com.cyma.videoloop.data.identity.DeviceIdentityRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device-health metrics that go into [DeviceMetricsDto], mirroring what
 * the Pi fleet's `upload_stats.py` collects so both fleets report the same shape.
 *
 * Every metric is read defensively: signage ROMs vary wildly in what they expose
 * (`/proc/stat` can be EACCES, a box may have no thermal zone at all), and a
 * single unreadable path must degrade to `null` rather than drop the whole report
 * or crash the reporter loop.
 */
@Singleton
class MetricsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: DeviceIdentityRepository,
    private val geoLocationRepository: GeoLocationRepository,
) {
    private val activityManager: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    suspend fun collect(): DeviceMetricsDto = withContext(Dispatchers.IO) {
        val (ramMb, ramPercent) = readRamUsage()
        val fix = runCatching { geoLocationRepository.cachedFix() }.getOrNull()
        DeviceMetricsDto(
            deviceId = identity.getOrCreateDeviceId(),
            cpuTemp = readCpuTemp()?.format2(),
            memAvail = readFreeDiskMb()?.format2(),
            uptime = (SystemClock.elapsedRealtime() / 3_600_000.0).format2(),
            ramUsageMb = ramMb?.format2(),
            ramUsagePercent = ramPercent?.format2(),
            cpuUsagePercent = readCpuUsagePercent(),
            wifiSignalStrength = readRssi(),
            ipAddress = readIpAddress(),
            wifiSsid = readSsid(),
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            locationTimestamp = fix?.timestamp,
        )
    }

    /**
     * CPU temperature in °C. Prefers the sysfs thermal zones (what the Pi reads);
     * falls back to [HardwarePropertiesManager], which is API 24+ and only callable
     * by a device owner — which this app is (see
     * [com.cyma.videoloop.admin.DeviceOwnerManager]).
     */
    private fun readCpuTemp(): Double? =
        readThermalZoneTemp() ?: readHardwarePropertiesTemp()

    private fun readThermalZoneTemp(): Double? {
        // thermal_zone0 is the CPU on the boxes we ship; scan the rest as a fallback
        // because zone ordering isn't guaranteed across BSPs.
        val zones = (0 until MAX_THERMAL_ZONES).map { File("/sys/class/thermal/thermal_zone$it/temp") }
        for (zone in zones) {
            val raw = runCatching { zone.readText().trim().toDouble() }.getOrNull() ?: continue
            // Kernels report milli-°C; some BSPs already report °C.
            val celsius = if (raw > 1_000) raw / 1_000 else raw
            if (celsius in PLAUSIBLE_TEMP_RANGE) return celsius
        }
        return null
    }

    @SuppressLint("NewApi")
    private fun readHardwarePropertiesTemp(): Double? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return runCatching {
            val hpm = context.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE)
                as HardwarePropertiesManager
            hpm.getDeviceTemperatures(
                HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                HardwarePropertiesManager.TEMPERATURE_CURRENT,
            ).map { it.toDouble() }.firstOrNull { it in PLAUSIBLE_TEMP_RANGE }
        }.getOrNull()
    }

    /** Free space on the data partition, in MB (the Pi's `memAvail`). */
    private fun readFreeDiskMb(): Double? = runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        (stat.availableBlocksLong * stat.blockSizeLong) / 1024.0 / 1024.0
    }.getOrNull()

    /** Used RAM as (MB, percent). */
    private fun readRamUsage(): Pair<Double?, Double?> = runCatching {
        val info = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val total = info.totalMem
        if (total <= 0) return@runCatching null to null
        val used = total - info.availMem
        (used / 1024.0 / 1024.0) to (used * 100.0 / total)
    }.getOrDefault(null to null)

    /**
     * System-wide CPU busy percentage, from two `/proc/stat` samples
     * [CPU_SAMPLE_MS] apart. `/proc/stat` is readable on the boxes we ship but is
     * restricted on some hardened ROMs — null there rather than a fake number.
     */
    private suspend fun readCpuUsagePercent(): Double? {
        val first = readCpuTimes() ?: return null
        delay(CPU_SAMPLE_MS)
        val second = readCpuTimes() ?: return null
        val totalDelta = second.total - first.total
        if (totalDelta <= 0) return null
        val idleDelta = second.idle - first.idle
        return ((totalDelta - idleDelta) * 100.0 / totalDelta).coerceIn(0.0, 100.0)
    }

    private fun readCpuTimes(): CpuTimes? = runCatching {
        val line = File("/proc/stat").useLines { lines ->
            lines.firstOrNull { it.startsWith("cpu ") }
        } ?: return@runCatching null
        val values = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 5) return@runCatching null
        // user nice system idle iowait [irq softirq steal ...]
        CpuTimes(total = values.sum(), idle = values[3] + values[4])
    }.getOrNull()

    // ACCESS_WIFI_STATE is declared; the location permission that SSID/RSSI need on
    // API 27+ is self-granted via device-owner privilege (DeviceOwnerManager).
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readRssi(): Int? = runCatching {
        wifiManager.connectionInfo?.rssi?.takeIf { it != INVALID_RSSI && it < 0 }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readSsid(): String? = runCatching {
        wifiManager.connectionInfo?.ssid
            ?.removeSurrounding("\"")
            // WifiManager.UNKNOWN_SSID is API 29+; the literal is stable across levels
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }.getOrNull()

    /**
     * First non-loopback IPv4 address — the equivalent of the Pi's
     * `hostname -I | awk '{print $1}'`. Enumerating interfaces works on every API
     * level we support, unlike the `ConnectivityManager.activeNetwork` path (API 23+).
     */
    private fun readIpAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull().also { if (it == null) Log.d(TAG, "no IPv4 address found") }

    private data class CpuTimes(val total: Long, val idle: Long)

    private companion object {
        private const val TAG = "MetricsCollector"
        private const val MAX_THERMAL_ZONES = 12
        private const val CPU_SAMPLE_MS = 1_000L
        private const val INVALID_RSSI = -127
        private val PLAUSIBLE_TEMP_RANGE = 1.0..150.0
    }
}

/** Two decimals, dot separator — the Pi sends these as `"{:.2f}"` strings. */
private fun Double.format2(): String = String.format(Locale.US, "%.2f", this)
