package com.cyma.videoloop.wifi

import android.util.Log
import fi.iki.elonen.NanoHTTPD

/**
 * Tiny HTTP server that renders the WiFi-setup form to the installer's phone once
 * it has joined the setup hotspot.
 *
 * Two responsibilities:
 *  1. Serve `GET /` — a mobile form listing the pre-scanned networks (plus a
 *     manual-SSID field) and a password input.
 *  2. Answer the OS captive-portal *probe* URLs (`/generate_204`, Apple/Windows
 *     equivalents, …) with a redirect to `/`, which nudges the phone to pop the
 *     "Sign in to network" sheet automatically. Auto-open is best-effort — the
 *     TV screen also shows the URL to type manually — because we don't run a DNS
 *     interceptor on the hotspot.
 *
 * A submitted form calls [onSubmit] on a background thread; the coordinator then
 * tears the hotspot down and joins the chosen network.
 */
class CaptivePortalServer(
    port: Int,
    private val networksProvider: () -> List<ScannedNetwork>,
    private val onRescanRequested: () -> Unit,
    private val onSubmit: (ssid: String, password: String) -> Unit,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        Log.i(TAG, "${session.method} $uri")

        if (session.method == Method.POST && uri == "/connect") {
            return handleConnect(session)
        }
        if (uri == "/rescan") {
            // Kick off an async rescan; the page reloads the form once it's done.
            onRescanRequested()
            return newFixedLengthResponse(Response.Status.OK, MIME_HTML, renderRescanning())
        }
        if (uri in PROBE_PATHS) {
            // 302 back to the portal so the phone flags the network as captive.
            return newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "").apply {
                addHeader("Location", "/")
            }
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, renderForm(error = null))
    }

    private fun handleConnect(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        runCatching { session.parseBody(body) }
        val params = session.parameters
        val picked = params["ssid"]?.firstOrNull().orEmpty().trim()
        val manual = params["ssid_manual"]?.firstOrNull().orEmpty().trim()
        val ssid = manual.ifEmpty { picked }
        val password = params["password"]?.firstOrNull().orEmpty()

        if (ssid.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.OK, MIME_HTML,
                renderForm(error = "Please choose or type a network name."),
            )
        }

        onSubmit(ssid, password)
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, renderConnecting(ssid))
    }

    private fun renderForm(error: String?): String {
        val options = networksProvider().joinToString("\n") { net ->
            val lock = if (net.secured) " 🔒" else ""
            "<option value=\"${net.ssid.htmlEscape()}\">${net.ssid.htmlEscape()}$lock</option>"
        }
        val errorHtml = error?.let { "<p class=\"err\">${it.htmlEscape()}</p>" }.orEmpty()
        return """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta charset="utf-8">
            <title>Connect this display to WiFi</title>
            <style>
              body{font-family:-apple-system,system-ui,sans-serif;margin:0;background:#0f1115;color:#e8eaed}
              .card{max-width:420px;margin:0 auto;padding:24px}
              h1{font-size:20px;margin:8px 0 16px}
              label{display:block;margin:16px 0 6px;font-size:13px;color:#9aa0a6}
              select,input{width:100%;box-sizing:border-box;padding:12px;font-size:16px;
                border:1px solid #3c4043;border-radius:8px;background:#1e2126;color:#e8eaed}
              button{margin-top:24px;width:100%;padding:14px;font-size:16px;font-weight:600;
                border:0;border-radius:8px;background:#1a73e8;color:#fff}
              .err{color:#f28b82;font-size:14px}
              .hint{color:#9aa0a6;font-size:12px;margin-top:4px}
              .row{display:flex;align-items:baseline;justify-content:space-between}
              .rescan{color:#8ab4f8;font-size:13px;text-decoration:none}
            </style></head><body><div class="card">
            <h1>Connect this display to WiFi</h1>
            $errorHtml
            <form method="POST" action="/connect">
              <div class="row"><label for="ssid">Network</label><a class="rescan" href="/rescan">↻ Rescan</a></div>
              <select id="ssid" name="ssid">$options</select>
              <label for="ssid_manual">Or type a network name</label>
              <input id="ssid_manual" name="ssid_manual" placeholder="(optional — overrides the list)" autocomplete="off">
              <label for="password">Password</label>
              <input id="password" name="password" type="password" placeholder="Leave blank for open networks">
              <button type="submit">Connect</button>
              <p class="hint">The display will switch to this network and this setup page will disappear.</p>
            </form>
            </div></body></html>
        """.trimIndent()
    }

    private fun renderRescanning(): String = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta charset="utf-8"><meta http-equiv="refresh" content="6;url=/">
        <title>Rescanning…</title>
        <style>body{font-family:-apple-system,system-ui,sans-serif;background:#0f1115;color:#e8eaed;
          text-align:center;padding:48px 24px}h1{font-size:20px}p{color:#9aa0a6}
          a{color:#8ab4f8}</style>
        </head><body>
        <h1>Rescanning nearby networks…</h1>
        <p>This page will refresh in a few seconds. If it doesn't, <a href="/">tap here</a>.</p>
        </body></html>
    """.trimIndent()

    private fun renderConnecting(ssid: String): String = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta charset="utf-8"><title>Connecting…</title>
        <style>body{font-family:-apple-system,system-ui,sans-serif;background:#0f1115;color:#e8eaed;
          text-align:center;padding:48px 24px}h1{font-size:20px}p{color:#9aa0a6}</style>
        </head><body>
        <h1>Connecting to ${ssid.htmlEscape()}…</h1>
        <p>The display is switching networks. This setup hotspot will now disappear —
        you can close this page. Check the display screen to confirm it's online.</p>
        </body></html>
    """.trimIndent()

    fun startSafely(): Boolean = runCatching {
        start(SOCKET_READ_TIMEOUT, false)
        true
    }.getOrElse {
        Log.e(TAG, "server start failed on port $listeningPort", it)
        false
    }

    companion object {
        private const val TAG = "CaptivePortalServer"

        /** Preferred port (best captive auto-open); [FALLBACK_PORT] if it can't bind. */
        const val PREFERRED_PORT = 80
        const val FALLBACK_PORT = 8080

        private val PROBE_PATHS = setOf(
            "/generate_204", "/gen_204",           // Android
            "/hotspot-detect.html", "/library/test/success.html", // Apple
            "/ncsi.txt", "/connecttest.txt", "/redirect", // Windows
            "/canonical.html", "/success.txt",     // Firefox / misc
        )

        /**
         * Binds [PREFERRED_PORT] if possible, else [FALLBACK_PORT]. Returns the
         * started server, or null if neither port could be bound.
         */
        fun startOnAvailablePort(
            networksProvider: () -> List<ScannedNetwork>,
            onRescanRequested: () -> Unit,
            onSubmit: (ssid: String, password: String) -> Unit,
        ): CaptivePortalServer? {
            for (port in intArrayOf(PREFERRED_PORT, FALLBACK_PORT)) {
                val server = CaptivePortalServer(port, networksProvider, onRescanRequested, onSubmit)
                if (server.startSafely()) return server
            }
            return null
        }
    }
}

private fun String.htmlEscape(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
