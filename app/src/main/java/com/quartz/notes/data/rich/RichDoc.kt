package com.quartz.notes.data.rich

import com.quartz.notes.data.model.ImageData
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class BlockType { HEADING, SUBHEADING, BODY, BULLET, TASK, IMAGE, DIVIDER }

@Serializable
enum class Align { START, CENTER, END, JUSTIFY }

/** Inline character styles. "Valued" styles carry a value (a color hex or a font id). */
@Serializable
enum class InlineStyle(val valued: Boolean = false) {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKE,
    HIGHLIGHT(valued = true),
    COLOR(valued = true),
    FONT(valued = true),
}

/** A styled character range [start, end) within a block's text. */
@Serializable
data class StyleSpan(
    val start: Int,
    val end: Int,
    val style: InlineStyle,
    val value: String? = null,
)

/** One paragraph of rich text. */
@Serializable
data class Block(
    val id: String = newBlockId(),
    val type: BlockType = BlockType.BODY,
    val text: String = "",
    val spans: List<StyleSpan> = emptyList(),
    val align: Align = Align.START,
    val checked: Boolean = false,
    val indent: Int = 0,
    /** The picture, for [BlockType.IMAGE] blocks (pictures placed in line with the text). */
    val image: ImageData? = null,
    /** Width of an in-line picture as a fraction of the text width. */
    val imageWidth: Float = 0.7f,
)

/** A rich text document: an ordered list of blocks. Stored as JSON. */
@Serializable
data class RichDoc(val blocks: List<Block> = listOf(Block())) {

    fun encode(): String = RichJson.encodeToString(this)

    /** Text used for search indexing. */
    fun plainText(): String = blocks.joinToString("\n") { it.text }

    fun isBlank(): Boolean = blocks.all { it.text.isBlank() && it.image == null && it.type != BlockType.DIVIDER }

    /** Media paths of in-line pictures. */
    fun mediaPaths(): List<String> = blocks.mapNotNull { it.image?.path }

    /** Readable preview lines with list markers, skipping empty lines. */
    fun previewLines(): List<String> = blocks.mapNotNull { b ->
        val t = b.text.trim()
        if (t.isEmpty()) null else when (b.type) {
            BlockType.BULLET -> "• $t"
            BlockType.TASK -> (if (b.checked) "☑ " else "☐ ") + t
            else -> t
        }
    }

    companion object {
        fun decode(raw: String?): RichDoc {
            if (raw.isNullOrBlank()) return RichDoc()
            val doc = runCatching { RichJson.decodeFromString<RichDoc>(raw) }.getOrElse { fromPlainText(raw) }
            return if (doc.blocks.isEmpty()) RichDoc() else doc
        }

        fun fromPlainText(text: String): RichDoc = RichDoc(text.split('\n').map { Block(text = it) })
    }
}

private val RichJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

fun newBlockId(): String = UUID.randomUUID().toString()
