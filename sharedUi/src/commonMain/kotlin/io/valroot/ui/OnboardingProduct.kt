package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.onboarding.OnboardingState
import io.valroot.core.sources.SourceWorkflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun OnboardingProduct(
    app: PortfolioApplication,
    host: PlatformActions,
    appearance: AppearanceTheme,
    onAppearance: (AppearanceTheme) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember(app) { mutableStateOf<OnboardingState?>(null) }
    var loaded by remember(app) { mutableStateOf(false) }
    var busy by remember(app) { mutableStateOf(false) }
    var error by remember(app) { mutableStateOf<String?>(null) }
    var workflow by remember(app) { mutableStateOf<SourceWorkflow?>(null) }
    var localRevision by remember(app) { mutableStateOf(0) }
    val revision by host.dataRevision.collectAsState()
    LaunchedEffect(app, revision, localRevision) {
        try {
            val saved = withContext(host.dispatcher) { app.settings.onboarding() }
            state = saved
            loaded = true
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Setup could not be opened. Check available storage and reopen Valroot."
        }
    }

    fun update(
        action: OnboardingAction,
        afterSave: () -> Unit = {},
    ) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val saved = withContext(host.dispatcher) { app.settings.updateOnboarding(action) }
                state = saved
                afterSave()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Setup could not be saved. Check available storage and try again."
            } finally {
                busy = false
            }
        }
    }
    val current = state
    if (!loaded) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(error ?: "Opening your portfolio")
            if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    } else if (current != null && !current.completed && !current.skipped) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            OnboardingScreen(current, busy, error, { update(it) }) { selected ->
                update(OnboardingAction.Complete) { workflow = selected }
            }
        }
    } else {
        Product(
            app,
            host,
            appearance,
            onAppearance,
            onSetup = { restart -> update(if (restart) OnboardingAction.Restart else OnboardingAction.Resume) },
            setupError = error,
            initialWorkflow = workflow,
            onWorkflowOpened = { workflow = null },
            onLocalDataChanged = { localRevision++ },
        )
    }
}
