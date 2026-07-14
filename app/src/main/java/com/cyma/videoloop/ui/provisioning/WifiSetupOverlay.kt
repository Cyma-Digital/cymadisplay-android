package com.cyma.videoloop.ui.provisioning

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyma.videoloop.util.generateQrBitmap
import com.cyma.videoloop.wifi.ProvisioningState

/**
 * Non-blocking overlay for background WiFi provisioning. Rendered on top of the
 * always-running content (playback/pairing) — it occupies only a corner card, so
 * video keeps playing behind it. Renders nothing when [state] is
 * [ProvisioningState.Idle].
 */
@Composable
fun WifiSetupOverlay(
    state: ProvisioningState,
    onPermissionsGranted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is ProvisioningState.Idle) return

    if (state is ProvisioningState.NeedsPermission) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { onPermissionsGranted() }
        LaunchedEffect(state) { launcher.launch(state.permissions.toTypedArray()) }
        return
    }

    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomStart) {
        Card {
            when (state) {
                is ProvisioningState.AwaitingPhone -> AwaitingPhoneContent(state)
                is ProvisioningState.Connecting -> StatusRow("Connecting to ${state.ssid}…")
                ProvisioningState.Preparing -> StatusRow("Starting WiFi setup…")
                is ProvisioningState.Failed ->
                    Text(state.message, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                else -> Unit
            }
        }
    }
}

@Composable
private fun AwaitingPhoneContent(state: ProvisioningState.AwaitingPhone) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val qr = remember(state.qrPayload) { generateQrBitmap(state.qrPayload, 400) }
        Box(
            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(8.dp)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(
                    bitmap = qr,
                    contentDescription = "WiFi setup QR code",
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Column(modifier = Modifier.width(280.dp)) {
            Text("This display needs WiFi", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            if (state.retryAfterFailure) {
                Text(
                    "Couldn't connect — wrong password? Scan again to retry.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                "Scan with your phone, then pick your WiFi and enter its password.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Or join: ${state.ssid}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            state.portalUrl?.let {
                Text(
                    "Page not opening? Browse to $it",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusRow(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(20.dp),
    ) { content() }
}
