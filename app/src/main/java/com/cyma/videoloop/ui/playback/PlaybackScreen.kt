package com.cyma.videoloop.ui.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PlaybackScreen(viewModel: PlaybackViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is PlaybackUiState.Loading -> DownloadDialog(state.progress, state.itemIndex, state.total)
        PlaybackUiState.WaitingForContent -> WaitingForContentScreen()
        is PlaybackUiState.Ready -> PlaybackEngine(
            items = state.items,
            modifier = Modifier.fillMaxSize(),
            onPlaybackError = viewModel::onPlaybackError,
        )
        is PlaybackUiState.Error -> ErrorScreen(state.message)
    }
}

@Composable
private fun WaitingForContentScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Waiting for content", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "This display is paired and ready. Add content in the Cyma admin panel.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(32.dp))
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun DownloadDialog(progress: Int, itemIndex: Int, total: Int) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.padding(32.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(progress = progress / 100f)
                Text("Preparing media…", modifier = Modifier.padding(top = 16.dp))
                if (total > 1) {
                    Text("Item ${itemIndex + 1} of $total", modifier = Modifier.padding(top = 4.dp))
                }
                Text("$progress%", modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun ErrorScreen(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}
