package com.cyma.videoloop

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cyma.videoloop.data.identity.DeviceIdentityRepository
import com.cyma.videoloop.data.schedule.ScheduleRepository
import com.cyma.videoloop.domain.model.Orientation
import com.cyma.videoloop.ui.pairing.PairingScreen
import com.cyma.videoloop.ui.playback.PlaybackScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var identity: DeviceIdentityRepository
    @Inject lateinit var scheduleRepository: ScheduleRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sane default before the schedule flow emits — avoids a brief portrait
        // flash on cold start when the device is held vertically.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        try { enableEdgeToEdge() } catch (_: Exception) {}
        // Kiosk-style immersive mode: hide status & navigation bars. They peek
        // briefly on a swipe from the edge and auto-hide.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // TV/signage devices stay locked to landscape and silently ignore
        // setRequestedOrientation — we rotate the Compose tree in software
        // instead for vertical schedules.
        val isTv = isTelevision()
        setContent {
            MaterialTheme {
                val orientation by scheduleRepository.schedule()
                    .map { it.orientation }
                    .distinctUntilChanged()
                    .collectAsState(initial = Orientation.HORIZONTAL)

                LaunchedEffect(orientation) {
                    android.util.Log.i("MainActivity", "orientation=$orientation isTv=$isTv")
                    if (!isTv) {
                        this@MainActivity.requestedOrientation = orientation.toActivityInfo()
                    }
                }

                RotatedScreen(degrees = if (isTv) orientation.softwareRotation() else 0f) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        var startDestination by remember { mutableStateOf<String?>(null) }
                        LaunchedEffect(Unit) {
                            startDestination = resolveStartDestination()
                        }
                        // Re-route to pairing whenever the backend rejects us
                        // (ScheduleRepository.syncFromNetwork flips this on 4xx).
                        LaunchedEffect(Unit) {
                            identity.pairedFlow().collect { paired ->
                                if (!paired && startDestination != null) {
                                    val current = navController.currentBackStackEntry?.destination?.route
                                    if (current == "playback") {
                                        navController.navigate("pairing") {
                                            popUpTo("playback") { inclusive = true }
                                        }
                                    }
                                }
                            }
                        }
                        when (startDestination) {
                            null -> SplashScreen()
                            else -> NavHost(navController = navController, startDestination = startDestination!!) {
                                composable("pairing") {
                                    PairingScreen(onPaired = {
                                        navController.navigate("playback") {
                                            popUpTo("pairing") { inclusive = true }
                                        }
                                    })
                                }
                                composable("playback") { PlaybackScreen() }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Decide which screen to open by asking the schedule endpoint whether this
     * device is paired. This avoids the trap of trusting a missing local token
     * — even after a fresh install, a device that was paired in the backend
     * jumps straight to playback.
     *
     *  - 2xx from the schedule endpoint → paired → playback
     *  - 4xx                            → not paired → pairing
     *  - network unreachable            → fall back to last-known cached state
     */
    private suspend fun resolveStartDestination(): String {
        val serverSays = scheduleRepository.isPaired()
        when (serverSays) {
            true -> identity.setPaired(true)
            false -> identity.setPaired(false)
            null -> Unit  // unreachable — keep cached state
        }
        return when (serverSays) {
            true -> "playback"
            false -> "pairing"
            null -> if (identity.isLocallyPaired()) "playback" else "pairing"
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Rotates [content] by [degrees] around the screen center, swapping width/height
 * for 90°/270° so portrait content is laid out at portrait dimensions before
 * being rotated into the landscape viewport. Used on TV/signage hardware that
 * won't physically rotate.
 */
@Composable
private fun RotatedScreen(degrees: Float, content: @Composable () -> Unit) {
    if (degrees == 0f) {
        content()
        return
    }
    val swap = degrees == 90f || degrees == 270f
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { rotationZ = degrees },
        content = { Box(Modifier) { content() } },
    ) { measurables, constraints ->
        val outerW = constraints.maxWidth
        val outerH = constraints.maxHeight
        val childW = if (swap) outerH else outerW
        val childH = if (swap) outerW else outerH
        val placeable = measurables[0].measure(Constraints.fixed(childW, childH))
        layout(outerW, outerH) {
            placeable.place((outerW - childW) / 2, (outerH - childH) / 2)
        }
    }
}

private fun Context.isTelevision(): Boolean {
    val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

private fun Orientation.toActivityInfo(): Int = when (this) {
    Orientation.HORIZONTAL -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    Orientation.VERTICAL -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    Orientation.HORIZONTAL_INVERTED -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    Orientation.VERTICAL_INVERTED -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
}

private fun Orientation.softwareRotation(): Float = when (this) {
    Orientation.HORIZONTAL -> 0f
    Orientation.VERTICAL -> 90f
    Orientation.HORIZONTAL_INVERTED -> 180f
    Orientation.VERTICAL_INVERTED -> 270f
}
