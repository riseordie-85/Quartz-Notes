package com.quartz.notes.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.update.AppUpdater
import com.quartz.notes.data.update.UpdateState
import com.quartz.notes.ui.components.ListRow
import com.quartz.notes.ui.components.QuartzCard
import com.quartz.notes.ui.theme.LocalQuartzColors

/** Once "Install unknown apps" is allowed and the person comes back, carries on with the update. */
@Composable
private fun ResumeAfterPermission(updater: AppUpdater, state: UpdateState) {
    LifecycleResumeEffect(state) {
        if (state is UpdateState.NeedsPermission && updater.canInstall()) updater.update()
        onPauseOrDispose { }
    }
}

/** What the update is doing, and the button for the next step. */
@Composable
private fun UpdateControls(updater: AppUpdater, state: UpdateState) {
    val context = LocalContext.current
    when (state) {
        is UpdateState.Available, is UpdateState.Failed -> Button(onClick = { updater.update() }) {
            Text(if (state is UpdateState.Failed) "Try again" else "Update")
        }
        is UpdateState.NeedsPermission -> Button(onClick = { context.startActivity(updater.permissionIntent()) }) { Text("Allow") }
        is UpdateState.Downloading, is UpdateState.Installing -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else -> Unit
    }
}

private fun detail(updater: AppUpdater, state: UpdateState): String = when (state) {
    UpdateState.Unknown -> "Version ${updater.installedVersionName}"
    UpdateState.Checking -> "Checking your sync server…"
    UpdateState.UpToDate -> "Version ${updater.installedVersionName} · up to date"
    is UpdateState.Available -> "You have ${updater.installedVersionName}"
    is UpdateState.Downloading -> "Downloading… ${(state.progress * 100).toInt()}%"
    is UpdateState.Installing -> "Confirm the install when Android asks"
    is UpdateState.NeedsPermission -> "Turn on \"Allow from this source\" for Quartz Notes, then come back"
    is UpdateState.Failed -> state.message
}

private fun headline(state: UpdateState): String = when (state) {
    is UpdateState.Available -> "Version ${state.release.versionName} is ready"
    is UpdateState.Downloading -> "Getting version ${state.release.versionName}"
    is UpdateState.Installing -> "Installing version ${state.release.versionName}"
    is UpdateState.NeedsPermission -> "Allow Quartz Notes to install updates"
    is UpdateState.Failed -> "The update didn't work"
    else -> "App updates"
}

/** Settings → App updates: the version here, and any newer one on the sync server. */
@Composable
fun UpdateSection(signedIn: Boolean, updater: AppUpdater = LocalAppContainer.current.updater) {
    val state by updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(signedIn) { if (signedIn) updater.check() }
    ResumeAfterPermission(updater, state)

    QuartzCard(Modifier.fillMaxWidth()) {
        if (!signedIn) {
            ListRow(
                title = "App updates",
                subtitle = "Version ${updater.installedVersionName}. Once sync is set up, new versions come from your sync server.",
                leading = { Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
            )
            return@QuartzCard
        }
        ListRow(
            title = headline(state),
            subtitle = detail(updater, state),
            leading = {
                Icon(
                    if (state is UpdateState.UpToDate) Icons.Rounded.Verified else Icons.Rounded.SystemUpdate,
                    null,
                    tint = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            },
            trailing = {
                when (state) {
                    UpdateState.UpToDate, UpdateState.Unknown -> TextButton(onClick = { updater.check() }) { Text("Check") }
                    UpdateState.Checking -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else -> UpdateControls(updater, state)
                }
            },
        )
        (state as? UpdateState.Downloading)?.let {
            LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 14.dp))
        }
    }
}

/** A slim card at the top of the home screen when a newer version is waiting. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier, updater: AppUpdater = LocalAppContainer.current.updater) {
    val state by updater.state.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableStateOf<Long?>(null) }
    ResumeAfterPermission(updater, state)
    val release = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Installing -> s.release
        is UpdateState.NeedsPermission -> s.release
        is UpdateState.Failed -> s.release
        else -> null
    } ?: return
    if (dismissed == release.versionCode) return

    QuartzCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(headline(state), fontSize = 15.sp)
                Text(detail(updater, state), fontSize = 13.sp, color = LocalQuartzColors.current.textSecondary)
            }
            UpdateControls(updater, state)
            if (state is UpdateState.Available) {
                IconButton(onClick = { dismissed = release.versionCode }) { Icon(Icons.Rounded.Close, "Not now") }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
        (state as? UpdateState.Downloading)?.let {
            LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 12.dp))
        }
    }
}
