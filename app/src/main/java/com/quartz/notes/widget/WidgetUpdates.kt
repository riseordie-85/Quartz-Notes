package com.quartz.notes.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.quartz.notes.data.QuartzRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * Keeps the home screen widgets in step with the notes: when a shelf, notebook or quick note changes
 * the way a widget shows it (here, or arriving from another device), the widgets are redrawn.
 */
object WidgetUpdates {
    @OptIn(FlowPreview::class)
    suspend fun follow(context: Context, repo: QuartzRepository) {
        val shelves = repo.observeShelves().map { list -> list.map { Triple(it.id, it.name, it.position) } }
        val notebooks = repo.observeAllNotebooks().map { list ->
            list.map { listOf(it.id, it.shelfId, it.title, it.colorArgb, it.texture, it.icon, it.position) }
        }
        val notes = repo.observeNotes().map { list -> list.map { listOf(it.id, it.title, it.plainText.take(300), it.colorKey, it.pinned) } }
        combine(shelves, notebooks, notes) { a, b, c -> Triple(a, b, c) }
            .distinctUntilChanged()
            .drop(1) // what's there at start is drawn already
            .debounce(800)
            .collect { updateAll(context) }
    }

    /** Redraws every widget that's on a home screen. */
    suspend fun updateAll(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        for (type in WidgetType.entries) {
            val widget = type.widget()
            if (manager.getGlanceIds(widget.javaClass).isNotEmpty()) runCatching { widget.updateAll(context) }
        }
    }
}
