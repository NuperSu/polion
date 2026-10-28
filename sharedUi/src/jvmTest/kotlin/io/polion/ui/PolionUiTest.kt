package io.polion.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.ActivityFilter
import io.polion.core.application.PortfolioApplication
import io.polion.core.model.ActivityId
import io.polion.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PolionUiTest {
    private class Host :
        PlatformActions,
        AutoCloseable {
        override val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        override val dataRevision = MutableStateFlow(0L)
        override val syncStatus = MutableStateFlow(SyncStatus())
        override val hasLock = false

        @Volatile var exportedName: String? = null

        @Volatile var exported: ByteArray? = null

        override fun saveFile(
            name: String,
            bytes: ByteArray,
            onResult: (String) -> Unit,
        ) {
            exportedName = name
            exported = bytes
            onResult("Exported")
        }

        override fun openFile(onResult: (ByteArray?) -> Unit) = onResult(null)

        override fun restoreBackup(
            bytes: ByteArray,
            password: String,
            onResult: (String) -> Unit,
        ) = onResult("Not used")

        override fun refresh(onResult: (String) -> Unit) = onResult("Not used")

        override fun setBackgroundRefresh(enabled: Boolean) = Unit

        override fun backgroundRefreshEnabled() = false

        override fun lock() = Unit

        override fun unlock(
            password: String,
            onResult: (Boolean) -> Unit,
        ) = onResult(false)

        override fun setLockPassword(
            password: String,
            onResult: (String) -> Unit,
        ) = onResult("Not used")

        override fun removeLockPassword(onResult: (String) -> Unit) = onResult("Not used")

        override fun close() = dispatcher.close()
    }

    @Test
    fun phoneNavigationFitsTheViewport() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.seedDemo()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { PolionApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() &&
                            onAllNodesWithText("Overview").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("POLION / YOUR SPACE").assertIsDisplayed()
                    onAllNodesWithText("Activity").filter(hasClickAction()).onFirst().performClick()
                    onNodeWithText("Search activity").assertIsDisplayed()
                    val searchBounds = onNodeWithText("Search activity").fetchSemanticsNode().boundsInRoot
                    assertTrue(searchBounds.left >= 0f && searchBounds.right <= 360f && searchBounds.bottom <= 800f)
                    onAllNodesWithText("Accounts").filter(hasClickAction()).onFirst().performClick()
                    onNodeWithText("Watch-only accounts").assertIsDisplayed()
                    val image = onRoot().captureToImage()
                    assertEquals(360, image.width)
                    assertEquals(800, image.height)
                }
            }
        }
    }

    @Test
    fun syncProgressAndSettingsRemainVisibleWhenDatabaseDispatcherIsBusy() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.seedDemo()
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                try {
                    runDesktopComposeUiTest(width = 800, height = 900) {
                        setContent { PolionApp(app, host) }
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() &&
                                onAllNodesWithText("Not synced yet").fetchSemanticsNodes().isNotEmpty()
                        }
                        host.dispatcher.dispatch(
                            EmptyCoroutineContext,
                            Runnable {
                                started.countDown()
                                release.await()
                            },
                        )
                        assertTrue(started.await(3, TimeUnit.SECONDS))
                        host.syncStatus.value = SyncStatus(SyncPhase.SYNCING, "Reading page 3")
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Reading page 3").fetchSemanticsNodes().isNotEmpty() }
                        onNode(
                            hasText("Syncing…") and
                                androidx.compose.ui.test
                                    .isNotEnabled(),
                        ).assertIsNotEnabled()
                        onNodeWithText("Details →").performClick()
                        onNodeWithText("Sources & sync").assertIsDisplayed()
                        onNodeWithText("Esplora API endpoint").assertIsDisplayed()
                        onNodeWithText("https://blockstream.info/api").assertIsDisplayed()
                        onNodeWithText("Sync in progress…").assertIsNotEnabled()
                    }
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun mediumLayoutRecordsAndPersistsManualActivity() {
        val path = Files.createTempFile("polion-ui", ".sqlite")
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                Host().use { host ->
                    val app = PortfolioApplication(db) { System.currentTimeMillis() }
                    app.seedDemo()
                    runDesktopComposeUiTest(width = 800, height = 900) {
                        setContent { PolionApp(app, host) }
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() &&
                                onAllNodesWithText("Overview").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithText("YOUR FINANCIAL SPACE").assertIsDisplayed()
                        onAllNodesWithText("Activity").filter(hasClickAction()).onFirst().performClick()
                        onNodeWithText("+ Add").performClick()
                        onNodeWithText("Bitcoin quantity (e.g. 0.025 or -0.01)").performTextInput("0.125")
                        onNodeWithText("Label (optional)").performTextInput("UI deposit")
                        onNodeWithText("Record locally").performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("UI deposit").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithText("UI deposit").assertIsDisplayed()
                        onAllNodesWithText("Settings").filter(hasClickAction()).onFirst().performClick()
                        onNodeWithTag("page-content").performScrollToNode(hasText("Manage manual prices →"))
                        onNodeWithText("Manage manual prices →").performClick()
                        onNodeWithText("Price per whole coin in USD").performTextInput("70000")
                        onNodeWithText("Save price observation").performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            app
                                .snapshot()
                                .valuations
                                .single()
                                .price
                                ?.price
                                ?.value
                                ?.toString() == "70000"
                        }
                    }
                }
            }
            JvmSqliteDatabase(path.toString()).use { db ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                assertEquals(1, app.snapshot(ActivityFilter(search = "UI deposit")).activityTotal)
                assertEquals(
                    "70000",
                    app
                        .snapshot()
                        .valuations
                        .single()
                        .price
                        ?.price
                        ?.value
                        ?.toString(),
                )
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun shortLandscapeWindowKeepsEveryNavigationDestinationReachable() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                runDesktopComposeUiTest(width = 800, height = 360) {
                    setContent { PolionApp(app, host) }
                    onAllNodesWithText("Settings")
                        .filter(hasClickAction())
                        .onFirst()
                        .performScrollTo()
                        .performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Make it yours").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Make it yours").performScrollTo().assertIsDisplayed()
                    onAllNodesWithText("Overview")
                        .filter(hasClickAction())
                        .onFirst()
                        .performScrollTo()
                        .performClick()
                    onNodeWithText("PORTFOLIO VALUE").performScrollTo().assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun phoneCanRemoveAndRestoreAnAccount() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.seedDemo()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { PolionApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() }
                    onAllNodesWithText("Accounts").filter(hasClickAction()).onFirst().performClick()
                    onNodeWithText("Remove account").performScrollTo().performClick()
                    onAllNodesWithText("Remove account").onLast().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Archived accounts").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(0, app.snapshot().activityTotal)
                    onNodeWithText("Restore account").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Account restored").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(4, app.snapshot().activityTotal)
                }
            }
        }
    }

    @Test
    fun wideLayoutEditsSearchesAndExports() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.seedDemo()
                runDesktopComposeUiTest(width = 1440, height = 1000) {
                    setContent { PolionApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() &&
                            onAllNodesWithText("Overview").fetchSemanticsNodes().isNotEmpty()
                    }
                    onAllNodesWithText("Activity").filter(hasClickAction()).onFirst().performClick()
                    onNodeWithText("Search activity").performTextInput("demo-deposit")
                    waitUntil(
                        timeoutMillis = 10_000,
                    ) { onAllNodesWithText("1 activities", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    onNode(hasText("Deposit") and hasText("+1.25 BTC")).performClick()
                    onNodeWithText("Label").performTextInput("Reviewed deposit")
                    onNodeWithText("Save correction").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Reviewed deposit").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals("Reviewed deposit", app.overlay(ActivityId("demo-deposit"))?.label)
                    onNodeWithText("Search activity").performTextReplacement("Reviewed deposit")
                    waitUntil(
                        timeoutMillis = 10_000,
                    ) { onAllNodesWithText("1 activities", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    onAllNodesWithText("Reports").filter(hasClickAction()).onFirst().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("↓  Export exact activity CSV"))
                    onNodeWithText("↓  Export exact activity CSV").performClick()
                    waitUntil(timeoutMillis = 10_000) { host.exported != null }
                    assertEquals("polion-activities.csv", host.exportedName)
                    assertTrue(host.exported!!.decodeToString().contains("Reviewed deposit"))
                    val image = onRoot().captureToImage()
                    assertEquals(1440, image.width)
                    assertEquals(1000, image.height)
                }
            }
        }
    }
}
