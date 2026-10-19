package com.quartz.notes.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
            NoteEditorRoute(noteId = id, onBack = { nav.popBackStack() })
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
                onBack = { nav.popBackStack() },
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
                onBack = { nav.popBackStack() },
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
                onBack = { nav.popBackStack() },
                onOpenPage = { nbId, pageId -> nav.navigate(Routes.notebook(nbId, pageId)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
