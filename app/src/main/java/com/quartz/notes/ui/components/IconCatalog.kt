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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icons for notebook covers and section pages, keyed by a stable id stored in the database. The
 * list and groups come from shared/icons/icons.json (shared with the web app): the first icons are
 * Material icons, the rest are drawn from SVG paths (IconPaths.kt).
 */
object NotebookIcons {
    private val material: Map<String, ImageVector> = mapOf(
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

    private val byKey: Map<String, ImageVector> by lazy {
        material + iconPaths.mapValues { (key, icon) -> vectorOf(key, icon) }
    }

    /** The pickers' groups, in order: a title and its icons. */
    val groups: List<Pair<String, List<Pair<String, ImageVector>>>> by lazy {
        iconGroups.map { (title, keys) -> title to keys.mapNotNull { k -> byKey[k]?.let { k to it } } }
    }

    val all: List<Pair<String, ImageVector>> by lazy { groups.flatMap { it.second } }

    fun of(key: String?): ImageVector? = key?.let { byKey[it] }

    private fun vectorOf(key: String, icon: IconPaths): ImageVector {
        val builder = ImageVector.Builder(
            name = key,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (d in icon.paths) {
            builder.addPath(
                pathData = addPathNodes(d),
                fill = SolidColor(Color.Black),
                pathFillType = if (icon.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            )
        }
        return builder.build()
    }
}
