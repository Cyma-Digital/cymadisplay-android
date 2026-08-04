package com.cyma.videoloop.ui.provisioning

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyma.videoloop.wifi.ProvisioningState

/**
 * Non-blocking overlay for background WiFi provisioning. Rendered on top of the
 * always-running content (playback/pairing) — it occupies only a corner of the screen,
 * so video keeps playing behind it. Renders nothing when [state] is
 * [ProvisioningState.Idle], which is also the state held through the boot grace window
 * (a box that connects on its own must never flash setup UI).
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
        when (state) {
            // Two separate cards, stacked top-to-bottom in the order they're performed:
            // join the box's hotspot, then open the portal on it.
            is ProvisioningState.AwaitingPhone -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WifiJoinCard(
                    ssid = state.ssid,
                    passphrase = state.passphrase,
                    retryReason = state.retryReason,
                )
                PortalAccessCard(portalUrl = state.portalUrl)
            }
            is ProvisioningState.Connecting -> ProvisioningCard { StatusRow("Conectando a ${state.ssid}…") }
            ProvisioningState.Verifying -> ProvisioningCard { StatusRow("Verificando conexão com a internet…") }
            ProvisioningState.Preparing -> ProvisioningCard { StatusRow("Iniciando configuração de WiFi…") }
            is ProvisioningState.Failed -> ProvisioningCard {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> Unit
        }
    }
}
