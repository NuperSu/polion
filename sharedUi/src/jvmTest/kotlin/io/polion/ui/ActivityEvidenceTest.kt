package io.polion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.application.ActivitySourceEvidence
import io.polion.core.application.SavedObservation
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ActivityEvidenceTest {
    @Test fun displaysObservationProvenanceAndRevisionLimitOnCompactScreen() {
        val evidence =
            listOf(
                ActivitySourceEvidence(
                    "source-1",
                    "bitcoin",
                    "txid",
                    "bitcoin-mainnet",
                    listOf(SavedObservation("provider", "2024-01-01T00:00:00Z", "abc", "v1")),
                    true,
                ),
            )
        runDesktopComposeUiTest(width = 360, height = 800) {
            setContent {
                PolionTheme(
                    AppearanceTheme.LIGHT,
                ) { Column(Modifier.verticalScroll(rememberScrollState())) { ActivityEvidenceContent(evidence) } }
            }
            onNodeWithText(
                "Saved observations may include multiple revisions; the decoder's exact revision is not identified here.",
            ).assertIsDisplayed()
            onNodeWithText("Source provider · retrieved 2024-01-01T00:00:00Z").performScrollTo().assertIsDisplayed()
            onNodeWithText("Revision v1 · checksum abc").performScrollTo().assertIsDisplayed()
            onNodeWithText("Showing the 10 most recent observations.").performScrollTo().assertIsDisplayed()
        }
    }
}
