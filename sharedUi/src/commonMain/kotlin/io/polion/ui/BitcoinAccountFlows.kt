package io.polion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.bitcoin.bitcoinMainnetAddress
import io.polion.core.bitcoin.bitcoinWallet
import io.polion.core.model.AccountId
import io.polion.core.model.OwnerId
import io.polion.core.model.PortfolioId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun AccountFlowDialog(
    title: String,
    busy: Boolean,
    close: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = {
        if (!busy) close()
    }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(enabled = !busy, onClick = close) { Text("Close") }
                }
                content()
            }
        }
    }
}

internal fun canonicalBitcoinSource(input: String): String {
    val text = input.trim()
    val expression = runCatching { "addr(${bitcoinMainnetAddress(text)})" }.getOrNull() ?: text
    return bitcoinWallet(expression).canonical
}

@Composable
internal fun BitcoinConnectDialog(
    app: PortfolioApplication,
    host: PlatformActions,
    data: PortfolioSnapshot,
    initialPortfolio: PortfolioId?,
    close: () -> Unit,
    onSaved: () -> Unit,
    notify: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var label by rememberSaveable { mutableStateOf("") }
    var expression by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf(data.sourceEndpoint) }
    var consent by rememberSaveable { mutableStateOf(data.sourceConsent) }
    var portfolio by rememberSaveable { mutableStateOf(initialPortfolio?.value.orEmpty()) }
    var account by rememberSaveable { mutableStateOf("") }
    var owner by rememberSaveable {
        mutableStateOf(
            data.ownership.owners
                .firstOrNull()
                ?.id
                ?.value
                .orEmpty(),
        )
    }
    var reviewed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val selectedPortfolio = data.ownership.portfolios.find { it.id.value == portfolio }
    val owners =
        data.ownership.owners.filter {
            selectedPortfolio == null ||
                selectedPortfolio.ownerIds.isEmpty() ||
                it.id in selectedPortfolio.ownerIds
        }
    val accounts =
        data.ownership.accounts.filter {
            it.kind != AccountKind.GROUP &&
                "polion:archived" !in it.tags &&
                (selectedPortfolio == null || selectedPortfolio.ownerIds.isEmpty() || it.ownerId in selectedPortfolio.ownerIds)
        }
    val canonical = runCatching { canonicalBitcoinSource(expression) }.getOrNull()
    AccountFlowDialog("Connect Bitcoin wallet", busy, close) {
        Text("Watch-only · Bitcoin mainnet")
        OutlinedTextField(label, {
            label = it
            reviewed = false
        }, enabled = !busy, label = { Text("Account / wallet name") }, modifier = Modifier.fillMaxWidth().testTag("connect-name"))
        OutlinedTextField(expression, {
            expression = it
            reviewed = false
        }, enabled = !busy, label = {
            Text("Public address, descriptor or xpub")
        }, modifier = Modifier.fillMaxWidth().testTag("connect-expression"))
        Text(
            "Public information only. Never enter private keys or recovery words. A bare xpub uses native SegWit receive and change branches.",
        )
        TransactionChoice(
            "Portfolio",
            listOf("" to "All portfolios") + data.ownership.portfolios.map { it.id.value to it.label },
            portfolio,
        ) {
            portfolio =
                it
            ; account = ""
            owner = ""
            reviewed = false
        }
        TransactionChoice("Account", listOf("" to "New account") + accounts.map { it.id.value to it.label }, account) {
            account = it
            reviewed =
                false
        }
        if (account.isEmpty()) {
            if (data.ownership.owners.isEmpty()) {
                Text("A local Personal owner will be created.")
            } else {
                TransactionChoice("Owner", owners.map { it.id.value to it.label }, owner) {
                    owner = it
                    reviewed = false
                }
            }
        }
        OutlinedTextField(endpoint, {
            endpoint = it
            consent = false
            reviewed = false
        }, enabled = !busy, label = {
            Text("Bitcoin provider (HTTPS Esplora endpoint)")
        }, modifier = Modifier.fillMaxWidth().testTag("connect-endpoint"))
        Row {
            Checkbox(consent, {
                consent = it
                reviewed = false
            }, enabled = !busy, modifier = Modifier.testTag("connect-consent"))
            Text("Allow public address and transaction queries to this provider.", Modifier.weight(1f))
        }
        if (canonical != null && reviewed) {
            Text("Validated public source: $canonical")
            Text(
                "Discovery checks both branches for a bare xpub and stops after 20 unused addresses per branch. A descriptor checks its defined branch. History beyond the gap can be missed. Accepted holdings appear in Accounts; source coverage remains provider-based.",
            )
            if (selectedPortfolio != null &&
                selectedPortfolio.accountIds.isNotEmpty()
            ) {
                Text("The chosen account will be included in ${selectedPortfolio.label}.")
            }
            Button(enabled = !busy && consent, modifier = Modifier.fillMaxWidth().testTag("connect-confirm"), onClick = {
                busy = true
                scope.launch {
                    try {
                        val wallet =
                            withContext(host.dispatcher) {
                                val suffix =
                                    kotlin.random.Random
                                        .nextLong()
                                        .toString()
                                        .removePrefix("-")
                                val ownerId = owner.takeIf { it.isNotEmpty() }?.let(::OwnerId) ?: OwnerId("owner-$suffix")
                                require(
                                    data.ownership.owners.isEmpty() || ownerId in owners.map { it.id } || account.isNotEmpty(),
                                ) { "Select an owner in this portfolio" }
                                val accountId = account.takeIf { it.isNotEmpty() }?.let(::AccountId) ?: AccountId("wallet-account-$suffix")
                                val id = TrackingTargetId("wallet-$suffix")
                                // A retried import of an undiscovered wallet returns the existing wallet ID.
                                var walletId = id
                                walletId =
                                    app.saveBitcoinWallet(
                                        TrackingTarget(
                                            id,
                                            accountId,
                                            TargetIdentity(BITCOIN_MAINNET, "wallet", requireNotNull(canonical)),
                                            TargetRole.WALLET,
                                            label.trim(),
                                        ),
                                        newAccount =
                                            if (account.isEmpty()) {
                                                Account(
                                                    accountId,
                                                    ownerId,
                                                    label.trim(),
                                                    AccountKind.WALLET,
                                                )
                                            } else {
                                                null
                                            },
                                        newOwner =
                                            if (account.isEmpty() &&
                                                data.ownership.owners.isEmpty()
                                            ) {
                                                Owner(ownerId, "Personal")
                                            } else {
                                                null
                                            },
                                        consentAccepted = consent,
                                        providerEndpoint = endpoint.trim(),
                                        portfolio = portfolio.takeIf { it.isNotBlank() }?.let(::PortfolioId),
                                    )
                                val scheduled = runCatching { host.updateRefreshPreferences(host.refreshPreferences()) }.isSuccess
                                Triple(walletId, scheduled, walletId != id)
                            }
                        onSaved()
                        close()
                        // The host owns discovery and history; its callback can acknowledge queued work.
                        if (!wallet.second) notify("Source saved. Automatic refresh could not be scheduled; check sync settings.")
                        if (wallet.third) notify("Resumed the wallet imported earlier; its account was kept.")
                        host.discoverWallet(wallet.first, notify)
                    } catch (
                        cancelled: CancellationException,
                    ) {
                        throw cancelled
                    } catch (
                        e: Exception,
                    ) {
                        error = e.message ?: "Could not save this source. Check the input and retry."
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Saving source…" else "Connect and fetch history") }
        } else {
            Button(
                enabled = !busy && label.isNotBlank() && expression.isNotBlank() && consent,
                modifier =
                    Modifier.fillMaxWidth().testTag(
                        "connect-review",
                    ),
                onClick = {
                    error = if (canonical == null) "Enter a valid Bitcoin mainnet public address, supported descriptor or xpub." else null
                    reviewed = canonical != null
                },
            ) { Text("Review connection") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun BitcoinHistoryDialog(
    app: PortfolioApplication,
    host: PlatformActions,
    data: PortfolioSnapshot,
    status: SyncStatus,
    close: () -> Unit,
    onSaved: () -> Unit,
    notify: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var endpoint by rememberSaveable { mutableStateOf(data.sourceEndpoint) }
    var consent by rememberSaveable { mutableStateOf(data.sourceConsent) }
    var recovery by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val running = status.phase == SyncPhase.SYNCING
    val failed = status.phase == SyncPhase.ERROR || data.sourceStatus.interrupted || data.sourceStatus.lastError != null

    fun save(
        refresh: Boolean,
        recover: Boolean,
    ) {
        busy = true
        scope.launch {
            try {
                val scheduled =
                    withContext(host.dispatcher) {
                        app.configureBitcoinSource(endpoint.trim(), consent)
                        runCatching { host.updateRefreshPreferences(host.refreshPreferences()) }.isSuccess
                    }
                onSaved()
                if (!scheduled) notify("Source saved. Automatic refresh could not be scheduled; check sync settings.")
                if (refresh) {
                    close()
                    if (recover) host.recoverBitcoinHistory(notify) else host.refresh(notify)
                } else {
                    notify("Bitcoin source settings saved")
                    close()
                }
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (
                _: Exception,
            ) {
                error = "Could not save source settings. Check the HTTPS endpoint and available storage."
            } finally {
                busy = false
            }
        }
    }
    AccountFlowDialog("Wallet history", busy, close) {
        OutlinedTextField(endpoint, {
            endpoint = it
            consent = false
        }, enabled = !busy && !running, label = {
            Text("Bitcoin provider (HTTPS Esplora endpoint)")
        }, modifier = Modifier.fillMaxWidth().testTag("history-endpoint"))
        Row {
            Checkbox(consent, { consent = it }, enabled = !busy, modifier = Modifier.testTag("history-consent"))
            Text("Allow public address and transaction queries to this provider.", Modifier.weight(1f))
        }
        Text(
            "Refresh scans active watched addresses and may fetch saved history again. Saved holdings stay visible while work runs. Pull-to-refresh updates prices only.",
        )
        if (running) Text(status.message, Modifier.testTag("history-progress"))
        if (failed) {
            Text("History refresh did not finish. Saved accepted records are retained; you can retry.")
            // The reason is shown here so the details view explains the failure instead of only reopening the screen.
            (if (status.phase == SyncPhase.ERROR) status.message else data.sourceStatus.lastError)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("history-error"))
            }
        }
        Button(enabled = consent && !busy && !running, modifier = Modifier.fillMaxWidth().testTag("history-refresh"), onClick = {
            save(true, false)
        }) {
            Text(
                if (failed) {
                    "Retry wallet history"
                } else {
                    "Refresh wallet history"
                },
            )
        }
        TextButton(enabled = !busy, onClick = { save(false, false) }) { Text("Save source settings") }
        TextButton(enabled = consent && !busy && !running, modifier = Modifier.testTag("history-recovery"), onClick = {
            recovery = true
        }) { Text("Full history recovery…") }
        Text("Full recovery is separate from an ordinary refresh. It re-fetches all active source history and uses extra provider traffic.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (recovery) {
        AlertDialog(onDismissRequest = { if (!busy) recovery = false }, title = { Text("Recover full Bitcoin history?") }, text = {
            Text(
                "Re-scan all active watched Bitcoin addresses at $endpoint. Saved records remain available until the replacement scan passes validation. Provider-based coverage is not independent proof of completeness.",
            )
        }, confirmButton = {
            TextButton(enabled = consent && !busy && !running, modifier = Modifier.testTag("history-recovery-confirm"), onClick = {
                recovery =
                    false
                ; save(true, true)
            }) { Text("Confirm full recovery") }
        }, dismissButton = {
            TextButton(enabled = !busy, onClick = {
                recovery =
                    false
            }) { Text("Cancel") }
        })
    }
}
