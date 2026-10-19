package com.quartz.notes.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quartz.notes.LocalAppContainer

/** A compact voice note player: play/pause, scrubber and time. */
@Composable
fun VoicePlayer(
    path: String,
    durationMs: Long,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
) {
    val audio = LocalAppContainer.current.audio
    val state by audio.state.collectAsStateWithLifecycle()
    val isCurrent = state.path == path
    val playing = isCurrent && state.playing
    val total = if (isCurrent && state.durationMs > 0) state.durationMs else durationMs
    var dragging by remember { mutableStateOf<Float?>(null) }
    val position = dragging ?: if (isCurrent && total > 0) state.positionMs.toFloat() / total else 0f
    val accent = MaterialTheme.colorScheme.primary

    Surface(modifier = modifier.height(56.dp), shape = CircleShape, color = background, shadowElevation = 2.dp) {
        Row(Modifier.padding(start = 6.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = { audio.toggle(path) }, shape = CircleShape, color = accent, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.White,
                    )
                }
            }
            Slider(
                value = position.coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { audio.seek(path, (it * total).toLong()) }
                    dragging = null
                },
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = content.copy(alpha = 0.2f)),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(
                formatDuration(if (isCurrent && state.positionMs > 0) state.positionMs else total),
                fontSize = 13.sp,
                color = content.copy(alpha = 0.8f),
            )
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                trailing()
            }
        }
    }
}
