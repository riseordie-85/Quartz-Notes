package com.quartz.notes.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** One change pushed by the server: which collection, what happened, and the record. */
data class RealtimeEvent(val collection: String, val action: String, val record: JsonObject)

/**
 * The server's live change stream (PocketBase realtime): a long-lived connection that tells this
 * device the moment a record changes, so it can sync right away instead of waiting to check.
 */
class RealtimeClient(http: OkHttpClient = SyncClient.defaultHttp) {
    private val http = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /**
     * Connects and listens for changes in [collections]. [onOpen] runs once the subscription is in
     * place, [onClosed] when the connection ends for any reason (with the error, if there was one).
     * Close the result to stop.
     */
    fun listen(
        auth: SyncAuth,
        collections: List<String>,
        onEvent: (RealtimeEvent) -> Unit,
        onOpen: () -> Unit = {},
        onClosed: (Throwable?) -> Unit = {},
    ): Closeable {
        val url = auth.serverUrl.toHttpUrlOrNull()?.newBuilder()?.addPathSegments("api/realtime")?.build()
            ?: run {
                onClosed(IllegalArgumentException("That server address isn't valid"))
                return Closeable {}
            }
        val request = Request.Builder().url(url).header("Accept", "text/event-stream").build()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (type == "PB_CONNECT") {
                    val clientId = runCatching { Json.parseToJsonElement(data).jsonObject["clientId"]?.jsonPrimitive?.content }.getOrNull()
                    if (clientId == null || !subscribe(auth, clientId, collections)) {
                        eventSource.cancel()
                        onClosed(IllegalStateException("Couldn't subscribe to live changes"))
                        return
                    }
                    onOpen()
                    return
                }
                val topic = type ?: return
                val json = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
                val record = json["record"] as? JsonObject ?: return
                val action = json["action"]?.jsonPrimitive?.content.orEmpty()
                onEvent(RealtimeEvent(topic.substringBefore("/"), action, record))
            }

            override fun onClosed(eventSource: EventSource) = onClosed(null)

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                onClosed(t ?: IllegalStateException("Live changes stopped (${response?.code})"))
            }
        }
        val source = EventSources.createFactory(http).newEventSource(request, listener)
        return Closeable { source.cancel() }
    }

    /** Tells the server which collections this connection wants to hear about. */
    private fun subscribe(auth: SyncAuth, clientId: String, collections: List<String>): Boolean {
        val body = buildJsonObject {
            put("clientId", clientId)
            put("subscriptions", buildJsonArray { collections.forEach { add(kotlinx.serialization.json.JsonPrimitive("$it/*")) } })
        }
        val url = auth.serverUrl.toHttpUrlOrNull()?.newBuilder()?.addPathSegments("api/realtime")?.build() ?: return false
        val request = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", auth.token)
            .build()
        return runCatching { http.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }
}
