package com.quartz.notes.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A signed-in account on a sync server. */
data class SyncAuth(val serverUrl: String, val token: String, val userId: String, val email: String)

/** Why a sync request failed. */
sealed class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The server couldn't be reached (offline, Tailscale off, server down). */
    class Offline(cause: Throwable) : SyncException("Can't reach the sync server", cause)

    /** The sign-in is no longer valid (password changed, account removed). */
    class SignedOut(message: String = "Signed out of the sync server") : SyncException(message)

    /** The record changed on the server since this device last saw it. */
    class Conflict : SyncException("Changed on another device")

    class NotFound : SyncException("Not found on the sync server")

    /** Any other refusal. [fields] lists the record fields the server complained about. */
    class Server(val status: Int, message: String, val fields: Set<String> = emptySet()) : SyncException(message)
}

/** One page of a record listing. */
data class RecordPage(val items: List<JsonObject>, val page: Int, val totalPages: Int)

/**
 * Talks to the PocketBase sync server's REST API. Every call runs on the IO dispatcher and throws
 * [SyncException] on failure.
 */
class SyncClient(private val http: OkHttpClient = defaultHttp) {

    /** Signs in with email and password. */
    suspend fun signIn(serverUrl: String, email: String, password: String): SyncAuth {
        val body = buildJsonObject {
            put("identity", email.trim())
            put("password", password)
        }
        val res = send(serverUrl, null, "POST", listOf("api", "collections", "users", "auth-with-password"), body = body)
        return authFrom(serverUrl, res)
    }

    /** Swaps a sign-in for a fresh one (they expire after 30 days). */
    suspend fun refresh(auth: SyncAuth): SyncAuth {
        val res = send(auth.serverUrl, auth.token, "POST", listOf("api", "collections", "users", "auth-refresh"))
        return authFrom(auth.serverUrl, res)
    }

    /** Records in [collection] changed at or after [since] (a server timestamp; "" for everything). */
    suspend fun changes(auth: SyncAuth, collection: String, since: String, page: Int, perPage: Int = 200): RecordPage {
        val query = buildList {
            if (since.isNotEmpty()) add("filter" to "updated >= '${since.replace("'", "")}'")
            add("sort" to "updated,id")
            add("page" to page.toString())
            add("perPage" to perPage.toString())
            add("skipTotal" to "false")
        }
        val res = send(auth.serverUrl, auth.token, "GET", listOf("api", "collections", collection, "records"), query = query)
        return RecordPage(
            items = res["items"]?.jsonArray?.map { it.jsonObject }.orEmpty(),
            page = res["page"]?.jsonPrimitive?.int ?: page,
            totalPages = res["totalPages"]?.jsonPrimitive?.int ?: 1,
        )
    }

    suspend fun get(auth: SyncAuth, collection: String, id: String): JsonObject =
        send(auth.serverUrl, auth.token, "GET", listOf("api", "collections", collection, "records", id))

    /** Creates a record. Throws [SyncException.Conflict] if one with that id already exists. */
    suspend fun create(auth: SyncAuth, collection: String, record: JsonObject): JsonObject = try {
        send(auth.serverUrl, auth.token, "POST", listOf("api", "collections", collection, "records"), body = record)
    } catch (e: SyncException.Server) {
        if (e.status == 400 && "id" in e.fields) throw SyncException.Conflict() else throw e
    }

    /** Updates a record changed from revision [baseRev]. Throws [SyncException.Conflict] if it moved on. */
    suspend fun update(auth: SyncAuth, collection: String, id: String, baseRev: Long, record: JsonObject): JsonObject {
        val body = JsonObject(record + ("baseRev" to kotlinx.serialization.json.JsonPrimitive(baseRev)))
        return send(auth.serverUrl, auth.token, "PATCH", listOf("api", "collections", collection, "records", id), body = body)
    }

    // ---- Plumbing ----

    private fun authFrom(serverUrl: String, res: JsonObject): SyncAuth {
        val record = res["record"]?.jsonObject ?: throw SyncException.Server(200, "Unexpected sign-in reply")
        return SyncAuth(
            serverUrl = serverUrl,
            token = res["token"]?.jsonPrimitive?.content ?: throw SyncException.Server(200, "Unexpected sign-in reply"),
            userId = record["id"]?.jsonPrimitive?.content.orEmpty(),
            email = record["email"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    private suspend fun send(
        serverUrl: String,
        token: String?,
        method: String,
        path: List<String>,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonObject? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val base = serverUrl.toHttpUrlOrNull() ?: throw SyncException.Server(0, "That server address isn't valid")
        val url: HttpUrl = base.newBuilder().apply {
            path.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .method(method, body?.toString()?.toRequestBody(JSON_TYPE) ?: if (method == "GET") null else ByteArray(0).toRequestBody())
            .apply { if (token != null) header("Authorization", token) }
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw SyncException.Offline(e)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
            when {
                r.isSuccessful -> json
                r.code == 401 -> throw SyncException.SignedOut()
                r.code == 403 && token != null -> throw SyncException.SignedOut()
                r.code == 404 -> throw SyncException.NotFound()
                r.code == 409 -> throw SyncException.Conflict()
                else -> throw SyncException.Server(r.code, describe(r.code, json), (json["data"] as? JsonObject)?.keys.orEmpty())
            }
        }
    }

    private fun describe(code: Int, json: JsonObject): String {
        val message = json["message"]?.jsonPrimitive?.content ?: "The sync server answered $code"
        val fields = (json["data"] as? JsonObject)?.keys?.joinToString(", ")
        return if (fields.isNullOrEmpty()) message else "$message ($fields)"
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()

        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Tidies a typed server address: adds https:// if there's no scheme, drops a trailing slash. */
        fun normalizeUrl(input: String): String {
            val t = input.trim().trimEnd('/')
            if (t.isEmpty()) return t
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }
    }
}

/** Reads a JSON value as plain text ("" for null). */
internal fun JsonElement?.text(): String = (this as? kotlinx.serialization.json.JsonPrimitive)?.let { if (it is kotlinx.serialization.json.JsonNull) "" else it.content }.orEmpty()
