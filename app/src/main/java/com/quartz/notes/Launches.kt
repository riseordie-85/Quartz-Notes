package com.quartz.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/** Somewhere a widget (or another way in) asks the app to go. */
sealed interface LaunchRequest {
    data class OpenNotebook(val id: Long) : LaunchRequest
    data class OpenNote(val id: Long) : LaunchRequest
    data object NewNote : LaunchRequest
    data object NewNotebook : LaunchRequest

    /** The home screen at the top, on [shelfId] when it's given. */
    data class Notebooks(val shelfId: Long?) : LaunchRequest

    /** The home screen, scrolled to the quick notes. */
    data object QuickNotes : LaunchRequest
}

/**
 * Intents that open the app somewhere, and the one waiting to be acted on. The app's screens pick it up
 * (the navigation for whole screens, the home screen for things on it) and clear it.
 */
object Launches {
    private const val ACTION = "com.quartz.notes.action.OPEN"

    /** Waiting for the navigation. */
    val pending = MutableStateFlow<LaunchRequest?>(null)

    /** Waiting for the home screen (a new notebook, a shelf, the quick notes). */
    val home = MutableStateFlow<LaunchRequest?>(null)

    fun intent(context: Context, request: LaunchRequest): Intent {
        val (kind, id) = when (request) {
            is LaunchRequest.OpenNotebook -> "notebook" to request.id
            is LaunchRequest.OpenNote -> "note" to request.id
            LaunchRequest.NewNote -> "new-note" to 0L
            LaunchRequest.NewNotebook -> "new-notebook" to 0L
            is LaunchRequest.Notebooks -> "notebooks" to (request.shelfId ?: -1L)
            LaunchRequest.QuickNotes -> "quick-notes" to 0L
        }
        return Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            // Each place has its own address, so Android keeps their taps apart.
            .setData(Uri.parse("quartz-notes://open/$kind/$id"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    fun parse(intent: Intent?): LaunchRequest? {
        if (intent?.action != ACTION) return null
        val parts = intent.data?.pathSegments ?: return null
        val kind = parts.getOrNull(0) ?: return null
        val id = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return when (kind) {
            "notebook" -> LaunchRequest.OpenNotebook(id)
            "note" -> LaunchRequest.OpenNote(id)
            "new-note" -> LaunchRequest.NewNote
            "new-notebook" -> LaunchRequest.NewNotebook
            "notebooks" -> LaunchRequest.Notebooks(id.takeIf { it > 0 })
            "quick-notes" -> LaunchRequest.QuickNotes
            else -> null
        }
    }

    /**
     * Hands each waiting request to [act], one at a time, clearing it as it's taken. Clearing doesn't stop
     * [act] part-way (making a new note, say), as it did when the screen restarted its work on every change.
     */
    suspend fun follow(act: suspend (LaunchRequest) -> Unit) {
        pending.filterNotNull().collect { r ->
            pending.value = null
            act(r)
        }
    }

    /** Queues the intent's request, if it has one. */
    fun handle(intent: Intent?) {
        parse(intent)?.let { pending.value = it }
    }
}
