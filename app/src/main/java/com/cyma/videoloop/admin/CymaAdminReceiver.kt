package com.cyma.videoloop.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * Device-admin receiver for the Cyma signage box.
 *
 * This is provisioned once per box at the warehouse:
 *
 * ```
 * adb shell dpm set-device-owner com.cyma.videoloop/.admin.CymaAdminReceiver
 * ```
 *
 * (The box must have no accounts added for the command to succeed.)
 *
 * Being device owner is what lets the app silently self-grant the location
 * permission and program the WiFi configuration during the [WiFi setup flow]
 * without any on-device prompts — see [DeviceOwnerManager]. The receiver itself
 * needs no behaviour; its existence + the `dpm` command are what matter.
 */
class CymaAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context.applicationContext, CymaAdminReceiver::class.java)
    }
}
