package io.valroot.ui

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
import io.valroot.core.accounting.AccountingReport
import io.valroot.core.accounting.Consumption
import io.valroot.core.accounting.DisposalReport
import io.valroot.core.accounting.Lot
import io.valroot.core.application.AccountingView
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AssetAccountingDetailsTest {
    @Test
    fun compactViewFiltersAssetShowsUncertaintyAndPagesLots() {
        val btc = AssetId("BTC")
        val account = AccountId("account")
        val one = ExactInteger.parse("1")
        val lots =
            (1..11).map { index ->
                Lot("btc-$index", OwnerId("owner"), account, btc, 0, "$index", one, null, false)
            } + Lot("other-asset", OwnerId("owner"), account, AssetId("ETH"), 0, "0", one, null, false)
        val disposal =
            DisposalReport(ActivityId("sale"), account, btc, one, null, null, null, false, listOf(Consumption("btc-1", one, null)))
        val view =
            AccountingView(
                AccountingReport(AssetId("USD"), lots, listOf(disposal), emptyList()),
                listOf(ActivityId("unclassified")),
                emptyList(),
                listOf(ActivityId("uncertain")),
            )
        runDesktopComposeUiTest(width = 360, height = 800) {
            setContent {
                ValrootTheme(AppearanceTheme.LIGHT) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AssetAccountingContent(view, btc, 0, mapOf(account to "Wallet")) { }
                    }
                }
            }
            onNodeWithText("Open lots (11)").assertIsDisplayed()
            onNodeWithText(
                "Some portfolio activity needs classification or correction. Asset lots and gains may be incomplete or unavailable.",
            ).assertIsDisplayed()
            onNodeWithText("Some portfolio activity has unverified ordering; FIFO results are provisional.").assertIsDisplayed()
            onNodeWithText("Lot other-asset · exact basis unknown").assertDoesNotExist()
            onNodeWithText("Next").performScrollTo().performClick()
            onNodeWithText("Lot btc-11 · exact basis unknown").performScrollTo().assertIsDisplayed()
            onNodeWithText("Proceeds unknown · basis unknown · gain unknown USD").performScrollTo().assertIsDisplayed()
            onNodeWithText("Consumed 1 from btc-1 · exact basis unknown").performScrollTo().assertIsDisplayed()
        }
    }
}
