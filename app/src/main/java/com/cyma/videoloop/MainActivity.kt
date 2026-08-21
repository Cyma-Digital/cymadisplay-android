package com.cyma.videoloop

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.cyma.videoloop.ui.provisioning.WifiSetupOverlay
import com.cyma.videoloop.ui.status.NetworkStatusIndicator
import com.cyma.videoloop.ui.theme.CymaTheme
import com.cyma.videoloop.wifi.ConnectivityMonitor
import com.cyma.videoloop.wifi.ProvisioningState
import com.cyma.videoloop.wifi.WifiProvisioningCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var identity: DeviceIdentityRepository
    @Inject lateinit var scheduleRepository: ScheduleRepository
    @Inject lateinit var provisioningCoordinator: WifiProvisioningCoordinator
    @Inject lateinit var connectivityMonitor: ConnectivityMonitor

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result ignored — the appop is re-checked on next launch */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // "Draw over other apps" sets the SYSTEM_ALERT_WINDOW appop to
        // MODE_ALLOWED, which is what grants the background-activity-start
        // exemption the boot receiver needs to relaunch after a reboot. Prompt for
        // it on launch until it's explicitly allowed.
        ensureOverlayPermission()
        // Watch connectivity in the background and run WiFi setup (hotspot +
        // captive portal) without ever interrupting playback. App-scoped, so it
        // survives activity recreation.
        provisioningCoordinator.ensureRunning()
        // Lock the physical panel to landscape for good. Signage boxes (e.g.
        // Amlogic TX3) silently ignore setRequestedOrientation anyway, so we never
        // rely on hardware rotation: every orientation from the schedule is applied
        // in software by RotatedScreen below. Locking here just pins the panel to a
        // known base so the software rotation is deterministic and there's no
        // double-rotation on ROMs that *would* honour the request.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        try { enableEdgeToEdge() } catch (_: Exception) {}
        // Kiosk-style immersive mode: hide status & navigation bars. They peek
        // briefly on a swipe from the edge and auto-hide.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            CymaTheme {
                val orientation by scheduleRepository.schedule()
                    .map { it.orientation }
                    .distinctUntilChanged()
                    .collectAsState(initial = Orientation.HORIZONTAL)

                LaunchedEffect(orientation) {
                    android.util.Log.i("MainActivity", "orientation=$orientation")
                }

                // Always rotate in software off the schedule's orientation field.
                // The panel is pinned landscape in onCreate, so this is the single
                // source of truth for how content is oriented, regardless of ROM.
                RotatedScreen(degrees = orientation.softwareRotation()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        val navController = rememberNavController()
                        var startDestination by remember { mutableStateOf<String?>(null) }
                        val provisioningState by provisioningCoordinator.state.collectAsState()
                        // Plain collectAsState (not lifecycle-aware) so the 5 s
                        // heartbeat never pauses; the remembered snapshot shows the
                        // true state on the first frame (no red flash on cold start).
                        val netStatus by connectivityMonitor.networkStatusFlow()
                            .collectAsState(initial = remember { connectivityMonitor.currentStatus() })
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
                        // Content is the always-on base layer; WiFi setup renders
                        // as a corner overlay on top of it and never replaces it.
                        Box(modifier = Modifier.fillMaxSize()) {
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
                            WifiSetupOverlay(
                                state = provisioningState,
                                onPermissionsGranted = { provisioningCoordinator.onPermissionsGranted() },
                            )
                            // Drawn last = always on top of content and the WiFi
                            // overlay. Bottom-right (overlay sits bottom-left).
                            NetworkStatusIndicator(status = netStatus)
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
    private fun ensureOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isOverlayOpAllowed()) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { overlayPermissionLauncher.launch(intent) }
    }

    /**
     * True only when the SYSTEM_ALERT_WINDOW appop is *explicitly* MODE_ALLOWED —
     * the state the ROM's background-activity-start exemption actually requires.
     *
     * We must read the RAW op mode: Settings.canDrawOverlays() and the regular
     * checkOp/noteOp variants resolve an unset op (MODE_DEFAULT) to "allowed" when
     * the manifest declares the permission, so they report the permission as
     * granted even though the user never enabled it and the BAL exemption is not
     * active. unsafeCheckOpRawNoThrow returns the stored mode without that
     * resolution, so MODE_DEFAULT stays distinguishable from MODE_ALLOWED.
     */
    private fun isOverlayOpAllowed(): Boolean {
        val aom = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            aom.unsafeCheckOpRawNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), packageName
            )
        } else {
            // No raw-mode API before API 29; fall back to the resolved check.
            return Settings.canDrawOverlays(this)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private suspend fun resolveStartDestination(): String {
        // Connectivity no longer gates the start destination — WiFi setup runs as a
        // background overlay (see WifiProvisioningCoordinator), so content always
        // shows. Offline just means the cached schedule keeps playing.
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

private fun Orientation.softwareRotation(): Float = when (this) {
    Orientation.HORIZONTAL -> 0f
    Orientation.VERTICAL -> 90f
    Orientation.HORIZONTAL_INVERTED -> 180f
    Orientation.VERTICAL_INVERTED -> 270f
}
