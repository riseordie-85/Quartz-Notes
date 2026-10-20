package com.quartz.notes.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Kinds of freely placed page elements stored in the page_elements table. */
object ElementKind {
    const val IMAGE = "IMAGE"
    const val VOICE = "VOICE"
    const val TEXT = "TEXT"
    const val SHAPE = "SHAPE"
    const val ARROW = "ARROW"
    const val CONNECTOR = "CONNECTOR"
    const val STROKE = "STROKE"
}

/** Picture frame styles for images on pages. */
enum class FrameStyle(val label: String) {
    NONE("None"),
    WHITE("White"),
    BLACK("Black"),
    GOLD("Gold"),
    ROUNDED("Rounded"),
    POLAROID("Polaroid"),
}

/** How a picture relates to the page's text. */
enum class ImagePlacement {
    /** Placed anywhere, over the text. */
    FLOAT,

    /** Snapped to the left edge; paragraphs beside it are narrowed. */
    WRAP_LEFT,

    /** Snapped to the right edge; paragraphs beside it are narrowed. */
    WRAP_RIGHT,

    /** In the attachments strip at the bottom of the page. */
    ATTACHED,
}

/** Payload for an image element. [aspect] is height / width of the picture itself. */
@Serializable
data class ImageData(
    val path: String,
    val aspect: Float,
    val frame: FrameStyle = FrameStyle.WHITE,
    val tape: Boolean = false,
    val shadow: Boolean = true,
    val caption: String = "",
    val placement: ImagePlacement = ImagePlacement.FLOAT,
) {
    val wraps: Boolean get() = placement == ImagePlacement.WRAP_LEFT || placement == ImagePlacement.WRAP_RIGHT
    val attached: Boolean get() = placement == ImagePlacement.ATTACHED
}

/** Payload for a voice note element. */
@Serializable
data class VoiceData(
    val path: String,
    val durationMs: Long,
    /** In the attachments strip at the bottom of the page instead of placed on it. */
    val attached: Boolean = false,
)

/** Is this picture or recording in the page's bottom attachments strip (rather than placed on the page)? */
fun isAttachedElement(kind: String, data: String): Boolean = when (kind) {
    ElementKind.IMAGE -> decodeImageData(data)?.attached == true
    ElementKind.VOICE -> decodeVoiceData(data)?.attached == true
    else -> false
}

/** A free text box. [doc] is an encoded [com.quartz.notes.data.rich.RichDoc]. */
@Serializable
data class TextBoxData(val doc: String = "")

enum class ShapeType(val label: String) {
    RECTANGLE("Square"),
    ROUNDED("Rounded"),
    ELLIPSE("Circle"),
    TRIANGLE("Triangle"),
    DIAMOND("Diamond"),
    STAR("Star"),
}

/**
 * A shape with optional text inside. Colors are ARGB; [fill] null means no fill, and 0 for
 * [outline] / [textColor] means "match the page's ink".
 */
@Serializable
data class ShapeData(
    val shape: ShapeType = ShapeType.ELLIPSE,
    val fill: Int? = 0xFF00A8D6.toInt(),
    val outline: Int = 0xFF111114.toInt(),
    val outlineLevel: Int = 3,
    val text: String = "",
    val textColor: Int = 0,
)

/** How a connector between two items is drawn. */
enum class LineRoute(val label: String) { ELBOW("Elbow"), STRAIGHT("Straight"), CURVE("Curve") }

/** A free arrow from the element's (x, y) to (x + dx, y + dy), in page widths. Color 0 = page ink. */
@Serializable
data class ArrowData(
    val dx: Float,
    val dy: Float,
    val color: Int = 0,
    val level: Int = 3,
    val startHead: Boolean = false,
    val endHead: Boolean = true,
)

/** A line joining two elements (nodes); it follows them when they move. Color 0 = page ink. */
@Serializable
data class ConnectorData(
    val from: Long,
    val to: Long,
    val route: LineRoute = LineRoute.ELBOW,
    val color: Int = 0,
    val level: Int = 3,
    val endHead: Boolean = false,
)

/** A brush stroke: x0, y0, x1, y1 ... in page widths. Color 0 = page ink. [level] is 1–5. */
@Serializable
data class StrokeData(
    val points: List<Float>,
    val color: Int = 0,
    val level: Int = 2,
)

@PublishedApi
internal val ElementJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

inline fun <reified T> T.encodePayload(): String = ElementJson.encodeToString(this)
inline fun <reified T> decodePayload(raw: String): T? = runCatching { ElementJson.decodeFromString<T>(raw) }.getOrNull()

fun ImageData.encode(): String = encodePayload()
fun VoiceData.encode(): String = encodePayload()
fun decodeImageData(raw: String): ImageData? = decodePayload(raw)
fun decodeVoiceData(raw: String): VoiceData? = decodePayload(raw)

/** The media path referenced by an element's payload, whatever its kind. */
fun elementMediaPath(kind: String, raw: String): String? {
    val parsed = when (kind) {
        ElementKind.IMAGE -> decodeImageData(raw)?.path
        ElementKind.VOICE -> decodeVoiceData(raw)?.path
        ElementKind.TEXT, ElementKind.SHAPE, ElementKind.ARROW, ElementKind.CONNECTOR, ElementKind.STROKE -> return null
        else -> null
    }
    // Fall back to a loose read, e.g. for payloads written by a newer version with extra fields.
    return parsed ?: LoosePath.find(raw)?.groupValues?.get(1)
}

private val LoosePath = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"")
