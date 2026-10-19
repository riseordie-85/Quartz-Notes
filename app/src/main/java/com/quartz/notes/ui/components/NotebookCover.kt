package com.quartz.notes.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.model.Texture

private const val CoverAspect = 0.685f
private val CoverShape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 22.dp, bottomEnd = 22.dp)

/** A notebook cover in the Quartz style: colored board, darker spine, optional gloss sheen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotebookCover(
    title: String,
    color: Color,
    texture: Texture,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val textureBrush = rememberTextureBrush(texture, whiteInk = false)
    BoxWithConstraints(
        modifier
            .aspectRatio(CoverAspect)
            .shadow(6.dp, CoverShape, clip = false)
            .clip(CoverShape)
            .background(color)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val w = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            textureBrush?.let { drawRect(it) }
            val spine = size.width * 0.075f
            drawRect(Color.Black.copy(alpha = 0.14f), size = Size(spine, size.height))
            drawLine(Color.White.copy(alpha = 0.22f), Offset(spine, 0f), Offset(spine, size.height), strokeWidth = 1.dp.toPx())

            val sheen = Path().apply {
                moveTo(spine, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height * 0.46f)
                lineTo(spine, size.height * 0.62f)
                close()
            }
            val strength = if (texture == Texture.GLOSS) 0.9f else 0.35f
            clipPath(sheen) {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = strength), Color.White.copy(alpha = 0f)),
                        center = Offset(size.width * 0.5f, size.height * 0.52f),
                        radius = size.width * 0.8f,
                    ),
                )
            }
            if (texture == Texture.GLOSS) {
                // Thin highlight along the sheen's lower edge.
                drawLine(
                    Color.White.copy(alpha = 0.35f),
                    Offset(spine, size.height * 0.62f),
                    Offset(size.width, size.height * 0.46f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        Column(
            Modifier.fillMaxSize().padding(start = w * 0.1f, end = w * 0.04f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(w * 0.3f))
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = (w.value * 0.12f).coerceIn(10f, 22f).sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1.5f), 4f),
                        lineHeight = (w.value * 0.14f).coerceIn(12f, 26f).sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (icon != null) {
                Spacer(Modifier.height(w * 0.05f))
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(w * 0.3f))
            }
        }
    }
}

/** The special "Recent" cover that opens recently edited pages. */
@Composable
fun RecentCover(icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BoxWithConstraints(modifier.aspectRatio(CoverAspect)) {
        val w = maxWidth
        NotebookCover(
            title = "",
            color = Color(0xFF0050D0),
            texture = Texture.GLOSS,
            icon = null,
            onClick = onClick,
        )
        Icon(
            icon,
            contentDescription = "Recent pages",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = w * 0.18f, start = w * 0.06f)
                .size(w * 0.42f),
        )
    }
}

/** Dashed placeholder cover for creating a notebook. */
@Composable
fun NewNotebookCover(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
    Box(
        modifier
            .aspectRatio(CoverAspect)
            .clip(CoverShape)
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
            .combinedClickableCompat(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = outline, modifier = Modifier.size(34.dp))
            Text("New", color = outline, fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = combinedClickable(onClick = onClick)
