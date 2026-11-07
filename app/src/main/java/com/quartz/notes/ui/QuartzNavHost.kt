package com.quartz.notes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.quartz.notes.LaunchRequest
import com.quartz.notes.Launches
import com.quartz.notes.LocalAppContainer
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.filterNotNull
import com.quartz.notes.ui.home.HomeRoute
import com.quartz.notes.ui.note.NoteEditorRoute
import com.quartz.notes.ui.notebook.NotebookScreen
import com.quartz.notes.ui.recent.RecentScreen
import com.quartz.notes.ui.search.SearchScreen
import com.quartz.notes.ui.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val NOTE = "note/{id}"
    const val NOTEBOOK = "notebook/{id}?page={page}"
    const val SEARCH = "search?notebook={notebook}"
    const val SETTINGS = "settings"
    const val RECENT = "recent"

    fun note(id: Long) = "note/$id"
    fun notebook(id: Long, page: Long = -1L) = "notebook/$id?page=$page"
    fun search(notebookId: Long? = null) = "search?notebook=${notebookId ?: -1L}"
}

@Composable
fun QuartzNavHost() {
    val nav = rememberNavController()
    val container = LocalAppContainer.current

    // Opened from a widget: go to the notebook or note, or hand the home screen what's for it. Collected
    // rather than keyed on the request, so clearing it doesn't cancel the work (making a new note) under way.
    LaunchedEffect(Unit) {
        Launches.pending.filterNotNull().collect { r ->
            Launches.pending.value = null
            when (r) {
                is LaunchRequest.OpenNotebook -> nav.navigate(Routes.notebook(r.id)) { popUpTo(Routes.HOME) }
                is LaunchRequest.OpenNote -> nav.navigate(Routes.note(r.id)) { popUpTo(Routes.HOME) }
                LaunchRequest.NewNote -> {
                    val id = container.repository.createNote()
                    nav.navigate(Routes.note(id)) { popUpTo(Routes.HOME) }
                }
                else -> {
                    nav.popBackStack(Routes.HOME, inclusive = false)
                    Launches.home.value = r
                }
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeRoute(
                onOpenNote = { nav.navigate(Routes.note(it)) },
                onOpenNotebook = { nav.navigate(Routes.notebook(it)) },
                onOpenRecent = { nav.navigate(Routes.RECENT) },
                onSearch = { nav.navigate(Routes.search()) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.NOTE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            NoteEditorRoute(noteId = id, onBack = { nav.back() })
        }
        composable(
            Routes.NOTEBOOK,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("page") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val page = entry.arguments?.getLong("page")?.takeIf { it >= 0 }
            NotebookScreen(
                notebookId = id,
                initialPageId = page,
                onBack = { nav.back() },
                onSearch = { nav.navigate(Routes.search(id)) },
            )
        }
        composable(
            Routes.SEARCH,
            arguments = listOf(
                navArgument("notebook") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { entry ->
            val notebookId = entry.arguments?.getLong("notebook")?.takeIf { it >= 0 }
            SearchScreen(
                notebookId = notebookId,
                onBack = { nav.back() },
                onOpenNote = { nav.navigate(Routes.note(it)) },
                onOpenPage = { nbId, pageId ->
                    if (notebookId != null) {
                        // Came from inside this notebook: go back to it, landing on the page.
                        nav.popBackStack()
                        nav.navigate(Routes.notebook(nbId, pageId)) {
                            popUpTo(Routes.NOTEBOOK) { inclusive = true }
                        }
                    } else {
                        nav.navigate(Routes.notebook(nbId, pageId))
                    }
                },
            )
        }
        composable(Routes.RECENT) {
            RecentScreen(
                onBack = { nav.back() },
                onOpenPage = { nbId, pageId -> nav.navigate(Routes.notebook(nbId, pageId)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.back() },
                onOpenNote = { nav.navigate(Routes.note(it)) },
                onOpenPage = { nb, page -> nav.navigate(Routes.notebook(nb, page)) },
            )
        }
    }
}

/** Pops one screen, ignoring extra taps while a screen is already animating out (which would pop Home). */
private fun NavController.back() {
    if (previousBackStackEntry != null) popBackStack()
}
