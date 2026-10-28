package com.quartz.notes.data.sync

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/** What the Sync settings show. */
data class SyncPrefs(
    val serverUrl: String = "",
    val email: String = "",
    val signedIn: Boolean = false,
    /** When the last complete sync finished (ms), or 0. */
    val lastSyncAt: Long = 0,
    /** Why the last sync failed, or null. */
    val lastError: String? = null,
)

/** Where the sync engine keeps its sign-in, this device's id and its download positions. */
interface SyncStore {
    suspend fun auth(): SyncAuth?
    suspend fun setAuth(auth: SyncAuth?)
    suspend fun deviceId(): String

    /** The server time of the newest change downloaded from [collection] ("" for none yet). */
    suspend fun cursor(collection: String): String
    suspend fun setCursor(collection: String, value: String)
    suspend fun clearCursors()

    suspend fun recordResult(at: Long, error: String?)
}

private val Context.syncData by preferencesDataStore(name = "sync")

/** [SyncStore] kept in the app's private storage. */
class DataStoreSyncStore(context: Context) : SyncStore {
    private val store = context.applicationContext.syncData

    private object Keys {
        val server = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
        val userId = stringPreferencesKey("user_id")
        val email = stringPreferencesKey("email")
        val device = stringPreferencesKey("device_id")
        val lastSync = longPreferencesKey("last_sync_at")
        val lastError = stringPreferencesKey("last_error")
        fun cursor(collection: String) = stringPreferencesKey("cursor_$collection")
    }

    val prefs: Flow<SyncPrefs> = store.data.map { p ->
        SyncPrefs(
            serverUrl = p[Keys.server].orEmpty(),
            email = p[Keys.email].orEmpty(),
            signedIn = !p[Keys.token].isNullOrEmpty(),
            lastSyncAt = p[Keys.lastSync] ?: 0,
            lastError = p[Keys.lastError],
        )
    }

    override suspend fun auth(): SyncAuth? {
        val p = store.data.first()
        val token = p[Keys.token]
        if (token.isNullOrEmpty()) return null
        return SyncAuth(p[Keys.server].orEmpty(), token, p[Keys.userId].orEmpty(), p[Keys.email].orEmpty())
    }

    override suspend fun setAuth(auth: SyncAuth?) {
        store.edit { p ->
            if (auth == null) {
                p.remove(Keys.token)
                p.remove(Keys.userId)
                p.remove(Keys.lastError)
                p.remove(Keys.lastSync)
            } else {
                p[Keys.server] = auth.serverUrl
                p[Keys.token] = auth.token
                p[Keys.userId] = auth.userId
                p[Keys.email] = auth.email
            }
        }
    }

    override suspend fun deviceId(): String {
        store.data.first()[Keys.device]?.let { return it }
        val id = UUID.randomUUID().toString()
        store.edit { if (it[Keys.device] == null) it[Keys.device] = id }
        return store.data.first()[Keys.device] ?: id
    }

    override suspend fun cursor(collection: String): String = store.data.first()[Keys.cursor(collection)].orEmpty()

    override suspend fun setCursor(collection: String, value: String) {
        store.edit { it[Keys.cursor(collection)] = value }
    }

    override suspend fun clearCursors() {
        store.edit { p -> p.asMap().keys.filter { it.name.startsWith("cursor_") }.forEach { p.remove(it as Preferences.Key<*>) } }
    }

    override suspend fun recordResult(at: Long, error: String?) {
        store.edit { p ->
            if (error == null) {
                p[Keys.lastSync] = at
                p.remove(Keys.lastError)
            } else {
                p[Keys.lastError] = error
            }
        }
    }
}

/** A [SyncStore] in memory (tests). */
class MemorySyncStore(private var auth: SyncAuth? = null) : SyncStore {
    private val device = UUID.randomUUID().toString()
    private val cursors = HashMap<String, String>()
    var lastError: String? = null
        private set

    override suspend fun auth() = auth
    override suspend fun setAuth(auth: SyncAuth?) {
        this.auth = auth
    }
    override suspend fun deviceId() = device
    override suspend fun cursor(collection: String) = cursors[collection].orEmpty()
    override suspend fun setCursor(collection: String, value: String) {
        cursors[collection] = value
    }
    override suspend fun clearCursors() = cursors.clear()
    override suspend fun recordResult(at: Long, error: String?) {
        lastError = error
    }
}
