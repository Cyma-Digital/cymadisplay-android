package com.cyma.videoloop.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.cyma.videoloop.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val launch = Intent(appContext, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
                appContext.startActivity(launch)
            } finally {
                pendingResult.finish()
            }
        }, LAUNCH_DELAY_MS)
    }

    companion object {
        private const val LAUNCH_DELAY_MS = 5_000L
    }
}
