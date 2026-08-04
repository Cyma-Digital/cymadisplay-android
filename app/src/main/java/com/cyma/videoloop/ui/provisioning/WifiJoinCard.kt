package com.cyma.videoloop.ui.provisioning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyma.videoloop.util.wifiQrPayload

/**
 * Step 1 of setup: get the installer's phone onto the box's own setup hotspot.
 *
 * Two ways to join, both on this card: scan the `WIFI:` QR (Android 10+ camera /
 * Google Lens, iOS 11+ Camera join in one tap — no typing), or read the SSID/password
 * off the banner and join by hand. The banner is not decoration: it's the only path on
 * phones whose camera doesn't parse `WIFI:` payloads, and the credentials are
 * OS-random on the LocalOnlyHotspot tier (e.g. `AndroidShare_6325`), so it's what
 * saves the installer from transcribing them.
 *
 * This QR *is* scannable before the phone has any connection to the box — unlike the
 * step-2 portal URL, which only resolves once the phone is on this hotspot.
 *
 * [retryReason] is the failure text from the previous attempt (null on the first
 * arming) — the coordinator distinguishes "never associated" (usually a typo'd
 * password) from "joined, but that network has no internet".
 */
@Composable
fun WifiJoinCard(ssid: String, passphrase: String?, retryReason: String?) {
    ProvisioningCard {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.width(CardTextWidth)) {
                Text("Este display precisa de WiFi", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(6.dp))
                retryReason?.let { reason ->
                    Text(
                        reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(6.dp))
                }
                StepLabel(1, "conecte-se a esta rede")
                Spacer(Modifier.size(6.dp))
                Text(
                    "Escaneie o QR ao lado para entrar na rede, ou conecte-se manualmente:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(6.dp))
                NetworkBanner(ssid = ssid, passphrase = passphrase)
            }
            QrTile(
                payload = wifiQrPayload(ssid, passphrase),
                contentDescription = "QR code para conectar à rede WiFi $ssid",
                caption = "Entrar na rede",
            )
        }
    }
}
