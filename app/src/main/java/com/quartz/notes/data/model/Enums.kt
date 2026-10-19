package com.quartz.notes.data.model

/** Ruling printed on a notebook page. */
enum class PageType(val label: String) {
    PLAIN("Plain"),
    LINES("Lines"),
    DOTTED("Dotted"),
    GRID("Grid"),
}

/** Surface texture used by notebook covers and pages. */
enum class Texture(val label: String) {
    NONE("None"),
    GLOSS("Gloss"),
    PAPER("Paper"),
    LINEN("Linen"),
    CANVAS("Canvas"),
    LEATHER("Leather");

    companion object {
        val coverOptions = listOf(GLOSS, NONE, LINEN, LEATHER, CANVAS, PAPER)
        val pageOptions = listOf(NONE, PAPER, LINEN, CANVAS)
    }
}

/** How a notebook page lays out its content. Scrapbook mode arrives in a later phase. */
enum class LayoutMode {
    INLINE,
    SCRAPBOOK,
}

/** Owner of an attachment row. */
enum class OwnerType { NOTE, PAGE }

/** Kind of attached media. */
enum class AttachmentKind { IMAGE, AUDIO }

inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
