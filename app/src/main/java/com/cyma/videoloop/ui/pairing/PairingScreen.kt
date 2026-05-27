package com.cyma.videoloop.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.status) {
        if (uiState.status == PairingStatus.Paired) onPaired()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Pair this display", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(32.dp))

        Text("Device ID", style = MaterialTheme.typography.labelMedium)
        Text(
            uiState.deviceId,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(24.dp))

        Text("Pairing Code", style = MaterialTheme.typography.labelMedium)
        Text(
            uiState.pairingCode,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(32.dp))

        when (uiState.status) {
            PairingStatus.Idle -> Unit

            PairingStatus.Polling -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "Waiting for pairing…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            PairingStatus.Paired -> {
                Text("Paired!", style = MaterialTheme.typography.bodyLarge)
            }

            PairingStatus.TimedOut -> {
                Text(
                    "Timed out after 5 minutes.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.retryPolling() }) { Text("Try again") }
            }

            PairingStatus.Error -> {
                uiState.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.retryPolling() }) { Text("Retry") }
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "Enter this code in the Cyma admin panel to activate this display.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
