package com.quartz.notes.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The server's app/version.json, as the release workflow writes it. */
class AppReleaseTest {
    @Test
    fun readsWhatTheReleaseWorkflowWrites() {
        val r = AppRelease.parse("""{"versionCode":36,"versionName":"0.17.0","apk":"quartz-notes-0.17.0.apk","size":12345678,"sha256":"ABCDEF01"}""")
        assertEquals(AppRelease(36, "0.17.0", "quartz-notes-0.17.0.apk", 12345678, "abcdef01"), r)
    }

    @Test
    fun anythingElseIsNoRelease() {
        // An older server answers with the web app's page instead.
        assertNull(AppRelease.parse("<!doctype html><html></html>"))
        assertNull(AppRelease.parse("""{"versionName":"0.17.0","apk":"a.apk"}"""))
        assertNull(AppRelease.parse("""{"versionCode":36,"versionName":"0.17.0"}"""))
        // The file name stays inside /app.
        assertNull(AppRelease.parse("""{"versionCode":36,"apk":"../evil.apk"}"""))
        assertNull(AppRelease.parse("""{"versionCode":36,"apk":"x/y.apk"}"""))
    }
}
