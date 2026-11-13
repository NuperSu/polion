package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.AssetId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.onboarding.OnboardingGoal
import io.valroot.core.onboarding.OnboardingPresentation
import io.valroot.core.onboarding.OnboardingStep
import io.valroot.core.sources.SourceWorkflow
import io.valroot.core.sources.SupportedSources
import io.valroot.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class OnboardingHandoffUiTest {
    @Test
    fun completingWithEachSupportedSourceOpensItsFormWithoutGrantingConsentOrCreatingRecords() {
        SupportedSources.choices.forEach { choice ->
            JvmSqliteDatabase(":memory:").use { db ->
                OfflineHost().use { host ->
                    val app = PortfolioApplication(db) { 1L }
                    app.settings.updateOnboarding(OnboardingAction.Next)
                    app.settings.updateOnboarding(OnboardingAction.Next)
                    app.settings.updateOnboarding(OnboardingAction.ToggleSource(choice.id))
                    app.settings.updateOnboarding(OnboardingAction.Next)
                    runDesktopComposeUiTest(width = 1000, height = 900) {
                        setContent { ValrootApp(app, host) }
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Ready at your pace").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithTag("onboarding").performScrollToNode(hasText("Continue with ${choice.title}"))
                        onNodeWithText("Continue with ${choice.title}").performClick()
                        val title =
                            when (choice.workflow) {
                                SourceWorkflow.BITCOIN_ADDRESS -> "Track an account"
                                SourceWorkflow.BITCOIN_PUBLIC_WALLET -> "Track a Bitcoin wallet"
                                SourceWorkflow.MANUAL_ACTIVITY -> "Record an activity"
                            }
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithText(title).assertIsDisplayed()
                        assertTrue(app.settings.onboarding()!!.completed)
                        assertEquals(setOf(choice.id), app.settings.onboarding()!!.sourceIds)
                        assertOfflineAndEmpty(app, host)
                        onNodeWithText(if (choice.workflow == SourceWorkflow.BITCOIN_PUBLIC_WALLET) "Cancel" else "Close")
                            .performScrollTo()
                            .performClick()
                        openAdvancedTools()
                        onNodeWithTag("workspace-menu").performClick()
                        onAllNodesWithText("Settings").onLast().performClick()
                        onNodeWithTag("destination-HOME").performClick()
                        onNodeWithText(title).assertDoesNotExist()
                        assertOfflineAndEmpty(app, host)
                    }
                }
            }
        }
    }

    @Test
    fun reopenedDatabaseResumesSkippedAnswersAndPersistsCompletionAcrossFreshAppInstances() {
        val directory = Files.createTempDirectory("valroot-onboarding-reopen")
        val path = directory.resolve("portfolio.db").toString()
        try {
            JvmSqliteDatabase(path).use { db ->
                OfflineHost().use { host ->
                    val app = PortfolioApplication(db) { 1L }
                    runDesktopComposeUiTest(width = 1000, height = 900) {
                        setContent { ValrootApp(app, host) }
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Set up your space").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithText("Continue").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Choose your interests").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithTag("onboarding-goal-BALANCES").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { OnboardingGoal.BALANCES in app.settings.onboarding()!!.goals }
                        onNodeWithTag("onboarding").performScrollToNode(hasText("Continue"))
                        onNodeWithText("Continue").performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Explore supported sources").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithTag("onboarding").performScrollToNode(hasTestTag("onboarding-source-bitcoin-public-wallet"))
                        onNodeWithTag("onboarding-source-bitcoin-public-wallet").performClick()
                        waitUntil(timeoutMillis = 10_000) { "bitcoin-public-wallet" in app.settings.onboarding()!!.sourceIds }
                        onNodeWithTag("onboarding").performScrollToNode(hasText("Guided conversation"))
                        onNodeWithText("Guided conversation").performClick()
                        waitUntil(timeoutMillis = 10_000) { app.settings.onboarding()!!.presentation == OnboardingPresentation.ASSISTED }
                        onNodeWithTag("onboarding").performScrollToNode(hasText("Skip setup for now"))
                        onNodeWithText("Skip setup for now").performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                        }
                        assertTrue(app.settings.onboarding()!!.skipped)
                        assertOfflineAndEmpty(app, host)
                    }
                }
            }
            JvmSqliteDatabase(path).use { db ->
                OfflineHost().use { host ->
                    val app = PortfolioApplication(db) { 2L }
                    runDesktopComposeUiTest(width = 1000, height = 900) {
                        setContent { ValrootApp(app, host) }
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                        }
                        openAdvancedTools()
                        onNodeWithTag("workspace-menu").performClick()
                        onAllNodesWithText("Settings").onLast().performClick()
                        onNodeWithTag("page-content").performScrollToNode(hasText("Resume setup"))
                        onNodeWithText("Resume setup").performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("How would you like to start tracking?").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithText("Guided conversation").assertIsSelected()
                        onNodeWithTag("onboarding").performScrollToNode(hasTestTag("onboarding-source-bitcoin-public-wallet"))
                        onNodeWithTag("onboarding-source-bitcoin-public-wallet").assertIsSelected()
                        assertEquals(setOf(OnboardingGoal.BALANCES), app.settings.onboarding()!!.goals)
                        assertEquals(OnboardingStep.SOURCES, app.settings.onboarding()!!.step)
                        assertFalse(app.settings.onboarding()!!.skipped)
                        onNodeWithTag("onboarding").performScrollToNode(hasText("Continue"))
                        onNodeWithText("Continue").performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Your space is ready. Where shall we begin?").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithTag("onboarding").performScrollToNode(hasTestTag("onboarding-finish"))
                        onNodeWithTag("onboarding-finish").performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                        }
                        assertOfflineAndEmpty(app, host)
                    }
                }
            }
            JvmSqliteDatabase(path).use { db ->
                OfflineHost().use { host ->
                    val app = PortfolioApplication(db) { 3L }
                    runDesktopComposeUiTest(width = 1000, height = 900) {
                        setContent { ValrootApp(app, host) }
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithTag("onboarding").assertDoesNotExist()
                        onNodeWithText("Track a Bitcoin wallet").assertDoesNotExist()
                        assertTrue(app.settings.onboarding()!!.completed)
                        assertEquals(setOf("bitcoin-public-wallet"), app.settings.onboarding()!!.sourceIds)
                        assertOfflineAndEmpty(app, host)
                    }
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun assertOfflineAndEmpty(
        app: PortfolioApplication,
        host: OfflineHost,
    ) {
        assertEquals(0, host.sideEffectRequests)
        assertFalse(app.sources.consent())
        assertFalse(app.prices.sourceConsent())
        val snapshot = app.snapshot()
        assertTrue(snapshot.ownership.owners.isEmpty())
        assertTrue(snapshot.ownership.accounts.isEmpty())
        assertTrue(snapshot.ownership.targets.isEmpty())
        assertFalse(snapshot.sampleData)
    }

    /** Any provider request or host mutation fails the test at its call site. */
    private class OfflineHost :
        PlatformActions,
        AutoCloseable {
        override val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        override val dataRevision = MutableStateFlow(0L)
        override val syncStatus = MutableStateFlow(SyncStatus())
        override val hasLock = false

        @Volatile var sideEffectRequests = 0
            private set

        private fun unexpected(): Nothing {
            sideEffectRequests++
            error("Setup must not request host side effects")
        }

        override fun saveFile(
            name: String,
            bytes: ByteArray,
            onResult: (String) -> Unit,
        ): Unit = unexpected()

        override fun saveBackup(
            password: String,
            onResult: (String) -> Unit,
        ): Unit = unexpected()

        override fun restoreBackup(
            password: String,
            onResult: (String) -> Unit,
        ): Unit = unexpected()

        override fun verifyAccountingOrder(onResult: (String) -> Unit): Unit = unexpected()

        override fun discoverWallet(
            walletId: TrackingTargetId,
            onResult: (String) -> Unit,
        ): Unit = unexpected()

        override fun fetchHistoricalPrice(
            asset: AssetId,
            quote: AssetId,
            dayUtcMillis: Long,
            apiKey: String,
            onResult: (String) -> Unit,
        ): Unit = unexpected()

        override fun refresh(onResult: (String) -> Unit): Unit = unexpected()

        override fun recoverBitcoinHistory(onResult: (String) -> Unit): Unit = unexpected()

        override fun setBackgroundRefresh(enabled: Boolean): Unit = unexpected()

        override fun backgroundRefreshEnabled() = false

        override fun lock(): Unit = unexpected()

        override fun unlock(
            password: String,
            onResult: (UnlockResult) -> Unit,
        ): Unit = unexpected()

        override fun setLockPassword(
            password: String,
            onResult: (String) -> Unit,
        ): Unit = unexpected()

        override fun removeLockPassword(onResult: (String) -> Unit): Unit = unexpected()

        override fun close() = dispatcher.close()
    }
}
