package io.polion.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.Density
import io.polion.core.application.ActivityFilter
import io.polion.core.application.PortfolioApplication
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityRepository
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.SourceRecordId
import io.polion.core.reconcile.ActivityOverlay
import io.polion.core.reconcile.ClassificationRule
import io.polion.core.reconcile.LinkState
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

        override fun openFile(onResult: (ByteArray?) -> Unit) = onResult(null)

        override fun restoreBackup(
            bytes: ByteArray,
            password: String,
            onResult: (String) -> Unit,
        ) = onResult("Not used")

        override fun verifyAccountingOrder(onResult: (String) -> Unit) = onResult("Not used")

        override fun discoverWallet(
            walletId: io.polion.core.model.TrackingTargetId,
            onResult: (String) -> Unit,
        ) = onResult("Not used")

        override fun fetchHistoricalPrice(
            asset: io.polion.core.model.AssetId,
            quote: io.polion.core.model.AssetId,
            dayUtcMillis: Long,
            apiKey: String,
            onResult: (String) -> Unit,
        ) {
            priceRequests++
            onResult("Daily historical price saved from CoinGecko.")
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
    fun dataHealthOpensUnknownActiveScopeEvidence() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.seedDemo()
                runDesktopComposeUiTest(width = 800, height = 900) {
                    setContent { PolionApp(app, host) }
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
    fun narrowPhoneNavigationWorksWithLargeText() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.seedDemo()
                runDesktopComposeUiTest(width = 320, height = 800) {
                    setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                            PolionApp(app, host)
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() }
                    onAllNodesWithText("Activity").filter(hasClickAction()).onFirst().performClick()
                    onNodeWithText("Search activity").assertIsDisplayed()
                    onAllNodesWithText("Accounts").filter(hasClickAction()).onFirst().performClick()
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
    fun fullHistoryRecoveryRequiresConsentAndExplicitConfirmation() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                host.syncStatus.value = SyncStatus(SyncPhase.ERROR, "A full history recovery is required.")
                val data = app.snapshot()
                runDesktopComposeUiTest(width = 800, height = 900) {
                    setContent {
                        WorkflowDialog("sync", data, app, host, {}, { _, operation -> operation() }, {}, { _, _ -> })
                    }
                    onNodeWithTag("recover-bitcoin-history").performScrollTo().assertIsNotEnabled()
                    onNodeWithText("A full history recovery is required.").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("bitcoin-recovery-consent").performScrollTo().performClick()
                    onNodeWithTag("recover-bitcoin-history").performScrollTo().assertIsNotEnabled()
                    onNodeWithText("I allow public address queries to this endpoint").performScrollTo()
                    onNodeWithTag("bitcoin-source-consent").performClick()
                    onNodeWithTag("recover-bitcoin-history").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { host.recoveryRequests == 1 }
                    assertEquals(0, host.refreshRequests)
                    assertTrue(app.sourceConsent())
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
                app.setDecision(ActivityId("accepted-out"), ActivityId("accepted-in"), LinkState.ACCEPTED)
                app.setDecision(ActivityId("conflict-out"), ActivityId("conflict-in"), LinkState.ACCEPTED)
                val original = repository.current()
                val data = app.snapshot()
                var closed = false
                runDesktopComposeUiTest(width = 800, height = 1000) {
                    setContent {
                        WorkflowDialog("reconcile", data, app, host, { closed = true }, { _, operation -> operation() }, {}, { _, _ -> })
                    }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText("Outgoing: accepted-out · accepted-out-event").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("Outgoing: accepted-out · accepted-out-event").performScrollTo().assertIsDisplayed()
                    onNodeWithText("Incoming: accepted-in · accepted-in-event").performScrollTo().assertIsDisplayed()
                    onNodeWithText("Unresolved: activity inactive").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("remove-transfer-accepted-out-accepted-in").performScrollTo().performClick()
                    onNodeWithText("No accepted transfer relationships.").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("reject-transfer-conflict-conflict-in-conflict-out").performScrollTo().performClick()
                    onNodeWithText("No transfer link conflicts.").performScrollTo().assertIsDisplayed()
                    runOnIdle {
                        assertTrue(app.transferLinks().isEmpty())
                        assertTrue(app.transferLinkConflicts().isEmpty())
                        assertEquals(1, app.reconciliationCandidates(86_400_000).size)
                        assertEquals(original, repository.current())
                    }
                    onNodeWithTag("accept-transfer-candidate-in-candidate-out").performScrollTo().performClick()
                    runOnIdle {
                        assertTrue(closed)
                        assertEquals(ActivityId("candidate-out"), app.transferLinks().single().outgoingActivity)
                        assertEquals(original, repository.current())
                        assertTrue(app.reconciliationCandidates(86_400_000).isEmpty())
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
                app.saveRule(original)
                app.saveRule(anyKind)
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
                    runOnIdle { assertEquals(original.copy(result = ActivityKind.REWARD), app.rules().first { it.id == original.id }) }
                    onNodeWithTag("toggle-rule-${original.id}").performScrollTo().performClick()
                    runOnIdle {
                        assertEquals(
                            original.copy(result = ActivityKind.REWARD, enabled = true),
                            app.rules().first {
                                it.id ==
                                    original.id
                            },
                        )
                    }
                    onNodeWithTag("toggle-rule-${original.id}").performScrollTo().performClick()
                    runOnIdle { assertEquals(original.copy(result = ActivityKind.REWARD), app.rules().first { it.id == original.id }) }
                    onNodeWithTag("edit-rule-${anyKind.id}").performScrollTo().performClick()
                    onNodeWithText("Any classification").performScrollTo().assertIsDisplayed()
                    onNodeWithText("Save rule").performScrollTo().performClick()
                    runOnIdle { assertEquals(anyKind, app.rules().first { it.id == anyKind.id }) }
                }
            }
        }
    }

    @Test
    fun historicalPriceRequiresExplicitConsentAndDate() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                val data = app.snapshot()
                runDesktopComposeUiTest(width = 800, height = 900) {
                    setContent {
                        WorkflowDialog("price", data, app, host, {}, { _, operation -> operation() }, {}, { _, _ -> })
                    }
                    onNodeWithTag("fetch-historical-price").performScrollTo().assertIsNotEnabled()
                    onNodeWithTag("price-source-consent").performScrollTo().performClick()
                    onNodeWithText(
                        "Historical UTC date (YYYY-MM-DD)",
                    ).performScrollTo().performTextInput(
                        kotlin.time.Clock.System
                            .now()
                            .toString()
                            .substringBefore('T'),
                    )
                    onNodeWithTag("fetch-historical-price").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { host.priceRequests == 1 }
                    onNodeWithText("Daily historical price saved from CoinGecko.").performScrollTo().assertIsDisplayed()
                }
                assertTrue(app.priceSourceConsent())
                assertEquals(1, host.priceRequests)
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
    fun activityCorrectionsSupportEveryLegAndResetToSource() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { System.currentTimeMillis() }
            app.seedDemo()
            val data = app.snapshot()
            val base = data.activities.first { it.activity.movements.size == 1 }
            val first = base.activity.movements.single()
            val second = first.copy(id = EventId("unknown-leg"), amount = AtomicAmount(AssetId("unknown"), ExactInteger.parse("42")))
            val source = base.original.copy(movements = listOf(first, second))
            val item = base.copy(original = source, activity = source)
            var saved: ActivityOverlay? = null
            runDesktopComposeUiTest(width = 360, height = 800) {
                setContent { ActivityDialog(item, data, {}, { saved = it }) }
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
    fun tenThousandActivitiesRemainPagedAndSearchable() {
        JvmSqliteDatabase(":memory:").use { db ->
            Host().use { host ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.seedDemo()
                val started = System.nanoTime()
                repeat(10_000) { index ->
                    val id = "bulk-${index.toString().padStart(5, '0')}"
                    app.saveManualActivity(
                        io.polion.core.ledger.Activity(
                            ActivityId(id),
                            System.currentTimeMillis(),
                            id,
                            io.polion.core.ledger.ActivityKind.DEPOSIT,
                            io.polion.core.ledger.ActivityStatus.CONFIRMED,
                            listOf(
                                io.polion.core.ledger.Movement(
                                    io.polion.core.model
                                        .EventId(id),
                                    io.polion.core.model
                                        .AccountId("demo-wallet"),
                                    io.polion.core.model.AtomicAmount(
                                        io.polion.core.model
                                            .AssetId("bitcoin:btc"),
                                        io.polion.core.model.ExactInteger
                                            .parse("1"),
                                    ),
                                    io.polion.core.ledger.MovementRole.TRANSFER,
                                ),
                            ),
                            emptySet(),
                            null,
                            true,
                        ),
                    )
                }
                runDesktopComposeUiTest(width = 1440, height = 1000) {
                    setContent { PolionApp(app, host) }
                    waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Opening your portfolio").fetchSemanticsNodes().isEmpty() }
                    onAllNodesWithText("Activity").filter(hasClickAction()).onFirst().performClick()
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
