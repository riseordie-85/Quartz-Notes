package com.quartz.notes.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.update.AppUpdater
import com.quartz.notes.data.update.UpdateState
import com.quartz.notes.ui.theme.QuartzTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app offering a newer build from the sync server, in Settings and on the home screen. */
@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<QuartzApp>()
    private lateinit var server: MockWebServer
    private lateinit var updater: AppUpdater

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        updater = AppUpdater(
            app,
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            serverUrl = { server.url("/").toString().trimEnd('/') },
            http = OkHttpClient(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun release(code: Long, name: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"versionCode":$code,"versionName":"$name","apk":"quartz-notes-$name.apk","size":10,"sha256":""}""")

    private fun show() {
        val settings = runBlocking { app.container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides app.container) {
                QuartzTheme(settings = settings, dark = false) {
                    Column {
                        UpdateBanner(updater = updater)
                        UpdateSection(signedIn = true, updater = updater)
                    }
                }
            }
        }
    }

    @Test
    fun offersANewerBuildAndAsksToAllowInstalling() {
        server.enqueue(release(updater.installedVersionCode + 1, "99.0"))
        show()
        // Settings checks when it opens.
        rule.waitUntil(10_000) { updater.state.value is UpdateState.Available }
        assertEquals("/app/version.json", server.takeRequest().path)
        rule.onAllNodesWithTextCount("Version 99.0 is ready", 2)
        // On a test device installing from the app isn't allowed yet, so it asks for that first.
        if (!updater.canInstall()) {
            updater.update()
            rule.waitUntil(10_000) { updater.state.value is UpdateState.NeedsPermission }
            rule.onAllNodesWithTextCount("Allow Quartz Notes to install updates", 2)
        }
    }

    @Test
    fun aNewVersionPopsUpOnceWithUpdateAndLater() {
        server.enqueue(release(updater.installedVersionCode + 1, "99.1"))
        val settings = runBlocking { app.container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides app.container) {
                QuartzTheme(settings = settings, dark = false) { UpdatePrompt(updater = updater) }
            }
        }
        updater.check()
        rule.waitUntil(10_000) { updater.state.value is UpdateState.Available }
        rule.onNodeWithText("Quartz Notes 99.1 is ready").assertExists()
        rule.onNodeWithText("Update").assertExists()
        rule.onNodeWithText("Later").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Quartz Notes 99.1 is ready").assertDoesNotExist()
        // Not again for the same version while the app runs.
        assertEquals(updater.installedVersionCode + 1, updater.promptedFor)
    }

    @Test
    fun saysUpToDateWhenTheServerHasThisVersion() {
        server.enqueue(release(updater.installedVersionCode, "same"))
        show()
        rule.waitUntil(10_000) { updater.state.value == UpdateState.UpToDate }
        rule.onNodeWithText("up to date", substring = true, useUnmergedTree = true).assertExists()
        // Nothing to offer on the home screen.
        rule.onNodeWithText("is ready", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun anOlderServerWithoutAppBuildsIsFine() {
        // It answers with the web app's page instead.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<!doctype html><title>Quartz</title>"))
        show()
        rule.waitUntil(10_000) { updater.state.value == UpdateState.UpToDate }
        rule.onNodeWithText("Check", useUnmergedTree = true).performClick()
    }
}

/** The banner and the Settings row both say it. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, count: Int) {
    waitForIdle()
    onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).assertCountEquals(count)
}
