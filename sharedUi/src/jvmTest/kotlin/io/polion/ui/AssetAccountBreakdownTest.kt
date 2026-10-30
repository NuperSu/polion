package io.polion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.AssetValuation
import io.polion.core.holdings.BalanceCoverage
import io.polion.core.holdings.BalanceKey
import io.polion.core.holdings.BalanceView
import io.polion.core.market.AssetMetadata
import io.polion.core.market.PriceStatus
import io.polion.core.model.AccountId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.ExactInteger
import io.polion.core.model.OwnerId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AssetAccountBreakdownTest {
    @Test
    fun showsScopedBalancesIncludingNegativeAndPaginatesWithoutOtherAssets() {
        val asset = AssetId("BTC")
        val accounts =
            (1..11).map {
                Account(
                    AccountId("a$it"),
                    OwnerId("owner"),
                    "Wallet ${it.toString().padStart(2, '0')}",
                    AccountKind.WALLET,
                )
            }
        val quantities =
            accounts.associate { BalanceKey(it.id, asset) to ExactInteger.parse(if (it.id.value == "a11") "-250" else "100") } +
                (BalanceKey(accounts.first().id, AssetId("ETH")) to ExactInteger.parse("999"))
        val valuation =
            AssetValuation(
                asset,
                AtomicAmount(asset, ExactInteger.parse("750")),
                AssetMetadata("BTC", "Bitcoin", 2, 0),
                PriceStatus.MISSING,
                null,
                null,
            )
        runDesktopComposeUiTest(width = 360, height = 800) {
            setContent {
                PolionTheme(AppearanceTheme.LIGHT) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AssetAccountBreakdown(valuation, BalanceView(quantities, BalanceCoverage.PARTIAL, 0), accounts)
                    }
                }
            }
            onNodeWithText("History coverage: partial · confirmed movements only").assertIsDisplayed()
            onNodeWithText("1.00 BTC · account a1").assertIsDisplayed()
            onNodeWithText("999 atomic units · account a1").assertDoesNotExist()
            onNodeWithText("Next accounts").performScrollTo().performClick()
            onNodeWithText("Wallet 11").performScrollTo().assertIsDisplayed()
            onNodeWithText("-2.50 BTC · account a11").assertIsDisplayed()
        }
    }
}
