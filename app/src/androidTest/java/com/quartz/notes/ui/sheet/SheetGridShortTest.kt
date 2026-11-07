package com.quartz.notes.ui.sheet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quartz.notes.data.sheet.SheetCell
import com.quartz.notes.data.sheet.SheetData
import com.quartz.notes.data.sheet.addr
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The grid squeezed very short (the keyboard up on a folded phone) once crashed working out its
 * scrollbars, whose thumbs had a minimum length longer than the track.
 */
@RunWith(AndroidJUnit4::class)
class SheetGridShortTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aVeryShortGridStillDraws() {
        val cells = LinkedHashMap<String, SheetCell>()
        for (r in 0 until 200) for (c in 0 until 30) cells[addr(r, c)] = SheetCell("$r,$c")
        val state = SheetState(SheetData(rows = 200, cols = 30, cells = cells))
        var height by mutableStateOf(400)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(300.dp).height(height.dp)) {
                    SheetGridView(state, locked = false, paper = Color.White, ink = Color.Black)
                }
            }
        }
        rule.waitForIdle()
        // Shrinks it step by step, as the keyboard sliding up does, down to nearly nothing.
        for (h in listOf(200, 120, 80, 64, 56, 50, 44, 30, 10)) {
            height = h
            rule.waitForIdle()
        }
        // Then a cell picked while it's that short.
        state.select(150, 20)
        rule.waitForIdle()
        height = 60
        rule.waitForIdle()
    }
}
