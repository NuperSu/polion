package io.valroot.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.AssetId
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ModelWorkspaceRestorationTest {
    @Test
    fun detailTabAndBackSurviveHostRecreation() {
        JvmSqliteDatabase(":memory:").use { db ->
            RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                val visible = mutableStateOf(true)
                var registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
                runDesktopComposeUiTest(width = 600, height = 900) {
                    setContent {
                        if (visible.value) {
                            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { ValrootApp(app, host) }
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-PORTFOLIO").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("coin-BTC").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("coin-BTC").performScrollTo().performClick()
                    onNodeWithTag("detail-Transactions").performScrollTo().performClick()
                    onNodeWithTag("coin-transaction-search").performScrollTo().performTextInput("demo-payment")
                    var saved = emptyMap<String, List<Any?>>()
                    runOnIdle {
                        saved = registry.performSave()
                        visible.value = false
                    }
                    runOnIdle {
                        registry = SaveableStateRegistry(saved, canBeSaved = { true })
                        visible.value = true
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("detail-Transactions").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("demo-payment").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("model-back").performClick()
                    onNodeWithTag("coin-BTC").performScrollTo().assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun incompleteTradeDraftSurvivesCloseAndHostRecreationWithoutSubmission() {
        JvmSqliteDatabase(":memory:").use { db ->
            RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                val before = app.snapshot().activityTotal
                val visible = mutableStateOf(true)
                var registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
                runDesktopComposeUiTest(width = 600, height = 900) {
                    setContent {
                        if (visible.value) {
                            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { ValrootApp(app, host) }
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-PORTFOLIO").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("add-transaction").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("add-transaction").performClick()
                    onNodeWithTag("choose-manual").performScrollTo().performClick()
                    onNodeWithText("Sell").performClick()
                    onNodeWithText("Amount (Sell)").performScrollTo().performTextInput("1.25")
                    onNodeWithTag("preview-save-transaction").assertIsNotEnabled()
                    onNodeWithContentDescription("Close").performClick()
                    onNodeWithTag("add-transaction").performClick()
                    onNodeWithTag("choose-manual").performScrollTo().performClick()
                    onNodeWithText("1.25").performScrollTo().assertIsDisplayed()
                    var saved = emptyMap<String, List<Any?>>()
                    runOnIdle {
                        saved = registry.performSave()
                        visible.value = false
                    }
                    runOnIdle {
                        registry = SaveableStateRegistry(saved, canBeSaved = { true })
                        visible.value = true
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("preview-save-transaction").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("1.25").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("preview-save-transaction").assertIsNotEnabled()
                    kotlin.test.assertEquals(before, app.snapshot().activityTotal)
                }
            }
        }
    }

    @Test
    fun portfolioQuoteAndRangeSurviveHostRecreation() {
        JvmSqliteDatabase(":memory:").use { db ->
            RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                app.savePortfolio(
                    io.valroot.core.ownership
                        .Portfolio(
                            io.valroot.core.model
                                .PortfolioId("euro-test"),
                            "Euro test",
                            "EUR",
                        ),
                )
                val visible = mutableStateOf(true)
                var registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
                runDesktopComposeUiTest(width = 600, height = 900) {
                    setContent {
                        if (visible.value) {
                            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { ValrootApp(app, host) }
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-PORTFOLIO").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    onNodeWithText("All portfolios").performClick()
                    onNodeWithText("Euro test").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("EUR").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("1W").performClick().assertIsSelected()
                    var saved = emptyMap<String, List<Any?>>()
                    runOnIdle {
                        saved = registry.performSave()
                        visible.value = false
                    }
                    runOnIdle {
                        registry = SaveableStateRegistry(saved, canBeSaved = { true })
                        visible.value = true
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("EUR").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Euro test").assertIsDisplayed()
                    onNodeWithText("1W").assertIsSelected()
                }
            }
        }
    }

    @Test
    fun advancedActivityBackRestoresCoinTabAndEarlierActivitySearch() {
        JvmSqliteDatabase(":memory:").use { db ->
            RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                runDesktopComposeUiTest(width = 600, height = 900) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    onNodeWithTag("more-ACTIVITY").performClick()
                    onNodeWithText("Search activity").performTextInput("demo-deposit")
                    onNodeWithText("Back").performClick()
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("coin-BTC").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("coin-BTC").performScrollTo().performClick()
                    onNodeWithTag("detail-Transactions").performScrollTo().performClick()
                    onNodeWithText("Search transactions").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("model-back").performClick()
                    openAdvancedTools()
                    onNodeWithTag("more-ACTIVITY").performClick()
                    onNodeWithText("demo-deposit").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun openMarketOnlyDetailUpdatesWhenSavedPriceChanges() {
        JvmSqliteDatabase(":memory:").use { db ->
            RestorationHost().use { host ->
                var now = 1_800_000_000_000L
                val app = PortfolioApplication(db) { now }
                app.updateOnboarding(OnboardingAction.Skip)
                val btc = AssetId("bitcoin:btc")

                fun savePrice(
                    id: String,
                    value: String,
                ) {
                    app.recordManualPrice(
                        io.valroot.core.market.PriceObservation(
                            id,
                            io.valroot.core.model
                                .Price(
                                    btc,
                                    AssetId("USD"),
                                    io.valroot.core.model.ExactDecimal
                                        .parse(value),
                                ),
                            null,
                            now,
                            now,
                            true,
                        ),
                    )
                }
                savePrice("first-price", "64000")
                app.setMarketFavorites(setOf(btc))
                runDesktopComposeUiTest(width = 600, height = 900) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("BTC").fetchSemanticsNodes().isNotEmpty() }
                    onAllNodesWithText("BTC").onFirst().performClick()
                    onNodeWithTag("coin-unit-price").assertTextEquals("$64,000.00")
                    runOnIdle {
                        now += 1_000
                        savePrice("second-price", "65000")
                        host.dataRevision.value++
                    }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithTag("coin-unit-price").fetchSemanticsNodes().any {
                            it.config[SemanticsProperties.Text].any { text -> text.text == "$65,000.00" }
                        }
                    }
                    onNodeWithTag("coin-unit-price").assertTextEquals("$65,000.00")
                }
            }
        }
    }

    @Test
    fun escapeReturnsFromCoinDetailToPortfolio() {
        JvmSqliteDatabase(":memory:").use { db ->
            RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                runDesktopComposeUiTest(width = 600, height = 900) {
                    setContent { ValrootApp(app, host) }
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("coin-BTC").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("coin-BTC").performScrollTo().performClick()
                    onNodeWithTag("detail-Transactions").performScrollTo().performClick().performKeyInput {
                        keyDown(Key.Escape)
                        keyUp(Key.Escape)
                    }
                    onNodeWithTag("coin-BTC").performScrollTo().assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun modelRefreshSuppressesDuplicateRequestsWhileQueuedOrRunning() {
        JvmSqliteDatabase(":memory:").use { db ->
            RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                app.setAutomaticPriceConsent(true)
                val before = app.snapshot().valuations
                runDesktopComposeUiTest(width = 600, height = 900) {
                    setContent { ValrootApp(app, host) }
                    onNodeWithTag("model-refresh").performClick()
                    waitUntil(timeoutMillis = 10_000) { host.refreshCalls == 1 }
                    onNodeWithTag("model-refresh").assertIsNotEnabled()
                    runOnIdle { host.priceRefreshStatus.value = PriceRefreshPhase.RUNNING }
                    onNodeWithTag("model-refresh").assertIsNotEnabled()
                    kotlin.test.assertEquals(1, host.refreshCalls)
                    kotlin.test.assertEquals(before, app.snapshot().valuations)
                }
            }
        }
    }

    @Test
    fun keyboardTraversesAndActivatesDesktopNavigation() {
        JvmSqliteDatabase(":memory:").use { db ->
            RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                runDesktopComposeUiTest(width = 1200, height = 900) {
                    setContent { ValrootApp(app, host) }
                    onNodeWithTag("model-MAIN").performSemanticsAction(SemanticsActions.RequestFocus)
                    onNodeWithTag("model-MAIN").assertIsFocused().performKeyInput {
                        keyDown(Key.Tab)
                        keyUp(Key.Tab)
                    }
                    onNodeWithTag("model-PORTFOLIO").assertIsFocused().performKeyInput {
                        keyDown(Key.Enter)
                        keyUp(Key.Enter)
                    }
                    onNodeWithTag("model-PORTFOLIO").assertIsSelected().performKeyInput {
                        keyDown(Key.ShiftLeft)
                        keyDown(Key.Tab)
                        keyUp(Key.Tab)
                        keyUp(Key.ShiftLeft)
                    }
                    onNodeWithTag("model-MAIN").assertIsFocused().performKeyInput {
                        keyDown(Key.Enter)
                        keyUp(Key.Enter)
                    }
                    onNodeWithTag("model-MAIN").assertIsSelected()
                }
            }
        }
    }

    @Test
    fun activityAccountKindAndStatusSurviveHostRecreation() {
        JvmSqliteDatabase(":memory:").use { db ->
            RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                val visible = mutableStateOf(true)
                var registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
                runDesktopComposeUiTest(width = 800, height = 900) {
                    setContent {
                        if (visible.value) {
                            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { ValrootApp(app, host) }
                        }
                    }
                    openAdvancedTools()
                    onNodeWithTag("more-ACTIVITY").performClick()
                    onNode(hasText("Deposit") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
                        .performScrollTo()
                        .performClick()
                    onNodeWithText("All accounts").performScrollTo().performClick()
                    onNodeWithText("Example wallet").performClick()
                    onNodeWithTag("activity-status-filter").performScrollTo().performClick()
                    onNodeWithTag("status-PENDING").performClick()
                    var saved = emptyMap<String, List<Any?>>()
                    runOnIdle {
                        saved = registry.performSave()
                        visible.value = false
                    }
                    runOnIdle {
                        registry = SaveableStateRegistry(saved, canBeSaved = { true })
                        visible.value = true
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("activity-status-filter").fetchSemanticsNodes().isNotEmpty() }
                    onNode(hasText("Deposit") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
                        .performScrollTo()
                        .assertIsSelected()
                    onNodeWithText("Example wallet").performScrollTo().assertIsDisplayed()
                    onNodeWithText("Status: Pending ▾").performScrollTo().assertIsDisplayed()
                }
            }
        }
    }

    internal class RestorationHost :
        PlatformActions,
        AutoCloseable {
        override val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        override val dataRevision = MutableStateFlow(0L)
        override val syncStatus = MutableStateFlow(SyncStatus())
        override val priceRefreshStatus = MutableStateFlow(PriceRefreshPhase.IDLE)
        override val hasLock = false

        var preferences = RefreshPreferences()
        var refreshCalls = 0

        override fun refreshPreferences() = preferences

        override fun updateRefreshPreferences(preferences: RefreshPreferences) {
            this.preferences = preferences
        }

        @Volatile var priceRequests = 0

        @Volatile var recoveryRequests = 0

        @Volatile var refreshRequests = 0

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

        override fun saveBackup(
            password: String,
            onResult: (String) -> Unit,
        ) = onResult("Not used")

        override fun restoreBackup(
            password: String,
            onResult: (String) -> Unit,
        ) = onResult("Not used")

        override fun verifyAccountingOrder(onResult: (String) -> Unit) = onResult("Not used")

        override fun discoverWallet(
            walletId: io.valroot.core.model.TrackingTargetId,
            onResult: (String) -> Unit,
        ) = onResult("Not used")

        override fun fetchHistoricalPrice(
            asset: io.valroot.core.model.AssetId,
            quote: io.valroot.core.model.AssetId,
            dayUtcMillis: Long,
            apiKey: String,
            onResult: (String) -> Unit,
        ) {
            priceRequests++
            onResult("Daily historical price saved from CoinGecko.")
        }

        override fun fetchPriceHistory(
            asset: AssetId,
            quote: AssetId,
            days: Int,
            apiKey: String,
            onResult: (String) -> Unit,
        ) {
            priceRequests++
            onResult("Saved Bitcoin price history.")
        }

        override fun refreshAutomaticPrices(onResult: (String) -> Unit) {
            refreshCalls++
            priceRefreshStatus.value = PriceRefreshPhase.QUEUED
            onResult("Price refresh queued.")
        }

        override fun refresh(onResult: (String) -> Unit) {
            refreshRequests++
            onResult("Not used")
        }

        override fun recoverBitcoinHistory(onResult: (String) -> Unit) {
            recoveryRequests++
            onResult("Recovery started")
        }

        override fun setBackgroundRefresh(enabled: Boolean) = Unit

        override fun backgroundRefreshEnabled() = false

        override fun lock() = Unit

        override fun unlock(
            password: String,
            onResult: (UnlockResult) -> Unit,
        ) = onResult(UnlockResult(false))

        override fun setLockPassword(
            password: String,
            onResult: (String) -> Unit,
        ) = onResult("Not used")

        override fun removeLockPassword(onResult: (String) -> Unit) = onResult("Not used")

        override fun close() = dispatcher.close()
    }
}
