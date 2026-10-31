package com.quartz.notes.data.templates

import android.content.Context
import com.quartz.notes.data.rich.RichDoc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A ready-made page the app offers (from shared/templates/starters.json, also used by the web app). */
data class StarterPage(
    val key: String,
    val name: String,
    val title: String,
    val pageType: String,
    val backgroundArgb: Int?,
    val texture: String,
    val layoutMode: String,
    val content: RichDoc,
)

/** A ready-made quick note. */
data class StarterNote(val key: String, val name: String, val title: String, val colorKey: String, val content: RichDoc)

/** The starter templates. Read once from the app's assets. */
class Starters(val pages: List<StarterPage>, val notes: List<StarterNote>) {
    companion object {
        @Volatile
        private var cached: Starters? = null

        fun get(context: Context): Starters = cached ?: synchronized(this) {
            cached ?: parse(context.assets.open("starters.json").bufferedReader().use { it.readText() }).also { cached = it }
        }

        fun parse(text: String): Starters {
            val root = Json.parseToJsonElement(text).jsonObject
            fun JsonObject.s(key: String) = this[key]?.jsonPrimitive?.contentOrNull
            val pages = root["pages"]!!.jsonArray.map { e ->
                val o = e.jsonObject
                val p = o["page"]!!.jsonObject
                StarterPage(
                    key = o.s("key")!!,
                    name = o.s("name")!!,
                    title = p.s("title").orEmpty(),
                    pageType = p.s("pageType") ?: "LINES",
                    backgroundArgb = p["backgroundArgb"]?.jsonPrimitive?.intOrNull,
                    texture = p.s("texture") ?: "NONE",
                    layoutMode = p.s("layoutMode") ?: "INLINE",
                    content = RichDoc.decode(p["content"].toString()),
                )
            }
            val notes = root["notes"]!!.jsonArray.map { e ->
                val o = e.jsonObject
                val n = o["note"]!!.jsonObject
                StarterNote(
                    key = o.s("key")!!,
                    name = o.s("name")!!,
                    title = n.s("title").orEmpty(),
                    colorKey = n.s("colorKey") ?: "default",
                    content = RichDoc.decode(n["content"].toString()),
                )
            }
            return Starters(pages, notes)
        }
    }
}
