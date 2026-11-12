package io.valroot.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PhaseFourAccountsTest {
    @Test fun validatedConnectionAcknowledgmentIsNotAcceptedHistory() {
        for ((width, height, fontScale) in listOf(
            Triple(360, 800, 1f),
            Triple(390, 844, 1f),
            Triple(1440, 900, 1f),
            Triple(390, 844, 1.5f),
        )) {
            JvmSqliteDatabase(":memory:").use { db ->
                ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                    val app = PortfolioApplication(db) { 1_800_000_000_000L }
                    val host = Host(delegate)
                    var closed = false
                    runDesktopComposeUiTest(width = width, height = height) {
                        setContent {
                            val density = LocalDensity.current
                            val status by host.syncStatus.collectAsState()
                            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                                ValrootTheme(
                                    AppearanceTheme.DARK,
                                ) { BitcoinConnectDialog(app, host, app.snapshot(), null, { closed = true }, {}, {}, syncStatus = status) }
                            }
                        }
                        onNodeWithTag("connect-confirm").assertIsNotEnabled()
                        onNodeWithTag("connect-blocker").assertTextEquals("Enter an account or wallet name.")
                        onNodeWithTag("connect-name").performScrollTo().performTextInput("My Bitcoin account")
                        onNodeWithTag("connect-expression").performScrollTo().performTextInput("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
                        onNodeWithTag("connect-confirm").performScrollTo().assertIsEnabled()
                        val output = File("build/phase4-account-previews").apply { mkdirs() }
                        ImageIO.write(
                            onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(),
                            "png",
                            File(output, "${width}x$height-$fontScale-connect-ready.png"),
                        )
                        onNodeWithTag("connect-confirm").performClick()
                        waitUntil(timeoutMillis = 10000) { host.discovery == 1 }
                        waitForIdle()
                        // The dialog stays open and follows discovery; it closes only on a successful discovery status.
                        assertTrue(!closed)
                        onNodeWithTag("connect-status").assertTextEquals("Discovery queued")
                        onNodeWithTag("connect-confirm").assertIsNotEnabled()
                        host.syncStatus.value = SyncStatus(SyncPhase.SUCCESS, "Wallet discovery complete", SyncOperation.DISCOVERY)
                        waitUntil(timeoutMillis = 10000) { closed }
                        val summary = app.analytics.accountOverview().single()
                        assertEquals(
                            "addr(1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa)",
                            summary.sources
                                .single()
                                .identity.canonicalKey,
                        )
                        assertEquals(null, summary.accepted)
                        assertTrue(summary.holdings.isEmpty())
                    }
                }
            }
        }
    }

    @Test fun accountsArchiveRestoreAndDirectHistoryRecoveryRemainReachable() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.demo.seed()
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.sources.setConsent(true)
                val host = Host(delegate)
                val before = app.csv.export()
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("model-menu").fetchSemanticsNodes().isNotEmpty() }
                    openConnections()
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("connection-demo-wallet").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("connection-demo-wallet").performScrollTo().performClick()
                    onNodeWithTag("account-archive-action").performScrollTo()
                    val output = File("build/phase4-account-previews").apply { mkdirs() }
                    ImageIO.write(
                        onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(),
                        "png",
                        File(output, "390x844-1.0-account-details.png"),
                    )
                    onNodeWithTag("account-archive-action").performClick()
                    onNodeWithTag("confirm-account-archive").performClick()
                    waitUntil(timeoutMillis = 10000) {
                        app
                            .snapshot()
                            .ownership.accounts
                            .single()
                            .tags
                            .contains("valroot:archived")
                    }
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("account-archive-action").fetchSemanticsNodes().isEmpty() }
                    onNodeWithTag("connection-demo-wallet").performScrollTo().performClick()
                    onNodeWithTag("account-archive-action").performScrollTo().performClick()
                    onNodeWithTag("confirm-account-archive").performClick()
                    waitUntil(timeoutMillis = 10000) {
                        !app
                            .snapshot()
                            .ownership.accounts
                            .single()
                            .tags
                            .contains("valroot:archived")
                    }
                    onNodeWithTag("connection-demo-wallet").performScrollTo().performClick()
                    onNodeWithText("Review UTXOs").performScrollTo().performClick()
                    onNodeWithText("UTXO comparison details").assertIsDisplayed()
                    onNodeWithText("Close").performClick()
                    onNodeWithTag("connection-demo-wallet").performScrollTo().performClick()
                    onNodeWithTag("account-refresh-history").performScrollTo().performClick()
                    onNodeWithTag("history-recovery").performScrollTo().performClick()
                    assertEquals(0, host.recovery)
                    ImageIO.write(
                        onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(),
                        "png",
                        File(output, "390x844-1.0-recovery-confirmation.png"),
                    )
                    onNodeWithText("Cancel").performClick()
                    assertEquals(0, host.recovery)
                    onNodeWithTag("history-recovery").performScrollTo().performClick()
                    onNodeWithTag("history-recovery-confirm").performClick()
                    waitUntil(timeoutMillis = 10000) { host.recovery == 1 }
                    assertEquals(0, host.history)
                    assertEquals(before, app.csv.export())
                }
            }
        }
    }

    @Test fun historyRetryDraftAndConsentSurviveRecreation() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                db.execute("INSERT OR REPLACE INTO application_settings VALUES ('source_error', 'Fixture interruption')")
                val data = app.snapshot()
                val host = Host(delegate)
                val visible = mutableStateOf(true)
                var registry = SaveableStateRegistry(null) { true }
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent {
                        if (visible.value) {
                            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                                ValrootTheme(
                                    AppearanceTheme.DARK,
                                ) { BitcoinHistoryDialog(app, host, data, SyncStatus(), {}, {}, {}) }
                            }
                        }
                    }
                    var saved = emptyMap<String, List<Any?>>()
                    runOnIdle {
                        saved = registry.performSave()
                        visible.value = false
                    }
                    runOnIdle {
                        registry = SaveableStateRegistry(saved) { true }
                        visible.value = true
                    }
                    onNodeWithText("Retry wallet history").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("history-refresh").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10000) { host.history == 1 }
                    assertEquals(0, host.recovery)
                    assertTrue(app.sources.consent())
                }
            }
        }
    }

    @Test fun schedulingConsentAndPreferencesSurviveBackupAndReopen() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            app.settings.setRefreshPreferences(true, true)
            app.sources.setConsent(true)
            app.prices.setAutomaticConsent(true)
            val backup = app.backup.export("schedule-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { restored ->
                app.backup.restoreToStaging(restored, backup, "schedule-password".toCharArray())
                val copy = PortfolioApplication(restored) { 1000L }
                val preferences = RefreshPreferences(copy.settings.automaticRefresh() == true, copy.settings.wifiOnlyRefresh())
                assertEquals(
                    RefreshSchedulePlan(true, true, true),
                    refreshSchedulePlan(preferences, copy.sources.consent(), copy.prices.automaticConsent()),
                )
                assertEquals(RefreshSchedulePlan(false, true, true), refreshSchedulePlan(preferences, false, true))
                assertEquals(
                    RefreshSchedulePlan(false, false, true),
                    refreshSchedulePlan(preferences.copy(automaticRefresh = false), true, true),
                )
            }
        }
    }

    @Test fun connectDialogReportsDiscoveryFailureAndRetriesWithoutSavingAgain() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                val host = Host(delegate)
                var closed = false
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent {
                        val status by host.syncStatus.collectAsState()
                        ValrootTheme(AppearanceTheme.DARK) {
                            BitcoinConnectDialog(app, host, app.snapshot(), null, { closed = true }, {}, {}, syncStatus = status)
                        }
                    }
                    onNodeWithTag("connect-name").performScrollTo().performTextInput("Cold wallet")
                    onNodeWithTag("connect-expression").performScrollTo().performTextInput("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
                    onNodeWithTag("connect-confirm").performScrollTo().assertIsEnabled().performClick()
                    waitUntil(timeoutMillis = 10000) { host.discovery == 1 }
                    host.syncStatus.value =
                        SyncStatus(SyncPhase.ERROR, "Wallet discovery incomplete. Check the provider; retry.", SyncOperation.DISCOVERY)
                    waitForIdle()
                    onNodeWithTag("connect-status").assertTextEquals("Wallet discovery incomplete. Check the provider; retry.")
                    onNodeWithTag("connect-confirm")
                        .performScrollTo()
                        .assertTextEquals("Retry discovery")
                        .assertIsEnabled()
                        .performClick()
                    waitUntil(timeoutMillis = 10000) { host.discovery == 2 }
                    waitForIdle()
                    assertTrue(!closed)
                    assertEquals(1, app.analytics.accountOverview().size)
                }
            }
        }
    }

    private class Host(
        delegate: PlatformActions,
    ) : PlatformActions by delegate {
        override val syncStatus = MutableStateFlow(SyncStatus())
        var discovery = 0
        var history = 0
        var recovery = 0

        override fun discoverWallet(
            walletId: TrackingTargetId,
            onResult: (String) -> Unit,
        ) {
            discovery++
            syncStatus.value =
                SyncStatus(SyncPhase.SYNCING, "Discovery queued", SyncOperation.DISCOVERY)
            onResult("Request acknowledged")
        }

        override fun refresh(onResult: (String) -> Unit) {
            history++
            onResult("History requested")
        }

        override fun recoverBitcoinHistory(onResult: (String) -> Unit) {
            recovery++
            onResult("Recovery requested")
        }
    }
}
