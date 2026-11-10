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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AccountFlowDialog(
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

private const val REPLY_WAIT_MILLIS = 3_000L

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
    syncStatus: SyncStatus = SyncStatus(),
) {
    val scope = rememberCoroutineScope()
    var label by rememberSaveable { mutableStateOf("") }
    var expression by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf(data.sourceEndpoint) }
    // Connecting is consent: the dialog states what the provider receives.
    val consent = true
    var portfolio by rememberSaveable { mutableStateOf(initialPortfolio?.value.orEmpty()) }
    var account by rememberSaveable { mutableStateOf("") }
    var owner by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Set once the wallet is saved. Discovery then runs in the host and this dialog follows its status.
    var savedWallet by remember { mutableStateOf<TrackingTargetId?>(null) }
    // The host status when discovery was requested. Only a discovery status that differs from it counts as progress.
    var baseline by remember { mutableStateOf<SyncStatus?>(null) }
    // The host's immediate reply to the request. It explains a rejected request and is shown until a discovery status arrives.
    var hostReply by remember { mutableStateOf<String?>(null) }
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
    val ownerChoice = owners.firstOrNull { it.id.value == owner }?.id ?: owners.firstOrNull()?.id
    val canonical = runCatching { canonicalBitcoinSource(expression) }.getOrNull()
    val wallet = savedWallet
    val discovery = syncStatus.takeIf { wallet != null && it != baseline && it.operation == SyncOperation.DISCOVERY }
    // A reply with no discovery status (for example a rejected request) allows a retry only after a short wait, so a
    // host that acknowledges before publishing its first status does not flash a retry button.
    var replyUnanswered by remember { mutableStateOf(false) }
    LaunchedEffect(hostReply, discovery == null) {
        replyUnanswered = false
        if (hostReply != null && discovery == null) {
            delay(REPLY_WAIT_MILLIS)
            replyUnanswered = true
        }
    }
    val failed = wallet != null && (discovery?.phase == SyncPhase.ERROR || (discovery == null && replyUnanswered))
    val blocker =
        when {
            label.isBlank() -> "Enter an account or wallet name."
            canonical == null -> "Enter a valid Bitcoin mainnet public address, supported descriptor or xpub."
            account.isEmpty() && data.ownership.owners.isNotEmpty() && ownerChoice == null ->
                "No owner in this portfolio can hold the wallet. Choose another portfolio."
            endpoint.isBlank() -> "Enter the HTTPS provider endpoint."
            else -> null
        }

    fun discover(walletId: TrackingTargetId) {
        savedWallet = walletId
        baseline = syncStatus
        hostReply = null
        host.discoverWallet(walletId) { hostReply = it }
    }

    LaunchedEffect(discovery) {
        if (discovery?.phase == SyncPhase.SUCCESS) {
            notify(discovery.message)
            close()
        }
    }

    fun submit() {
        saving = true
        error = null
        val suffix =
            kotlin.random.Random
                .nextLong()
                .toString()
                .removePrefix("-")
        val accountId = account.takeIf { it.isNotEmpty() }?.let(::AccountId) ?: AccountId("wallet-account-$suffix")
        val ownerId = ownerChoice?.takeIf { account.isEmpty() } ?: OwnerId("owner-$suffix")
        val id = TrackingTargetId("wallet-$suffix")
        scope.launch {
            try {
                val walletId =
                    withContext(host.dispatcher) {
                        require(data.ownership.owners.isEmpty() || account.isNotEmpty() || ownerChoice != null) {
                            "Select an owner in this portfolio"
                        }
                        // A retried import of an undiscovered wallet returns the existing wallet ID.
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
                                    Account(accountId, ownerId, label.trim(), AccountKind.WALLET)
                                } else {
                                    null
                                },
                            newOwner =
                                if (account.isEmpty() && data.ownership.owners.isEmpty()) {
                                    Owner(ownerId, "Personal")
                                } else {
                                    null
                                },
                            consentAccepted = consent,
                            providerEndpoint = endpoint.trim(),
                            portfolio = portfolio.takeIf { it.isNotBlank() }?.let(::PortfolioId),
                        )
                    }
                val scheduled =
                    withContext(host.dispatcher) {
                        runCatching { host.updateRefreshPreferences(host.refreshPreferences()) }.isSuccess
                    }
                onSaved()
                if (!scheduled) notify("Source saved. Automatic refresh could not be scheduled; check sync settings.")
                if (walletId != id) {
                    val resumedAccount =
                        withContext(host.dispatcher) {
                            val ownership = app.snapshot().ownership
                            ownership.targets
                                .firstOrNull { it.id == walletId }
                                ?.let { wallet -> ownership.accounts.firstOrNull { it.id == wallet.accountId }?.label }
                        }
                    notify(
                        "This wallet is already tracked${resumedAccount?.let {
                            " in \"$it\""
                        } ?: ""}; fetching its addresses and history again.",
                    )
                }
                discover(walletId)
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (
                e: Exception,
            ) {
                error = e.message ?: "Could not save this source. Check the input and retry."
            } finally {
                saving = false
            }
        }
    }

    AccountFlowDialog("Connect Bitcoin wallet", saving, close) {
        val editable = wallet == null && !saving
        Text("Watch-only · Bitcoin mainnet")
        OutlinedTextField(label, { label = it }, enabled = editable, label = {
            Text("Account / wallet name")
        }, modifier = Modifier.fillMaxWidth().testTag("connect-name"))
        OutlinedTextField(expression, { expression = it }, enabled = editable, label = {
            Text("Public address, descriptor or xpub")
        }, modifier = Modifier.fillMaxWidth().testTag("connect-expression"))
        Text(
            "Public information only. Never enter private keys or recovery words. A bare xpub uses native SegWit receive and change branches.",
        )
        if (canonical != null) {
            Text("Validated public source: $canonical")
            Text(
                "Discovery checks both branches for a bare xpub and stops after 20 unused addresses per branch. A descriptor checks its defined branch. History beyond the gap can be missed. Accepted holdings appear in Accounts; source coverage remains provider-based.",
            )
            if (selectedPortfolio != null && selectedPortfolio.accountIds.isNotEmpty()) {
                Text("The chosen account will be included in ${selectedPortfolio.label}.")
            }
        }
        TransactionChoice(
            "Portfolio",
            listOf("" to "All portfolios") + data.ownership.portfolios.map { it.id.value to it.label },
            portfolio,
        ) {
            portfolio = it
            account = ""
            owner = ""
        }
        TransactionChoice("Account", listOf("" to "New account") + accounts.map { it.id.value to it.label }, account) {
            account = it
        }
        if (account.isEmpty()) {
            if (data.ownership.owners.isEmpty()) {
                Text("A local Personal owner will be created.")
            } else {
                TransactionChoice("Owner", owners.map { it.id.value to it.label }, ownerChoice?.value.orEmpty()) { owner = it }
            }
        }
        OutlinedTextField(endpoint, {
            endpoint = it
        }, enabled = editable, label = {
            Text("Bitcoin provider (HTTPS Esplora endpoint)")
        }, modifier = Modifier.fillMaxWidth().testTag("connect-endpoint"))
        Text(
            "The provider receives these addresses and your IP address to read their history.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (wallet == null) {
            blocker?.let { Text(it, modifier = Modifier.testTag("connect-blocker")) }
        } else {
            Text(
                discovery?.message ?: hostReply ?: "Saved. Discovery is queued; progress appears here.",
                color = if (discovery?.phase == SyncPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("connect-status"),
            )
        }
        Button(
            enabled = if (wallet == null) !saving && blocker == null else failed,
            modifier = Modifier.fillMaxWidth().testTag("connect-confirm"),
            onClick = {
                val current = wallet
                if (current == null) submit() else discover(current)
            },
        ) {
            Text(
                when {
                    saving -> "Importing…"
                    wallet == null -> "Import wallet"
                    failed -> "Retry discovery"
                    else -> "Discovering…"
                },
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("connect-save-error")) }
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
    // Connecting is consent: the dialog states what the provider receives.
    val consent = true
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
                endpointProblem(host, data.sourceEndpoint, endpoint.trim(), consent)?.let {
                    error = it
                    busy = false
                    return@launch
                }
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
        }, enabled = !busy && !running, label = {
            Text("Bitcoin provider (HTTPS Esplora endpoint)")
        }, modifier = Modifier.fillMaxWidth().testTag("history-endpoint"))
        Text(
            "The provider receives these addresses and your IP address to read their history.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** A changed custom endpoint is checked for Bitcoin mainnet before it is saved; the default endpoint is mainnet. */
internal suspend fun endpointProblem(
    host: PlatformActions,
    previous: String,
    endpoint: String,
    consent: Boolean,
): String? {
    if (!consent || endpoint == previous || endpoint == io.polion.core.application.DEFAULT_ENDPOINT) return null
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { host.checkBitcoinEndpoint(endpoint) }
}
