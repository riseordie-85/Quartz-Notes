package com.quartz.notes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Requests from widgets are carried through to the end, even when they take a moment (making a new note). */
class LaunchesFollowTest {
    @Test
    fun aSlowRequestFinishesAfterItsCleared() = runBlocking {
        Launches.pending.value = null
        val done = CompletableDeferred<LaunchRequest>()
        val follower = launch {
            Launches.follow { r ->
                delay(50) // the database making the note
                done.complete(r)
            }
        }
        yield()
        Launches.pending.value = LaunchRequest.NewNote
        assertEquals(LaunchRequest.NewNote, withTimeout(2_000) { done.await() })
        assertNull("taken, so it isn't done twice", Launches.pending.value)
        follower.cancel()
    }

    @Test
    fun oneWaitingBeforehandIsTakenToo() = runBlocking {
        // Opened cold from a widget: the request is there before the screen starts following.
        Launches.pending.value = LaunchRequest.OpenNote(7)
        val seen = mutableListOf<LaunchRequest>()
        val follower = launch { Launches.follow { seen += it } }
        withTimeout(2_000) { while (seen.isEmpty()) yield() }
        Launches.pending.value = LaunchRequest.QuickNotes
        withTimeout(2_000) { while (seen.size < 2) yield() }
        assertEquals(listOf(LaunchRequest.OpenNote(7), LaunchRequest.QuickNotes), seen)
        follower.cancel()
    }
}
