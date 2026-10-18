package com.quartz.notes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.quartz.notes.domain.model.GradientSpec

@Composable
fun QuartzGradientContainer(
    globalGradient: GradientSpec?,
    noteOverrideGradient: GradientSpec? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val activeGradient = noteOverrideGradient ?: globalGradient
    val backgroundModifier = if (activeGradient != null && activeGradient.id != "none") {
        Modifier.background(brush = activeGradient.toBrush())
    } else {
        Modifier.background(color = MaterialTheme.colorScheme.background)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(backgroundModifier),
        content = content
    )
}
