package io.polion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import io.polion.core.application.AccountOverview
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.model.PortfolioId
import io.polion.core.ownership.TargetRole
import io.polion.ui.model.ModelAccountRow
import io.polion.ui.model.ModelAccounts
import io.polion.ui.model.ModelAccountsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AccountTool { RULES, RECONCILIATION, UTXO }

@Composable
internal fun AccountsWorkspace(
    app: PortfolioApplication,
    host: PlatformActions,
    data: PortfolioSnapshot,
    portfolio: PortfolioId?,
    revision: Long,
    sync: SyncStatus,
    onConnect: () -> Unit,
    onHistory: () -> Unit,
    onAdvanced: (Page) -> Unit,
    onChanged: () -> Unit,
    onTool: (AccountTool) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var accounts by remember(app, portfolio, data.currency) { mutableStateOf<List<AccountOverview>?>(null) }
    var retry by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var archive by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var request by remember(selected) { mutableStateOf<String?>(null) }
    LaunchedEffect(app, revision, data, portfolio, retry) {
        loading = true
        try {
            accounts = withContext(host.dispatcher) { app.accountOverview(portfolio) }
            error = null
        } catch (
            cancelled: CancellationException,
        ) {
            throw cancelled
        } catch (_: Exception) {
            error = "Account details could not be loaded."
        } finally {
            loading = false
        }
    }
    Column {
        if (shouldShowSyncBanner(sync, data.sourceStatus)) {
            val operation =
                when (sync.operation) {
                    SyncOperation.HISTORY -> "Wallet history"
                    SyncOperation.DISCOVERY -> "Wallet discovery and history"
                    SyncOperation.ORDER -> "Order verification"
                    SyncOperation.PRICES -> "Price import"
                }
            val title =
                when (sync.phase) {
                    SyncPhase.SYNCING -> "$operation requested or running"
                    SyncPhase.SUCCESS -> "$operation complete"
                    SyncPhase.ERROR -> "Wallet refresh needs attention"
                    SyncPhase.IDLE -> "Wallet refresh interrupted"
                }
            Text(title)
            Text(if (sync.phase == SyncPhase.IDLE) "Saved accepted holdings are retained." else sync.message)
            TextButton(onClick = onHistory) { Text("History progress and retry") }
        }
        ModelAccounts(
            ModelAccountsState(
                accounts.orEmpty().map { summary ->
                    val account = summary.account
                    val archived = "polion:archived" in account.tags
                    val last = summary.accepted?.observedAt
                    val status =
                        when {
                            archived -> "Archived · history retained" + (last?.let { " · last accepted $it" } ?: "")
                            summary.sources.isEmpty() -> "Local ledger · no provider source"
                            last == null -> "No accepted wallet history yet"
                            !summary.currentSourcesAccepted -> "Last accepted $last · source configuration changed"
                            else -> "Last accepted $last · provider-based coverage"
                        }
                    ModelAccountRow(
                        account.label,
                        account.kind.name.lowercase(),
                        status,
                        account.id.value,
                        summary.sources.map { target ->
                            val identity = target.identity.canonicalKey
                            "Bitcoin ${if (target.role == TargetRole.WALLET) "wallet" else "address"} · ${if (identity.length > 44) {
                                identity.take(
                                    22,
                                ) + "…" + identity.takeLast(12)
                            } else {
                                identity
                            }}"
                        },
                        "Portfolios: " + summary.portfolios.joinToString().ifEmpty { "All portfolios only" },
                        summary.currentSourcesAccepted && !archived,
                    )
                },
            ),
            onConnect,
            { selected = it },
        )
        if (loading) Text("Updating saved account details…")
        error?.let {
            Text(it)
            TextButton(onClick = { retry++ }) { Text("Retry account details") }
        }
        TextButton(onClick = onHistory, modifier = Modifier.testTag("accounts-refresh-history")) { Text("Refresh wallet history") }
        TextButton(onClick = { onAdvanced(Page.ACCOUNTS) }) { Text("Account and sync tools") }
    }
    accounts?.find { it.account.id.value == selected }?.let { detail ->
        val archived = "polion:archived" in detail.account.tags
        AlertDialog(onDismissRequest = { if (!busy) selected = null }, title = { Text(detail.account.label) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Portfolios: " + detail.portfolios.joinToString().ifEmpty { "All portfolios only" })
                Text("Source provider: ${data.sourceEndpoint}")
                detail.accepted?.let { accepted ->
                    val endpoint = detail.acceptedProviderEndpoint ?: "Saved provider identity ${accepted.providerSourceId}"
                    Text("Last accepted: ${accepted.observedAt}\nAccepted provider: $endpoint")
                }
                if (detail.sources.isEmpty()) Text("Local manual account. No public provider queries are configured.")
                SelectionContainer { Column { detail.sources.forEach { Text("${it.label}\n${it.identity.canonicalKey}") } } }
                if (detail.derivedAddresses > 0) Text("${detail.derivedAddresses} derived addresses · manage in account tools")
                Text(if (archived) "Saved holdings (archived)" else "Holdings in this account")
                if (detail.holdings.isEmpty()) Text("No recorded holdings")
                detail.holdings.forEach { asset ->
                    Text(
                        "${asset.metadata?.let {
                            formatUnits(
                                asset.amount.atomicUnits,
                                it.decimals,
                            )
                        } ?: asset.amount.atomicUnits} ${asset.metadata?.symbol ?: asset.asset.value} · ${asset.value?.let {
                            money(
                                it,
                                data.currency,
                            )
                        } ?: "—"}",
                    )
                }
                if (!archived) {
                    TextButton(enabled = sync.phase != SyncPhase.SYNCING, onClick = onHistory) { Text("Refresh wallet history") }
                    detail.sources.filter { it.role == TargetRole.WALLET }.forEach { wallet ->
                        TextButton(enabled = !busy && sync.phase != SyncPhase.SYNCING, onClick = {
                            request = "Discovery requested; progress is shown in Accounts."
                            host.discoverWallet(wallet.id) { request = it }
                        }) { Text("Discover addresses · ${wallet.label}") }
                    }
                }
                request?.let { Text(it) }
                TextButton(onClick = {
                    selected = null
                    onAdvanced(Page.ACCOUNTS)
                }) { Text("Addresses, owners and groups") }
                TextButton(onClick = {
                    selected = null
                    onTool(AccountTool.RULES)
                }) { Text("Classification rules") }
                TextButton(onClick = {
                    selected = null
                    onTool(AccountTool.RECONCILIATION)
                }) { Text("Reconciliation") }
                TextButton(onClick = {
                    selected = null
                    onTool(AccountTool.UTXO)
                }) { Text("Review UTXOs") }
                TextButton(onClick = {
                    selected = null
                    onAdvanced(Page.REPORTS)
                }) { Text("Order verification and accounting") }
                TextButton(
                    enabled = !busy && sync.phase != SyncPhase.SYNCING,
                    modifier =
                        Modifier.testTag(
                            "account-archive-action",
                        ),
                    onClick = {
                        archive =
                            detail.account.id.value
                    },
                ) { Text(if (archived) "Restore account" else "Archive account") }
                error?.let { Text(it) }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { selected = null }) { Text("Close") } })
    }
    accounts?.find { it.account.id.value == archive }?.let { detail ->
        val restoring = "polion:archived" in detail.account.tags
        AlertDialog(onDismissRequest = {
            if (!busy) archive = null
        }, title = { Text(if (restoring) "Restore account?" else "Archive account?") }, text = {
            Text(
                if (restoring) {
                    "Restore this account and its eligible sources to active views. Independent archive choices remain in effect."
                } else {
                    "Pause this account's sources and hide its holdings from active portfolios. Saved history and treatments are retained."
                },
            )
        }, confirmButton = {
            TextButton(enabled = !busy, modifier = Modifier.testTag("confirm-account-archive"), onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(host.dispatcher) { app.archiveAccount(detail.account.id, !restoring) }
                        archive = null
                        selected = null
                        onChanged()
                        retry++
                    } catch (
                        cancelled: CancellationException,
                    ) {
                        throw cancelled
                    } catch (
                        _: Exception,
                    ) {
                        error = "Could not change the account. Saved history is retained."
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (restoring) "Restore" else "Archive") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = { archive = null }) { Text("Cancel") } })
    }
}
