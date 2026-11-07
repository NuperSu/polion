package io.polion.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.polion.core.application.LandingPage
import io.polion.core.application.PortfolioApplication
import io.polion.core.onboarding.OnboardingAction
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.ui.model.ChartData
import io.polion.ui.model.ChartStyle
import io.polion.ui.model.ModelInteractiveChart
import io.polion.ui.model.buildCandles
import io.polion.ui.model.chartPercent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InteractiveChartTest {
    private val data =
        ChartData(
            listOf(100.0, 110.0, null, 90.0, 120.0, 130.0, 125.0, 140.0),
            (0L until 8L).map { it * 3_600_000L },
            { "$${it.toInt()}" },
            { "t${it / 3_600_000L}" },
        )

    @Test fun candlesTakeFirstHighLowLastPerBucketAndKeepGaps() {
        val candles = buildCandles(data, target = 4)
        assertEquals(4, candles.size)
        assertEquals(100.0, candles[0]!!.open)
        assertEquals(110.0, candles[0]!!.close)
        // The second bucket has one known value; the third spans 120 to 130.
        assertEquals(90.0, candles[1]!!.open)
        assertEquals(90.0, candles[1]!!.high)
        assertEquals(130.0, candles[2]!!.high)
        assertEquals(125.0, candles[3]!!.open)
        val gap = buildCandles(ChartData(listOf(1.0, null, null, 2.0), listOf(0L, 1L, 2L, 3L), { "" }, { "" }), target = 4)
        assertNull(gap[1])
        assertEquals("+40.00%", chartPercent(100.0, 140.0))
        assertEquals("-10.00%", chartPercent(100.0, 90.0))
        assertEquals("—", chartPercent(null, 1.0))
    }

    @Test fun hoverAndTouchShowValueChangeAndTime() {
        runDesktopComposeUiTest(width = 400, height = 300) {
            setContent {
                PolionTheme(AppearanceTheme.DARK) {
                    ModelInteractiveChart(data, ChartStyle.LINE, Modifier.fillMaxWidth().height(200.dp))
                }
            }
            onNodeWithTag("chart-tooltip").assertDoesNotExist()
            onNodeWithTag("interactive-chart").performMouseInput {
                moveTo(
                    androidx.compose.ui.geometry
                        .Offset(width - 1f, height / 2f),
                )
            }
            waitForIdle()
            onNodeWithText("$140 · +40.00%").assertExists()
            onNodeWithText("t7").assertExists()
            onNodeWithTag("interactive-chart").performMouseInput { exit() }
            waitForIdle()
            onNodeWithTag("chart-tooltip").assertDoesNotExist()
            onNodeWithTag("interactive-chart").performTouchInput {
                down(
                    androidx.compose.ui.geometry
                        .Offset(1f, height / 2f),
                )
            }
            waitForIdle()
            onNodeWithText("$100 · 0.00%").assertExists()
            onNodeWithTag("interactive-chart").performTouchInput { up() }
            waitForIdle()
            onNodeWithTag("chart-tooltip").assertDoesNotExist()
        }
    }

    @Test fun hiddenAmountsKeepPercentagesInTheTooltip() {
        runDesktopComposeUiTest(width = 400, height = 300) {
            setContent {
                PolionTheme(AppearanceTheme.DARK) {
                    ModelInteractiveChart(data.copy(hideValues = true), ChartStyle.CANDLES, Modifier.fillMaxWidth().height(200.dp))
                }
            }
            assertTrue(onAllNodesWithText("$", substring = true).fetchSemanticsNodes().isEmpty())
            onNodeWithTag("interactive-chart").performMouseInput {
                moveTo(
                    androidx.compose.ui.geometry
                        .Offset(width - 1f, height / 2f),
                )
            }
            waitForIdle()
            onNodeWithText("O *****", substring = true).assertExists()
            onNodeWithText("+40.00%", substring = true).assertExists()
        }
    }

    @Test fun portfolioShowsPerformanceAllocationAndKeepsTheCandleChoice() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.updateOnboarding(OnboardingAction.Skip)
                app.seedDemo()
                app.setLandingPage(LandingPage.PORTFOLIO)
                runDesktopComposeUiTest(width = 600, height = 1600) {
                    setContent { PolionApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("portfolio-stats").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Cost basis").assertExists()
                    onNodeWithTag("portfolio-allocation").performScrollTo()
                    onNodeWithText("100.0%").assertExists()
                    onNodeWithTag("chart-style-candles").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { app.generalPreferences().candleCharts }
                    onNodeWithTag("chart-style-line").performClick()
                    waitUntil(timeoutMillis = 10_000) { !app.generalPreferences().candleCharts }
                    assertTrue(onAllNodesWithText("Portfolio value change includes", substring = true).fetchSemanticsNodes().isEmpty())
                }
            }
        }
    }
}
