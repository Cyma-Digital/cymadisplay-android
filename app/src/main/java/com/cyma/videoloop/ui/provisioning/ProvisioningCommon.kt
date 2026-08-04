package com.cyma.videoloop.ui.provisioning

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyma.videoloop.util.generateQrBitmap

/**
 * Building blocks shared by the setup-overlay cards ([WifiJoinCard],
 * [PortalAccessCard]) and the status states in `WifiSetupOverlay`.
 *
 * Sizes here are deliberately compact: two cards are stacked vertically in a corner
 * of a 720p/1080p landscape panel, on top of live playback, so they must not grow
 * into the video.
 */

/** Edge of a QR tile. Small enough for two stacked cards, big enough to scan at arm's length. */
internal val QrTileSize = 120.dp

/** Width of the text column beside a QR tile. */
internal val CardTextWidth = 280.dp

@Composable
internal fun ProvisioningCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(16.dp),
    ) { content() }
}

@Composable
internal fun QrTile(payload: String, contentDescription: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val qr = remember(payload) { generateQrBitmap(payload, 400) }
        Box(
            modifier = Modifier.size(QrTileSize).clip(RoundedCornerShape(8.dp)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(
                    bitmap = qr,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(caption, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Prominent SSID/password callout. Kept alongside the join QR as the fallback for
 * phones that can't scan a `WIFI:` payload (pre-Android-10 cameras) and for a manual
 * join when the scan doesn't take.
 */
@Composable
internal fun NetworkBanner(ssid: String, passphrase: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(10.dp),
    ) {
        Text(
            ssid,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            if (passphrase != null) "Senha: $passphrase" else "Rede aberta (sem senha)",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** "Passo N" label above a card's body, so the vertical stack reads as a sequence. */
@Composable
internal fun StepLabel(step: Int, title: String) {
    Text(
        "Passo $step de 2 — $title",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun StatusRow(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
