package com.quartz.notes.ui.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.quartz.notes.data.model.BackgroundFit
import com.quartz.notes.ui.theme.LocalQuartzColors

/**
 * Lays out a page's background picture, like setting a wallpaper: a live preview the shape of the
 * page, how the picture fits (or placed by hand: drag and pinch in the preview), and which way up
 * it goes. Nothing changes on the page until Save.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageBackgroundDialog(
    picture: Any?,
    /** The fit saved now (mode, hand placement, turn). */
    fit: String?,
    freeform: Boolean,
    /** The page's height divided by its width. */
    pageAspect: Float,
    paperColor: Color,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(BackgroundFit.mode(fit, freeform)) }
    var manual by remember { mutableStateOf(BackgroundFit.manual(fit) ?: BackgroundFit.Manual(0f, 0f, 1f)) }
    var rotation by remember { mutableIntStateOf(BackgroundFit.rotation(fit)) }
    val muted = LocalQuartzColors.current.textSecondary
    val choices = if (freeform) {
        listOf(BackgroundFit.FIT to "Fit", BackgroundFit.FILL to "Fill", BackgroundFit.STRETCH to "Stretch", BackgroundFit.MANUAL to "Adjust")
    } else {
        listOf(BackgroundFit.WIDTH to "Fit width", BackgroundFit.REPEAT to "Repeat down the page")
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Background picture", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(14.dp))

                // The page in miniature, with the picture laid out as chosen.
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val aspect = pageAspect.coerceIn(0.3f, 3f)
                    val tallest = 420.dp
                    val w = min(maxWidth, tallest / aspect)
                    val h = w * aspect
                    val widthPx = with(LocalDensity.current) { w.toPx() }
                    val shape = RoundedCornerShape(6.dp)
                    Box(
                        Modifier
                            .size(w, h)
                            .clip(shape)
                            .background(paperColor)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                    ) {
                        PaperPicture(
                            model = picture,
                            mode = mode,
                            manual = manual,
                            rotation = rotation,
                            pageWidthPx = widthPx,
                            onAspect = {},
                            modifier = Modifier.matchParentSize(),
                        )
                        if (mode == BackgroundFit.MANUAL) {
                            Box(Modifier.matchParentSize().adjustPicture(widthPx, { manual }) { manual = it })
                        }
                    }
                }

                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    choices.forEach { (value, label) ->
                        FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(label) })
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    IconButton(onClick = { rotation = (rotation + 270) % 360 }) {
                        Icon(Icons.AutoMirrored.Rounded.RotateLeft, contentDescription = "Turn left")
                    }
                    Text(if (rotation == 0) "Upright" else "Turned $rotation°", fontSize = 14.sp, color = muted, modifier = Modifier.widthIn(min = 90.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { rotation = (rotation + 90) % 360 }) {
                        Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = "Turn right")
                    }
                    if (mode == BackgroundFit.MANUAL) {
                        Box(Modifier.padding(horizontal = 8.dp).size(width = 1.dp, height = 28.dp).background(muted.copy(alpha = 0.3f)))
                        IconButton(onClick = { manual = manual.copy(scale = (manual.scale / 1.15f).coerceAtLeast(0.2f)) }) {
                            Icon(Icons.Rounded.Remove, contentDescription = "Smaller")
                        }
                        IconButton(onClick = { manual = manual.copy(scale = (manual.scale * 1.15f).coerceAtMost(8f)) }) {
                            Icon(Icons.Rounded.Add, contentDescription = "Bigger")
                        }
                    }
                }
                if (mode == BackgroundFit.MANUAL) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Drag to move, pinch to zoom", fontSize = 13.sp, color = muted)
                        TextButton(onClick = { manual = BackgroundFit.Manual(0f, 0f, 1f) }) { Text("Reset") }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { onSave(BackgroundFit.encode(mode, manual, rotation)) }) { Text("Save") }
                }
            }
        }
    }
}
