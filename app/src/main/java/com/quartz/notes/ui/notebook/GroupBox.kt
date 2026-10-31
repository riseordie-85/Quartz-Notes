package com.quartz.notes.ui.notebook

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FlipToBack
import androidx.compose.material.icons.rounded.FlipToFront
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The box around items picked with the lasso: drag inside it to move them all, and a toolbar to
 * copy, duplicate, restack or remove them.
 */
@Composable
internal fun GroupBox(
    box: Rect,
    pageWidthPx: Float,
    canvas: PageCanvasState,
    locked: Boolean,
    onMoved: (Offset) -> Unit,
    onCopy: () -> Unit,
    onDuplicate: () -> Unit,
    onFront: () -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val moved by rememberUpdatedState(onMoved)
    val latestLocked by rememberUpdatedState(locked)
    Box(
        Modifier
            .offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
            .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
            .border(2.dp, accent, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                // Drag anywhere inside the box to move the whole group.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (latestLocked) return@awaitEachGesture
                    var total = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        val d = c.positionChange()
                        if (d != Offset.Zero) {
                            total += d
                            canvas.groupDrag = total
                        }
                        c.consume()
                    }
                    if (total != Offset.Zero) moved(total) else canvas.groupDrag = Offset.Zero
                }
            },
    )
    if (locked) return
    val barHeightPx = with(density) { 58.dp.toPx() }
    val barWidthPx = with(density) { 340.dp.toPx() }
    val y = if (box.top > barHeightPx) box.top - barHeightPx else box.bottom + with(density) { 8.dp.toPx() }
    val x = box.left.coerceIn(0f, (pageWidthPx - barWidthPx).coerceAtLeast(0f))
    Surface(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .onGloballyPositioned { c ->
                val root = canvas.contentCoords
                if (root != null && root.isAttached && c.isAttached) {
                    canvas.toolbarRect = Rect(root.localPositionOf(c, Offset.Zero), androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat()))
                }
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy") }
            IconButton(onClick = onDuplicate) { Icon(Icons.Rounded.LibraryAdd, contentDescription = "Duplicate") }
            IconButton(onClick = onFront) { Icon(Icons.Rounded.FlipToFront, contentDescription = "Bring to front") }
            IconButton(onClick = onBack) { Icon(Icons.Rounded.FlipToBack, contentDescription = "Send to back") }
            IconButton(onClick = onDone) { Icon(Icons.Rounded.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

/** "Paste" offered where the page was held. */
@Composable
internal fun PastePill(at: Offset, onPaste: () -> Unit) {
    val density = LocalDensity.current
    val lift = with(density) { 56.dp.toPx() }
    Surface(
        modifier = Modifier.offset { IntOffset(at.x.roundToInt(), (at.y - lift).coerceAtLeast(0f).roundToInt()) },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        TextButton(onClick = onPaste) {
            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text("Paste")
        }
    }
}
