package com.quartz.notes.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.sync.SyncStatus
import com.quartz.notes.ui.theme.LocalQuartzColors

/**
 * A small cloud showing how syncing is going: synced, syncing, offline (server out of reach) or a
 * problem. Tapping it syncs now. Hidden when sync isn't set up.
 */
@Composable
fun SyncIndicator(modifier: Modifier = Modifier) {
    val sync = LocalAppContainer.current.sync
    val status by sync.status.collectAsStateWithLifecycle()
    if (status == SyncStatus.OFF) return
    val pulse by rememberInfiniteTransition(label = "sync").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val (icon, label) = when (status) {
        SyncStatus.SYNCING -> Icons.Rounded.CloudSync to "Syncing"
        SyncStatus.OFFLINE -> Icons.Rounded.CloudOff to "Can't reach the sync server. Tap to try again"
        SyncStatus.ERROR -> Icons.Rounded.SyncProblem to "Sync problem. Tap to try again, or see Settings → Sync"
        else -> Icons.Rounded.CloudDone to "Synced. Tap to sync now"
    }
    IconButton(onClick = { sync.requestSync(0) }, modifier = modifier) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (status == SyncStatus.ERROR) MaterialTheme.colorScheme.error else LocalQuartzColors.current.textSecondary,
            modifier = Modifier.size(26.dp).alpha(if (status == SyncStatus.SYNCING) pulse else 1f),
        )
    }
}
