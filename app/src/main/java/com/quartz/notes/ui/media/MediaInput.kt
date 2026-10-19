package com.quartz.notes.ui.media

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.StoredImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** Launchers for adding pictures from the photo picker or the camera. */
@Stable
class ImageAdder internal constructor(
    val pickFromGallery: () -> Unit,
    val takePhoto: () -> Unit,
)

/**
 * Remembers photo picker and camera launchers. Each chosen picture is copied into app storage
 * (compressed if that setting is on) and passed to [onImage].
 */
@Composable
fun rememberImageAdder(onImage: (StoredImage) -> Unit): ImageAdder {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnImage by rememberUpdatedState(onImage)
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }

    fun import(uri: Uri, deleteAfter: File? = null) {
        scope.launch {
            val compress = container.settings.settings.first().compressImages
            container.media.importImage(uri, compress)
                .onSuccess { latestOnImage(it) }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't add that picture", Toast.LENGTH_SHORT).show() }
            deleteAfter?.delete()
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { import(it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = cameraFile
        cameraFile = null
        if (saved && path != null) {
            val file = File(path)
            import(container.media.uriFor(file), deleteAfter = file)
        } else if (path != null) {
            File(path).delete()
        }
    }

    return remember(gallery, camera) {
        ImageAdder(
            pickFromGallery = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            takePhoto = {
                val (file, uri) = container.media.newCameraTarget()
                cameraFile = file.absolutePath
                runCatching { camera.launch(uri) }.onFailure {
                    cameraFile = null
                    Toast.makeText(context, "No camera app available", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

/** Asks for microphone access when needed, then runs [onGranted]. */
@Composable
fun rememberMicPermission(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) latest() else Toast.makeText(context, "Microphone access is needed to record voice notes", Toast.LENGTH_LONG).show()
    }
    return remember(launcher) {
        {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                latest()
            } else {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
