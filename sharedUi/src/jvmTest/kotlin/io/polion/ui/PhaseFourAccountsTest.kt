package io.polion.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import io.polion.core.application.PortfolioApplication
import io.polion.core.model.TrackingTargetId
import io.polion.core.onboarding.OnboardingAction
import io.polion.core.storage.JvmSqliteDatabase
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
                            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                                PolionTheme(
                                    AppearanceTheme.DARK,
                                ) { BitcoinConnectDialog(app, host, app.snapshot(), null, { closed = true }, {}, {}) }
                            }
                        }
                        onNodeWithTag("connect-review").assertIsNotEnabled()
                        onNodeWithTag("connect-name").performScrollTo().performTextInput("My Bitcoin account")
                        onNodeWithTag("connect-expression").performScrollTo().performTextInput("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
                        onNodeWithTag("connect-consent").performScrollTo().performClick()
                        onNodeWithTag("connect-review").performScrollTo().performClick()
                        onNodeWithTag("connect-confirm").performScrollTo()
                        val output = File("build/phase4-account-previews").apply { mkdirs() }
                        ImageIO.write(
                            onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(),
                            "png",
                            File(output, "${width}x$height-$fontScale-connect-review.png"),
                        )
                        onNodeWithTag("connect-confirm").performClick()
                        waitUntil(timeoutMillis = 10000) { host.discovery == 1 }
                        assertTrue(closed)
                        assertEquals(SyncPhase.SYNCING, host.syncStatus.value.phase)
                        val summary = app.accountOverview().single()
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
                app.seedDemo()
                app.updateOnboarding(OnboardingAction.Skip)
                app.setSourceConsent(true)
                val host = Host(delegate)
                val before = app.exportCsv()
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent { PolionApp(app, host) }
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("model-menu").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("model-menu").performClick()
                    onNodeWithText("Accounts").performClick()
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("account-demo-wallet").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("account-demo-wallet").performScrollTo().performClick()
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
                            .contains("polion:archived")
                    }
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("account-archive-action").fetchSemanticsNodes().isEmpty() }
                    onNodeWithTag("account-demo-wallet").performScrollTo().performClick()
                    onNodeWithTag("account-archive-action").performScrollTo().performClick()
                    onNodeWithTag("confirm-account-archive").performClick()
                    waitUntil(timeoutMillis = 10000) {
                        !app
                            .snapshot()
                            .ownership.accounts
                            .single()
                            .tags
                            .contains("polion:archived")
                    }
                    onNodeWithTag("account-demo-wallet").performScrollTo().performClick()
                    onNodeWithText("Review UTXOs").performScrollTo().performClick()
                    onNodeWithText("UTXO comparison details").assertIsDisplayed()
                    onNodeWithText("Close").performClick()
                    onNodeWithTag("accounts-refresh-history").performScrollTo().performClick()
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
                    assertEquals(before, app.exportCsv())
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
                                PolionTheme(
                                    AppearanceTheme.DARK,
                                ) { BitcoinHistoryDialog(app, host, data, SyncStatus(), {}, {}, {}) }
                            }
                        }
                    }
                    onNodeWithTag("history-consent").performScrollTo().performClick()
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
                    assertTrue(app.sourceConsent())
                }
            }
        }
    }

    @Test fun schedulingConsentAndPreferencesSurviveBackupAndReopen() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            app.setRefreshPreferences(true, true)
            app.setSourceConsent(true)
            app.setAutomaticPriceConsent(true)
            val backup = app.exportBackup("schedule-password".toCharArray())
            JvmSqliteDatabase(":memory:").use { restored ->
                app.restoreToStaging(restored, backup, "schedule-password".toCharArray())
                val copy = PortfolioApplication(restored) { 1000L }
                val preferences = RefreshPreferences(copy.automaticRefreshPreference() == true, copy.wifiOnlyRefreshPreference())
                assertEquals(
                    RefreshSchedulePlan(true, true, true),
                    refreshSchedulePlan(preferences, copy.sourceConsent(), copy.automaticPriceConsent()),
                )
                assertEquals(RefreshSchedulePlan(false, true, true), refreshSchedulePlan(preferences, false, true))
                assertEquals(
                    RefreshSchedulePlan(false, false, true),
                    refreshSchedulePlan(preferences.copy(automaticRefresh = false), true, true),
                )
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
