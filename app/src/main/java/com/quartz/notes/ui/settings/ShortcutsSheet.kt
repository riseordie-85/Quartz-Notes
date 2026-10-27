package com.quartz.notes.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.quartz.notes.ui.components.QuartzSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.ui.theme.LocalQuartzColors

/** One shortcut: the keys to press and what they do. */
private class Shortcut(val keys: List<String>, val action: String)

private class ShortcutGroup(val title: String, val note: String? = null, val items: List<Shortcut>)

private fun keys(vararg k: String) = k.toList()

/** Every shortcut the editor understands (kept in step with the editor's key handling). */
private val Groups = listOf(
    ShortcutGroup(
        title = "Editing",
        items = listOf(
            Shortcut(keys("Ctrl", "Z"), "Undo"),
            Shortcut(keys("Ctrl", "Y"), "Redo (or Ctrl + Shift + Z)"),
            Shortcut(keys("Ctrl", "A"), "Select all"),
            Shortcut(keys("Ctrl", "C"), "Copy"),
            Shortcut(keys("Ctrl", "X"), "Cut"),
            Shortcut(keys("Ctrl", "V"), "Paste"),
            Shortcut(keys("Esc"), "Stop typing"),
        ),
    ),
    ShortcutGroup(
        title = "Text style",
        items = listOf(
            Shortcut(keys("Ctrl", "B"), "Bold"),
            Shortcut(keys("Ctrl", "I"), "Italic"),
            Shortcut(keys("Ctrl", "U"), "Underline"),
            Shortcut(keys("Ctrl", "Shift", "X"), "Strikethrough"),
        ),
    ),
    ShortcutGroup(
        title = "Paragraphs",
        items = listOf(
            Shortcut(keys("Ctrl", "Shift", "H"), "Heading"),
            Shortcut(keys("Ctrl", "Shift", "J"), "Subheading"),
            Shortcut(keys("Ctrl", "Shift", "B"), "Body text"),
            Shortcut(keys("Ctrl", "Shift", "8"), "Bulleted list"),
            Shortcut(keys("Ctrl", "Shift", "L"), "Checklist"),
            Shortcut(keys("Ctrl", "Shift", "U"), "Check or uncheck items"),
            Shortcut(keys("Tab"), "Indent (or Ctrl + ])"),
            Shortcut(keys("Shift", "Tab"), "Outdent (or Ctrl + [)"),
        ),
    ),
    ShortcutGroup(
        title = "Notebook pages",
        items = listOf(
            Shortcut(keys("Alt", "→"), "Next page (or Ctrl + Page Down)"),
            Shortcut(keys("Alt", "←"), "Previous page (or Ctrl + Page Up)"),
            Shortcut(keys("Ctrl", "Alt", "Home"), "First page"),
            Shortcut(keys("Ctrl", "Alt", "End"), "Last page"),
        ),
    ),
    ShortcutGroup(
        title = "Mouse and pen",
        note = "Selections and handles work across paragraphs with a finger, mouse or pen.",
        items = listOf(
            Shortcut(keys("Click"), "Put the cursor there, anywhere on a line"),
            Shortcut(keys("Double-click"), "Select a word"),
            Shortcut(keys("Drag"), "Select text"),
            Shortcut(keys("Shift", "Click"), "Extend the selection"),
            Shortcut(keys("Right-click"), "Edit menu: cut, copy, paste, style, undo"),
            Shortcut(keys("Long-press"), "Select a word with a finger or pen, then drag the handles"),
        ),
    ),
)

/** Reference list of keyboard, mouse and pen shortcuts. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShortcutsSheet(onDismiss: () -> Unit) {
    val secondary = LocalQuartzColors.current.textSecondary
    QuartzSheet(
        onDismissRequest = onDismiss,
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
            Text("Keyboard shortcuts", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text(
                "On a Mac-style keyboard, Cmd works in place of Ctrl.",
                fontSize = 13.sp,
                color = secondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Groups.forEach { group ->
                Text(
                    group.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = secondary,
                    modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
                )
                group.note?.let { Text(it, fontSize = 13.sp, color = secondary, modifier = Modifier.padding(bottom = 6.dp)) }
                group.items.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s.action, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            s.keys.forEachIndexed { i, k ->
                                if (i > 0) Text("+", fontSize = 13.sp, color = secondary, modifier = Modifier.align(Alignment.CenterVertically))
                                KeyCap(k)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun KeyCap(label: String) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
