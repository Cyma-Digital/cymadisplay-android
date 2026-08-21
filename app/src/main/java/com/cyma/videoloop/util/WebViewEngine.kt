package com.cyma.videoloop.util

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import androidx.webkit.WebViewCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which Chromium the box actually runs. Diagnosis only — nothing branches on it.
 *
 * The signage boxes lie about their OS version (one reports
 * `ro.build.version.release=16.0` on API 24), so the Android version tells you nothing
 * about the CSS the WebView will accept. The WebView package version is the real floor —
 * see CLAUDE.md § "Legacy WebView CSS support floor". Logging it under the template tag
 * means one `logcat -s TplWebView` capture carries both the engine version and the
 * engine's own CSS complaints, which is what you need when a template renders wrong on
 * one box and right on another.
 */
object WebViewEngine {

    private const val TAG = "TplWebView"
    private val logged = AtomicBoolean(false)

    /**
     * Chromium major version, or null when it can't be read.
     *
     * [WebViewCompat.getCurrentWebViewPackage] returns null on some OEM API 21-25 builds,
     * so fall back to the user-agent string, which is always there and needs no WebView
     * instance. A null means "assume the floor", never "assume modern".
     */
    fun majorVersion(context: Context): Int? {
        WebViewCompat.getCurrentWebViewPackage(context)?.versionName
            ?.substringBefore('.')?.toIntOrNull()?.let { return it }
        return runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
            ?.let { Regex("""Chrome/(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }

    /** Logs the engine once per process. */
    fun logOnce(context: Context) {
        if (!logged.compareAndSet(false, true)) return
        val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        Log.i(
            TAG,
            "WebView engine: chromium=${majorVersion(context) ?: "unknown"} " +
                "package=${pkg?.packageName ?: "unknown"} version=${pkg?.versionName ?: "unknown"}",
        )
    }
}
