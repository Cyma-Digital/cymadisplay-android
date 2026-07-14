package com.cyma.videoloop.admin

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [DevicePolicyManager] for the bits the WiFi-provisioning
 * flow relies on.
 *
 * The box is expected to be provisioned as **device owner** at the warehouse
 * (`adb shell dpm set-device-owner com.cyma.videoloop/.admin.CymaAdminReceiver`).
 * Device-owner status unlocks two things the flow needs on a headless,
 * remote-only box:
 *
 *  1. **Silent runtime-permission grants** — the location permission that
 *     [android.net.wifi.WifiManager.startScan] and `startLocalOnlyHotspot` require
 *     can be granted with no user prompt.
 *  2. **The WiFi-config carve-out** — device owners (unlike ordinary apps on
 *     Android 10+) may still add and enable WiFi networks, so the box can join
 *     the network the installer picks — see [com.cyma.videoloop.wifi.WifiJoiner].
 *
 * Everything degrades gracefully when the app is *not* device owner: the grants
 * are skipped (the caller falls back to a runtime permission request) and the
 * join uses the best-effort suggestion API.
 */
@Singleton
class DeviceOwnerManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** True when this app is the device owner and the privileged path is available. */
    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    /**
     * The runtime permissions needed to scan for and host WiFi networks. On a
     * device-owner box these are granted silently; otherwise the caller must ask.
     */
    val wifiRuntimePermissions: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    fun hasWifiRuntimePermissions(): Boolean = wifiRuntimePermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Silently grant [wifiRuntimePermissions] via device-owner privilege. No-op
     * (returns false) when not device owner. Safe to call repeatedly.
     */
    fun grantWifiPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (!isDeviceOwner()) return false
        val admin = CymaAdminReceiver.component(context)
        var allGranted = true
        for (permission in wifiRuntimePermissions) {
            val ok = runCatching {
                dpm.setPermissionGrantState(
                    admin,
                    context.packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }.getOrElse {
                Log.w(TAG, "setPermissionGrantState failed for $permission", it)
                false
            }
            allGranted = allGranted && ok
        }
        return allGranted
    }

    private companion object {
        private const val TAG = "DeviceOwnerManager"
    }
}
