package io.valroot.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelected
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
import androidx.compose.ui.unit.Density
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityRepository
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.SourceRecordId
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.reconcile.ActivityOverlay
import io.valroot.core.reconcile.ClassificationRule
import io.valroot.core.reconcile.LinkState
import io.valroot.core.storage.JvmSqliteDatabase
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
class ValrootUiTest {
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
    fun assetDetailCanSwitchFromManualToProviderPrices() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Bitcoin"))
                    onNodeWithText("Bitcoin").performClick()
                    onNodeWithTag("use-provider-prices").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Provider prices selected; manual history retained").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals(
                        null,
                        app
                            .snapshot()
                            .valuations
                            .single()
                            .price,
                    )
                    assertEquals("4", db.query("SELECT COUNT(*) FROM market_prices WHERE manual=1").single().single())
                }
            }
        }
    }

    @Test
    fun portfolioTotalWarnsWhenCurrentPriceIsStale() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                var clock = System.currentTimeMillis()
                val app = PortfolioApplication(db) { clock }
                app.demo.seed()
                clock += 3 * 3_600_000L
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("1 asset uses stale prices").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("page-content").performScrollToNode(hasText("1 asset uses stale prices"))
                    onNodeWithText("1 asset uses stale prices").assertIsDisplayed()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Stale · manual quote"))
                    onNodeWithText("Stale · manual quote").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun automaticPricesRequireConsentAndScheduleRefresh() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                val data = app.snapshot()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { WorkflowDialog("price", data, app, host, {}, { _, operation -> operation() }, {}, { _, _ -> }) }
                    assertEquals(false, app.prices.automaticConsent())
                    onNodeWithTag("automatic-price-consent").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { host.automaticPrices }
                    assertTrue(app.prices.automaticConsent())
                    onNodeWithTag("refresh-prices-now").performScrollTo().performClick()
                    onAllNodesWithText("Price refresh queued.").onFirst().assertIsDisplayed()
                    onNodeWithTag("refresh-prices-now").assertIsNotEnabled()
                    host.priceRefreshStatus.value = PriceRefreshPhase.RUNNING
                    onNodeWithText("Refreshing prices…").assertIsDisplayed()
                    host.priceRefreshStatus.value = PriceRefreshPhase.IDLE
                    onNodeWithTag("automatic-price-consent").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { !host.automaticPrices }
                    assertEquals(false, app.prices.automaticConsent())
                }
            }
        }
    }

    @Test
    fun openLotLinksToItsAcquisitionActivity() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                val acquisition =
                    app
                        .accounting
                        .report()
                        .report.lots
                        .first()
                        .acquisitionActivityId!!
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Bitcoin"))
                    onNodeWithText("Bitcoin").performClick()
                    onNodeWithText("View FIFO lots and disposals").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("View acquisition activity").fetchSemanticsNodes().isNotEmpty() }
                    onAllNodesWithText("View acquisition activity").onFirst().performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Activity details").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText(acquisition.value).assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun accountSourceOpensScanDetails() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                val account =
                    app
                        .snapshot()
                        .ownership.accounts
                        .single()
                        .id
                app.accounts.saveTarget(
                    io.valroot.core.ownership.TrackingTarget(
                        io.valroot.core.model
                            .TrackingTargetId("source-test"),
                        account,
                        io.valroot.core.ownership.TargetIdentity(
                            io.valroot.core.model
                                .NetworkId("bitcoin-mainnet"),
                            "address",
                            "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
                        ),
                        io.valroot.core.ownership.TargetRole.LEAF,
                        "Test source",
                    ),
                )
                runDesktopComposeUiTest(width = 1000, height = 900) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Accounts").onLast().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("View scan details"))
                    onNodeWithText("View scan details").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("No saved fetch job").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Source scan details").assertIsDisplayed()
                    onNodeWithText("No saved fetch job").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun disposalOpensRecordedActivityAndEvidence() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Bitcoin"))
                    onNodeWithText("Bitcoin").performClick()
                    onNodeWithText("View FIFO lots and disposals").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("View disposal activity").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("View disposal activity").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Activity details").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("demo-payment").assertIsDisplayed()
                    onNodeWithText("Source evidence").performScrollTo().assertIsDisplayed()
                    onNodeWithText("Manual entry").performScrollTo().assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun assetDetailOpensAndClearsActivityFilter() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                val asset = app.snapshot().valuations.first()
                val expected = app.snapshot(ActivityFilter(asset = asset.asset)).activityTotal
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    val name = asset.metadata?.name ?: asset.asset.value
                    onNodeWithTag("page-content").performScrollToNode(hasText(name))
                    onNodeWithText(name).performClick()
                    onNodeWithTag("detail-Transactions").performScrollTo().performClick()
                    onNodeWithText("Open in Activity").performScrollTo().performClick()
                    waitUntil(
                        timeoutMillis = 10_000,
                    ) { onAllNodesWithText("$expected activities · matching filters").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Asset: ${asset.asset.value}").assertIsDisplayed()
                    onNodeWithText("Clear asset").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Asset: ${asset.asset.value}").fetchSemanticsNodes().isEmpty() }
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

        @Volatile var priceRequests = 0

        @Volatile var automaticPrices = false

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

        override fun updateRefreshPreferences(preferences: RefreshPreferences) {
            automaticPrices = preferences.automaticRefresh
        }

        override fun refreshAutomaticPrices(onResult: (String) -> Unit) {
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

    @Test
    fun compactAccountManagementArchivesAndRestoresWithoutDeletingHistory() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                val original = app.snapshot()
                val account = original.ownership.accounts.single()
                val originalRows = db.query("SELECT COUNT(*) FROM ledger_activities").single().single()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty()
                    }
                    openAdvancedTools()
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Manage accounts"))
                    onNodeWithTag("manage-accounts").assertIsDisplayed().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Watch-only accounts"))
                    onNodeWithText("Watch-only accounts").assertIsDisplayed()
                    val archiveTag = "archive-account-${account.id.value}"
                    onNodeWithTag("page-content").performScrollToNode(hasTestTag(archiveTag))
                    onNodeWithTag(archiveTag).performScrollTo().assertIsEnabled().performClick()
                    onNodeWithText("Archive ${account.label}?").assertIsDisplayed()
                    onNodeWithText("This does not delete the account or its history.", substring = true).assertIsDisplayed()
                    onNodeWithText("Keep account").performClick()
                    assertTrue(
                        "valroot:archived" !in
                            app
                                .snapshot()
                                .ownership.accounts
                                .single()
                                .tags,
                    )
                    onNodeWithTag(archiveTag).performClick()
                    onNodeWithTag("confirm-archive-account").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Archived accounts").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertTrue(
                        "valroot:archived" in
                            app
                                .snapshot()
                                .ownership.accounts
                                .single()
                                .tags,
                    )
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.targets
                            .none { it.enabled },
                    )
                    assertEquals(originalRows, db.query("SELECT COUNT(*) FROM ledger_activities").single().single())
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Account archived; saved history retained").fetchSemanticsNodes().isNotEmpty()
                    }
                    val restoreTag = "restore-account-${account.id.value}"
                    onNodeWithTag("page-content").performScrollToNode(hasTestTag(restoreTag))
                    onNodeWithTag(restoreTag).performScrollTo().assertIsDisplayed().assertIsEnabled()
                    onNodeWithText("Account archived; saved history retained").assertIsDisplayed()
                    val restoreBounds = onNodeWithTag(restoreTag).fetchSemanticsNode().boundsInRoot
                    val snackbarBounds = onNodeWithTag("status-message").fetchSemanticsNode().boundsInRoot
                    assertTrue(restoreBounds.bottom <= snackbarBounds.top, "Restore must remain above the active status message")
                    onNodeWithTag(restoreTag).performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        check(
                            onAllNodesWithText("Could not save. Check your input and available storage, then try again.")
                                .fetchSemanticsNodes()
                                .isEmpty(),
                        ) { "Restore was rejected by the save operation" }
                        onAllNodesWithText("Archived accounts").fetchSemanticsNodes().isEmpty()
                    }
                    assertEquals(original.ownership, app.snapshot().ownership)
                    assertEquals(originalRows, db.query("SELECT COUNT(*) FROM ledger_activities").single().single())
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACCOUNTS").assertIsDisplayed().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Watch-only accounts"))
                    onNodeWithText("Watch-only accounts").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun activityNetworkMenuUsesRecordedSources() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                val account =
                    app
                        .snapshot()
                        .ownership.accounts
                        .single()
                        .id
                val payload = "network-fixture"
                io.valroot.core.storage.Store(db).ingest(
                    io.valroot.core.storage.RawObservation(
                        "network-source",
                        "fixture",
                        "network-tx",
                        "network-test",
                        "provider",
                        "2024-01-01T00:00:00Z",
                        payload,
                        "1",
                        io.valroot.core.storage
                            .sha256(payload),
                        "fixture",
                    ),
                )
                ActivityRepository(db).save(
                    Activity(
                        ActivityId("network-tx"),
                        1L,
                        "network-tx",
                        ActivityKind.DEPOSIT,
                        ActivityStatus.CONFIRMED,
                        listOf(
                            Movement(
                                EventId("network-event"),
                                account,
                                AtomicAmount(AssetId("BTC"), ExactInteger.parse("1")),
                                MovementRole.TRANSFER,
                            ),
                        ),
                        setOf(SourceRecordId("network-source")),
                        "fixture",
                        false,
                    ),
                )
                app.reload()
                runDesktopComposeUiTest(width = 1000, height = 900) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACTIVITY").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasTestTag("activity-network-filter"))
                    onNodeWithTag("activity-network-filter").performScrollTo().performClick()
                    onNodeWithTag("network-network-test").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Search activity"))
                    waitUntil(
                        timeoutMillis = 10_000,
                    ) { onAllNodesWithText("1 activities", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("page-content").performScrollToNode(hasText("Network: network-test ▾"))
                    onNodeWithText("Network: network-test ▾").assertIsDisplayed()
                    onNodeWithTag("page-content").performScrollToNode(hasText("1970-01-01 · 1 source records"))
                    onNodeWithText("1970-01-01 · 1 source records").performClick()
                    onNodeWithText("View saved observations").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Source provider · retrieved 2024-01-01T00:00:00Z").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("Source provider · retrieved 2024-01-01T00:00:00Z").performScrollTo().assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun activityStatusMenuFiltersConfirmedHistory() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                runDesktopComposeUiTest(width = 1000, height = 900) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACTIVITY").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasTestTag("activity-status-filter"))
                    onNodeWithTag("activity-status-filter").performScrollTo().performClick()
                    onNodeWithTag("status-PENDING").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Search activity"))
                    waitUntil(
                        timeoutMillis = 10_000,
                    ) { onAllNodesWithText("1 activities", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("page-content").performScrollToNode(hasText("Status: Pending ▾"))
                    onNodeWithText("Status: Pending ▾").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun assetDisplayChoiceIsSavedFromPortfolio() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.demo.seed()
                runDesktopComposeUiTest(width = 1000, height = 900) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasTestTag("hide-empty-assets"))
                    waitUntil(conditionDescription = "hide empty control enabled", timeoutMillis = 10_000) {
                        onAllNodes(hasTestTag("hide-empty-assets") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("hide-empty-assets").performScrollTo().assertIsEnabled()
                    onNodeWithTag("hide-empty-assets").performClick()
                    waitForIdle()
                    waitUntil(
                        conditionDescription = "hide empty preference persisted",
                        timeoutMillis = 10_000,
                    ) { app.settings.assetList().hideEmpty }
                    assertEquals(true, PortfolioApplication(db) { System.currentTimeMillis() }.settings.assetList().hideEmpty)
                    onNodeWithTag("page-content").performScrollToNode(hasTestTag("hide-small-assets"))
                    waitUntil(conditionDescription = "hide small control enabled", timeoutMillis = 10_000) {
                        onAllNodes(hasTestTag("hide-small-assets") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("hide-small-assets").performScrollTo().assertIsEnabled()
                    onNodeWithTag("hide-small-assets").performClick()
                    waitForIdle()
                    waitUntil(
                        conditionDescription = "hide small preference persisted",
                        timeoutMillis = 10_000,
                    ) { app.settings.assetList().hideSmall }
                    assertEquals(true, PortfolioApplication(db) { System.currentTimeMillis() }.settings.assetList().hideSmall)
                }
            }
        }
    }

    @Test
    fun erasingLocalDataReturnsToFreshOnboardingWithoutHostRevision() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 1000, height = 900) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                    }
                    openAdvancedTools()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Settings").onLast().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Erase local portfolio data"))
                    onNodeWithText("Erase local portfolio data").performScrollTo().performClick()
                    onNodeWithText("Type ERASE to confirm").performTextInput("ERASE")
                    onNodeWithText("Erase this device’s data").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Set up your space").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals(0L, host.dataRevision.value)
                    assertEquals(
                        io.valroot.core.onboarding
                            .OnboardingState(),
                        app.settings.onboarding(),
                    )
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.accounts
                            .isEmpty(),
                    )
                    assertTrue(!app.snapshot().sampleData)
                }
            }
        }
    }

    @Test
    fun freshInstallCanCompleteWithoutAccountsOrNetworkRequests() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                runDesktopComposeUiTest(width = 420, height = 900) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Set up your space").fetchSemanticsNodes().isNotEmpty()
                    }
                    for (title in listOf("Choose your interests", "Explore supported sources", "Ready at your pace")) {
                        onNodeWithText("Continue").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
                        }
                    }
                    onNodeWithTag("onboarding-finish").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertTrue(app.settings.onboarding()!!.completed)
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.accounts
                            .isEmpty(),
                    )
                    assertEquals(0, host.refreshRequests)
                    assertEquals(0, host.priceRequests)
                    assertTrue(!app.snapshot().sampleData)
                }
            }
        }
    }

    @Test
    fun freshInstallCanSkipOfflineAndResumeSavedSetupFromSettings() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
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
                    waitUntil(timeoutMillis = 10_000) {
                        io.valroot.core.onboarding.OnboardingGoal.BALANCES in app.settings.onboarding()!!.goals
                    }
                    onNodeWithText("Skip setup for now").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                    }
                    openAdvancedTools()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Settings").onLast().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Resume setup"))
                    onNodeWithText("Resume setup").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Choose your interests").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("onboarding-goal-BALANCES").performScrollTo().assertIsSelected()
                    assertEquals(0, host.refreshRequests)
                    assertEquals(0, host.priceRequests)
                    assertTrue(
                        app
                            .snapshot()
                            .ownership.accounts
                            .isEmpty(),
                    )
                    assertTrue(!app.snapshot().sampleData)
                }
            }
        }
    }

    @Test
    fun dataHealthOpensUnknownActiveScopeEvidence() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
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
    fun phoneNavigationFitsTheViewport() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() &&
                            onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                    }
                    openAdvancedTools()
                    onNodeWithTag("destination-HOME").performClick()
                    onNodeWithTag("main-market").assertIsDisplayed()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACTIVITY").performClick()
                    onNodeWithText("Search activity").assertIsDisplayed()
                    val searchBounds = onNodeWithText("Search activity").fetchSemanticsNode().boundsInRoot
                    assertTrue(searchBounds.left >= 0f && searchBounds.right <= 360f && searchBounds.bottom <= 800f)
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACCOUNTS").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Watch-only accounts"))
                    onNodeWithText("Watch-only accounts").assertIsDisplayed()
                    val image = onRoot().captureToImage()
                    assertEquals(360, image.width)
                    assertEquals(800, image.height)
                }
            }
        }
    }

    @Test
    fun narrowPhoneNavigationWorksWithLargeText() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 320, height = 800) {
                    setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                            ValrootApp(app, host)
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() }
                    openAdvancedTools()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACTIVITY").performClick()
                    onNodeWithText("Search activity").assertIsDisplayed()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACCOUNTS").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Watch-only accounts"))
                    onNodeWithText("Watch-only accounts").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun syncProgressAndSettingsRemainVisibleWhenDatabaseDispatcherIsBusy() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
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
            Host().use { host ->
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

    @Test
    fun transferReviewRemovesRelationshipsRejectsConflictsAndKeepsSuggestionsUsable() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { 1000L }
                app.settings.updateOnboarding(OnboardingAction.Skip)

                fun activity(
                    id: String,
                    amount: String,
                    status: ActivityStatus = ActivityStatus.CONFIRMED,
                ) = Activity(
                    ActivityId(id),
                    100,
                    id,
                    ActivityKind.TRANSFER,
                    status,
                    listOf(
                        Movement(
                            EventId("$id-event"),
                            AccountId(id),
                            AtomicAmount(AssetId("bitcoin:btc"), ExactInteger.parse(amount)),
                            MovementRole.TRANSFER,
                        ),
                    ),
                    emptySet(),
                    null,
                    manual = true,
                )
                val records =
                    listOf(
                        activity("accepted-out", "-10"),
                        activity("accepted-in", "10"),
                        activity("conflict-out", "-20"),
                        activity("conflict-in", "20", ActivityStatus.REVERSED),
                        activity("candidate-out", "-30"),
                        activity("candidate-in", "30"),
                    )
                val repository = ActivityRepository(db)
                records.forEach { repository.save(it) }
                app.transactions.setDecision(ActivityId("accepted-out"), ActivityId("accepted-in"), LinkState.ACCEPTED)
                app.transactions.setDecision(ActivityId("conflict-out"), ActivityId("conflict-in"), LinkState.ACCEPTED)
                val original = repository.current()
                val data = app.snapshot()
                var closed = false
                runDesktopComposeUiTest(width = 800, height = 1000) {
                    setContent {
                        WorkflowDialog("reconcile", data, app, host, { closed = true }, { _, operation -> operation() }, {}, { _, _ -> })
                    }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Out: ", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    // Rows describe each side by date, account and amount rather than internal IDs.
                    onNodeWithText("Out: ", substring = true).performScrollTo().assertIsDisplayed()
                    onNodeWithText("In: ", substring = true).performScrollTo().assertIsDisplayed()
                    onNodeWithText("Unresolved: activity inactive").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("remove-transfer-accepted-out-accepted-in").performScrollTo().performClick()
                    onNodeWithText("No accepted transfer relationships.").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("reject-transfer-conflict-conflict-in-conflict-out").performScrollTo().performClick()
                    onNodeWithText("No transfer link conflicts.").performScrollTo().assertIsDisplayed()
                    runOnIdle {
                        assertTrue(app.accounting.transferLinks().isEmpty())
                        assertTrue(app.accounting.transferLinkConflicts().isEmpty())
                        assertEquals(1, app.accounting.reconciliationCandidates(86_400_000).size)
                        assertEquals(original, repository.current())
                    }
                    onNodeWithTag("accept-transfer-candidate-in-candidate-out").performScrollTo().performClick()
                    runOnIdle {
                        assertTrue(closed)
                        assertEquals(
                            ActivityId("candidate-out"),
                            app.accounting
                                .transferLinks()
                                .single()
                                .outgoingActivity,
                        )
                        assertEquals(original, repository.current())
                        assertTrue(app.accounting.reconciliationCandidates(86_400_000).isEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun ruleManagerEditsAndTogglesWithoutBroadeningFilters() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                val original =
                    ClassificationRule(
                        "filtered-rule",
                        37,
                        ActivityKind.DEPOSIT,
                        ActivityKind.PAYMENT,
                        AssetId("bitcoin:btc"),
                        SourceRecordId("filtered-source"),
                        enabled = false,
                    )
                val anyKind = original.copy(id = "any-kind-rule", matchKind = null, priority = 12)
                app.transactions.saveRule(original)
                app.transactions.saveRule(anyKind)
                val data = app.snapshot()
                runDesktopComposeUiTest(width = 800, height = 1000) {
                    setContent {
                        WorkflowDialog("rule", data, app, host, {}, { _, operation -> operation() }, {}, { _, _ -> })
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(original.id).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("edit-rule-${original.id}").performScrollTo().performClick()
                    onNodeWithText("Match asset ID (blank for any)").performScrollTo().assertIsDisplayed()
                    onNodeWithText("deposit").performScrollTo().performClick()
                    onNodeWithText("reward").performClick()
                    onNodeWithText("Save rule").performScrollTo().performClick()
                    runOnIdle {
                        assertEquals(
                            original.copy(result = ActivityKind.REWARD),
                            app.transactions.rules().first {
                                it.id ==
                                    original.id
                            },
                        )
                    }
                    onNodeWithTag("toggle-rule-${original.id}").performScrollTo().performClick()
                    runOnIdle {
                        assertEquals(
                            original.copy(result = ActivityKind.REWARD, enabled = true),
                            app.transactions.rules().first {
                                it.id ==
                                    original.id
                            },
                        )
                    }
                    onNodeWithTag("toggle-rule-${original.id}").performScrollTo().performClick()
                    runOnIdle {
                        assertEquals(
                            original.copy(result = ActivityKind.REWARD),
                            app.transactions.rules().first {
                                it.id ==
                                    original.id
                            },
                        )
                    }
                    onNodeWithTag("edit-rule-${anyKind.id}").performScrollTo().performClick()
                    onNodeWithText("Any classification").performScrollTo().assertIsDisplayed()
                    onNodeWithText("Save rule").performScrollTo().performClick()
                    runOnIdle { assertEquals(anyKind, app.transactions.rules().first { it.id == anyKind.id }) }
                }
            }
        }
    }

    @Test
    fun historicalPriceRangeRequiresExplicitConsent() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                val data = app.snapshot()
                runDesktopComposeUiTest(width = 800, height = 900) {
                    setContent {
                        WorkflowDialog("price", data, app, host, {}, { _, operation -> operation() }, {}, { _, _ -> })
                    }
                    onNodeWithTag("fetch-historical-price").performScrollTo().assertIsNotEnabled()
                    onNodeWithTag("price-source-consent").performScrollTo().performClick()
                    onNodeWithTag("fetch-historical-price").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { host.priceRequests == 1 }
                    onNodeWithText("Saved Bitcoin price history.").performScrollTo().assertIsDisplayed()
                }
                assertTrue(app.prices.sourceConsent())
                assertEquals(1, host.priceRequests)
            }
        }
    }

    @Test
    fun mediumLayoutRecordsAndPersistsManualActivity() {
        val path = Files.createTempFile("valroot-ui", ".sqlite")
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                Host().use { host ->
                    val app = PortfolioApplication(db) { System.currentTimeMillis() }
                    app.settings.updateOnboarding(OnboardingAction.Skip)
                    app.demo.seed()
                    runDesktopComposeUiTest(width = 800, height = 900) {
                        setContent { ValrootApp(app, host) }
                        waitUntil(timeoutMillis = 10_000) {
                            onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() &&
                                onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                        }
                        openAdvancedTools()
                        onNodeWithTag("destination-HOME").performClick()
                        onNodeWithTag("main-market").assertIsDisplayed()
                        onNodeWithTag("workspace-menu").performClick()
                        onAllNodesWithText("Advanced tools").onLast().performClick()
                        onNodeWithTag("more-ACTIVITY").performClick()
                        onNodeWithTag("workspace-add-action").performClick()
                        onNodeWithTag("source-choice-manual-activity").performScrollTo().performClick()
                        onNodeWithText("BTC quantity (positive or negative)").performScrollTo().performTextInput("0.125")
                        onNodeWithText("Label (optional)").performScrollTo().performTextInput("UI deposit")
                        onNodeWithText("Record locally").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("UI deposit").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithText("UI deposit").assertIsDisplayed()
                        onNodeWithTag("workspace-menu").performClick()
                        onAllNodesWithText("Settings").onLast().performClick()
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
                app.settings.updateOnboarding(OnboardingAction.Skip)
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
                app.settings.updateOnboarding(OnboardingAction.Skip)
                runDesktopComposeUiTest(width = 800, height = 360) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Settings").onLast().performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Make it yours"))
                    onNodeWithText("Make it yours").performScrollTo().assertIsDisplayed()
                    onAllNodesWithText("Portfolio")
                        .filter(hasClickAction())
                        .onFirst()
                        .performScrollTo()
                        .performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("PRICED VALUE"))
                    onNodeWithText("PRICED VALUE").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun activityCorrectionsSupportEveryLegAndResetToSource() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { System.currentTimeMillis() }
            app.settings.updateOnboarding(OnboardingAction.Skip)
            app.demo.seed()
            val data = app.snapshot()
            val base = data.activities.first { it.activity.movements.size == 1 }
            val first = base.activity.movements.single()
            val second = first.copy(id = EventId("unknown-leg"), amount = AtomicAmount(AssetId("unknown"), ExactInteger.parse("42")))
            val source = base.original.copy(movements = listOf(first, second))
            val item = base.copy(original = source, activity = source)
            var saved: ActivityOverlay? = null
            runDesktopComposeUiTest(width = 360, height = 800) {
                setContent { ActivityDialog(item, data, {}, {}, save = { saved = it }) }
                onNodeWithText("Correct quantity 2 (atomic units; empty = keep)").performScrollTo().performTextInput("123")
                onNodeWithText("Save correction").performScrollTo().performClick()
                assertEquals(ExactInteger.parse("123"), saved?.amounts?.get(second.id)?.atomicUnits)
                onNodeWithText("Reset quantity 2 to source").performScrollTo().performClick()
                onNodeWithText("Save correction").performScrollTo().performClick()
                assertTrue(saved!!.amounts.isEmpty())
                assertEquals(ExactInteger.parse("42"), source.movements[1].amount.atomicUnits)
            }
        }
    }

    @Test
    fun phoneCanArchiveAndRestoreAnAccount() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() }
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACCOUNTS").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("Archive account"))
                    onNodeWithText("Archive account").performScrollTo().performClick()
                    onNodeWithTag("confirm-archive-account").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Archived accounts").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(0, app.snapshot().activityTotal)
                    onNodeWithTag("page-content").performScrollToNode(hasText("Restore account"))
                    onNodeWithText("Restore account").performScrollTo().assertIsEnabled().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Account restored").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(4, app.snapshot().activityTotal)
                }
            }
        }
    }

    @Test
    fun tenThousandActivitiesRemainPagedAndSearchable() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                val started = System.nanoTime()
                repeat(10_000) { index ->
                    val id = "bulk-${index.toString().padStart(5, '0')}"
                    app.transactions.saveManualActivity(
                        io.valroot.core.ledger.Activity(
                            ActivityId(id),
                            System.currentTimeMillis(),
                            id,
                            io.valroot.core.ledger.ActivityKind.DEPOSIT,
                            io.valroot.core.ledger.ActivityStatus.CONFIRMED,
                            listOf(
                                io.valroot.core.ledger.Movement(
                                    io.valroot.core.model
                                        .EventId(id),
                                    io.valroot.core.model
                                        .AccountId("demo-wallet"),
                                    io.valroot.core.model.AtomicAmount(
                                        io.valroot.core.model
                                            .AssetId("bitcoin:btc"),
                                        io.valroot.core.model.ExactInteger
                                            .parse("1"),
                                    ),
                                    io.valroot.core.ledger.MovementRole.TRANSFER,
                                ),
                            ),
                            emptySet(),
                            null,
                            true,
                        ),
                    )
                }
                runDesktopComposeUiTest(width = 1440, height = 1000) {
                    setContent { ValrootApp(app, host) }
                    openAdvancedTools()
                    waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() }
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACTIVITY").performClick()
                    waitUntil(
                        timeoutMillis = 30_000,
                    ) { onAllNodesWithText("10004 activities", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    assertTrue(onAllNodesWithText("Deposit").fetchSemanticsNodes().size <= 51)
                    onNodeWithText("Search activity").performTextInput("bulk-09999")
                    waitUntil(
                        timeoutMillis = 30_000,
                    ) { onAllNodesWithText("1 activities", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(1, app.snapshot(ActivityFilter(search = "bulk-09999")).activityTotal)
                }
                println("10,000-activity seed, projection, paginated render and search: ${(System.nanoTime() - started) / 1_000_000} ms")
            }
        }
    }

    @Test
    fun wideLayoutEditsSearchesAndExports() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 1440, height = 1000) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() &&
                            onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty()
                    }
                    openAdvancedTools()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-ACTIVITY").performClick()
                    onNodeWithText("Search activity").performTextInput("demo-deposit")
                    waitUntil(
                        timeoutMillis = 10_000,
                    ) { onAllNodesWithText("1 activities", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    onNode(hasText("Deposit") and hasText("+1.25 BTC")).performClick()
                    onNodeWithText("Label").performTextInput("Reviewed deposit")
                    onNodeWithText("Save correction").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Reviewed deposit").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals("Reviewed deposit", app.transactions.overlay(ActivityId("demo-deposit"))?.label)
                    onNodeWithText("Search activity").performTextReplacement("Reviewed deposit")
                    waitUntil(
                        timeoutMillis = 10_000,
                    ) { onAllNodesWithText("1 activities", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("destination-REPORTS").performClick()
                    onNodeWithTag("page-content").performScrollToNode(hasText("↓  Export all active activity CSV"))
                    onNodeWithText("↓  Export all active activity CSV").performClick()
                    waitUntil(timeoutMillis = 10_000) { host.exported != null }
                    assertEquals("valroot-activities.csv", host.exportedName)
                    assertTrue(host.exported!!.decodeToString().contains("Reviewed deposit"))
                    val image = onRoot().captureToImage()
                    assertEquals(1440, image.width)
                    assertEquals(1000, image.height)
                }
            }
        }
    }

    @Test
    fun compactAddAndAppearancePreferencesRemainReachable() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 360, height = 800) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Main").fetchSemanticsNodes().isNotEmpty() }
                    openAdvancedTools()
                    onNodeWithTag("compact-navigation").assertIsDisplayed()
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithTag("add-action").performClick()
                    onNodeWithText("Add to your portfolio").assertIsDisplayed()
                    onNodeWithTag("source-choice-bitcoin-address").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("source-choice-bitcoin-public-wallet").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("source-choice-manual-activity").performScrollTo().assertIsDisplayed()
                    onNodeWithText("Close").performClick()
                    onNodeWithTag("destination-OVERVIEW").performClick()
                    onNodeWithText("PRICED VALUE").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("workspace-menu").performClick()
                    onAllNodesWithText("Advanced tools").onLast().performClick()
                    onNodeWithTag("more-SETTINGS").performClick()
                    for (key in listOf("light", "dark", "system")) {
                        val tag = "appearance-$key"
                        onNodeWithTag("page-content").performScrollToNode(hasTestTag(tag))
                        onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
                        waitUntil(timeoutMillis = 10_000) {
                            app.settings.theme() == key && onAllNodes(hasTestTag(tag) and isSelected()).fetchSemanticsNodes().isNotEmpty()
                        }
                        onNodeWithTag("page-content").performScrollToNode(hasTestTag(tag))
                        onNodeWithTag(tag).performScrollTo().assertIsSelected()
                    }
                    onNodeWithTag("destination-HOME").performClick()
                    onNodeWithText("Market").assertIsDisplayed()
                }
            }
        }
    }
}
