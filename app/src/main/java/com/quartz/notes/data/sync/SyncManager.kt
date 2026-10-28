package com.quartz.notes.data.sync

import android.content.Context
import com.quartz.notes.data.db.QuartzDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides when to sync: a couple of seconds after edits stop, when the app comes to the front,
 * every 30 seconds while it's open, and every 15 minutes in the background ([SyncWorker]).
 */
class SyncManager(
    context: Context,
    db: QuartzDatabase,
    private val scope: CoroutineScope,
    /** Pictures, recordings or fonts arrived (true when fonts did). */
    onFilesArrived: (fontsChanged: Boolean) -> Unit = {},
    /** A row was updated with a newer version from another device. */
    onRemoteChange: (table: String, uid: String) -> Unit = { _, _ -> },
) {
    private val app = context.applicationContext
    private val syncDao = db.syncDao()
    val store = DataStoreSyncStore(app)
    val engine = SyncEngine(db, SyncClient(), store, app.filesDir, onFilesArrived, onRemoteChange)
    private val realtime = RealtimeClient()
    private var live: java.io.Closeable? = null
    private var liveLoop: Job? = null

    private val _status = MutableStateFlow(SyncStatus.OFF)

    /** How syncing is going, for the indicator on the home screen. */
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** What the Sync settings show. */
    val prefs: Flow<SyncPrefs> = store.prefs

    private val _running = MutableStateFlow(false)
    /** True while a sync is in progress. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val busy = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private var pending: Job? = null
    private var foregroundLoop: Job? = null
    private var refreshed = false

    fun start() {
        // Edits: every local change bumps the newest change time; sync shortly after they stop.
        scope.launch {
            var newest = syncDao.newestChange() ?: 0L
            syncDao.observeNewestChange().collect { t ->
                if (t != null && t > newest) {
                    newest = t
                    requestSync(EDIT_DELAY_MS)
                }
            }
        }
        // Keep the background schedule in step with signing in and out.
        scope.launch {
            store.prefs.distinctUntilChangedBy { it.signedIn }.collect { SyncWorker.schedule(app, it.signedIn) }
        }
        scope.launch { store.prefs.collect { refreshStatus() } }
    }

    /** The app came to the front (true) or went to the back (false). */
    fun setForeground(foreground: Boolean) {
        foregroundLoop?.cancel()
        stopLive()
        if (!foreground) return
        foregroundLoop = scope.launch {
            if (!refreshed && store.auth() != null) {
                refreshed = runCatching { engine.refreshSignIn() }.isSuccess
            }
            startLive()
            while (isActive) {
                requestSync(0)
                // With the live connection up, checking now and then is only a backstop.
                delay(if (live != null && liveConnected) POLL_LIVE_MS else POLL_MS)
            }
        }
    }

    @Volatile
    private var liveConnected = false

    /** Keeps the live connection open while the app is in front, reconnecting when it drops. */
    private fun startLive() {
        liveLoop?.cancel()
        liveLoop = scope.launch {
            var backoff = 2_000L
            while (isActive) {
                val auth = store.auth() ?: return@launch
                val device = store.deviceId()
                val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                live = realtime.listen(
                    auth,
                    com.quartz.notes.data.db.SyncTriggers.TABLES + "media",
                    onEvent = { e ->
                        // Our own changes come back too; only other devices' need a sync.
                        val from = (e.record["device"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        if (from != device) requestSync(LIVE_DELAY_MS)
                    },
                    onOpen = {
                        liveConnected = true
                        backoff = 2_000L
                        refreshStatus()
                    },
                    onClosed = {
                        liveConnected = false
                        closed.complete(Unit)
                        refreshStatus()
                    },
                )
                closed.await()
                live = null
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun stopLive() {
        liveLoop?.cancel()
        liveLoop = null
        live?.close()
        live = null
        liveConnected = false
    }

    /** Syncs after [delayMs], replacing any sync already waiting. */
    fun requestSync(delayMs: Long = 0) {
        synchronized(this) {
            pending?.cancel()
            pending = scope.launch {
                delay(delayMs)
                scope.launch { runOnce() }
            }
        }
    }

    /** Syncs now and waits; returns the error message, or null on success. */
    suspend fun syncNow(): String? {
        if (store.auth() == null) return "Not signed in"
        return try {
            _running.value = true
            refreshStatus()
            engine.sync()
            null
        } catch (e: SyncException) {
            e.message
        } finally {
            _running.value = busy.get()
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        scope.launch {
            val prefs = store.prefs.first()
            _status.value = when {
                !prefs.signedIn -> SyncStatus.OFF
                _running.value -> SyncStatus.SYNCING
                prefs.lastError == SyncException.Offline(Exception()).message -> SyncStatus.OFFLINE
                prefs.lastError != null -> SyncStatus.ERROR
                else -> SyncStatus.SYNCED
            }
        }
    }

    private suspend fun runOnce() {
        if (store.auth() == null) return
        // One at a time; a request that arrives meanwhile runs once more afterwards.
        if (!busy.compareAndSet(false, true)) {
            again.set(true)
            return
        }
        try {
            do {
                again.set(false)
                _running.value = true
                refreshStatus()
                runCatching { engine.sync() }
            } while (again.get())
        } finally {
            busy.set(false)
            _running.value = false
            refreshStatus()
        }
    }

    suspend fun signIn(serverUrl: String, email: String, password: String) {
        engine.signIn(serverUrl, email, password)
        refreshed = true
        requestSync(0)
        if (foregroundLoop?.isActive == true) startLive()
    }

    suspend fun signOut() {
        pending?.cancel()
        stopLive()
        engine.signOut()
    }

    /** Whether there's an account, for the background worker. */
    suspend fun signedIn(): Boolean = store.prefs.first().signedIn

    companion object {
        const val EDIT_DELAY_MS = 2_000L
        const val POLL_MS = 30_000L
        const val POLL_LIVE_MS = 120_000L

        /** A short wait after a live change, so a burst of changes syncs once. */
        const val LIVE_DELAY_MS = 300L
    }
}

/** What the sync indicator shows. */
enum class SyncStatus { OFF, SYNCING, SYNCED, OFFLINE, ERROR }
