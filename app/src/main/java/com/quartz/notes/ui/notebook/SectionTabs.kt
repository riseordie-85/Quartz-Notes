package com.quartz.notes.ui.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.quartz.notes.data.db.PageEntity

/** Extra room at the screen edge for section tabs (on top of the page's own margin). */
internal val TabExtra: Dp = 10.dp

/** Visible width of a tab: the page margin (16dp) plus [TabExtra]. */
private val TabVisible: Dp = 26.dp

/**
 * Notebook tabs, one per section, in the section page's color. In two-page view, sections already
 * reached sit on the left edge and the ones still ahead on the right, like a real tabbed notebook;
 * on one page at a time they all sit on the right. Tap one to jump to its section.
 */
@Composable
internal fun BoxScope.SectionTabs(
    sections: List<PageEntity>,
    /** Position of each section page among the notebook's pages. */
    indexOf: (PageEntity) -> Int,
    /** Position of the first page showing (-1 for the index page). */
    firstShown: Int,
    twoUp: Boolean,
    height: Dp,
    onOpen: (Long) -> Unit,
) {
    if (sections.isEmpty()) return
    val top = 34.dp
    val gap = 10.dp
    val available = height - top * 2
    val tabHeight = ((available - gap * (sections.size - 1)) / sections.size).coerceIn(14.dp, 84.dp)
    sections.forEachIndexed { i, section ->
        val onLeft = twoUp && indexOf(section) <= firstShown
        val shape = if (onLeft) {
            RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
        } else {
            RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
        }
        val color = section.sectionColor
        Box(
            Modifier
                .align(if (onLeft) Alignment.TopStart else Alignment.TopEnd)
                .offset(y = top + (tabHeight + gap) * i)
                .size(TabVisible, tabHeight)
                .shadow(2.dp, shape)
                .clip(shape)
                .background(color)
                .drawWithContent {
                    drawContent()
                    // A soft glossy band across the tab.
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0f)),
                            start = Offset(0f, size.height * 0.25f),
                            end = Offset(size.width, size.height * 0.65f),
                        ),
                    )
                }
                .clickable { onOpen(section.id) }
                .semantics { contentDescription = "Section: " + section.title.ifBlank { "Untitled section" } },
        )
    }
}
