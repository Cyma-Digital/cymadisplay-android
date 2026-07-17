package com.cyma.videoloop.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cyma.videoloop.wifi.NetworkStatus

private val OfflineColor = Color(0xFFE53935)    // red
private val NoInternetColor = Color(0xFFFFB300) // amber
private val OnlineColor = Color(0xFF43A047)     // green

/**
 * A small always-on status light in the viewer's bottom-right corner:
 * red = offline, amber = connected-but-no-internet, green = validated internet.
 *
 * Placed *inside* MainActivity's RotatedScreen, so [Alignment.BottomEnd] maps to
 * the viewer's bottom-right in every orientation. Diameter and margin scale off
 * the short side of the (post-rotation) content frame — read via
 * [BoxWithConstraints], not LocalConfiguration, because the activity is pinned to
 * landscape and the configuration would report the wrong axis in portrait.
 *
 * Plain filled circle — no border, no shadow. No touch modifiers either: an empty
 * fill-size Box does not consume input, so content below stays interactive.
 */
@Composable
fun NetworkStatusIndicator(
    status: NetworkStatus,
    modifier: Modifier = Modifier,
) {
    val color = when (status) {
        NetworkStatus.OFFLINE -> OfflineColor
        NetworkStatus.NO_INTERNET -> NoInternetColor
        NetworkStatus.ONLINE -> OnlineColor
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val minSide = minOf(maxWidth, maxHeight)
        val diameter = (minSide * 0.008f).coerceIn(4.dp, 20.dp)
        val margin = (minSide * 0.015f).coerceIn(6.dp, 28.dp)
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(margin)
                .size(diameter)
                .clip(CircleShape)
                .background(color),
        )
    }
}
