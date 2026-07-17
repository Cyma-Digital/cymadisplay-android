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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
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
                is ProvisioningState.Connecting -> StatusRow("Conectando a ${state.ssid}…")
                ProvisioningState.Preparing -> StatusRow("Iniciando configuração de WiFi…")
                is ProvisioningState.Failed ->
                    Text(state.message, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                else -> Unit
            }
        }
    }
}

/**
 * Two-step flow: (1) join the shown network manually on the phone — the SSID/
 * password banner is the "notification", read directly off this card, not a QR —
 * then (2) scan the single QR (or type the URL) to open the setup page. There's
 * no WiFi-join QR: the phone must already be on the hotspot before the portal is
 * reachable at all.
 */
@Composable
private fun AwaitingPhoneContent(state: ProvisioningState.AwaitingPhone) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(modifier = Modifier.width(300.dp)) {
            Text("Este display precisa de WiFi", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            if (state.retryAfterFailure) {
                Text(
                    "Não foi possível conectar — senha incorreta? Tente de novo.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                "Passo 1 — no seu celular, conecte-se a esta rede WiFi:",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.size(6.dp))
            NetworkBanner(ssid = state.ssid, passphrase = state.passphrase)
            Spacer(Modifier.size(12.dp))
            Text(
                if (state.portalUrl != null) {
                    "Passo 2 — depois de conectar, escaneie o QR ao lado (ou acesse ${state.portalUrl})."
                } else {
                    "Passo 2 — depois de conectar, aguarde o endereço da página aparecer aqui."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.portalUrl?.let { url ->
            QrTile(payload = url, contentDescription = "QR code da página de configuração")
        }
    }
}

/** Prominent SSID/password callout — the thing the installer actually reads to join. */
@Composable
private fun NetworkBanner(ssid: String, passphrase: String?) {
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

@Composable
private fun QrTile(payload: String, contentDescription: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val qr = remember(payload) { generateQrBitmap(payload, 400) }
        Box(
            modifier = Modifier.size(150.dp).clip(RoundedCornerShape(8.dp)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(
                    bitmap = qr,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        Text("Abrir configuração", style = MaterialTheme.typography.labelSmall)
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
