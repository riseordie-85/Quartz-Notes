package com.timcordova.quartznotes.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.timcordova.quartznotes.data.MediaRepository

/**
 * What to hand an image loader for a stored picture: its file, or null while it hasn't arrived on
 * this device yet. When sync brings the file in, this changes and the picture appears.
 */
@Composable
fun MediaRepository.model(path: String): Any? {
    val arrivals by arrivals.collectAsState()
    val file = file(path)
    return remember(path, arrivals) { if (file.exists()) file else null }
}
