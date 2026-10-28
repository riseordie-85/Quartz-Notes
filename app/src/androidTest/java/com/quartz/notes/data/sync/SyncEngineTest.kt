package com.quartz.notes.data.sync

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.db.QuartzDatabase
import com.quartz.notes.data.model.ConnectorData
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.model.encodePayload
import com.quartz.notes.data.rich.RichDoc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Two simulated devices syncing through a real PocketBase server with the repo's schema and rules.
 * CI starts the server on the emulator's host (10.0.2.2:8090) before these run.
 */
@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = ArrayList<QuartzDatabase>()
    private lateinit var auth: SyncAuth

    /** One simulated device: its own database, repository and sync engine, same account. */
    inner class Device {
        val db = QuartzDatabase.build(context, name = null).also { opened += it }
        val repo = QuartzRepository(db, scope)
        val store = MemorySyncStore(auth)
        val engine = SyncEngine(db, SyncClient(), store)

        fun sync(): SyncResult = runBlocking { engine.sync() }
        fun settle() = runBlocking { repo.getNote(-1) }
        fun notes() = runBlocking { db.quickNoteDao().getAll() }
        fun note(uid: String) = runBlocking { db.quickNoteDao().byUid(uid) }
    }

    @Before
    fun signUpTestUser() {
        auth = runBlocking { SyncClient().signIn(SERVER, createUser(), PASSWORD) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        opened.forEach { it.close() }
    }

    // ---- Tests ----

    @Test
    fun aNoteTravelsBothWays() {
        val a = Device()
        val b = Device()
        val id = runBlocking { a.repo.createNote() }
        a.repo.saveNote(id, "Groceries", RichDoc.fromPlainText("milk\neggs"), "mint")
        a.settle()
        val uid = runBlocking { a.db.quickNoteDao().get(id)!!.uid }
        assertTrue(a.sync().uploaded >= 1)

        b.sync()
        val onB = b.note(uid)
        assertNotNull("the note reached the second device", onB)
        assertEquals("Groceries", onB!!.title)
        assertEquals("mint", onB.colorKey)
        assertEquals(RichDoc.fromPlainText("milk\neggs"), RichDoc.decode(onB.content))

        b.repo.saveNote(onB.id, "Groceries!", RichDoc.fromPlainText("milk\neggs\nbread"), "mint")
        b.settle()
        b.sync()
        a.sync()
        assertEquals("Groceries!", a.note(uid)!!.title)
        assertEquals("milk\neggs\nbread", a.note(uid)!!.plainText)
        // Nothing left to upload on either side.
        assertTrue(runBlocking { a.db.syncDao().dirty() }.isEmpty())
        assertTrue(runBlocking { b.db.syncDao().dirty() }.isEmpty())
    }

    @Test
    fun deletingReachesTheOtherDevice() {
        val a = Device()
        val b = Device()
        val id = runBlocking { a.repo.createNote() }
        a.repo.saveNote(id, "Temporary", RichDoc.fromPlainText("x"), "default")
        a.settle()
        val uid = runBlocking { a.db.quickNoteDao().get(id)!!.uid }
        a.sync()
        b.sync()
        assertNotNull(b.note(uid))

        a.repo.deleteNote(id)
        a.settle()
        a.sync()
        b.sync()
        assertTrue("hidden on the second device", b.notes().none { it.uid == uid })
        assertEquals(true, b.note(uid)?.deleted)
    }

    @Test
    fun notebooksWithPagesItemsAndAttachments() {
        val a = Device()
        val b = Device()
        val shelfA = runBlocking { a.db.shelfDao().getAll().single() }
        val nb = runBlocking { a.repo.createNotebook(shelfA.id, "Trip", 0xFF3366CC.toInt(), "GLOSS", "plane") }
        runBlocking { a.repo.insertPage(nb, 0) }
        val pagesA = runBlocking { a.db.pageDao().getByNotebook(nb) }
        a.repo.savePageContent(pagesA[0].id, RichDoc.fromPlainText("Day one"))
        val s1 = runBlocking { a.repo.insertElement(PageElementEntity(pageId = pagesA[1].id, kind = ElementKind.SHAPE, x = 0.1f)) }
        val s2 = runBlocking { a.repo.insertElement(PageElementEntity(pageId = pagesA[1].id, kind = ElementKind.SHAPE, x = 0.6f)) }
        a.repo.addElement(
            PageElementEntity(pageId = pagesA[1].id, kind = ElementKind.CONNECTOR, data = ConnectorData(from = s1, to = s2).encodePayload()),
        )
        val noteId = runBlocking { a.repo.createNote() }
        a.repo.saveNote(noteId, "With a picture", RichDoc(), "default")
        a.repo.addNoteImage(noteId, "media/pic.jpg", 640, 480)
        a.settle()
        a.sync()
        b.sync()

        // B's own starting shelf became A's, rather than a second "Notebooks".
        val shelvesB = runBlocking { b.db.shelfDao().getAll() }
        assertEquals(1, shelvesB.size)
        assertEquals(shelfA.uid, shelvesB.single().uid)

        val nbB = runBlocking { b.db.notebookDao().getAll() }.single { it.title == "Trip" }
        assertEquals("plane", nbB.icon)
        assertEquals(shelvesB.single().id, nbB.shelfId)
        val pagesB = runBlocking { b.db.pageDao().getByNotebook(nbB.id) }
        assertEquals(pagesA.map { it.uid }, pagesB.map { it.uid })
        assertEquals("Day one", pagesB[0].plainText)

        val itemsB = runBlocking { b.db.pageElementDao().getForPage(pagesB[1].id) }
        assertEquals(3, itemsB.size)
        val connector = itemsB.single { it.kind == ElementKind.CONNECTOR }
        val cd = decodePayload<ConnectorData>(connector.data)!!
        val shapeUids = runBlocking { listOf(a.db.pageElementDao().uidOf(s1), a.db.pageElementDao().uidOf(s2)) }
        assertEquals(shapeUids, listOf(cd.from, cd.to).map { id -> itemsB.single { it.id == id }.uid })

        val noteB = b.notes().single { it.title == "With a picture" }
        val attachment = runBlocking { b.db.attachmentDao().getFor("NOTE", noteB.id) }.single()
        assertEquals("media/pic.jpg", attachment.path)
        assertEquals(640, attachment.width)
    }

    @Test
    fun theLaterEditWinsWhicheverDeviceSyncsFirst() {
        val a = Device()
        val b = Device()
        val id = runBlocking { a.repo.createNote() }
        a.repo.saveNote(id, "Start", RichDoc(), "default")
        a.settle()
        val uid = runBlocking { a.db.quickNoteDao().get(id)!!.uid }
        a.sync()
        b.sync()
        val idB = b.note(uid)!!.id

        // Older edit uploaded first, newer edit second.
        a.repo.saveNote(id, "Older edit (A)", RichDoc(), "default")
        a.settle()
        SystemClock.sleep(40)
        b.repo.saveNote(idB, "Newer edit (B)", RichDoc(), "default")
        b.settle()
        a.sync()
        assertEquals("the newer local edit is kept", 1, b.sync().conflicts)
        a.sync()
        assertEquals("Newer edit (B)", a.note(uid)!!.title)
        assertEquals("Newer edit (B)", b.note(uid)!!.title)

        // Newer edit uploaded first, older edit second.
        b.repo.saveNote(idB, "Older edit (B)", RichDoc(), "default")
        b.settle()
        SystemClock.sleep(40)
        a.repo.saveNote(id, "Newer edit (A)", RichDoc(), "default")
        a.settle()
        a.sync()
        assertEquals("the older local edit gives way", 1, b.sync().conflicts)
        a.sync()
        assertEquals("Newer edit (A)", a.note(uid)!!.title)
        assertEquals("Newer edit (A)", b.note(uid)!!.title)
    }

    @Test
    fun firstSyncMergesWhatBothDevicesHad() {
        val a = Device()
        val b = Device()
        val na = runBlocking { a.repo.createNote() }
        a.repo.saveNote(na, "From A", RichDoc(), "default")
        val nbA = runBlocking { a.repo.createNotebook(a.db.shelfDao().getAll().single().id, "A's notebook", 0, "GLOSS", "none") }
        val nb = runBlocking { b.repo.createNote() }
        b.repo.saveNote(nb, "From B", RichDoc(), "default")
        a.settle()
        b.settle()

        a.sync()
        b.sync()
        a.sync()
        for (d in listOf(a, b)) {
            assertEquals(setOf("From A", "From B"), d.notes().map { it.title }.toSet())
            assertEquals(1, runBlocking { d.db.shelfDao().getAll() }.size)
            assertEquals(listOf("A's notebook"), runBlocking { d.db.notebookDao().getAll() }.map { it.title })
        }
        assertNotNull(nbA)
    }

    @Test
    fun offlineIsReportedAndNothingIsLost() {
        val a = Device()
        val id = runBlocking { a.repo.createNote() }
        a.repo.saveNote(id, "Offline note", RichDoc(), "default")
        a.settle()
        runBlocking { a.store.setAuth(auth.copy(serverUrl = "http://10.0.2.2:1")) }
        try {
            a.sync()
            fail("should have failed")
        } catch (e: SyncException.Offline) {
            // expected
        }
        assertEquals("Can't reach the sync server", a.store.lastError)
        assertTrue(runBlocking { a.db.syncDao().dirty() }.isNotEmpty())

        runBlocking { a.store.setAuth(auth) }
        a.sync()
        assertNull(a.store.lastError)
        assertTrue(runBlocking { a.db.syncDao().dirty() }.isEmpty())
    }

    @Test
    fun signingOutMarksEverythingForTheNextServer() {
        val a = Device()
        val id = runBlocking { a.repo.createNote() }
        a.repo.saveNote(id, "Keep", RichDoc(), "default")
        val gone = runBlocking { a.repo.createNote() }
        a.repo.saveNote(gone, "Gone", RichDoc(), "default")
        a.settle()
        val goneUid = runBlocking { a.db.quickNoteDao().get(gone)!!.uid }
        a.sync()
        a.repo.deleteNote(gone)
        a.settle()
        a.sync()
        assertEquals("a synced note leaves a deleted marker", true, a.note(goneUid)?.deleted)

        runBlocking { a.engine.signOut() }
        assertNull(runBlocking { a.store.auth() })
        assertEquals(listOf("Keep"), a.notes().map { it.title })
        assertNull("deleted markers are cleared", a.note(goneUid))
        val states = runBlocking { a.db.syncDao().dirty() }
        assertTrue(states.isNotEmpty())
        assertTrue("everything uploads again", states.all { it.rev == 0L })
    }

    @Test
    fun liveNotesStayHiddenWhileDeletedOnesArriveLater() = runBlocking {
        // A deleted note that a device never saw doesn't appear at all.
        val a = Device()
        val id = a.repo.createNote()
        a.repo.saveNote(id, "Brief", RichDoc(), "default")
        a.settle()
        a.sync()
        a.repo.deleteNote(id)
        a.settle()
        a.sync()
        val b = Device()
        b.sync()
        assertTrue(b.notes().none { it.title == "Brief" })
        assertTrue(b.db.quickNoteDao().getAll().isEmpty())
        assertTrue(b.repo.observeNotes().first().isEmpty())
    }

    // ---- Test server ----

    private fun createUser(): String {
        val http = OkHttpClient()
        val json = "application/json".toMediaType()
        fun post(path: String, body: String, token: String? = null): String {
            val req = Request.Builder().url("$SERVER$path").post(body.toRequestBody(json))
                .apply { if (token != null) header("Authorization", token) }.build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                check(r.isSuccessful) { "$path failed: ${r.code} $text" }
                return text
            }
        }
        val su = post(
            "/api/collections/_superusers/auth-with-password",
            buildJsonObject { put("identity", SU_EMAIL); put("password", SU_PASSWORD) }.toString(),
        )
        val token = Json.parseToJsonElement(su).jsonObject["token"]!!.jsonPrimitive.content
        val email = "sync-${UUID.randomUUID().toString().take(8)}@test.local"
        post(
            "/api/collections/users/records",
            buildJsonObject { put("email", email); put("password", PASSWORD); put("passwordConfirm", PASSWORD) }.toString(),
            token,
        )
        return email
    }

    companion object {
        const val SERVER = "http://10.0.2.2:8090"
        const val SU_EMAIL = "admin@test.local"
        const val SU_PASSWORD = "adminpass123"
        const val PASSWORD = "testpass123"
    }
}
