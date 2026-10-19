package com.quartz.notes.ui.notebook

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.FlipToFront
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.ImageData
import com.quartz.notes.data.model.decodeImageData
import com.quartz.notes.data.model.decodeVoiceData
import com.quartz.notes.ui.media.DecoratedImage
import com.quartz.notes.ui.media.VoicePlayer
import com.quartz.notes.ui.media.heightFactor
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Fixed height of a voice note player on a page. */
val VoiceElementHeight: Dp = 56.dp
private val VoiceHandleWidth: Dp = 30.dp

/**
 * Element positions and widths are stored in "page widths" (0.5 = half the page's width) so they
 * keep their place when the page changes size (folding, spreads). This is the bottom edge, in dp.
 */
fun PageElementEntity.bottom(pageWidth: Dp): Dp = when (kind) {
    ElementKind.IMAGE -> pageWidth * (y + width * (decodeImageData(data)?.heightFactor() ?: 1f))
    else -> pageWidth * y + VoiceElementHeight
}

/** Callbacks from the element layer. */
class ElementActions(
    val onSelect: (Long?) -> Unit,
    val onCommit: (PageElementEntity) -> Unit,
    val onView: (ImageData) -> Unit,
    val onDecorate: (PageElementEntity, ImageData) -> Unit,
    val onFront: (PageElementEntity) -> Unit,
    val onDelete: (PageElementEntity) -> Unit,
)

/**
 * Pictures and voice notes placed freely on a page. Tap one to select it; then drag to move,
 * pinch to resize and twist to rotate. The layer itself ignores touches elsewhere so the text
 * underneath stays editable.
 */
@Composable
fun BoxScope.PageElementLayer(
    elements: List<PageElementEntity>,
    pageWidth: Dp,
    selectedId: Long?,
    actions: ElementActions,
) {
    for (element in elements.sortedBy { it.z }) {
        key(element.id) {
            ElementItem(element, pageWidth, selected = element.id == selectedId, actions = actions)
        }
    }
    // The selected element's toolbar is drawn last so it sits above everything.
    elements.firstOrNull { it.id == selectedId }?.let { selected ->
        ElementToolbar(selected, pageWidth, actions)
    }
}

@Composable
private fun ElementItem(element: PageElementEntity, pageWidth: Dp, selected: Boolean, actions: ElementActions) {
    // Live copy while gesturing; reset whenever the saved version changes.
    var live by remember(element) { mutableStateOf(element) }
    val latest by rememberUpdatedState(live)
    val density = LocalDensity.current
    val widthPx = with(density) { pageWidth.toPx() }

    // Save shortly after a gesture settles.
    LaunchedEffect(live) {
        if (live != element) {
            delay(350)
            actions.onCommit(live)
        }
    }

    val imageData = remember(element.data) { if (element.kind == ElementKind.IMAGE) decodeImageData(element.data) else null }
    val voiceData = remember(element.data) { if (element.kind == ElementKind.VOICE) decodeVoiceData(element.data) else null }
    val heightFactor = imageData?.heightFactor()
    val accent = MaterialTheme.colorScheme.primary

    Box(
        Modifier
            .offset { IntOffset((live.x * widthPx).roundToInt(), (live.y * widthPx).roundToInt()) }
            .pointerInput(element.id) {
                detectTapGestures(
                    onTap = { actions.onSelect(element.id) },
                    onLongPress = { actions.onSelect(element.id) },
                )
            }
            .then(
                if (selected) {
                    Modifier.pointerInput(element.id, widthPx) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            val cur = latest
                            val newWidth = (cur.width * zoom).coerceIn(0.12f, 1.4f)
                            val hf = heightFactor
                            // Keep the element's center fixed while it's resized.
                            val dx = if (hf != null) (cur.width - newWidth) / 2 else 0f
                            val dy = if (hf != null) (cur.width - newWidth) * hf / 2 else 0f
                            live = cur.copy(
                                x = (cur.x + dx + pan.x / widthPx).coerceIn(-newWidth * 0.8f, 1f - newWidth * 0.2f),
                                y = (cur.y + dy + pan.y / widthPx).coerceAtLeast(0f),
                                width = if (cur.kind == ElementKind.IMAGE) newWidth else cur.width,
                                rotation = if (cur.kind == ElementKind.IMAGE) (cur.rotation + rotation) % 360f else 0f,
                            )
                        }
                    }
                } else {
                    Modifier
                },
            )
            .graphicsLayer { rotationZ = live.rotation },
    ) {
        when {
            imageData != null -> {
                val w = pageWidth * live.width
                Box(
                    if (selected) {
                        Modifier.border(2.dp, accent, RoundedCornerShape(4.dp)).padding(2.dp)
                    } else {
                        Modifier
                    },
                ) {
                    DecoratedImage(imageData, w)
                }
            }
            voiceData != null -> {
                val w = pageWidth * live.width
                Row(
                    Modifier
                        .width(w + VoiceHandleWidth)
                        .height(VoiceElementHeight)
                        .then(if (selected) Modifier.border(2.dp, accent, CircleShape) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Grab handle: the player's own buttons take taps, so moving starts here.
                    Box(Modifier.width(VoiceHandleWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.DragIndicator, contentDescription = "Move voice note", tint = accent)
                    }
                    VoicePlayer(voiceData.path, voiceData.durationMs, modifier = Modifier.width(w))
                }
            }
        }
    }
}

@Composable
private fun ElementToolbar(element: PageElementEntity, pageWidth: Dp, actions: ElementActions) {
    val image = remember(element.data) { if (element.kind == ElementKind.IMAGE) decodeImageData(element.data) else null }
    val density = LocalDensity.current
    val widthPx = with(density) { pageWidth.toPx() }
    val barHeightPx = with(density) { 58.dp.toPx() }
    val top = element.y * widthPx
    val y = if (top > barHeightPx) top - barHeightPx else with(density) { element.bottom(pageWidth).toPx() } + with(density) { 8.dp.toPx() }
    val x = (element.x * widthPx).coerceIn(0f, (widthPx - with(density) { 200.dp.toPx() }).coerceAtLeast(0f))

    Surface(
        modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (image != null) {
                IconButton(onClick = { actions.onView(image) }) { Icon(Icons.Rounded.OpenInFull, contentDescription = "View full size") }
                IconButton(onClick = { actions.onDecorate(element, image) }) { Icon(Icons.Rounded.AutoAwesome, contentDescription = "Decorate") }
            }
            IconButton(onClick = { actions.onFront(element) }) { Icon(Icons.Rounded.FlipToFront, contentDescription = "Bring to front") }
            IconButton(onClick = { actions.onDelete(element) }) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
