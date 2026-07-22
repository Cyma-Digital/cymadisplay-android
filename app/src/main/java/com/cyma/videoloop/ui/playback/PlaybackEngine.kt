package com.cyma.videoloop.ui.playback

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import coil.compose.AsyncImage
import com.cyma.videoloop.BuildConfig
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection
import kotlin.time.Duration.Companion.seconds

private const val TAG = "PlaybackEngine"

@Composable
fun PlaybackEngine(
    items: List<ResolvedItem>,
    modifier: Modifier = Modifier,
    onPlaybackError: (ResolvedItem) -> Unit = {},
) {
    if (items.isEmpty()) return

    var currentIndex by remember(items) { mutableIntStateOf(0) }
    val current = items[currentIndex % items.size]
    val isOnly = items.size == 1
    val advance: () -> Unit = {
        if (!isOnly) currentIndex = (currentIndex + 1) % items.size
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val item = current) {
            is ResolvedItem.Video -> VideoSlot(item, isOnly = isOnly, onEnded = advance, onPlaybackError = onPlaybackError)
            is ResolvedItem.Image -> ImageSlot(item, isOnly = isOnly, onElapsed = advance)
            is ResolvedItem.Template -> key(item.templateId) { TemplateSlot(item, isOnly = isOnly, onElapsed = advance) }
        }
    }
}

@Composable
private fun VideoSlot(
    item: ResolvedItem.Video,
    isOnly: Boolean,
    onEnded: () -> Unit,
    onPlaybackError: (ResolvedItem) -> Unit,
) {
    val context = LocalContext.current
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnError by rememberUpdatedState(onPlaybackError)
    val currentIsOnly by rememberUpdatedState(isOnly)

    val exoPlayer = remember {
        Log.d(TAG, "Creating ExoPlayer")
        ExoPlayer.Builder(context)
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .build()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    Log.d(TAG, "STATE_ENDED; isOnly=$currentIsOnly")
                    if (currentIsOnly) {
                        exoPlayer.seekToDefaultPosition()
                        exoPlayer.play()
                    } else {
                        currentOnEnded()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error: ${error.message}", error)
                currentOnError(item)
                if (currentIsOnly) {
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    exoPlayer.play()
                } else {
                    currentOnEnded()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(item.uri) {
        exoPlayer.repeatMode = if (isOnly) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exoPlayer.stop()
        exoPlayer.setMediaItem(MediaItem.fromUri(item.uri))
        exoPlayer.seekToDefaultPosition()
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        onDispose {
            exoPlayer.stop()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "Releasing ExoPlayer")
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        exoPlayer.setVideoSurface(Surface(surface))
                    }
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        exoPlayer.clearVideoSurface()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun ImageSlot(
    item: ResolvedItem.Image,
    isOnly: Boolean,
    onElapsed: () -> Unit,
) {
    if (!isOnly) {
        LaunchedEffect(item.id) {
            delay(maxOf(item.durationSec, 1).seconds)
            onElapsed()
        }
    }

    AsyncImage(
        model = item.uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

private const val TEMPLATE_ASSET_DOMAIN = "appassets.cyma.local"
private const val TPL_TAG = "TplWebView"

// Reveal is now gated on actual paint completion (postVisualStateCallback,
// wired in buildTemplateWebView), not a blind timer. `onPageFinished`
// (resource-fetch-complete) fires near-instantly for these locally-intercepted
// assets — well before Chromium has painted — so it alone is too early; we use
// it only as the trigger to *request* the visual-state callback, which fires
// once the DOM is actually drawable. This constant is the safety-net timeout:
// if the callback never arrives (stuck load, or API < 23 with no callback),
// reveal anyway rather than leaving the WebView INVISIBLE forever.
private const val FOUC_GATE_HOLD_MS = 2500L

// After the visual-state callback reports the DOM drawable, Chromium may still
// be rasterizing heavy backgrounds (the dense radial-gradient / bg image) tile
// by tile. onComplete fires at DOM-ready, before those tiles finish. Because
// the WebView is kept VISIBLE (behind a cover) it actually rasterizes during
// this window, so holding a short settle after onComplete lets the raster
// finish before we drop the cover — revealing a fully-painted page.
private const val REVEAL_SETTLE_MS = 600L

/**
 * Builds the template view: a [FrameLayout] holding the [WebView] with an opaque
 * black cover [View] stacked on top. The WebView is kept VISIBLE the whole time
 * so Chromium actually rasterizes it — an INVISIBLE WebView defers rasterization
 * entirely, so its heavy background would tile in visibly *after* being shown
 * (confirmed on-device). The cover hides that raster until it completes; once the
 * paint settles ([onReady]) [TemplateSlot] drops the cover to reveal a
 * fully-painted page. Compose can't occlude this WebView on this hardware (it
 * composites outside Compose's draw/z-order pipeline), but a native sibling in
 * the same FrameLayout z-orders normally.
 *
 * Child 0 is the WebView, child 1 is the cover.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun buildTemplateView(
    context: Context,
    item: ResolvedItem.Template,
    onReady: () -> Unit,
): FrameLayout {
    val templateRoot = item.indexFile.parentFile!!
    val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(TEMPLATE_ASSET_DOMAIN)
        .addPathHandler("/", InternalStoragePathHandler(context, templateRoot))
        .build()

    val webView = WebView(context).apply {
        // Force a hardware layer. Templates paint a dense repeating
        // radial-gradient background (background-size: 1vh 1vh → thousands of
        // cells); software rasterization fills it in visibly tile-by-tile,
        // top-left→bottom-right, on the weak signage GPU. A hardware layer is
        // GPU-rasterized (cheap) and — unlike the default compositing path on
        // this hardware — is composited by the render thread *with* ancestor
        // transforms, so the software screen-rotation (RotatedScreen's
        // rotationZ) actually applies to the WebView instead of leaving it
        // offset/clipped in inverted orientations.
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // CSS in templates uses `100vh` / `5vh` heavily. Without explicit
        // MATCH_PARENT layout params, WebView's default WRAP_CONTENT
        // measures content first → content needs viewport height → WebView
        // is still 0 tall → content collapses to 0 → WebView wraps to 0
        // and we render a white screen with `font-size: 0`.
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // Templates set their own backgrounds via CSS, but a 16:9 template
        // on a slightly-off-16:9 viewport letterboxes — keep the bars dark
        // so they read as intentional rather than as a render glitch.
        setBackgroundColor(Color.BLACK)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        setNetworkAvailable(false)
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val path = request.url.path ?: "/"
                if (path == "/favicon.ico") {
                    return WebResourceResponse(
                        "image/png", "utf-8",
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }
                val response = assetLoader.shouldInterceptRequest(request.url)
                if (response != null) {
                    if (response.mimeType != null) {
                        Log.d(TAG, "Template ${item.templateId} served ${request.url}")
                        return response
                    }
                }
                val urlStr = request.url.toString()
                val assetPath = urlStr.substringAfter("cymadisplay.assets/", "").substringBefore('?')
                if (assetPath.isNotEmpty()) {
                    val localFile = File(templateRoot, assetPath)
                    if (localFile.exists() && localFile.isFile) {
                        val mime = URLConnection.guessContentTypeFromName(assetPath)
                        Log.d(TAG, "Template ${item.templateId} intercepted S3 → $assetPath")
                        return WebResourceResponse(
                            mime, "utf-8", localFile.inputStream(),
                        )
                    }
                }
                if (response != null) {
                    Log.w(TAG, "Template ${item.templateId} missing ${request.url}")
                    return response
                }
                Log.w(TAG, "Template ${item.templateId} blocking ${request.url}")
                return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    ByteArrayInputStream(ByteArray(0)),
                )
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = true

            override fun onPageFinished(view: WebView, url: String) {
                // onPageFinished fires when resources are fetched, well before
                // Chromium has painted. Use it only to request a visual-state
                // callback, which fires once the current DOM state is drawable.
                Log.d(TPL_TAG, "onPageFinished ${item.templateId}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    view.postVisualStateCallback(
                        0L,
                        object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                // DOM is drawable, but heavy-background tiles may
                                // still be rasterizing — settle before revealing.
                                Log.d(TPL_TAG, "visualState onComplete ${item.templateId}, settle ${REVEAL_SETTLE_MS}ms")
                                view.postDelayed({
                                    Log.d(TPL_TAG, "revealing ${item.templateId} (paint)")
                                    onReady()
                                }, REVEAL_SETTLE_MS)
                            }
                        },
                    )
                } else {
                    // No postVisualStateCallback before API 23; the timeout
                    // fallback in TemplateSlot handles the reveal instead.
                    onReady()
                }
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val line = "${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}"
                when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e(TPL_TAG, line)
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TPL_TAG, line)
                    else -> Log.d(TPL_TAG, line)
                }
                return true
            }
        }
        loadUrl("https://$TEMPLATE_ASSET_DOMAIN/index.html")
    }

    // Opaque cover stacked on top of the (always-visible) WebView; dropped by
    // TemplateSlot once the template has painted. Rotation-agnostic (plain black).
    val cover = View(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(Color.BLACK)
    }

    return FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(webView)
        addView(cover)
    }
}

@Composable
private fun TemplateSlot(
    item: ResolvedItem.Template,
    isOnly: Boolean,
    onElapsed: () -> Unit,
) {
    val context = LocalContext.current

    if (!isOnly) {
        LaunchedEffect(item.id) {
            delay(maxOf(item.durationSec, 1).seconds)
            onElapsed()
        }
    }

    var isReady by remember(item.indexFile) { mutableStateOf(false) }
    val container = remember(item.indexFile) {
        // Reveal (drop the cover) once the template has actually painted, so the
        // tiled raster of the heavy background is never shown filling in.
        buildTemplateView(context, item, onReady = { isReady = true })
    }

    // Safety-net timeout: if the paint callback never arrives (stuck load, or
    // API < 23), drop the cover anyway so the template isn't hidden forever.
    // Whichever fires first wins; setting isReady twice is idempotent.
    LaunchedEffect(item.indexFile) {
        delay(FOUC_GATE_HOLD_MS)
        isReady = true
    }

    DisposableEffect(container) {
        onDispose {
            Log.d(TAG, "Releasing WebView for template ${item.templateId}")
            (container.getChildAt(0) as? WebView)?.let {
                it.stopLoading()
                it.destroy()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(ComposeColor.Black)) {
        AndroidView(
            factory = { container },
            // Drop the native cover (child 1) once painted. The WebView (child 0)
            // stays VISIBLE throughout so it keeps rasterizing under the cover.
            update = { fl -> fl.getChildAt(1).visibility = if (isReady) View.GONE else View.VISIBLE },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
