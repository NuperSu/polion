package io.polion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.AssetAllocation
import io.polion.core.application.AssetValuation
import io.polion.core.holdings.BalanceCoverage
import io.polion.core.market.AssetMetadata
import io.polion.core.market.PriceStatus
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AllocationPanelTest {
    @Test
    fun compactPanelShowsReconciledSharesAndExclusionsWithPartialCoverage() {
        val allocation =
            AssetAllocation.project(
                listOf(
                    valuation("a", "1"),
                    valuation("b", "1", status = PriceStatus.STALE),
                    valuation("c", "1"),
                    valuation("unpriced", null),
                    valuation("negative", "-9000", amount = "-1"),
                    valuation("zero-price", "0"),
                ),
                AssetId("USD"),
                BalanceCoverage.PARTIAL,
            )
        runDesktopComposeUiTest(width = 360, height = 800) {
            setContent {
                PolionTheme(AppearanceTheme.LIGHT) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { AllocationPanel(allocation) }
                }
            }
            onNodeWithText("History coverage: partial · allocation may be incomplete").assertIsDisplayed()
            onNodeWithText("Included subtotal · 3.00 USD").assertIsDisplayed()
            onNodeWithText("33.34% · 1.00 USD").assertIsDisplayed()
            onNodeWithText("stale price").performScrollTo().assertIsDisplayed()
            onNodeWithText("UNPRICED · price unavailable").performScrollTo().assertIsDisplayed()
            onNodeWithText("NEGATIVE · zero or negative holding").performScrollTo().assertIsDisplayed()
            onNodeWithText("ZERO-PRICE · zero or negative priced value").performScrollTo().assertIsDisplayed()
            onNodeWithTag("allocation-negative").assertDoesNotExist()
            onNodeWithTag("allocation-unpriced").assertDoesNotExist()
        }
    }

    @Test
    fun widePanelRetainsExactLargeValues() {
        val allocation =
            AssetAllocation.project(
                listOf(valuation("precise", "9007199254740993.25")),
                AssetId("USD"),
                BalanceCoverage.COMPLETE,
            )
        runDesktopComposeUiTest(width = 1200, height = 800) {
            setContent { PolionTheme(AppearanceTheme.DARK) { AllocationPanel(allocation) } }
            onNodeWithText("History coverage: complete").assertIsDisplayed()
            onNodeWithText("Included subtotal · 9007199254740993.25 USD").assertIsDisplayed()
            onNodeWithText("100.00% · 9007199254740993.25 USD").assertIsDisplayed()
            onNodeWithText("Excluded from allocation").assertDoesNotExist()
        }
    }

    @Test
    fun allUnpricedPanelExplainsUnavailableAllocationWithoutZeroSubtotal() {
        val allocation =
            AssetAllocation.project(listOf(valuation("unknown", null)), AssetId("USD"), BalanceCoverage.UNKNOWN)
        runDesktopComposeUiTest(width = 360, height = 800) {
            setContent { PolionTheme(AppearanceTheme.LIGHT) { AllocationPanel(allocation) } }
            onNodeWithText("No positive priced holdings").assertIsDisplayed()
            onNodeWithText("History coverage: unknown · allocation may be incomplete").assertIsDisplayed()
            onNodeWithText("UNKNOWN · price unavailable").assertIsDisplayed()
            onNodeWithTag("allocation-subtotal").assertDoesNotExist()
            onNodeWithText("0.00%").assertDoesNotExist()
        }
    }

    private fun valuation(
        id: String,
        value: String?,
        amount: String = "1",
        status: PriceStatus = if (value == null) PriceStatus.MISSING else PriceStatus.FRESH,
    ): AssetValuation {
        val asset = AssetId(id)
        return AssetValuation(
            asset,
            AtomicAmount(asset, ExactInteger.parse(amount)),
            AssetMetadata(id.uppercase(), "Asset $id", 0, 0),
            status,
            value?.let(ExactDecimal::parse),
            null,
        )
    }
}
