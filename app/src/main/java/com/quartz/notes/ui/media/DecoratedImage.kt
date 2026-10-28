package com.quartz.notes.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.model.FrameStyle
import com.quartz.notes.data.model.ImageData

/** Frame insets as fractions of the element's width. */
private data class FrameInsets(val side: Float, val top: Float, val bottom: Float)

private fun FrameStyle.insets(): FrameInsets = when (this) {
    FrameStyle.NONE, FrameStyle.ROUNDED -> FrameInsets(0f, 0f, 0f)
    FrameStyle.WHITE -> FrameInsets(0.045f, 0.045f, 0.045f)
    FrameStyle.BLACK -> FrameInsets(0.025f, 0.025f, 0.025f)
    FrameStyle.GOLD -> FrameInsets(0.05f, 0.05f, 0.05f)
    FrameStyle.POLAROID -> FrameInsets(0.055f, 0.055f, 0.24f)
}

/** Height of a decorated image, as a multiple of its width. */
fun ImageData.heightFactor(): Float {
    val i = frame.insets()
    val innerWidth = 1f - 2 * i.side
    return i.top + innerWidth * aspect + i.bottom
}

/** A picture with its frame, tape and shadow, drawn at [width]. */
@Composable
fun DecoratedImage(data: ImageData, width: Dp, modifier: Modifier = Modifier) {
    val media = LocalAppContainer.current.media
    val insets = data.frame.insets()
    val height = width * data.heightFactor()
    val shape: Shape = when (data.frame) {
        FrameStyle.ROUNDED -> RoundedCornerShape(width * 0.07f)
        FrameStyle.POLAROID, FrameStyle.WHITE -> RoundedCornerShape(2.dp)
        else -> RectangleShape
    }
    val frameColor = when (data.frame) {
        FrameStyle.WHITE, FrameStyle.POLAROID -> Color(0xFFFDFDFB)
        FrameStyle.BLACK -> Color(0xFF151517)
        FrameStyle.GOLD -> Color(0xFFC9A227)
        else -> Color.Transparent
    }

    Box(modifier.size(width, height)) {
        Box(
            Modifier
                .fillMaxSize()
                .shadow(if (data.shadow) 10.dp else 0.dp, shape, clip = false)
                .clip(shape)
                .background(frameColor),
        ) {
            if (data.frame == FrameStyle.GOLD) {
                // Double gold line around the picture.
                Canvas(Modifier.fillMaxSize()) {
                    val line = Color(0xFFF3DC85)
                    val inset = width.toPx() * 0.018f
                    drawRect(line, topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    val inset2 = width.toPx() * 0.036f
                    drawRect(Color(0xFF8C6D10), topLeft = Offset(inset2, inset2), size = androidx.compose.ui.geometry.Size(size.width - 2 * inset2, size.height - 2 * inset2), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = width * insets.side, end = width * insets.side, top = width * insets.top),
            ) {
                val innerWidth = width * (1f - 2 * insets.side)
                AsyncImage(
                    model = media.model(data.path),
                    contentDescription = data.caption.ifBlank { "Picture" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(innerWidth * data.aspect)
                        .then(if (data.frame == FrameStyle.ROUNDED) Modifier.clip(shape) else Modifier)
                        .then(if (data.frame == FrameStyle.WHITE || data.frame == FrameStyle.POLAROID) Modifier.border(0.5.dp, Color.Black.copy(alpha = 0.08f)) else Modifier),
                )
                if (data.frame == FrameStyle.POLAROID) {
                    val fontSize = with(LocalDensity.current) { (width * 0.075f).toSp() }
                    Box(Modifier.fillMaxWidth().height(width * insets.bottom), contentAlignment = Alignment.Center) {
                        Text(
                            data.caption,
                            style = TextStyle(fontFamily = FontFamily.Cursive, fontSize = fontSize, color = Color(0xFF2B2B30), textAlign = TextAlign.Center),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = width * 0.04f),
                        )
                    }
                }
            }
        }
        if (data.tape) {
            Tape(width, Modifier.align(Alignment.TopStart).offset(x = -width * 0.06f, y = -width * 0.01f).rotate(-38f))
            Tape(width, Modifier.align(Alignment.TopEnd).offset(x = width * 0.06f, y = -width * 0.01f).rotate(38f))
        }
    }
}

/** A strip of translucent tape with torn ends. */
@Composable
private fun Tape(imageWidth: Dp, modifier: Modifier) {
    val w = (imageWidth * 0.3f).coerceIn(36.dp, 140.dp)
    val h = w * 0.3f
    Canvas(modifier.size(w, h)) {
        val tooth = size.height / 6
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f)
            // Zig-zag torn edges on both ends.
            var y = 0f
            var i = 0
            lineTo(size.width, 0f)
            while (y < size.height) {
                y += tooth
                lineTo(size.width - if (i % 2 == 0) tooth else 0f, y.coerceAtMost(size.height))
                i++
            }
            lineTo(0f, size.height)
            y = size.height
            i = 0
            while (y > 0f) {
                y -= tooth
                lineTo(if (i % 2 == 0) tooth else 0f, y.coerceAtLeast(0f))
                i++
            }
            close()
        }
        drawPath(path, Color(0xB8EFE6C8))
        drawPath(path, Color.White.copy(alpha = 0.25f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    }
}
