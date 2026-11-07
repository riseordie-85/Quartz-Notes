package com.quartz.notes.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A build of the app the sync server offers (its app/version.json, written when it's published). */
data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val apk: String,
    val size: Long,
    val sha256: String,
) {
    companion object {
        /** Reads version.json, or null when it isn't one. */
        fun parse(text: String): AppRelease? {
            val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val code = (o["versionCode"] as? JsonPrimitive)?.longOrNull ?: return null
            val apk = str("apk")
            if (code <= 0 || apk.isEmpty() || apk.contains('/') || apk.contains("..")) return null
            return AppRelease(code, str("versionName"), apk, (o["size"] as? JsonPrimitive)?.longOrNull ?: 0L, str("sha256").lowercase())
        }
    }
}

sealed interface UpdateState {
    /** Not checked yet, or no sync server to ask. */
    data object Unknown : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState

    /** Handed to Android's installer, which asks to confirm. */
    data class Installing(val release: AppRelease) : UpdateState

    /** Android needs "Install unknown apps" allowed for Quartz Notes first. */
    data class NeedsPermission(val release: AppRelease) : UpdateState
    data class Failed(val message: String, val release: AppRelease?) : UpdateState
}

/**
 * Updates the app from the sync server: each published server image carries the matching app
 * build at /app (quartz-notes.apk and version.json), so every device can update itself.
 */
class AppUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val serverUrl: suspend () -> String?,
    private val http: OkHttpClient,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val mutex = Mutex()

    val installedVersionCode: Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    val installedVersionName: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    init {
        active = this
    }

    /** Asks the server whether there's a newer build. Quietly does nothing while one is under way. */
    fun check() = scope.launch {
        if (!mutex.tryLock()) return@launch
        try {
            val base = serverUrl()?.takeIf { it.isNotBlank() } ?: run {
                _state.value = UpdateState.Unknown
                return@launch
            }
            val before = _state.value
            if (before !is UpdateState.Available) _state.value = UpdateState.Checking
            _state.value = try {
                val release = fetchRelease(base)
                when {
                    release == null -> UpdateState.UpToDate
                    release.versionCode > installedVersionCode -> UpdateState.Available(release)
                    else -> UpdateState.UpToDate
                }
            } catch (e: IOException) {
                // Offline: an update already found stays on offer.
                if (before is UpdateState.Available) before else UpdateState.Unknown
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun fetchRelease(base: String): AppRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/app/version.json").header("Cache-Control", "no-cache").build()
        http.newCall(req).execute().use { res ->
            // An older server (no app build in it) answers 404 or with the web app's page.
            if (!res.isSuccessful) return@use null
            AppRelease.parse(res.body?.string().orEmpty())
        }
    }

    /** True when Android lets Quartz Notes install its own updates. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system screen where "Install unknown apps" is allowed for Quartz Notes. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Downloads the offered build and hands it to Android's installer. */
    fun update() = scope.launch {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.NeedsPermission -> s.release
            is UpdateState.Failed -> s.release ?: return@launch
            is UpdateState.Installing -> s.release
            else -> return@launch
        }
        if (!mutex.tryLock()) return@launch
        try {
            if (!canInstall()) {
                _state.value = UpdateState.NeedsPermission(release)
                return@launch
            }
            val base = serverUrl() ?: return@launch
            _state.value = UpdateState.Downloading(release, 0f)
            val file = try {
                download(base, release)
            } catch (e: IOException) {
                _state.value = UpdateState.Failed(e.message ?: "Couldn't download the update", release)
                return@launch
            }
            _state.value = UpdateState.Installing(release)
            try {
                install(file)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "Couldn't start the installer", release)
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun download(base: String, release: AppRelease): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "quartz-notes.apk")
        val req = Request.Builder().url("$base/app/${release.apk}").build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("The server doesn't have the update (${res.code})")
            val body = res.body ?: throw IOException("The download was empty")
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = 0f
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done.toFloat() / total).coerceIn(0f, 1f)
                            if (p - shown >= 0.02f) {
                                shown = p
                                _state.value = UpdateState.Downloading(release, p)
                            }
                        }
                    }
                }
            }
            val sum = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256.isNotEmpty() && sum != release.sha256) {
                file.delete()
                throw IOException("The download came through damaged; try again")
            }
        }
        file
    }

    private fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("quartz-notes.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateResultReceiver::class.java).setAction(ACTION_RESULT)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    internal fun installerSaid(status: Int, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit // The app restarts as the new version.
            PackageInstaller.STATUS_FAILURE_ABORTED -> release?.let { _state.value = UpdateState.Available(it) }
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                _state.value = UpdateState.Failed(
                    "This build is signed differently from the app on this device, so Android won't install it over the top",
                    release,
                )
            PackageInstaller.STATUS_FAILURE_STORAGE -> _state.value = UpdateState.Failed("Not enough space to install the update", release)
            else -> _state.value = UpdateState.Failed(message?.takeIf { it.isNotBlank() } ?: "The update didn't install", release)
        }
    }

    companion object {
        const val ACTION_RESULT = "com.quartz.notes.UPDATE_RESULT"

        @Volatile
        internal var active: AppUpdater? = null
    }
}

/** Hears back from Android's installer: asks the person to confirm, or reports how it went. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        AppUpdater.active?.installerSaid(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
