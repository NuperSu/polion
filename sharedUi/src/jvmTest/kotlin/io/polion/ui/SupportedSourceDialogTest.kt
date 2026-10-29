package io.polion.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.sources.SourceChoice
import io.polion.core.sources.SourceWorkflow
import io.polion.core.sources.SupportedSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SupportedSourceDialogTest {
    @Test
    fun catalogChoicesDisplayTheirMetadataAndDispatchTheSelectedWorkflow() {
        var selected: SourceChoice? = null
        var dismissed = false
        runDesktopComposeUiTest(width = 800, height = 900) {
            setContent {
                PolionTheme(AppearanceTheme.LIGHT) {
                    SupportedSourceDialog(onSelect = { selected = it }, onDismiss = { dismissed = true })
                }
            }
            SupportedSources.choices.forEach { choice ->
                onNodeWithTag("source-choice-${choice.id}").performScrollTo().assertIsDisplayed()
                onNodeWithText(choice.title).assertIsDisplayed()
                onNodeWithText(choice.description).assertIsDisplayed()
                onNodeWithTag("source-choice-${choice.id}").performClick()
                assertEquals(choice, selected)
            }
            onNodeWithText("Close").performClick()
            assertTrue(dismissed)
        }
    }

    @Test
    fun supportedWorkflowsRouteToTheirImplementedForms() {
        assertEquals(ProductDialog.ACCOUNT, SourceWorkflow.BITCOIN_ADDRESS.productDialog())
        assertEquals(ProductDialog.WALLET, SourceWorkflow.BITCOIN_PUBLIC_WALLET.productDialog())
        assertEquals(ProductDialog.ACTIVITY, SourceWorkflow.MANUAL_ACTIVITY.productDialog())
    }
}
