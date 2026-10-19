package com.quartz.notes.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.quartz.notes.AppContainer
import com.quartz.notes.LocalAppContainer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Creates a ViewModel with access to the app container. */
@Composable
inline fun <reified VM : ViewModel> quartzViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/** "Today", "Yesterday", "October 10th", or "October 10th, 2025" for other years. */
fun dateLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> {
        val month = date.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
        val day = date.dayOfMonth
        val base = "$month $day${ordinalSuffix(day)}"
        if (date.year != today.year) "$base, ${date.year}" else base
    }
}

private fun ordinalSuffix(day: Int): String = when {
    day in 11..13 -> "th"
    day % 10 == 1 -> "st"
    day % 10 == 2 -> "nd"
    day % 10 == 3 -> "rd"
    else -> "th"
}

/** Short relative time for lists ("Oct 10", "3:42 PM"). */
fun shortTime(millis: Long): String {
    val date = millis.toLocalDate()
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return if (date == LocalDate.now()) {
        java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(zoned)
    } else {
        java.time.format.DateTimeFormatter.ofPattern(
            if (date.year == LocalDate.now().year) "MMM d" else "MMM d, yyyy",
            Locale.getDefault(),
        ).format(zoned)
    }
}
