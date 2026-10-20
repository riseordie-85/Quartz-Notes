package com.quartz.notes.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** One button revealed behind a swiped card. */
@Immutable
data class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val contentColor: Color,
    val onClick: () -> Unit,
)

/**
 * Shared by every [SwipeReveal] in one list so at most one card is open at a time:
 * opening (or starting to drag) a card closes the others.
 */
@Stable
class SwipeRevealGroup {
    /** Key of the card that's open or being dragged, if any. */
    var openKey: Any? by mutableStateOf(null)
        internal set

    fun closeAll() {
        openKey = null
    }
}

@Composable
fun rememberSwipeRevealGroup(): SwipeRevealGroup = remember { SwipeRevealGroup() }

/**
 * Wraps a card so it can be dragged sideways to uncover action buttons behind it.
 *
 * Dragging right uncovers [startActions] on the left; dragging left uncovers [endActions] on the right.
 * On release the card settles open or closed depending on how far and how fast it was flung. Only
 * horizontal drags are claimed, so the list around it still scrolls. While open, tapping the card
 * closes it instead of reaching the card's own click.
 *
 * @param corner corner radius of the action buttons (match the card's).
 * @param inset padding between the buttons and the outer edges (top, bottom and the far side).
 * @param spacing gap between the card and the first button, and between buttons.
 * @param actionWidth preferred width of each button; they shrink on narrow cards.
 */
@Composable
fun SwipeReveal(
    key: Any,
    group: SwipeRevealGroup,
    modifier: Modifier = Modifier,
    startActions: List<SwipeAction> = emptyList(),
    endActions: List<SwipeAction> = emptyList(),
    corner: Dp = 18.dp,
    inset: Dp = 0.dp,
    spacing: Dp = 8.dp,
    actionWidth: Dp = 76.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }
    var offsetPx by remember { mutableFloatStateOf(0f) }

    // How far the card travels to fully uncover each side; never more than most of the card.
    fun revealPx(count: Int): Float {
        if (count == 0 || widthPx == 0) return 0f
        val wanted = with(density) { (actionWidth * count + spacing * count + inset).toPx() }
        return minOf(wanted, widthPx * 0.8f)
    }
    val startPx = revealPx(startActions.size)
    val endPx = revealPx(endActions.size)

    val dragState = rememberDraggableState { delta ->
        offsetPx = (offsetPx + delta).coerceIn(-endPx, startPx)
    }

    // Opening another card (or scrolling the list) closes this one.
    LaunchedEffect(group.openKey) {
        if (group.openKey != key && offsetPx != 0f) dragState.settleTo(0f) { offsetPx }
    }

    val close: () -> Unit = {
        if (group.openKey == key) group.openKey = null
        scope.launch { dragState.settleTo(0f) { offsetPx } }
    }

    // Which side is showing; only changes when the card crosses the middle, so dragging doesn't recompose.
    val side by remember { derivedStateOf { offsetPx.compareTo(0f) } }

    Box(
        modifier
            .onSizeChanged { widthPx = it.width }
            // Keep the sliding card from drawing over its neighbours while it's moved aside.
            .graphicsLayer {
                clip = offsetPx != 0f
                shape = RectangleShape
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = { group.openKey = key },
                onDragStopped = { velocity ->
                    val fling = with(density) { 450.dp.toPx() }
                    val o = offsetPx
                    val target = when {
                        velocity > fling -> if (o < 0f) 0f else startPx
                        velocity < -fling -> if (o > 0f) 0f else -endPx
                        startPx > 0f && o > startPx / 2 -> startPx
                        endPx > 0f && o < -endPx / 2 -> -endPx
                        else -> 0f
                    }
                    if (target == 0f && group.openKey == key) group.openKey = null
                    dragState.settleTo(target, velocity) { offsetPx }
                },
            ),
    ) {
        if (side != 0) {
            val actions = if (side > 0) startActions else endActions
            val revealed = if (side > 0) startPx else endPx
            val revealedDp = with(density) { revealed.toDp() }
            Row(
                Modifier
                    .matchParentSize()
                    .padding(
                        PaddingValues(
                            start = if (side > 0) inset else 0.dp,
                            end = if (side < 0) inset else 0.dp,
                            top = inset,
                            bottom = inset,
                        ),
                    )
                    // Buttons fade in as the card uncovers them.
                    .graphicsLayer { alpha = progress(offsetPx, revealed) },
                horizontalArrangement = if (side > 0) Arrangement.Start else Arrangement.End,
            ) {
                Row(
                    Modifier
                        .width((revealedDp - inset).coerceAtLeast(0.dp))
                        .fillMaxHeight()
                        .padding(start = if (side < 0) spacing else 0.dp, end = if (side > 0) spacing else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    actions.forEach { action ->
                        SwipeActionButton(
                            action = action,
                            corner = corner,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                // ...and grow from slightly smaller to full size.
                                .graphicsLayer {
                                    val scale = 0.8f + 0.2f * progress(offsetPx, revealed)
                                    scaleX = scale
                                    scaleY = scale
                                },
                            onClick = {
                                close()
                                action.onClick()
                            },
                        )
                    }
                }
            }
        }

        Box(Modifier.absoluteOffset { IntOffset(offsetPx.roundToInt(), 0) }) {
            content()
            if (side != 0) {
                // Swallows taps on an open card so they close it rather than open the note.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { detectTapGestures { close() } },
                )
            }
        }
    }
}

/** How much of a side's buttons are uncovered, 0..1. */
private fun progress(offset: Float, revealed: Float): Float =
    if (revealed > 0f) (abs(offset) / revealed).coerceIn(0f, 1f) else 0f

/**
 * Animates the card from where it is ([current]) to [target]. Runs as a drag so a new touch interrupts it;
 * each frame moves by the gap to the animated value, so clamping at the edges can't leave it off target.
 */
private suspend fun DraggableState.settleTo(target: Float, velocity: Float = 0f, current: () -> Float) {
    drag(MutatePriority.Default) {
        animate(
            initialValue = current(),
            targetValue = target,
            initialVelocity = velocity,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        ) { value, _ -> dragBy(value - current()) }
    }
}

@Composable
private fun SwipeActionButton(action: SwipeAction, corner: Dp, modifier: Modifier, onClick: () -> Unit) {
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(action.color)
            .clickable(onClickLabel = action.label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Labels only when the button is big enough to hold one comfortably.
        val roomy = maxWidth >= 56.dp && maxHeight >= 54.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(action.icon, contentDescription = if (roomy) null else action.label, tint = action.contentColor, modifier = Modifier.size(22.dp))
            if (roomy) {
                Spacer(Modifier.height(3.dp))
                Text(
                    action.label,
                    color = action.contentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
