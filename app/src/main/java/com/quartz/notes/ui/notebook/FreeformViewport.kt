package com.quartz.notes.ui.notebook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.quartz.notes.data.model.BackgroundFit
import kotlin.math.abs
import kotlin.math.max

/** Keeps a Freeform page from being moved further than its edges allow at scale [s]. */
private fun clampPan(pan: Offset, s: Float, canvas: Size, viewport: Size): Offset {
    val maxX = max(0f, (canvas.width * s - viewport.width) / 2)
    val maxY = max(0f, (canvas.height * s - viewport.height) / 2)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

/**
 * Zooming and moving around a Freeform page: two fingers pinch and drag; once zoomed in, one finger
 * on empty space drags too; Ctrl + mouse wheel zooms and the wheel scrolls. Items, text and tools
 * keep their own one-finger touches.
 */
internal fun Modifier.freeformZoom(session: PageSession, fit: Float, canvas: Size, viewport: Size): Modifier =
    this
        .pointerInput(fit, canvas, viewport) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2) {
                        val old = session.zoom
                        val new = (old * event.calculateZoom()).coerceIn(1f, 8f)
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val center = Offset(viewport.width / 2, viewport.height / 2)
                        // Keep the spot between the fingers under the fingers.
                        val rel = centroid - center - session.pan
                        val moved = session.pan + event.calculatePan() + rel - rel * (new / old)
                        session.zoom = new
                        session.pan = clampPan(moved, fit * new, canvas, viewport)
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(fit, canvas, viewport) {
            // Mouse wheel: Ctrl zooms, otherwise it scrolls the zoomed-in page.
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Scroll) continue
                    val delta = event.changes.fold(Offset.Zero) { acc, c -> acc + c.scrollDelta }
                    if (event.keyboardModifiers.isCtrlPressed) {
                        val new = (session.zoom * (if (delta.y < 0) 1.12f else 1 / 1.12f)).coerceIn(1f, 8f)
                        session.zoom = new
                        session.pan = clampPan(session.pan, fit * new, canvas, viewport)
                        event.changes.forEach { it.consume() }
                    } else if (session.zoomedIn) {
                        val step = 60.dp.toPx()
                        session.pan = clampPan(session.pan - Offset(delta.x * step, delta.y * step), fit * session.zoom, canvas, viewport)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        .pointerInput(fit, canvas, viewport) {
            // Zoomed in: one finger on empty space moves the page (anything that took the touch keeps it).
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                if (!session.zoomedIn) return@awaitEachGesture
                var moving = false
                var travelled = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed || event.changes.count { it.pressed } > 1) break
                    if (c.isConsumed && !moving) break
                    val d = c.positionChange()
                    travelled += abs(d.x) + abs(d.y)
                    if (moving || travelled > viewConfiguration.touchSlop) {
                        moving = true
                        session.pan = clampPan(session.pan + d, fit * session.zoom, canvas, viewport)
                        c.consume()
                    }
                }
            }
        }

/** Scales and moves a Freeform page (laid out at its own size, centered in the viewport). */
internal fun Modifier.freeformTransform(session: PageSession, fit: Float): Modifier = graphicsLayer {
    val s = fit * session.zoom
    scaleX = s
    scaleY = s
    translationX = session.pan.x
    translationY = session.pan.y
}

/**
 * A page's background picture, laid out by its fit: as wide as the page or repeated (text pages);
 * whole, covering, stretched or placed by hand (Freeform pages).
 */
@Composable
internal fun PaperPicture(
    model: Any?,
    mode: String,
    manual: BackgroundFit.Manual?,
    pageWidthPx: Float,
    onAspect: (Float) -> Unit,
    modifier: Modifier,
) {
    when (mode) {
        BackgroundFit.REPEAT -> {
            val painter = rememberAsyncImagePainter(model)
            Canvas(modifier) {
                val intrinsic = painter.intrinsicSize
                if (intrinsic.width <= 0f || intrinsic.height <= 0f || size.width <= 0f) return@Canvas
                val tileH = size.width * intrinsic.height / intrinsic.width
                var y = 0f
                while (y < size.height) {
                    translate(top = y) { with(painter) { draw(Size(size.width, tileH)) } }
                    y += tileH
                }
            }
        }
        BackgroundFit.WIDTH -> AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.TopCenter,
            onSuccess = { state ->
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) onAspect(size.height / size.width)
            },
            modifier = modifier,
        )
        else -> Box(modifier) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = when (mode) {
                    BackgroundFit.FILL -> ContentScale.Crop
                    BackgroundFit.STRETCH -> ContentScale.FillBounds
                    else -> ContentScale.Fit
                },
                alignment = Alignment.Center,
                modifier = Modifier
                    .matchParentSize()
                    .then(
                        if (mode == BackgroundFit.MANUAL && manual != null) {
                            Modifier.graphicsLayer {
                                translationX = manual.x * pageWidthPx
                                translationY = manual.y * pageWidthPx
                                scaleX = manual.scale
                                scaleY = manual.scale
                            }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/** Moving (one finger or mouse) and zooming (pinch) the background picture by hand. */
internal fun Modifier.adjustPicture(pageWidthPx: Float, value: () -> BackgroundFit.Manual, onChange: (BackgroundFit.Manual) -> Unit): Modifier =
    pointerInput(pageWidthPx) {
        detectTransformGestures { _, pan, zoom, _ ->
            val m = value()
            onChange(
                m.copy(
                    x = m.x + pan.x / pageWidthPx,
                    y = m.y + pan.y / pageWidthPx,
                    scale = (m.scale * zoom).coerceIn(0.2f, 8f),
                ),
            )
        }
    }
