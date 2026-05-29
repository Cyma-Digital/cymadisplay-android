package com.cyma.videoloop.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cyma.videoloop.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Launch synchronously inside onReceive: the BOOT_COMPLETED broadcast
        // grants a short background-activity-start window that expires within a
        // few seconds. Deferring the startActivity (Handler.postDelayed) lets it
        // lapse and the ROM blocks the launch. Start now, while the grant holds.
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
