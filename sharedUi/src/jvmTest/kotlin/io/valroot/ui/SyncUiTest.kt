package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncUiTest {
    @Test
    fun bannerNamesTheActualBackgroundOperation() {
        val status = androidx.compose.runtime.mutableStateOf(SyncStatus(SyncPhase.SYNCING, "Reading prices", SyncOperation.PRICES))
        runDesktopComposeUiTest(width = 360, height = 400) {
            setContent { ValrootTheme(AppearanceTheme.LIGHT) { SyncBanner(status.value, null) {} } }
            onNodeWithText("Price import in progress").assertIsDisplayed()
            onNodeWithText("History is being fetched. Saved balances update after the full scan is checked.").assertDoesNotExist()
            runOnIdle { status.value = SyncStatus(SyncPhase.SUCCESS, "Prices saved", SyncOperation.PRICES) }
            onNodeWithText("Price import complete").assertIsDisplayed()
            onNodeWithText("Sync complete").assertDoesNotExist()
            runOnIdle { status.value = SyncStatus(SyncPhase.ERROR, "Order unavailable", SyncOperation.ORDER) }
            onNodeWithText("Order verification needs attention").assertIsDisplayed()
            runOnIdle { status.value = SyncStatus(SyncPhase.SYNCING, "Reading history", SyncOperation.HISTORY) }
            onNodeWithText("Syncing…").assertIsDisplayed()
            onNodeWithText("History is being fetched. Saved balances update after the full scan is checked.").assertIsDisplayed()
        }
    }

    @Test
    fun dataHealthOpensUnknownActiveScopeEvidence() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 800, height = 900) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Accounts").onLast().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Review UTXO comparison →").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("Review UTXO comparison →").performScrollTo().performClick()
                    onNodeWithText("UTXO comparison details").assertIsDisplayed()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText(
                            "Evidence unknown for this active scope. Refresh to obtain a comparison; older backups may contain no graph evidence.",
                        ).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("Close comparison").performClick()
                    onNodeWithText("Review UTXO comparison →").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun syncProgressAndSettingsRemainVisibleWhenDatabaseDispatcherIsBusy() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                try {
                    runDesktopComposeUiTest(width = 800, height = 900) {
                        setContent { ValrootApp(app, host) }
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty()
                        }
                        openAdvancedTools()
                        onNodeWithTag("workspace-menu").performClick()
                        onAllNodesWithText("Accounts").onLast().performClick()
                        onNodeWithText("Not synced yet").assertIsDisplayed()
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
                        onNodeWithText("Syncing…").assertIsDisplayed()
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
    fun fullHistoryRecoveryRequiresConsentAndExplicitConfirmation() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                host.syncStatus.value = SyncStatus(SyncPhase.ERROR, "A full history recovery is required.")
                val data = app.snapshot()
                runDesktopComposeUiTest(width = 800, height = 900) {
                    setContent {
                        WorkflowDialog("sync", data, app, host, {}, { _, operation -> operation() }, {}, { _, _ -> })
                    }
                    onNodeWithTag("recover-bitcoin-history").performScrollTo().assertIsNotEnabled()
                    // The failure is shown in the sync details and in the account status; either match is the displayed error.
                    onAllNodesWithText("A full history recovery is required.").onFirst().performScrollTo().assertIsDisplayed()
                    onNodeWithTag("bitcoin-recovery-consent").performScrollTo().performClick()
                    onNodeWithTag("recover-bitcoin-history").performScrollTo().assertIsNotEnabled()
                    onNodeWithText("I allow public address queries to this endpoint").performScrollTo()
                    onNodeWithTag("bitcoin-source-consent").performClick()
                    onNodeWithTag("recover-bitcoin-history").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { host.recoveryRequests == 1 }
                    assertEquals(0, host.refreshRequests)
                    assertTrue(app.sources.consent())
                    host.syncStatus.value = SyncStatus(SyncPhase.SYNCING, "Reading page 1")
                    onNodeWithTag("recover-bitcoin-history").performScrollTo().assertIsNotEnabled()
                }
            }
        }
    }
}
