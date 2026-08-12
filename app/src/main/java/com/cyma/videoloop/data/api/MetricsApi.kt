package com.cyma.videoloop.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Device-health reporting, on a different host from [CymaApi]
 * (`BuildConfig.METRICS_BASE_URL`) — hence its own Retrofit instance, qualified
 * with [com.cyma.videoloop.di.Metrics].
 *
 * The payload deliberately mirrors the Raspberry-Pi fleet's `upload_stats.py`
 * key-for-key (including the lone snake_case `location_timestamp` and the
 * numbers-sent-as-strings), so Pi and Android boxes land in the same dashboard
 * with no backend change.
 */
interface MetricsApi {

    @POST("send2")
    suspend fun sendMetrics(@Body body: DeviceMetricsDto)

    /**
     * Google Geolocation API. Takes an absolute [url] (including the API key)
     * so googleapis.com doesn't need a third Retrofit instance.
     */
    @POST
    suspend fun geolocate(
        @Url url: String,
        @Body body: GeolocateRequestDto,
    ): GeolocateResponseDto
}

/**
 * One metrics report. Every field is nullable: a metric the ROM won't expose
 * (locked-down `/proc`, no thermal zone) is reported as `null` rather than a
 * fabricated zero, and never drops the rest of the report.
 *
 * Field types match the Pi's payload exactly — the formatted-to-2-decimals
 * values go over the wire as strings, `cpuUsagePercent` as a number.
 */
@Serializable
data class DeviceMetricsDto(
    val deviceId: String,
    val cpuTemp: String? = null,
    /** Free disk space in MB (the Pi's confusingly named `memAvail`). */
    val memAvail: String? = null,
    /** Uptime in hours. */
    val uptime: String? = null,
    val ramUsageMb: String? = null,
    val ramUsagePercent: String? = null,
    val cpuUsagePercent: Double? = null,
    /** RSSI in dBm. */
    val wifiSignalStrength: Int? = null,
    val ipAddress: String? = null,
    val wifiSsid: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("location_timestamp") val locationTimestamp: String? = null,
)

@Serializable
data class GeolocateRequestDto(
    val considerIp: Boolean = false,
    val wifiAccessPoints: List<AccessPointDto>,
)

@Serializable
data class AccessPointDto(
    val macAddress: String,
    val signalStrength: Int,
    val channel: Int? = null,
)

@Serializable
data class GeolocateResponseDto(
    val location: LatLngDto,
    val accuracy: Double? = null,
)

@Serializable
data class LatLngDto(
    val lat: Double,
    val lng: Double,
)
