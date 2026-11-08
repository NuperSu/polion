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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.polion.core.application.ActivityFilter
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.TransactionDraft
import io.polion.core.application.TransactionType
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.OwnerId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.storage.JvmSqliteDatabase
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PhaseThreeTransactionsTest {
    private val btc = io.polion.core.bitcoin.BITCOIN_ASSET
    private val usd = AssetId("USD")
    private val account = AccountId("manual")

    private fun setup(app: PortfolioApplication) {
        app.snapshot()
        app.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.saveAccount(Account(account, OwnerId("owner"), "Local account", AccountKind.ACCOUNT))
    }

    private fun trade(
        id: String,
        amount: String,
        price: String,
        type: TransactionType = TransactionType.BUY,
        time: Long = 1000L,
    ) = TransactionDraft(ActivityId(id), type, btc, account, ExactDecimal.parse(amount), time, usd, ExactDecimal.parse(price))

    @Test fun timelineUsesWeightedGrossExecutionsAndExportsAllMatchingPages() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 10000L }
            setup(app)
            app.saveTransaction(trade("buy1", "1", "100").copy(feeAsset = usd, feeQuantity = ExactDecimal.parse("2"), notes = "searchable"))
            app.saveTransaction(trade("buy2", "3", "200", time = 2000))
            app.saveTransaction(trade("sell", "0.5", "300", TransactionType.SELL, 3000))
            val filter = ActivityFilter(asset = btc, limit = 1)
            val timeline = transactionTimeline(app, app.snapshot(), filter, "All")
            assertEquals("175.00 USD", timeline.metrics[0].value)
            assertEquals("300.00 USD", timeline.metrics[1].value)
            assertEquals(3, timeline.total)
            assertEquals(1, timeline.cards.size)
            assertEquals("$150.00", timeline.cards.single().worthThen)
            assertEquals("—", timeline.cards.single().worthNow)
            val quoteTimeline = transactionTimeline(app, app.snapshot(), filter.copy(asset = usd), "All")
            assertEquals("$150.00", quoteTimeline.cards.single().worthNow)
            assertEquals("$150.00", quoteTimeline.cards.single().worthThen)
            val buys = transactionTimeline(app, app.snapshot(), filter, "Buy")
            assertEquals(2, buys.total)
            val ids =
                app
                    .transactionEntries(filter)
                    .filter {
                        it.activity.id.value
                            .startsWith("buy")
                    }.map { it.activity.id }
                    .toSet()
            val csv = app.exportCsv(filter, ids)
            assertTrue(csv.contains("buy1") && csv.contains("buy2") && !csv.contains("sell"))
            val search = transactionTimeline(app, app.snapshot(), filter.copy(search = "searchable"), "All")
            assertEquals(1, search.total)
            assertEquals("2 USD", search.cards.single().fee)
            val feeAsset = AssetId("fixture:fee")
            app.registerAsset(
                io.polion.core.market.AssetDefinition(
                    feeAsset,
                    io.polion.core.market
                        .AssetIdentity(io.polion.core.market.AssetKind.CUSTOM, null, "FEE"),
                ),
                io.polion.core.market
                    .AssetMetadata("FEE", "Fee fixture", 18, 0),
            )
            app.saveTransaction(
                trade(
                    "third-asset-fee",
                    "0.1",
                    "100",
                ).copy(feeAsset = feeAsset, feeQuantity = ExactDecimal.parse("0.01"), feeUnitPrice = ExactDecimal.parse("20")),
            )
            val feeTimeline = transactionTimeline(app, app.snapshot(), filter.copy(asset = feeAsset), "Fee")
            assertEquals("Local account", feeTimeline.cards.single().from)
            assertEquals("—", feeTimeline.cards.single().to)
            assertEquals("$0.20", feeTimeline.cards.single().worthThen)
        }
    }

    @Test fun formSavesLinkedTradeAfterReviewOnPhoneAndDesktopSizes() {
        for ((width, height, fontScale) in listOf(
            Triple(360, 800, 1f),
            Triple(390, 844, 1f),
            Triple(1440, 900, 1f),
            Triple(390, 844, 1.5f),
        )) {
            JvmSqliteDatabase(":memory:").use { db ->
                ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                    val app = PortfolioApplication(db) { 10000L }
                    setup(app)
                    // A deposit exposes the registered coin in the asset picker without a fake market feed.
                    app.saveTransaction(
                        TransactionDraft(ActivityId("opening"), TransactionType.DEPOSIT, btc, account, ExactDecimal.parse("1"), 0, usd),
                    )
                    val data = app.snapshot()
                    var saved = 0
                    runDesktopComposeUiTest(width = width, height = height) {
                        setContent {
                            val density = LocalDensity.current
                            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                                PolionTheme(AppearanceTheme.DARK) {
                                    TransactionEntry(app, host, data, true, btc, null, {}, {}, {}, {}, { saved++ }, {})
                                }
                            }
                        }
                        onNodeWithTag("preview-save-transaction").assertIsNotEnabled()
                        onNodeWithText("Executed / evidence price (USD); optional outside trades").performScrollTo().performTextInput("200")
                        onNodeWithText("Amount (Buy)").performScrollTo().performTextInput("1.25")
                        onNodeWithText("Notes").performScrollTo().performTextInput("UI buy $width")
                        onNodeWithTag("preview-save-transaction").performClick()
                        onNodeWithTag("transaction-review-title").assertIsDisplayed()
                        val output = File("build/phase3-transaction-previews").apply { mkdirs() }
                        ImageIO.write(
                            onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(),
                            "png",
                            File(output, "${width}x$height-$fontScale-trade-review.png"),
                        )
                        onNodeWithTag("transaction-confirm").performClick()
                        waitUntil(timeoutMillis = 10000) { saved == 1 }
                        onNodeWithTag("transaction-confirm").performClick()
                        waitUntil(timeoutMillis = 10000) { saved == 2 }
                        val entry = app.transactionEntries(ActivityFilter(search = "UI buy $width")).single()
                        assertEquals(listOf("125000000", "-25000"), entry.activity.movements.map { it.amount.atomicUnits.toString() })
                        assertEquals(2, app.treatments(entry.activity.id).size)
                    }
                }
            }
        }
    }

    @Test fun productionCoinJourneySavesEditsAndReversesWithRetainedIds() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(io.polion.core.onboarding.OnboardingAction.Skip)
                app.seedDemo()
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent { PolionApp(app, host) }
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("model-PORTFOLIO").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("coin-BTC").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("coin-BTC").performScrollTo().performClick()
                    onNodeWithTag("detail-Transactions").performScrollTo().performClick()
                    onNodeWithTag("detail-add-transaction").performScrollTo().performClick()
                    onNodeWithText("Executed / evidence price (USD); optional outside trades").performScrollTo().performTextInput("100")
                    onNodeWithText("Amount (Buy)").performScrollTo().performTextInput("0.25")
                    onNodeWithText("Notes").performScrollTo().performTextInput("Production journey")
                    onNodeWithTag("preview-save-transaction").performClick()
                    onNodeWithTag("transaction-confirm").performClick()
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("preview-save-transaction").fetchSemanticsNodes().isEmpty() }
                    waitUntil(timeoutMillis = 10000) { app.transactionEntries(ActivityFilter(search = "Production journey")).size == 1 }
                    val id =
                        app
                            .transactionEntries(ActivityFilter(search = "Production journey"))
                            .single()
                            .activity.id
                    waitUntil(
                        timeoutMillis = 10000,
                    ) { onAllNodesWithTag("transaction-card-${id.value}").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("transaction-card-${id.value}").performScrollTo()
                    val output = File("build/phase3-transaction-previews").apply { mkdirs() }
                    ImageIO.write(
                        onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(),
                        "png",
                        File(output, "390x844-1.0-saved-trade-card.png"),
                    )
                    onNodeWithTag("transaction-card-${id.value}").performClick()
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("edit-manual-transaction").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("edit-manual-transaction").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("preview-save-transaction").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Notes").performScrollTo().performTextReplacement("Production journey reviewed")
                    onNodeWithTag("preview-save-transaction").performClick()
                    onNodeWithTag("transaction-confirm").performClick()
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("preview-save-transaction").fetchSemanticsNodes().isEmpty() }
                    assertEquals("Production journey reviewed", app.transactionDraft(id)?.notes)
                    onNodeWithTag("transaction-card-${id.value}").performScrollTo().performClick()
                    onNodeWithText("Reverse manual transaction").performScrollTo().performClick()
                    onNodeWithText("Confirm reversal").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10000) {
                        app.effectiveActivity(id)?.activity?.status ==
                            io.polion.core.ledger.ActivityStatus.REVERSED
                    }
                    assertEquals(id, app.transactionDraft(id)?.id)
                }
            }
        }
    }

    @Test fun reviewAndDraftSurviveRecreationWithoutPrematureSubmission() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 10000L }
                setup(app)
                app.saveTransaction(
                    TransactionDraft(ActivityId("opening"), TransactionType.DEPOSIT, btc, account, ExactDecimal.parse("1"), 0, usd),
                )
                val data = app.snapshot()
                val visible = mutableStateOf(true)
                var registry = SaveableStateRegistry(null) { true }
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent {
                        if (visible.value) {
                            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                                PolionTheme(
                                    AppearanceTheme.DARK,
                                ) { TransactionEntry(app, host, data, true, btc, null, {}, {}, {}, {}, {}, {}) }
                            }
                        }
                    }
                    onNodeWithText("Executed / evidence price (USD); optional outside trades").performScrollTo().performTextInput("100")
                    onNodeWithText("Amount (Buy)").performScrollTo().performTextInput("2")
                    onNodeWithTag("preview-save-transaction").performClick()
                    var state = emptyMap<String, List<Any?>>()
                    runOnIdle {
                        state = registry.performSave()
                        visible.value = false
                    }
                    runOnIdle {
                        registry = SaveableStateRegistry(state) { true }
                        visible.value = true
                    }
                    waitUntil(timeoutMillis = 10000) { onAllNodesWithTag("transaction-confirm").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("transaction-review-title").assertIsDisplayed()
                    assertEquals(1, app.snapshot().activityTotal)
                    onNodeWithText("Edit draft").performClick()
                    onNodeWithText("2").performScrollTo().assertIsDisplayed()
                }
            }
        }
    }
}
