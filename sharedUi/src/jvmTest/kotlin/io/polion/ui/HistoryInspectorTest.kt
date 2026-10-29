package io.polion.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
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
    fun inspectDatedPointsWithoutTreatingMissingPricesAsZero() {
        val asset = AssetId("BTC")

        fun valuation(value: String?) =
            AssetValuation(
                asset,
                AtomicAmount(asset, ExactInteger.parse("1")),
                null,
                if (value == null) PriceStatus.MISSING else PriceStatus.FRESH,
                value?.let(ExactDecimal::parse),
                null,
            )
        val first = HistoryPoint(0L, listOf(valuation("12.345")))
        val second = HistoryPoint(3_600_000L, listOf(valuation(null)))
        assertEquals(ExactDecimal.parse("12.345"), historyTotal(first))
        assertNull(historyTotal(second))
        assertNull(historyTotal(HistoryPoint(0L, emptyList())))
        runDesktopComposeUiTest(width = 360, height = 300) {
            setContent { PolionTheme(AppearanceTheme.DARK) { HistoryInspector(listOf(first, second), AssetId("USD")) } }
            onNodeWithText("1970-01-01 01:00 UTC · Value unavailable").assertIsDisplayed()
            onNodeWithTag("history-previous").performClick()
            onNodeWithText("1970-01-01 00:00 UTC · 12.345 USD").assertIsDisplayed()
            onNodeWithTag("history-next").performClick()
            onNodeWithText("1970-01-01 01:00 UTC · Value unavailable").assertIsDisplayed()
        }
    }
}
