package io.valroot.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.ui.model.ModelPortfolioSummary
import io.valroot.ui.model.ModelPortfolioSummaryState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ModelPortfolioSummaryTest {
    @Test
    fun unsupportedRangesAreDisabled() {
        val selected = mutableListOf<String>()
        runDesktopComposeUiTest(width = 600, height = 900) {
            setContent {
                ModelPortfolioSummary(
                    state =
                        ModelPortfolioSummaryState(
                            balance = "—",
                            currency = "USD",
                            change = "—",
                            percentChange = "—",
                            selectedRange = "1D",
                            chartPoints = emptyList(),
                            enabledRanges = setOf("1D"),
                            chartTopLabel = "—",
                            chartBottomLabel = "—",
                        ),
                    wide = false,
                    short = false,
                    onRangeSelected = { selected += it },
                )
            }
            onNodeWithText("1H").assertIsNotEnabled()
            onNodeWithText("1D").performClick()
            runOnIdle { assertEquals(listOf("1D"), selected) }
        }
    }

    @Test
    fun rangeControlsDeliverTheirSelectionToTheHost() {
        var selected by mutableStateOf("1D")
        val selections = mutableListOf<String>()
        runDesktopComposeUiTest(width = 600, height = 900) {
            setContent {
                ModelPortfolioSummary(
                    state =
                        ModelPortfolioSummaryState(
                            balance = "123.45",
                            currency = "EUR",
                            change = "—",
                            percentChange = "—",
                            selectedRange = selected,
                            chartPoints = listOf(0.2f, 0.8f),
                            chartTopLabel = "High",
                            chartBottomLabel = "Low",
                        ),
                    wide = false,
                    short = false,
                    onRangeSelected = {
                        selections += it
                        selected = it
                    },
                )
            }
            onNodeWithText("123.45").assertIsDisplayed()
            onNodeWithText("EUR").assertIsDisplayed()
            for (range in listOf("1H", "1D", "1W", "1M", "YTD", "1Y", "ALL")) {
                onNodeWithText(range).performClick()
            }
            runOnIdle {
                assertEquals(listOf("1H", "1D", "1W", "1M", "YTD", "1Y", "ALL"), selections)
                assertEquals("ALL", selected)
            }
        }
    }
}
