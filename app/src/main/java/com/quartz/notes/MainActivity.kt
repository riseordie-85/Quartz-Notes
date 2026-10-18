package com.quartz.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.quartz.notes.data.local.entity.NoteEntity
import com.quartz.notes.ui.notes.NoteEditorScreen
import com.quartz.notes.ui.notes.NotesListScreen
import com.quartz.notes.ui.notes.NotesViewModel
import com.quartz.notes.ui.settings.SettingsScreen
import com.quartz.notes.ui.theme.QuartzNotesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: NotesViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val globalGradient by vm.globalGradient.collectAsStateWithLifecycle()
            val notes by vm.notes.collectAsStateWithLifecycle()
            val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
            val autoBackupEnabled by vm.autoBackupEnabled.collectAsStateWithLifecycle()
            val autoBackupInterval by vm.autoBackupIntervalHours.collectAsStateWithLifecycle()
            val autoBackupUri by vm.autoBackupUri.collectAsStateWithLifecycle()

            QuartzNotesTheme(themeMode = themeMode) {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "notes_list") {
                    composable("notes_list") {
                        NotesListScreen(
                            notes = notes,
                            globalGradient = globalGradient,
                            searchQuery = searchQuery,
                            onSearchChange = vm::updateSearch,
                            onNoteClick = { noteId -> navController.navigate("editor/$noteId") },
                            onCreateNote = { navController.navigate("editor/0") },
                            onDeleteNote = vm::deleteNoteToTrash,
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }

                    composable(
                        route = "editor/{noteId}",
                        arguments = listOf(navArgument("noteId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val noteId = backStackEntry.arguments?.getLong("noteId") ?: 0L
                        val existingNote = remember(noteId, notes) {
                            notes.find { it.id == noteId } ?: NoteEntity()
                        }

                        NoteEditorScreen(
                            initialNote = existingNote,
                            globalGradient = globalGradient,
                            onSaveNote = vm::saveNote,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(
                            currentThemeMode = themeMode,
                            currentGlobalGradient = globalGradient,
                            autoBackupEnabled = autoBackupEnabled,
                            autoBackupIntervalHours = autoBackupInterval,
                            autoBackupUriString = autoBackupUri,
                            onThemeModeChange = vm::setThemeMode,
                            onGlobalGradientChange = vm::setGlobalGradient,
                            onExportManualZip = vm::exportManualZip,
                            onExportManualJson = vm::exportManualJson,
                            onConfigureAutoBackupFolder = vm::setAutoBackupFolder,
                            onToggleAutoBackup = vm::setAutoBackupSchedule,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
