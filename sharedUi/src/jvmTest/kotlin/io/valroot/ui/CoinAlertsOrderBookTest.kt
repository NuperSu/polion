package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.alerts.AlertCondition
import io.valroot.core.alerts.PriceAlert
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.market.OrderBookSnapshot
import io.valroot.core.market.OrderLevel
import io.valroot.core.model.ExactDecimal
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.ui.model.ModelAlertRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CoinAlertsOrderBookTest {
    /** One CoinGecko top-250 entry for Bitcoin at 90,000; trending is empty. */
    private fun bitcoinFeeds(url: String): String =
        if (url.contains("/coins/markets")) {
            """[{"id":"bitcoin","symbol":"btc","name":"Bitcoin","market_cap_rank":1,"current_price":90000,
                "market_cap":1800000000000,"price_change_percentage_1h_in_currency":0.1,
                "price_change_percentage_24h_in_currency":1.5,"price_change_percentage_7d_in_currency":3.0,
                "sparkline_in_7d":{"price":[1.0,2.0,1.5,3.0]}}]"""
        } else {
            """{"coins":[]}"""
        }

    private fun book(
        bidPrice: String,
        askPrice: String,
        observedAtMillis: Long = System.currentTimeMillis(),
    ) = OrderBookSnapshot(
        venue = "Kraken",
        pair = "XBT/USD",
        bids =
            listOf(
                OrderLevel(ExactDecimal.parse(bidPrice), ExactDecimal.parse("0.5000")),
                OrderLevel(ExactDecimal.parse("89999.5"), ExactDecimal.parse("2.0")),
            ),
        asks =
            listOf(
                OrderLevel(ExactDecimal.parse(askPrice), ExactDecimal.parse("0.2500")),
                OrderLevel(ExactDecimal.parse("90001.00"), ExactDecimal.parse("3.0")),
            ),
        observedAtMillis = observedAtMillis,
    )

    @Test fun exactValuesDropTrailingZerosButKeepOneDigit() {
        assertEquals("90000.1", exactText(ExactDecimal.parse("90000.10")))
        assertEquals("2.0", exactText(ExactDecimal.parse("2.00")))
        assertEquals("2", exactText(ExactDecimal.parse("2")))
        assertEquals("0.5", exactText(ExactDecimal.parse("0.5000")))
    }

    @Test fun orderBookRowsPadTheShorterSide() {
        val rows = orderBookRows(book("89999.90", "90000.10").copy(asks = emptyList()))
        assertEquals(2, rows.size)
        assertEquals("89999.9", rows[0].bid)
        assertEquals("", rows[0].ask)
        assertEquals("", rows[1].ask)
        assertEquals(emptyList(), orderBookRows(null))
    }

    @Test fun thresholdsAcceptPlainAndGroupedNumbersOnly() {
        assertEquals(ExactDecimal.parse("100000"), parseAlertThreshold("100,000"))
        assertEquals(ExactDecimal.parse("2.5"), parseAlertThreshold(" 2.5 "))
        assertFailsWith<IllegalArgumentException> { parseAlertThreshold("") }
        assertFailsWith<IllegalArgumentException> { parseAlertThreshold("1,00") }
        assertFailsWith<IllegalArgumentException> { parseAlertThreshold("5.") }
    }

    @Test fun alertBlockReasonsNameWhatToDo() {
        assertEquals(ALERT_TOP_250_NOTE, alertBlockReason(null, marketConsent = true, listed = false))
        assertEquals(ALERT_CONSENT_NOTE, alertBlockReason("bitcoin", marketConsent = false, listed = true))
        assertEquals(ALERT_TOP_250_NOTE, alertBlockReason("bitcoin", marketConsent = true, listed = false))
        assertEquals(null, alertBlockReason("bitcoin", marketConsent = true, listed = true))
    }

    @Test fun alertRowsDescribeWaitingAndLastAlert() {
        val base =
            PriceAlert(
                id = "alert-1",
                coinId = "bitcoin",
                symbol = "btc",
                currency = "usd",
                condition = AlertCondition.ABOVE,
                threshold = ExactDecimal.parse("100000"),
                enabled = true,
                armed = false,
                reference = null,
                referenceAtMillis = null,
                lastObservedMillis = null,
                lastFiredMillis = null,
                createdAtMillis = 0L,
            )
        val waiting: ModelAlertRule = coinAlertRule(base)
        assertEquals("Above 100,000.00 USD", waiting.title)
        assertEquals("Price at or above 100,000.00 USD · waits for the price to cross back", waiting.description)
        assertEquals("CoinGecko · BTC/USD", waiting.source)
        val moved = coinAlertRule(base.copy(condition = AlertCondition.MOVE, threshold = ExactDecimal.parse("5"), armed = false))
        assertEquals("Moves 5%", moved.title)
        assertTrue(!moved.description.contains("waits"))
    }

    @Test fun alertsAddToggleEditAndDeleteFromTheCoinPage() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.updateOnboarding(OnboardingAction.Skip)
                app.setMarketDataConsent(true)
                app.refreshMarketFeeds({ url, _ -> bitcoinFeeds(url) })
                var notified = 0
                val host =
                    object : PlatformActions by delegate {
                        override fun notificationSettingsChanged() {
                            notified++
                        }
                    }
                runDesktopComposeUiTest(width = 600, height = 1400) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithTag("market-top-bitcoin:btc").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("market-top-bitcoin:btc").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("detail-Alerts").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("detail-Alerts").performScrollTo().performClick()
                    onNodeWithTag("alert-add").performClick()
                    onNodeWithTag("alert-save").performClick()
                    onNodeWithText("Enter a number, like 100000 or 2.5.").assertIsDisplayed()
                    onNodeWithTag("alert-threshold").performTextInput("100000")
                    onNodeWithTag("alert-save").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.priceAlerts("bitcoin").size == 1 }
                    val alert = app.priceAlerts("bitcoin").single()
                    assertEquals(AlertCondition.ABOVE, alert.condition)
                    assertEquals(ExactDecimal.parse("100000"), alert.threshold)
                    onNodeWithText("Above 100,000.00 USD").assertIsDisplayed()

                    onNodeWithTag("alert-switch-${alert.id}").performClick()
                    waitUntil(timeoutMillis = 10_000) { !app.priceAlerts("bitcoin").single().enabled }
                    onNodeWithTag("alert-editor").assertDoesNotExist()

                    onNodeWithTag("alert-row-${alert.id}").performClick()
                    onNodeWithTag("alert-threshold").performTextReplacement("120000")
                    onNodeWithTag("alert-save").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        app.priceAlerts("bitcoin").single().threshold == ExactDecimal.parse("120000")
                    }
                    onNodeWithText("Above 120,000.00 USD").assertIsDisplayed()
                    assertTrue(!app.priceAlerts("bitcoin").single().enabled, "Editing keeps the switch state")

                    onNodeWithTag("alert-row-${alert.id}").performClick()
                    onNodeWithTag("alert-delete").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.priceAlerts("bitcoin").isEmpty() }
                    onNodeWithText("Above 120,000.00 USD").assertDoesNotExist()
                    // Save, toggle, edit and delete each tell the host once.
                    assertEquals(4, notified)
                }
            }
        }
    }

    @Test fun orderBookLoadsAfterConsentAndKeepsTheLastBookWhenARequestFails() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = PortfolioApplication(db) { System.currentTimeMillis() }
                app.updateOnboarding(OnboardingAction.Skip)
                app.setMarketDataConsent(true)
                app.refreshMarketFeeds({ url, _ -> bitcoinFeeds(url) })
                var next: OrderBookResult = OrderBookResult.Loaded(book("89999.90", "90000.10"))
                var requests = 0
                val host =
                    object : PlatformActions by delegate {
                        override fun fetchOrderBook(
                            coinId: String,
                            onResult: (OrderBookResult) -> Unit,
                        ) {
                            requests++
                            onResult(next)
                        }
                    }
                runDesktopComposeUiTest(width = 600, height = 1400) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithTag("market-top-bitcoin:btc").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("market-top-bitcoin:btc").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("detail-Order book").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("detail-Order book").performScrollTo().performClick()

                    onNodeWithText(
                        "Order books come from Kraken. Kraken receives the trading pair and your IP address.",
                    ).assertIsDisplayed()
                    assertEquals(0, requests, "No order book is requested before consent")
                    onNodeWithTag("order-consent").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("order-row-0").fetchSemanticsNodes().isNotEmpty() }
                    assertTrue(app.orderBookConsent())
                    onNodeWithText("Kraken · XBT/USD · updated", substring = true).assertIsDisplayed()
                    onNodeWithTag("order-row-0").assertExists()
                    onNodeWithText("89999.9").assertIsDisplayed()
                    onNodeWithText("90000.1").assertIsDisplayed()
                    onNodeWithText("90001.0").assertIsDisplayed()
                    onNodeWithText("0.25").assertIsDisplayed()
                    onNodeWithText("Out of date").assertDoesNotExist()

                    // The next scheduled poll fails; the last book stays visible, marked out of date, with Retry.
                    next = OrderBookResult.Failed("Could not load Kraken's order book.")
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Out of date").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Could not load Kraken's order book.").assertIsDisplayed()
                    onNodeWithText("89999.9").assertIsDisplayed()
                    val before = requests
                    onNodeWithTag("order-retry").performClick()
                    waitUntil(timeoutMillis = 10_000) { requests > before }
                    onNodeWithText("Out of date").assertIsDisplayed()
                }
            }
        }
    }
}
