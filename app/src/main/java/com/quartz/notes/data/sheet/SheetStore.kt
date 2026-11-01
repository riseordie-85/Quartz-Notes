package com.quartz.notes.data.sheet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * How a sheet's JSON is kept in the database. Android reads a database row through a 2 MB window,
 * so a big sheet is kept compressed ("gz:" then gzip in base64); small ones stay plain. The sync
 * server and the web always get plain JSON.
 */
object SheetStore {
    private const val PREFIX = "gz:"

    /** Sheets bigger than this (in characters) are compressed. */
    const val COMPRESS_OVER = 64_000

    /** Search text kept for a sheet page, at most. */
    const val MAX_SEARCH_TEXT = 100_000

    fun pack(json: String?): String? {
        if (json == null || json.length <= COMPRESS_OVER || json.startsWith(PREFIX)) return json
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return PREFIX + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun unpack(stored: String?): String? {
        if (stored == null || !stored.startsWith(PREFIX)) return stored
        return runCatching {
            val bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length))
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    /** A sheet as stored: its JSON, compressed when big. */
    fun store(sheet: SheetData): String = pack(sheet.encode())!!

    /** The search text kept for a sheet. */
    fun searchText(sheet: SheetData): String = SheetGrid.plainText(sheet).take(MAX_SEARCH_TEXT)
}
