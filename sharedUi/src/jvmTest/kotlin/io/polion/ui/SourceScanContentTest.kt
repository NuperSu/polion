package io.polion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.SourceAddressScan
import io.polion.core.application.SourceScanJob
import io.polion.core.model.AccountId
import io.polion.core.model.NetworkId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SourceScanContentTest {
    @Test fun showsFetchStateAndPaginatesAddresses() {
        val rows =
            (1..11).map { index ->
                SourceAddressScan(
                    TrackingTarget(
                        TrackingTargetId("t$index"),
                        AccountId("account"),
                        TargetIdentity(NetworkId("bitcoin-mainnet"), "address", "address-$index"),
                        TargetRole.LEAF,
                        "Address $index",
                    ),
                    if (index ==
                        11
                    ) {
                        null
                    } else {
                        SourceScanJob("COMPLETED", 2, "PARTIAL", "provider")
                    },
                )
            }
        runDesktopComposeUiTest(width = 360, height = 800) {
            setContent {
                PolionTheme(
                    AppearanceTheme.LIGHT,
                ) { Column(Modifier.verticalScroll(rememberScrollState())) { SourceScanContent(rows) } }
            }
            onAllNodesWithText("Fetch completed · 2 pages · provider coverage partial").onFirst().performScrollTo().assertIsDisplayed()
            onNodeWithText("Next addresses").performScrollTo().performClick()
            onNodeWithText("Address 11").performScrollTo().assertIsDisplayed()
            onNodeWithText("No saved fetch job").assertIsDisplayed()
        }
    }
}
