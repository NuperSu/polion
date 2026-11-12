package io.valroot.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ColumnScope.SourceWorkflow(
    data: PortfolioSnapshot,
    app: PortfolioApplication,
    host: PlatformActions,
    scope: CoroutineScope,
    syncStatusState: State<SyncStatus>,
    addressState: MutableState<String>,
    consentState: MutableState<Boolean>,
    errorState: MutableState<String?>,
    acceptedRecoveryState: MutableState<Boolean>,
    sourceSaved: (String, Boolean) -> Unit,
    notify: (String) -> Unit,
    close: () -> Unit,
) {
    val syncStatus by syncStatusState
    var address by addressState
    var consent by consentState
    var error by errorState
    var acceptedRecovery by acceptedRecoveryState

    if (syncStatus.phase == SyncPhase.SYNCING) {
        Text("Syncing…", fontWeight = FontWeight.SemiBold)
        Text(syncStatus.message, fontSize = 12.sp)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Your endpoint is saved. Source settings can be changed after this scan finishes.", fontSize = 12.sp)
    }
    // The details view explains the last failure instead of only opening the settings.
    if (syncStatus.phase == SyncPhase.ERROR) {
        Text(syncStatus.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sync-error"))
    }
    Text(
        "Querying an indexer shares watched public addresses and your IP address with that provider. The default " +
            "blockstream.info falls back to mempool.space while it is unavailable; your own endpoint has no fallback. " +
            "Choose an HTTPS Esplora endpoint you trust. No cloud account is required.",
        fontSize = 13.sp,
    )
    Field("Esplora API endpoint", address) { address = it }
    Row(Modifier.toggleable(value = consent, role = Role.Checkbox) { consent = it }.testTag("bitcoin-source-consent")) {
        Checkbox(checked = consent, onCheckedChange = null)
        Text("I allow public address queries to this endpoint", Modifier.weight(1f).padding(top = 8.dp), fontSize = 13.sp)
    }
    Text(
        "Automatic refresh of prices and wallet history is set in Settings.",
        fontSize = 13.sp,
    )
    Text(
        "Android may defer background work. Desktop refresh runs while Valroot is open. Network errors preserve saved data. Pending activity is provisional, and source history is not independently verified.",
        fontSize = 12.sp,
    )

    fun saveSource(recoverHistory: Boolean) {
        if (!address.startsWith("https://") ||
            '@' in address ||
            '?' in address ||
            '#' in address
        ) {
            error = "Use an HTTPS Esplora API endpoint without credentials or query parameters."
            return
        }
        scope.launch {
            endpointProblem(host, data.sourceEndpoint, address, consent)?.let {
                error = it
                return@launch
            }
            runCatching {
                withContext(host.dispatcher) {
                    app.sources.setEndpoint(address)
                    app.sources.setConsent(consent)
                    // Reschedule from the saved switch; this dialog does not change it.
                    host.updateRefreshPreferences(host.refreshPreferences())
                }
            }.fold({
                sourceSaved(address, consent)
                when {
                    recoverHistory -> host.recoverBitcoinHistory { notify(it) }
                    consent -> host.refresh { notify(it) }
                    else -> notify("Source queries disabled")
                }
                close()
            }, { error = "Could not save source settings. Check the endpoint and available storage." })
        }
    }
    Button(
        onClick = { saveSource(false) },
        enabled = syncStatus.phase != SyncPhase.SYNCING,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (syncStatus.phase ==
                SyncPhase.SYNCING
            ) {
                "Sync in progress…"
            } else if (consent) {
                "Save & sync"
            } else {
                "Save offline preferences"
            },
        )
    }
    HorizontalDivider()
    Text("Full history recovery", fontWeight = FontWeight.SemiBold)
    if (syncStatus.phase == SyncPhase.ERROR) {
        Text(syncStatus.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
    Text(
        "Use recovery when Valroot cannot connect the saved chain checkpoint to the provider's current chain. " +
            "It downloads all history for active watched addresses from the selected endpoint " +
            "and uses extra provider traffic. " +
            "Saved records remain available until the replacement scan passes validation. " +
            "Coverage remains provider-based and unverified.",
        fontSize = 12.sp,
    )
    val recoveryEnabled = syncStatus.phase != SyncPhase.SYNCING
    Row(
        Modifier
            .toggleable(value = acceptedRecovery, enabled = recoveryEnabled, role = Role.Checkbox) { acceptedRecovery = it }
            .testTag("bitcoin-recovery-consent"),
    ) {
        Checkbox(checked = acceptedRecovery, onCheckedChange = null, enabled = recoveryEnabled)
        Text("I want to rescan full Bitcoin history", Modifier.weight(1f).padding(top = 8.dp), fontSize = 13.sp)
    }
    OutlinedButton(
        onClick = { saveSource(true) },
        enabled = consent && acceptedRecovery && syncStatus.phase != SyncPhase.SYNCING,
        modifier = Modifier.fillMaxWidth().testTag("recover-bitcoin-history"),
    ) {
        Text("Save & recover history")
    }
}
