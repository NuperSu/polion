package io.valroot.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.AssetId
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RewriteAcceptanceTest {
    @Test
    fun revisedPagesRemainReachableAcrossViewports() {
        for ((width, height, fontScale) in listOf(
            Triple(360, 800, 1f),
            Triple(390, 844, 1f),
            Triple(1440, 900, 1f),
            Triple(390, 844, 1.5f),
        )) {
            JvmSqliteDatabase(":memory:").use { db ->
                Host().use { host ->
                    val app = PortfolioApplication(db) { System.currentTimeMillis() }
                    app.updateOnboarding(OnboardingAction.Skip)
                    app.seedDemo()
                    runDesktopComposeUiTest(width = width, height = height) {
                        setContent {
                            val density = LocalDensity.current
                            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { ValrootApp(app, host) }
                        }

                        fun capture(page: String) {
                            val folder = File("build/phase1-production-previews").apply { mkdirs() }
                            ImageIO.write(
                                onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(),
                                "png",
                                File(folder, "${width}x$height-$fontScale-$page.png"),
                            )
                        }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-menu").fetchSemanticsNodes().isNotEmpty() }
                        openAdvancedTools()
                        onNodeWithTag("destination-HOME").performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("main-market").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithText("Saved coins").assertIsDisplayed()
                        onNodeWithText("PRICED VALUE").assertDoesNotExist()
                        capture("main")
                        onAllNodesWithContentDescription("to favorites", substring = true).onFirst().performClick()
                        // Favorites are saved on the database dispatcher, not the UI thread.
                        waitUntil(timeoutMillis = 10_000) { app.marketFavorites() == setOf(AssetId("bitcoin:btc")) }
                        assertEquals(app.marketFavorites(), PortfolioApplication(db) { System.currentTimeMillis() }.marketFavorites())
                        onNodeWithTag("page-content").performScrollToNode(hasText("Global market rankings are unavailable."))
                        onNodeWithText("Global market rankings are unavailable.").performScrollTo().assertIsDisplayed()
                        capture("main-discover")
                        onNodeWithTag("page-content").performScrollToNode(hasText("Top losers"))
                        capture("main-discover-bottom")
                        onNodeWithTag("destination-OVERVIEW").performClick()
                        onNodeWithText("PRICED VALUE").assertIsDisplayed()
                        capture("portfolio")
                        onNodeWithTag("add-action").performClick()
                        capture("add-method")
                        onNodeWithText("Bitcoin public wallet").performTouchInput { swipeUp() }
                        capture("add-method-bottom")
                        onNodeWithTag("source-choice-manual-activity").performScrollTo().performClick()
                        capture("manual-transaction")
                        onNodeWithText("Label (optional)").performScrollTo()
                        capture("manual-scrolled")
                        onNodeWithText("Record locally").performScrollTo()
                        capture("manual-bottom")
                        onNodeWithText("Close").performScrollTo().performClick()
                        onNodeWithTag("page-content").performScrollToNode(hasText("Bitcoin"))
                        capture("portfolio-assets")
                        onNodeWithText("Bitcoin").performClick()
                        onNodeWithTag("detail-General").assertIsDisplayed()
                        capture("coin-general")
                        onNodeWithTag("detail-Transactions").performScrollTo().performClick()
                        onNodeWithTag("detail-add-transaction").performScrollTo().assertIsDisplayed()
                        capture("coin-transactions")
                        onNodeWithText("Open in Activity").performScrollTo()
                        capture("coin-transactions-scrolled")
                        onNodeWithTag("detail-add-transaction").performScrollTo().performClick()
                        capture("coin-manual-transaction")
                        onNodeWithText("Close").performScrollTo().performClick()
                        onNodeWithText("Bitcoin").performClick()
                        onNodeWithTag("detail-Alerts").performScrollTo().performClick()
                        onNodeWithText(
                            "Price alerts are not available yet. Saved prices and manual observations are available in General.",
                        ).performScrollTo()
                            .assertIsDisplayed()
                        capture("coin-alerts")
                        onNodeWithTag("detail-Order book").performScrollTo().performClick()
                        capture("coin-order-book")
                        onNodeWithText("Close").performScrollTo().performClick()
                        onNodeWithTag("destination-REPORTS").performClick()
                        capture("insights")
                        onNodeWithTag("page-content").performScrollToNode(hasText("Asset allocation"))
                        onAllNodesWithText("Asset allocation").onFirst().assertIsDisplayed()
                        capture("insights-allocation")
                        onNodeWithTag("page-content").performScrollToNode(hasText("↓  Export all active activity CSV"))
                        capture("insights-export")
                        onNodeWithText("↓  Export all active activity CSV").performClick()
                        waitUntil(timeoutMillis = 10_000) { host.exportedName != null }
                        assertEquals("valroot-activities.csv", host.exportedName)
                        assertTrue(host.exported!!.isNotEmpty())
                        onNodeWithTag("workspace-menu").performClick()
                        capture("menu")
                        onNodeWithText("Accounts").performClick()
                        onNodeWithTag("page-content").performScrollToNode(hasText("Watch-only accounts"))
                        onNodeWithText("Watch-only accounts").assertIsDisplayed()
                        capture("accounts")
                        onNodeWithTag("workspace-menu").performClick()
                        onAllNodesWithText("Settings").onLast().performClick()
                        onNodeWithTag("automatic-refresh").performScrollTo().performClick()
                        onNodeWithTag("automatic-refresh-confirm").performClick()
                        onNodeWithTag("wifi-only-refresh").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { host.preferences == RefreshPreferences(true, true) }
                        capture("settings")
                        onNodeWithTag("page-content").performScrollToNode(hasTestTag("appearance-dark"))
                        capture("settings-appearance")
                    }
                }
            }
        }
    }

    @Test
    fun portfolioManualEntryValidatesAndPersistsExactQuantity() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                val before = app.snapshot().activityTotal
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-menu").fetchSemanticsNodes().isNotEmpty() }
                    openAdvancedTools()
                    onNodeWithTag("destination-HOME").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("main-market").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithTag("add-action").performClick()
                    onNodeWithTag("source-choice-manual-activity").performScrollTo().performClick()
                    onNodeWithText("Record locally").performScrollTo().performClick()
                    onNodeWithText("Enter a nonzero BTC quantity with up to 8 decimal places.").performScrollTo().assertIsDisplayed()
                    assertEquals(before, app.snapshot().activityTotal)
                    onNodeWithText("BTC quantity (positive or negative)").performScrollTo().performTextInput("0.00000001")
                    onNodeWithText("Label (optional)").performScrollTo().performTextInput("Acceptance one satoshi")
                    onNodeWithText("Record locally").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { app.snapshot().activityTotal == before + 1 }
                    assertEquals(
                        1,
                        app
                            .snapshot(
                                io.valroot.core.application
                                    .ActivityFilter(search = "Acceptance one satoshi"),
                            ).activityTotal,
                    )
                }
            }
        }
    }

    @Test
    fun savedMarketCoinWithoutHoldingOpensDetailWithoutInventingOwnership() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val now = System.currentTimeMillis()
                val app = PortfolioApplication(db) { now }
                app.updateOnboarding(OnboardingAction.Skip)
                app.snapshot()
                app.recordManualPrice(
                    io.valroot.core.market.PriceObservation(
                        "market-only-btc",
                        io.valroot.core.model.Price(
                            AssetId("bitcoin:btc"),
                            AssetId("USD"),
                            io.valroot.core.model.ExactDecimal
                                .parse("64000"),
                        ),
                        null,
                        now,
                        now,
                        true,
                    ),
                )
                assertTrue(app.snapshot().valuations.isEmpty())
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-menu").fetchSemanticsNodes().isNotEmpty() }
                    openAdvancedTools()
                    onNodeWithTag("destination-HOME").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("market-bitcoin:btc").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("market-bitcoin:btc").performClick()
                    onNodeWithText("Market data only · no tracked holding").assertIsDisplayed()
                    onNodeWithText("Owned").assertDoesNotExist()
                    onNodeWithText("Total gains").assertDoesNotExist()
                    onNodeWithText("BTC/USD · fresh").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("detail-Transactions").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("No transactions match this asset and filter.").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("No transactions match this asset and filter.").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("detail-add-transaction").performScrollTo().performClick()
                    onNodeWithText("Create manual account").performScrollTo().assertIsDisplayed()
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.accounts
                            .isEmpty(),
                    )
                    assertEquals(0, app.snapshot().activityTotal)
                }
            }
        }
    }

    @Test
    fun firstManualEntryCreatesLocalAccountAndRecordsOneSatoshi() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.updateOnboarding(OnboardingAction.Skip)
                assertTrue(
                    app
                        .snapshot()
                        .ownership.accounts
                        .isEmpty(),
                )
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-menu").fetchSemanticsNodes().isNotEmpty() }
                    openAdvancedTools()
                    onNodeWithTag("destination-HOME").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("main-market").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithTag("add-action").performClick()
                    onNodeWithTag("source-choice-manual-activity").performScrollTo().performClick()
                    onNodeWithText("Account name").assertExists()
                    onNodeWithText("Manual account").assertIsDisplayed()
                    onNodeWithText("Create manual account").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        app
                            .snapshot()
                            .ownership.accounts.size == 1 &&
                            onAllNodesWithText("BTC quantity (positive or negative)").fetchSemanticsNodes().isNotEmpty()
                    }
                    val account =
                        app
                            .snapshot()
                            .ownership.accounts
                            .single()
                    assertEquals("Manual account", account.label)
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.targets
                            .isEmpty(),
                    )
                    onNodeWithText("BTC quantity (positive or negative)").performScrollTo().performTextInput("0.00000001")
                    onNodeWithText("Label (optional)").performScrollTo().performTextInput("First satoshi")
                    onNodeWithText("Record locally").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { app.snapshot().activityTotal == 1 }
                    val activity =
                        app
                            .snapshot()
                            .activities
                            .single()
                            .activity
                    assertEquals(io.valroot.core.ledger.ActivityKind.DEPOSIT, activity.kind)
                    assertTrue(activity.manual)
                    val movement = activity.movements.single()
                    assertEquals(account.id, movement.accountId)
                    assertEquals(AssetId("bitcoin:btc"), movement.amount.assetId)
                    assertEquals(
                        io.valroot.core.model.ExactInteger
                            .parse("1"),
                        movement.amount.atomicUnits,
                    )
                }
            }
        }
    }

    @Test
    fun refreshRequestsExplicitConsentForAdditionalPortfolioCurrencies() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                app.savePortfolio(
                    io.valroot.core.ownership.Portfolio(
                        io.valroot.core.model
                            .PortfolioId("euro-acceptance"),
                        "Euro portfolio",
                        "EUR",
                    ),
                )
                app.setAutomaticPriceConsent(true)
                app.setAutomaticPortfolioPriceConsent(false)
                assertEquals(listOf(AssetId("USD")), app.automaticPriceCurrencies())
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-menu").fetchSemanticsNodes().isNotEmpty() }
                    openAdvancedTools()
                    onNodeWithTag("destination-HOME").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("main-market").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("↻").performClick()
                    onNodeWithTag("automatic-portfolio-price-consent").assertExists()
                    assertEquals(0, host.refreshCalls)
                    assertEquals(false, app.automaticPortfolioPriceConsent())
                    onNodeWithTag("automatic-portfolio-price-consent").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { app.automaticPortfolioPriceConsent() }
                    onNodeWithText("Close").performScrollTo().performClick()
                    onNodeWithText("↻").performClick()
                    waitUntil(timeoutMillis = 10_000) { host.refreshCalls == 1 }
                    assertEquals(setOf(AssetId("USD"), AssetId("EUR")), app.automaticPriceCurrencies().toSet())
                }
            }
        }
    }

    @Test
    fun refreshRequiresConsentAndPreventsDuplicatesWhileKeepingSavedData() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                val before = app.snapshot().valuations
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-menu").fetchSemanticsNodes().isNotEmpty() }
                    openAdvancedTools()
                    onNodeWithTag("destination-HOME").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("main-market").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("↻").performClick()
                    onNodeWithTag("automatic-price-consent").assertExists()
                    assertEquals(0, host.refreshCalls)
                    onNodeWithTag("automatic-price-consent").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { app.automaticPriceConsent() }
                    onNodeWithText("Close").performScrollTo().performClick()
                    onNodeWithText("↻").performClick()
                    waitUntil(timeoutMillis = 10_000) { host.refreshCalls == 1 }
                    onNodeWithText("Queued").assertIsNotEnabled()
                    runOnIdle { host.priceRefreshStatus.value = PriceRefreshPhase.RUNNING }
                    onNodeWithText("Refreshing…").assertIsNotEnabled()
                    assertEquals(1, host.refreshCalls)
                    runOnIdle { host.priceRefreshStatus.value = PriceRefreshPhase.IDLE }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("↻").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(before, app.snapshot().valuations)
                }
            }
        }
    }

    private class Host :
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
