package com.cyma.videoloop.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cyma.videoloop.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Launch synchronously. The background-activity-start succeeds only when
        // the app holds the SYSTEM_ALERT_WINDOW appop in MODE_ALLOWED, which this
        // ROM requires (a foreground service does NOT grant the exemption here).
        // MainActivity prompts the user to enable it on first launch.
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        context.startActivity(launch)
    }
}
