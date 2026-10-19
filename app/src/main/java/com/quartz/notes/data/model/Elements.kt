package com.quartz.notes.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Kinds of freely placed page elements stored in the page_elements table. */
object ElementKind {
    const val IMAGE = "IMAGE"
    const val VOICE = "VOICE"
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

/** Payload for an image element. [aspect] is height / width of the picture itself. */
@Serializable
data class ImageData(
    val path: String,
    val aspect: Float,
    val frame: FrameStyle = FrameStyle.WHITE,
    val tape: Boolean = false,
    val shadow: Boolean = true,
    val caption: String = "",
)

/** Payload for a voice note element. */
@Serializable
data class VoiceData(
    val path: String,
    val durationMs: Long,
)

private val ElementJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun ImageData.encode(): String = ElementJson.encodeToString(this)
fun VoiceData.encode(): String = ElementJson.encodeToString(this)
fun decodeImageData(raw: String): ImageData? = runCatching { ElementJson.decodeFromString<ImageData>(raw) }.getOrNull()
fun decodeVoiceData(raw: String): VoiceData? = runCatching { ElementJson.decodeFromString<VoiceData>(raw) }.getOrNull()

/** The media path referenced by an element's payload, whatever its kind. */
fun elementMediaPath(kind: String, raw: String): String? = when (kind) {
    ElementKind.IMAGE -> decodeImageData(raw)?.path
    ElementKind.VOICE -> decodeVoiceData(raw)?.path
    else -> null
}
