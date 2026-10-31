package com.quartz.notes.ui.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.model.PageSize
import com.quartz.notes.ui.theme.LocalQuartzColors

/**
 * Freeform page sizes: this device's screens first, then paper. Each tile shows the page's shape.
 * Paper sizes can be turned sideways.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageSizePicker(sizes: List<PageSize>, selected: PageSize?, onPick: (PageSize) -> Unit, modifier: Modifier = Modifier) {
    val screen = selected?.key in listOf(PageSize.COVER, PageSize.UNFOLDED, PageSize.SCREEN)
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sizes.forEach { base ->
                val isScreen = base.key in listOf(PageSize.COVER, PageSize.UNFOLDED, PageSize.SCREEN)
                // Paper keeps the chosen orientation when switching sizes.
                val option = if (!isScreen && selected?.landscape == true && !screen) base.portrait().rotated() else base
                val on = selected != null && selected.key == option.key && selected.landscape == option.landscape
                SizeTile(option, on) { onPick(option) }
            }
        }
        if (selected != null && !screen) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                SegmentedButton(
                    selected = !selected.landscape,
                    onClick = { if (selected.landscape) onPick(selected.rotated()) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Portrait") }
                SegmentedButton(
                    selected = selected.landscape,
                    onClick = { if (!selected.landscape) onPick(selected.rotated()) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Landscape") }
            }
        }
    }
}

@Composable
private fun SizeTile(size: PageSize, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .width(98.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(if (selected) 2.dp else 0.dp, if (selected) accent else MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .clickable(onClickLabel = "Use ${size.label}", onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The page's shape, at most 40 tall or 54 wide.
        val scale = minOf(54f / size.width, 40f / size.height)
        Box(Modifier.height(42.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size((size.width * scale).dp, (size.height * scale).dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
                    .border(1.5.dp, if (selected) accent else LocalQuartzColors.current.textSecondary, RoundedCornerShape(3.dp)),
            )
        }
        Text(size.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        Text(size.detail, fontSize = 11.sp, color = LocalQuartzColors.current.textSecondary, textAlign = TextAlign.Center, maxLines = 1)
    }
}
