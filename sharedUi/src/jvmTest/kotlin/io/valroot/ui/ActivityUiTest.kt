package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
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
import io.valroot.core.storage.JvmSqliteDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ActivityUiTest {
    @Test
    fun openLotLinksToItsAcquisitionActivity() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
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
    fun disposalOpensRecordedActivityAndEvidence() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
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
            TestHost().use { host ->
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

    @Test
    fun activityNetworkMenuUsesRecordedSources() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
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
            TestHost().use { host ->
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
    fun mediumLayoutRecordsAndPersistsManualActivity() {
        val path = Files.createTempFile("valroot-ui", ".sqlite")
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                TestHost().use { host ->
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
    fun tenThousandActivitiesRemainPagedAndSearchable() {
        JvmSqliteDatabase(":memory:").use { db ->
            TestHost().use { host ->
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
            TestHost().use { host ->
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
}
