package com.quartz.notes.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Work
import androidx.compose.ui.graphics.vector.ImageVector

/** Icons available for notebook covers, keyed by a stable id stored in the database. */
object NotebookIcons {
    val all: List<Pair<String, ImageVector>> = listOf(
        "book" to Icons.AutoMirrored.Rounded.MenuBook,
        "story" to Icons.Rounded.AutoStories,
        "lock" to Icons.Rounded.Lock,
        "star" to Icons.Rounded.Star,
        "heart" to Icons.Rounded.Favorite,
        "idea" to Icons.Rounded.Lightbulb,
        "mind" to Icons.Rounded.Psychology,
        "work" to Icons.Rounded.Work,
        "code" to Icons.Rounded.Code,
        "draft" to Icons.Rounded.Description,
        "edit" to Icons.Rounded.Edit,
        "art" to Icons.Rounded.Palette,
        "brush" to Icons.Rounded.Brush,
        "camera" to Icons.Rounded.PhotoCamera,
        "music" to Icons.Rounded.MusicNote,
        "movie" to Icons.Rounded.Movie,
        "game" to Icons.Rounded.SportsEsports,
        "car" to Icons.Rounded.DirectionsCar,
        "travel" to Icons.Rounded.Flight,
        "home" to Icons.Rounded.Home,
        "food" to Icons.Rounded.Restaurant,
        "shop" to Icons.Rounded.ShoppingCart,
        "money" to Icons.Rounded.Savings,
        "fitness" to Icons.Rounded.FitnessCenter,
        "school" to Icons.Rounded.School,
        "science" to Icons.Rounded.Science,
        "pets" to Icons.Rounded.Pets,
        "calendar" to Icons.Rounded.CalendarMonth,
    )

    private val byKey = all.toMap()

    fun of(key: String?): ImageVector? = byKey[key]
}
