package com.quartz.notes.ui.components

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The app's cover/section icons match shared/icons/icons.json, which the web app also uses. */
class IconCatalogTest {
    private val json: JsonObject = run {
        val file = listOf(File("../shared/icons/icons.json"), File("shared/icons/icons.json")).first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonObject
    }
    private val groups = json["groups"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun groupsAndKeysMatchTheSharedList() {
        val expected = groups.map { g -> g["title"]!!.jsonPrimitive.content to g["icons"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content } }
        assertEquals("run shared/icons/generate.py", expected, iconGroups)
        assertEquals(expected, NotebookIcons.groups.map { (title, icons) -> title to icons.map { it.first } })
        val keys = expected.flatMap { it.second }
        assertEquals("keys are unique", keys.size, keys.toSet().size)
    }

    @Test
    fun everyIconHasAPicture() {
        for (icon in groups.flatMap { it["icons"]!!.jsonArray }.map { it.jsonObject }) {
            val key = icon["key"]!!.jsonPrimitive.content
            assertNotNull(key, NotebookIcons.of(key))
            val paths = icon["paths"]?.jsonArray?.map { it.jsonPrimitive.content }
            if (paths != null) {
                val drawn = iconPaths[key]!!
                assertEquals("run shared/icons/generate.py", paths, drawn.paths)
                assertEquals(icon["evenOdd"]?.jsonPrimitive?.boolean ?: false, drawn.evenOdd)
            } else {
                assertTrue("$key needs a Material icon or paths", icon["symbol"] != null)
            }
        }
        assertEquals(null, NotebookIcons.of("none"))
    }
}
