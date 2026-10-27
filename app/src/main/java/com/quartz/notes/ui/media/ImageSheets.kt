package com.quartz.notes.ui.media

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.quartz.notes.ui.components.QuartzSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.model.FrameStyle
import com.quartz.notes.data.model.ImageData
import com.quartz.notes.ui.components.ConfirmDialog
import com.quartz.notes.ui.theme.LocalQuartzColors

/** Full-screen picture viewer with pinch to zoom. Double-tap toggles zoom. */
@Composable
fun ImageViewer(path: String, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null) {
    val media = LocalAppContainer.current.media
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var confirmDelete by remember { mutableStateOf(false) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale <= 1f) Offset.Zero else offset + pan
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = media.file(path),
                contentDescription = "Picture",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        })
                    }
                    .transformable(transform)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    val uri = media.uriFor(media.file(path))
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = context.contentResolver.getType(uri) ?: "image/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share picture"))
                }) { Icon(Icons.Rounded.Share, contentDescription = "Share", tint = Color.White) }
                if (onDelete != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove picture", tint = Color.White)
                    }
                }
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "Remove picture?",
            message = "The picture will be removed from this note.",
            confirmLabel = "Remove",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
        )
    }
}

/** Frame, tape, shadow and polaroid caption for a picture on a page. Changes apply live. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DecorateSheet(data: ImageData, onChange: (ImageData) -> Unit, onDismiss: () -> Unit) {
    var caption by remember { mutableStateOf(data.caption) }
    QuartzSheet(
        onDismissRequest = {
            if (caption != data.caption) onChange(data.copy(caption = caption))
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text("Decorate picture", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(220.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                val previewWidth = (190.dp / data.copy(caption = caption).heightFactor()).coerceAtMost(240.dp)
                DecoratedImage(data.copy(caption = caption), previewWidth)
            }

            Label("Frame")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FrameStyle.entries.forEach { f ->
                    FilterChip(selected = data.frame == f, onClick = { onChange(data.copy(frame = f, caption = caption)) }, label = { Text(f.label) })
                }
            }
            if (data.frame == FrameStyle.POLAROID) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it.take(60) },
                    label = { Text("Caption") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Label("Extras")
            ToggleRow("Tape on the corners", data.tape) { onChange(data.copy(tape = it, caption = caption)) }
            ToggleRow("Drop shadow", data.shadow) { onChange(data.copy(shadow = it, caption = caption)) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalQuartzColors.current.textSecondary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
