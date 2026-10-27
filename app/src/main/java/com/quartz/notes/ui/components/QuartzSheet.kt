package com.quartz.notes.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A bottom sheet that always tells its owner when it closes. A plain sheet can be pushed shut by a
 * mouse wheel without reporting it, leaving an invisible layer over the app that blocks every touch;
 * this one reports any close, however it happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuartzSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val latest by rememberUpdatedState(onDismissRequest)
    // Reported once, whichever way the sheet closed.
    val reported = remember { BooleanArray(1) }
    val dismiss = remember {
        {
            if (!reported[0]) {
                reported[0] = true
                latest()
            }
        }
    }
    LaunchedEffect(sheetState) {
        var shown = false
        snapshotFlow { sheetState.currentValue }.collect { value ->
            if (value != SheetValue.Hidden) shown = true else if (shown) dismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = containerColor,
        content = content,
    )
}
