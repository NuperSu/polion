package io.polion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.polion.core.onboarding.OnboardingAction
import io.polion.core.onboarding.OnboardingGoal
import io.polion.core.onboarding.OnboardingPresentation
import io.polion.core.onboarding.OnboardingState
import io.polion.core.onboarding.OnboardingStep
import io.polion.core.sources.SourceWorkflow
import io.polion.core.sources.SupportedSources

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OnboardingScreen(
    state: OnboardingState,
    busy: Boolean,
    error: String?,
    onAction: (OnboardingAction) -> Unit,
    onFinish: (SourceWorkflow?) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
                .testTag("onboarding")
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Text("POLION / YOUR SPACE", style = MaterialTheme.typography.labelLarge, color = Teal)
                Text("Set up your space", style = MaterialTheme.typography.headlineLarge)
                Text("Step ${state.step.ordinal + 1} of ${OnboardingStep.entries.size} · Saved on this device", color = Muted)
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OnboardingPresentation.entries.forEach { presentation ->
                        FilterChip(
                            selected = state.presentation == presentation,
                            enabled = !busy,
                            onClick = { onAction(OnboardingAction.SetPresentation(presentation)) },
                            label = { Text(if (presentation == OnboardingPresentation.FORM) "Step-by-step" else "Guided conversation") },
                        )
                    }
                }
                if (state.presentation == OnboardingPresentation.ASSISTED) {
                    Text("Polion guide · Local prompts, no AI model or account needed", color = Muted)
                }
            }
            item {
                val prompt =
                    when (state.step) {
                        OnboardingStep.WELCOME ->
                            if (state.presentation ==
                                OnboardingPresentation.ASSISTED
                            ) {
                                "Hello. Let’s make a space for your portfolio."
                            } else {
                                "A private place to follow your crypto"
                            }
                        OnboardingStep.GOALS ->
                            if (state.presentation ==
                                OnboardingPresentation.ASSISTED
                            ) {
                                "What would you like to keep track of?"
                            } else {
                                "Choose your interests"
                            }
                        OnboardingStep.SOURCES ->
                            if (state.presentation ==
                                OnboardingPresentation.ASSISTED
                            ) {
                                "How would you like to start tracking?"
                            } else {
                                "Explore supported sources"
                            }
                        OnboardingStep.READY ->
                            if (state.presentation ==
                                OnboardingPresentation.ASSISTED
                            ) {
                                "Your space is ready. Where shall we begin?"
                            } else {
                                "Ready at your pace"
                            }
                    }
                if (state.presentation == OnboardingPresentation.ASSISTED) {
                    Surface(
                        color = Mint,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().testTag("onboarding-guide-message"),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Polion guide", style = MaterialTheme.typography.labelLarge, color = Teal)
                            Text(prompt, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    if (state.goals.isNotEmpty() || state.sourceIds.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.padding(start = 32.dp, top = 12.dp),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Your choices", style = MaterialTheme.typography.labelLarge)
                                if (state.goals.isNotEmpty()) Text(state.goals.joinToString { it.label() })
                                if (state.sourceIds.isNotEmpty()) {
                                    Text(
                                        SupportedSources.choices.filter { it.id in state.sourceIds }.joinToString { it.title },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(prompt, style = MaterialTheme.typography.headlineSmall)
                }
            }
            when (state.step) {
                OnboardingStep.WELCOME ->
                    item {
                        Text("Polion is watch-only. Your records stay on this device. Never enter a private key or recovery phrase.")
                        Text(
                            "You can finish setup offline, without adding a source. Provider requests only happen through actions you choose later.",
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                OnboardingStep.GOALS ->
                    item {
                        Text("Optional preferences for your setup. They do not change balances or accounting.")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OnboardingGoal.entries.forEach { goal ->
                                FilterChip(
                                    selected = goal in state.goals,
                                    enabled = !busy,
                                    onClick = { onAction(OnboardingAction.ToggleGoal(goal)) },
                                    modifier = Modifier.testTag("onboarding-goal-${goal.name}"),
                                    label = { Text(goal.label()) },
                                )
                            }
                        }
                    }
                OnboardingStep.SOURCES -> {
                    item {
                        Text(
                            "Choose any that interest you. These preferences do not add accounts or contact a provider. Additional networks and exchange connections are not available here.",
                        )
                    }
                    SupportedSources.choices.forEach { choice ->
                        item {
                            FilterChip(
                                selected = choice.id in state.sourceIds,
                                enabled = !busy,
                                onClick = { onAction(OnboardingAction.ToggleSource(choice.id)) },
                                modifier = Modifier.fillMaxWidth().testTag("onboarding-source-${choice.id}"),
                                label = {
                                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(choice.title, style = MaterialTheme.typography.titleSmall)
                                        Text(choice.description)
                                        if (choice.requiresQueryConsent) Text("Public provider queries require your consent.")
                                    }
                                },
                            )
                        }
                    }
                }
                OnboardingStep.READY -> {
                    item {
                        Text("Your setup preferences are saved locally. Setup does not add sources or make network requests.")
                        Text(
                            "Use Add for public Bitcoin sources or manual records. Saved, synced, priced and accounting coverage are separate facts.",
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    item {
                        Button(
                            onClick = { onFinish(null) },
                            enabled = !busy,
                            modifier = Modifier.testTag("onboarding-finish"),
                        ) { Text("Open my portfolio") }
                    }
                    SupportedSources.choices.filter { it.id in state.sourceIds }.forEach { choice ->
                        item {
                            OutlinedButton(
                                onClick = { onFinish(choice.workflow) },
                                enabled = !busy,
                            ) { Text("Continue with ${choice.title}") }
                        }
                    }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.step != OnboardingStep.WELCOME) {
                        OutlinedButton(onClick = { onAction(OnboardingAction.Back) }, enabled = !busy) { Text("Back") }
                    }
                    if (state.step != OnboardingStep.READY) {
                        Button(onClick = { onAction(OnboardingAction.Next) }, enabled = !busy) { Text("Continue") }
                    }
                    TextButton(onClick = { onAction(OnboardingAction.Skip) }, enabled = !busy) { Text("Skip setup for now") }
                    TextButton(onClick = { onAction(OnboardingAction.Restart) }, enabled = !busy) { Text("Restart setup") }
                }
                Text("Resume setup from Settings at any time.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

private fun OnboardingGoal.label(): String =
    when (this) {
        OnboardingGoal.LONG_TERM_HOLDER -> "Long-term holdings"
        OnboardingGoal.ACTIVE_TRADER -> "Trading activity"
        OnboardingGoal.DEFI_USER -> "DeFi interests · source support planned"
        OnboardingGoal.BALANCES -> "Just track balances"
        OnboardingGoal.OTHER -> "Something else"
    }
