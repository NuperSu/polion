package io.polion.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.polion.core.application.AssetValuation
import io.polion.core.application.HistoryPoint
import io.polion.core.market.PriceStatus
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class HistoryInspectorTest {
    @Test
    fun totalsLeaveOutUnpricedHoldingsAndAreUnavailableOnlyWhenNoneIsPriced() {
        val btc = AssetId("BTC")
        val zero = AssetValuation(btc, AtomicAmount(btc, ExactInteger.ZERO), null, PriceStatus.MISSING, null, null)
        val active = zero.copy(amount = AtomicAmount(btc, ExactInteger.parse("1")))
        val priced = active.copy(priceStatus = PriceStatus.FRESH, value = ExactDecimal.parse("4"))
        assertEquals(ExactDecimal.parse("0"), historyTotal(HistoryPoint(0, listOf(zero))))
        assertEquals(ExactDecimal.parse("4"), historyTotal(HistoryPoint(0, listOf(zero, priced))))
        assertNull(historyTotal(HistoryPoint(0, listOf(zero, active))))
        assertNull(historyTotal(HistoryPoint(0, listOf(zero, priced.copy(priceStatus = PriceStatus.STALE)))))
        val eth = AssetId("ETH")
        val unpricedEth = active.copy(asset = eth, amount = AtomicAmount(eth, ExactInteger.parse("1")))
        val partial = HistoryPoint(0, listOf(priced, unpricedEth))
        assertEquals(ExactDecimal.parse("4"), historyTotal(partial))
        assertEquals(1, historyUnpriced(partial))
        runDesktopComposeUiTest(width = 360, height = 300) {
            setContent {
                PolionTheme(
                    AppearanceTheme.DARK,
                ) { HistoryInspector(listOf(HistoryPoint(0, listOf(zero)), partial), AssetId("USD")) }
            }
            onNodeWithText("1970-01-01 00:00 UTC · 4 USD · 1 asset has no price").assertIsDisplayed()
            onNodeWithTag("history-previous").performClick()
            onNodeWithText("1970-01-01 00:00 UTC · 0 USD").assertIsDisplayed()
        }
    }

    @Test
    fun chartDistinguishesLoadingErrorsAndMissingPrices() {
        runDesktopComposeUiTest(width = 360, height = 300) {
            setContent { ValueChart(emptyList(), Modifier.size(200.dp), dark = false, loading = true, error = false) }
            onNodeWithText("Loading history…").assertIsDisplayed()
            onNodeWithText("Fresh historical prices are needed to show value over time").assertDoesNotExist()
        }
        runDesktopComposeUiTest(width = 360, height = 300) {
            setContent { ValueChart(emptyList(), Modifier.size(200.dp), dark = false, loading = false, error = true) }
            onNodeWithText("History could not be loaded").assertIsDisplayed()
        }
        runDesktopComposeUiTest(width = 360, height = 300) {
            setContent { ValueChart(emptyList(), Modifier.size(200.dp), dark = false, loading = false, error = false) }
            onNodeWithText("No history available yet").assertIsDisplayed()
        }
        runDesktopComposeUiTest(width = 360, height = 300) {
            setContent { ValueChart(listOf(HistoryPoint(0, emptyList())), Modifier.size(200.dp), false, false, false) }
            onNodeWithText("No tracked holdings in this period").assertIsDisplayed()
        }
        val btc = AssetId("BTC")
        val missing = AssetValuation(btc, AtomicAmount(btc, ExactInteger.parse("1")), null, PriceStatus.MISSING, null, null)
        runDesktopComposeUiTest(width = 360, height = 300) {
            setContent { ValueChart(listOf(HistoryPoint(0, listOf(missing))), Modifier.size(200.dp), false, false, false) }
            onNodeWithText("Fresh historical prices are needed to show value over time").assertIsDisplayed()
        }
    }

    @Test
    fun inspectDatedPointsWithoutTreatingMissingPricesAsZero() {
        val asset = AssetId("BTC")

        fun valuation(
            value: String?,
            status: PriceStatus = if (value == null) PriceStatus.MISSING else PriceStatus.FRESH,
        ) = AssetValuation(
            asset,
            AtomicAmount(asset, ExactInteger.parse("1")),
            null,
            status,
            value?.let(ExactDecimal::parse),
            null,
        )
        val first = HistoryPoint(0L, listOf(valuation("12.345")))
        val second = HistoryPoint(3_600_000L, listOf(valuation(null)))
        val stale = HistoryPoint(7_200_000L, listOf(valuation("14", PriceStatus.STALE)))
        assertEquals(ExactDecimal.parse("12.345"), historyTotal(first))
        assertNull(historyTotal(second))
        assertNull(historyTotal(stale))
        assertNull(historyTotal(HistoryPoint(0L, emptyList())))
        runDesktopComposeUiTest(width = 360, height = 300) {
            setContent { PolionTheme(AppearanceTheme.DARK) { HistoryInspector(listOf(first, second, stale), AssetId("USD")) } }
            onNodeWithText("1970-01-01 02:00 UTC · Value unavailable · stale price").assertIsDisplayed()
            onNodeWithTag("history-previous").performClick()
            onNodeWithText("1970-01-01 01:00 UTC · Value unavailable · missing price").assertIsDisplayed()
            onNodeWithTag("history-previous").performClick()
            onNodeWithText("1970-01-01 00:00 UTC · 12.345 USD").assertIsDisplayed()
            onNodeWithTag("history-next").performClick()
            onNodeWithText("1970-01-01 01:00 UTC · Value unavailable · missing price").assertIsDisplayed()
        }
    }
}
