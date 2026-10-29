package io.polion.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.polion.core.onboarding.OnboardingAction
import io.polion.core.onboarding.OnboardingGoal
import io.polion.core.onboarding.OnboardingPresentation
import io.polion.core.onboarding.OnboardingReducer
import io.polion.core.onboarding.OnboardingState
import io.polion.core.onboarding.OnboardingStep
import io.polion.core.sources.SourceWorkflow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class OnboardingScreenTest {
    @Test
    fun formAndConversationShareAnswersBackAndOptionalCompletion() {
        var state by mutableStateOf(OnboardingState())
        var finished = false
        runDesktopComposeUiTest(width = 420, height = 900) {
            setContent {
                PolionTheme(AppearanceTheme.LIGHT) {
                    OnboardingScreen(state, false, null, { state = OnboardingReducer.reduce(state, it) }) { finished = true }
                }
            }
            onNodeWithTag("onboarding").performScrollToNode(hasText("Continue"))
            onNodeWithText("Continue").performScrollTo().performClick()
            onNodeWithTag("onboarding").performScrollToNode(hasTestTag("onboarding-goal-BALANCES"))
            onNodeWithTag("onboarding-goal-BALANCES").performScrollTo().performClick()
            onNodeWithTag("onboarding").performScrollToNode(hasText("Guided conversation"))
            onNodeWithText("Guided conversation").performClick()
            assertEquals(OnboardingPresentation.ASSISTED, state.presentation)
            onNodeWithTag("onboarding").performScrollToNode(hasTestTag("onboarding-goal-BALANCES"))
            onNodeWithTag("onboarding-goal-BALANCES").performScrollTo().assertIsSelected()
            onNodeWithTag("onboarding").performScrollToNode(hasText("Continue"))
            onNodeWithText("Continue").performScrollTo().performClick()
            onNodeWithTag("onboarding").performScrollToNode(hasTestTag("onboarding-source-bitcoin-address"))
            onNodeWithTag("onboarding-source-bitcoin-address").performScrollTo().performClick()
            onNodeWithTag("onboarding").performScrollToNode(hasText("Back"))
            onNodeWithText("Back").performScrollTo().performClick()
            assertEquals(setOf(OnboardingGoal.BALANCES), state.goals)
            onNodeWithTag("onboarding").performScrollToNode(hasText("Continue"))
            onNodeWithText("Continue").performScrollTo().performClick()
            onNodeWithTag("onboarding").performScrollToNode(hasTestTag("onboarding-source-bitcoin-address"))
            onNodeWithTag("onboarding-source-bitcoin-address").performScrollTo().assertIsSelected()
            onNodeWithTag("onboarding").performScrollToNode(hasText("Continue"))
            onNodeWithText("Continue").performScrollTo().performClick()
            onNodeWithTag("onboarding").performScrollToNode(hasTestTag("onboarding-finish"))
            onNodeWithTag("onboarding-finish").performScrollTo().performClick()
            assertTrue(finished)
        }
    }

    @Test
    fun selectedSourceOffersOnlyItsImplementedWorkflowAfterReview() {
        val actions = mutableListOf<OnboardingAction>()
        var workflow: SourceWorkflow? = null
        runDesktopComposeUiTest(width = 1000, height = 800) {
            setContent {
                PolionTheme(AppearanceTheme.LIGHT) {
                    OnboardingScreen(
                        OnboardingState(step = OnboardingStep.READY, sourceIds = setOf("bitcoin-public-wallet")),
                        false,
                        null,
                        { actions.add(it) },
                    ) { workflow = it }
                }
            }
            onNodeWithTag("onboarding").performScrollToNode(hasText("Continue with Bitcoin public wallet"))
            onNodeWithText("Continue with Bitcoin public wallet").performScrollTo().performClick()
            assertEquals(SourceWorkflow.BITCOIN_PUBLIC_WALLET, workflow)
            onNodeWithTag("onboarding").performScrollToNode(hasText("Skip setup for now"))
            onNodeWithText("Skip setup for now").performScrollTo().performClick()
            assertEquals(listOf<OnboardingAction>(OnboardingAction.Skip), actions)
        }
    }

    @Test
    fun saveFailureRetainsStepAndRetryActionsInCompactLandscape() {
        runDesktopComposeUiTest(width = 640, height = 360) {
            setContent {
                PolionTheme(AppearanceTheme.LIGHT) {
                    OnboardingScreen(OnboardingState(step = OnboardingStep.GOALS), false, "Could not save setup", {}, {})
                }
            }
            onNodeWithTag("onboarding").performScrollToNode(hasText("Could not save setup"))
            onNodeWithText("Could not save setup").performScrollTo().assertIsDisplayed()
            onNodeWithTag("onboarding").performScrollToNode(hasText("Continue"))
            onNodeWithText("Continue").performScrollTo().assertIsDisplayed()
            onNodeWithTag("onboarding").performScrollToNode(hasText("Back"))
            onNodeWithText("Back").performScrollTo().assertIsDisplayed()
        }
    }
}
