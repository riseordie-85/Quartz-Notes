package com.quartz.notes.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.quartz.notes.AppContainer
import com.quartz.notes.data.sync.SyncException
import com.quartz.notes.data.sync.SyncPrefs
import com.quartz.notes.ui.components.ListRow
import com.quartz.notes.ui.components.QuartzCard
import com.quartz.notes.ui.quartzViewModel
import com.quartz.notes.ui.shortTime
import com.quartz.notes.ui.theme.LocalQuartzColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SyncViewModel(private val container: AppContainer) : ViewModel() {
    private val sync = container.sync
    val prefs: StateFlow<SyncPrefs?> = sync.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val running: StateFlow<Boolean> = sync.running

    /** Signs in; reports an error message, or null when it worked. */
    fun signIn(server: String, email: String, password: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        val error = try {
            sync.signIn(server, email, password)
            null
        } catch (e: SyncException.SignedOut) {
            "That email and password didn't work"
        } catch (e: SyncException.Server) {
            if (e.status == 400) "That email and password didn't work" else e.message
        } catch (e: SyncException) {
            e.message
        }
        onResult(error)
    }

    fun syncNow(onResult: (String?) -> Unit) = viewModelScope.launch { onResult(sync.syncNow()) }

    fun signOut() = viewModelScope.launch { sync.signOut() }
}

/** Settings → Sync: sign in to the sync server, see how syncing is going. */
@Composable
fun SyncSection(onMessage: (String) -> Unit) {
    val vm = quartzViewModel { SyncViewModel(it) }
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    var signingIn by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val p = prefs ?: return

    QuartzCard(Modifier.fillMaxWidth()) {
        if (!p.signedIn) {
            ListRow(
                title = "Set up sync",
                subtitle = "Keep your notes the same on all your devices, through your own server",
                onClick = { signingIn = true },
                leading = { Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.primary) },
            )
        } else {
            val host = p.serverUrl.removePrefix("https://").removePrefix("http://")
            val status = when {
                running -> "Syncing…"
                p.lastError != null -> "Couldn't sync: ${p.lastError}"
                p.lastSyncAt > 0 -> "Last synced ${shortTime(p.lastSyncAt)}"
                else -> "Not synced yet"
            }
            ListRow(
                title = host,
                subtitle = status,
                leading = {
                    Icon(
                        if (p.lastError != null) Icons.Rounded.CloudOff else if (running) Icons.Rounded.CloudSync else Icons.Rounded.CloudDone,
                        null,
                        tint = if (p.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                },
                trailing = {
                    if (running) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { vm.syncNow { err -> onMessage(err?.let { "Couldn't sync: $it" } ?: "Synced") } }) {
                            Text("Sync now")
                        }
                    }
                },
            )
            HorizontalDivider(color = LocalQuartzColors.current.divider)
            ListRow(
                title = p.email,
                subtitle = "Signed in",
                leading = { Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary) },
                trailing = { TextButton(onClick = { confirmSignOut = true }) { Text("Sign out") } },
            )
        }
    }

    if (signingIn) {
        SignInDialog(
            initialServer = p.serverUrl,
            initialEmail = p.email,
            onDismiss = { signingIn = false },
            onSignIn = { server, email, password, done ->
                vm.signIn(server, email, password) { err ->
                    done(err)
                    if (err == null) {
                        signingIn = false
                        onMessage("Signed in. Syncing your notes…")
                    }
                }
            },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of sync?") },
            text = { Text("Your notes stay on this device. Changes here won't reach your other devices until you sign in again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    vm.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SignInDialog(
    initialServer: String,
    initialEmail: String,
    onDismiss: () -> Unit,
    onSignIn: (server: String, email: String, password: String, done: (String?) -> Unit) -> Unit,
) {
    var server by rememberSaveable { mutableStateOf(initialServer) }
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ready = server.isNotBlank() && email.isNotBlank() && password.isNotEmpty() && !busy

    fun submit() {
        if (!ready) return
        busy = true
        error = null
        onSignIn(server, email, password) { err ->
            scope.launch {
                busy = false
                error = err
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Sign in to sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server address") },
                    placeholder = { Text("your-server.tailnet.ts.net:8443") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                Text(
                    "Your phone or tablet needs Tailscale connected to reach the server.",
                    fontSize = 13.sp,
                    color = LocalQuartzColors.current.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = ready) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Sign in")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
