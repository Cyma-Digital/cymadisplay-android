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

/**
 * Step 2 of setup: open the captive-portal form that asks which network the box should
 * join. The QR encodes the full portal URL so nobody has to type an IP and `:8080` —
 * it's the deliberate substitute for the captive-portal auto-popup, which can't work
 * without root (no DNS interceptor on the hotspot; see `CaptivePortalServer`).
 *
 * Only reachable once the phone is on the hotspot from step 1, hence the order.
 * [portalUrl] is null until the AP interface has an IPv4 — say so rather than showing a
 * QR that leads nowhere.
 */
@Composable
fun PortalAccessCard(portalUrl: String?) {
    ProvisioningCard {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.width(CardTextWidth)) {
                StepLabel(2, "abra a página de configuração")
                Spacer(Modifier.size(6.dp))
                Text(
                    if (portalUrl != null) {
                        "Depois de conectar, escaneie o QR ao lado (ou acesse $portalUrl) " +
                            "e escolha a rede WiFi do local."
                    } else {
                        "Depois de conectar, aguarde o endereço da página aparecer aqui."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            portalUrl?.let { url ->
                QrTile(
                    payload = url,
                    contentDescription = "QR code da página de configuração",
                    caption = "Abrir configuração",
                )
            }
        }
    }
}
