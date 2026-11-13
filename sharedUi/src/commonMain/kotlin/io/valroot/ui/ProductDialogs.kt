package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.application.TransactionDraft
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.PortfolioId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.reconcile.EffectiveActivity
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductDialogs(
    page: Page,
    selectedPortfolio: PortfolioId?,
    setSelectedPortfolio: (PortfolioId?) -> Unit,
    selectedAsset: AssetValuation?,
    setSelectedAsset: (AssetValuation?) -> Unit,
    kind: ActivityKind?,
    setSelectedAccount: (AccountId?) -> Unit,
    selectedActivity: EffectiveActivity?,
    setSelectedActivity: (EffectiveActivity?) -> Unit,
    dialog: String?,
    setDialog: (String?) -> Unit,
    setMessage: (String?) -> Unit,
    removeAccount: Account?,
    setRemoveAccount: (Account?) -> Unit,
    addressWallet: TrackingTarget?,
    setAddressWallet: (TrackingTarget?) -> Unit,
    sourceScanTarget: TrackingTarget?,
    setSourceScanTarget: (TrackingTarget?) -> Unit,
    modelManual: Boolean,
    setModelManual: (Boolean) -> Unit,
    modelAdd: Boolean,
    setModelAdd: (Boolean) -> Unit,
    modelEditingId: String?,
    setModelEditingId: (String?) -> Unit,
    modelEditingDraft: TransactionDraft?,
    setModelEditingDraft: (TransactionDraft?) -> Unit,
    modelHistory: Boolean,
    setModelHistory: (Boolean) -> Unit,
    connectChooser: Boolean,
    setConnectChooser: (Boolean) -> Unit,
    modelConnect: Boolean,
    setModelConnect: (Boolean) -> Unit,
    modelEvmConnect: Boolean,
    setModelEvmConnect: (Boolean) -> Unit,
    exchangeConnect: String?,
    setExchangeConnect: (String?) -> Unit,
    csvImport: Boolean,
    setCsvImport: (Boolean) -> Unit,
    showRefreshDetails: Boolean,
    setShowRefreshDetails: (Boolean) -> Unit,
    manualAsset: AssetId?,
    setManualAsset: (AssetId?) -> Unit,
    sourceDraftEndpoint: String?,
    setSourceDraftEndpoint: (String?) -> Unit,
    sourceDraftConsent: Boolean?,
    setSourceDraftConsent: (Boolean?) -> Unit,
    refreshDetails: String?,
    selectedManualDraft: TransactionDraft?,
    selectedManualCanEdit: Boolean,
    revision: Int,
    onRevision: () -> Unit,
    app: PortfolioApplication,
    host: PlatformActions,
    presenter: ProductPresenter,
    snapshot: PortfolioSnapshot?,
    syncStatus: SyncStatus,
    busy: Boolean,
    modelMode: Boolean,
    modelDraftState: SaveableStateHolder,
    modelDraftScope: String,
    sourceFlowState: SaveableStateHolder,
    execute: (String, () -> Unit) -> Unit,
    refreshPrices: (Boolean) -> Unit,
) {
    if (connectChooser && snapshot != null) {
        WalletKindDialog(
            onBitcoin = {
                setConnectChooser(false)
                setModelConnect(true)
            },
            onEvm = {
                setConnectChooser(false)
                setModelEvmConnect(true)
            },
            onDismiss = { setConnectChooser(false) },
            onExchange = { kind ->
                setConnectChooser(false)
                setExchangeConnect(kind.id)
            },
        )
    }
    io.valroot.core.exchange.ExchangeKind.entries.firstOrNull { it.id == exchangeConnect }?.let { kind ->
        if (snapshot != null) {
            ExchangeConnectDialog(
                kind,
                app,
                host,
                snapshot,
                selectedPortfolio,
                close = { setExchangeConnect(null) },
                onSaved = { execute("${kind.label} account saved") {} },
                notify = { presenter.notify(it) },
            )
        }
    }
    if (modelEvmConnect && snapshot != null) {
        sourceFlowState.SaveableStateProvider("evm-connect") {
            EvmConnectDialog(
                app,
                host,
                snapshot,
                selectedPortfolio,
                close = { setModelEvmConnect(false) },
                onSaved = {
                    sourceFlowState.removeState("evm-connect")
                    execute("EVM address saved") {}
                },
                notify = { presenter.notify(it) },
                syncStatus = syncStatus,
            )
        }
    }
    if (modelConnect && snapshot != null) {
        sourceFlowState.SaveableStateProvider("bitcoin-connect") {
            BitcoinConnectDialog(
                app,
                host,
                snapshot,
                selectedPortfolio,
                close = { setModelConnect(false) },
                onSaved = {
                    sourceFlowState.removeState("bitcoin-connect")
                    execute("Public Bitcoin source saved") {}
                },
                notify = { presenter.notify(it) },
                syncStatus = syncStatus,
            )
        }
    }
    if (modelHistory && snapshot != null) {
        sourceFlowState.SaveableStateProvider("bitcoin-history") {
            BitcoinHistoryDialog(
                app,
                host,
                snapshot,
                syncStatus,
                close = { setModelHistory(false) },
                onSaved = {
                    sourceFlowState.removeState("bitcoin-history")
                    execute("Bitcoin source settings saved") {}
                },
                notify = { presenter.notify(it) },
            )
        }
    }
    if (modelAdd && snapshot != null && (modelEditingId == null || modelEditingDraft?.id?.value == modelEditingId)) {
        modelDraftState.SaveableStateProvider(modelDraftScope) {
            TransactionEntry(
                app,
                host,
                snapshot,
                modelManual,
                manualAsset,
                modelEditingDraft.takeIf { modelEditingId != null },
                onManual = { setModelManual(true) },
                onClose = { setModelAdd(false) },
                onWallet = {
                    setModelAdd(false)
                    setConnectChooser(true)
                },
                onImport = {
                    setModelAdd(false)
                    setCsvImport(true)
                },
                onSaved = {
                    setModelAdd(false)
                    modelDraftState.removeState(modelDraftScope)
                    setModelEditingId(null)
                    setModelEditingDraft(null)
                    execute("Transaction saved on this device") {}
                },
                onAccountCreated = { execute("Local account created") {} },
            )
        }
    }

    if (refreshDetails != null && showRefreshDetails) {
        AlertDialog(
            onDismissRequest = { setShowRefreshDetails(false) },
            title = { Text("Price refresh") },
            text = { Text(refreshDetails) },
            confirmButton = { TextButton(onClick = { setShowRefreshDetails(false) }) { Text("Close") } },
        )
    }
    if (dialog == "utxo") {
        UtxoReviewDialog(app, host) { setDialog(null) }
    }
    if (csvImport) {
        CsvImportFlow(app, host, onClose = { setCsvImport(false) }, onChanged = { onRevision() })
    }
    if (dialog == "wallet" && snapshot != null) {
        WalletDialog(
            snapshot.copy(
                sourceEndpoint = sourceDraftEndpoint ?: snapshot.sourceEndpoint,
                sourceConsent =
                    sourceDraftConsent ?: snapshot.sourceConsent,
            ),
            app,
            host,
            { setDialog(null) },
            {
                setMessage(it)
                onRevision()
            },
            host::discoverWallet,
        )
    }
    if (dialog != null &&
        dialog != "wallet" &&
        dialog != "add" &&
        snapshot != null
    ) {
        WorkflowDialog(
            dialog,
            snapshot.copy(
                sourceEndpoint = sourceDraftEndpoint ?: snapshot.sourceEndpoint,
                sourceConsent = sourceDraftConsent ?: snapshot.sourceConsent,
            ),
            app,
            host,
            { setDialog(null) },
            { success, action -> execute(success, action) },
            {
                setMessage(it)
                onRevision()
            },
            { endpoint, allowed ->
                setSourceDraftEndpoint(endpoint)
                setSourceDraftConsent(allowed)
            },
            initialAsset = manualAsset,
        )
    }
    addressWallet?.let { wallet ->
        AlertDialog(
            onDismissRequest = { setAddressWallet(null) },
            title = { Text(wallet.label) },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(snapshot!!.ownership.targets.filter { it.walletId == wallet.id }, key = { it.id.value }) { target ->
                        Column {
                            Text(target.label, fontWeight = FontWeight.Medium)
                            Text(target.identity.canonicalKey, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { setAddressWallet(null) }) { Text("Close") } },
        )
    }
    removeAccount?.let { account ->
        AlertDialog(
            onDismissRequest = { setRemoveAccount(null) },
            title = { Text("Archive ${account.label}?") },
            text = {
                Text(
                    "The account and its subaccounts will be hidden from active portfolios and their public address " +
                        "queries will stop. Saved addresses, activity, and accounting decisions stay on this device. This does not delete the account or its history. Restore it from Archived accounts on this page.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        execute("Account archived; saved history retained") { app.accounts.archiveAccount(account.id) }
                        setSelectedAccount(null)
                        setRemoveAccount(null)
                    },
                    enabled = !busy && syncStatus.phase != SyncPhase.SYNCING,
                    modifier =
                        Modifier.testTag(
                            "confirm-archive-account",
                        ),
                ) {
                    Text("Archive account")
                }
            },
            dismissButton = { TextButton(onClick = { setRemoveAccount(null) }) { Text("Keep account") } },
        )
    }
    sourceScanTarget?.let { target -> SourceScanDialog(app, host, target) { setSourceScanTarget(null) } }
    if (!modelMode) {
        selectedAsset?.let { asset ->
            AssetDialog(
                asset,
                snapshot!!,
                app,
                host,
                if (page ==
                    Page.HOME
                ) {
                    null
                } else {
                    selectedPortfolio
                },
                revision,
                { setSelectedAsset(null) },
                {
                    setManualAsset(asset.asset)
                    setSelectedAsset(null)
                    setDialog("price")
                },
                {
                    if (page == Page.HOME) setSelectedPortfolio(null)
                    presenter.dispatch(ProductEvent.AssetActivity(asset.asset))
                },
                { activity -> setSelectedActivity(activity) },
                {
                    execute(
                        "Provider prices selected; manual history retained",
                    ) { app.prices.useProvider(asset.asset, snapshot.currency) }
                    setSelectedAsset(null)
                },
                onAddTransaction = { assetId ->
                    setManualAsset(assetId)
                    setSelectedAsset(null)
                    setDialog("activity")
                },
                onRefreshPrices = { refreshPrices(true) },
            )
        }
    }
    selectedActivity?.let { item ->
        ActivityDialog(
            item,
            snapshot!!,
            {
                if (item.original.manual) {
                    selectedManualDraft?.let { draft ->
                        val executionTime = kotlin.time.Instant.fromEpochMilliseconds(draft.atMillis)
                        Text("${draft.type.name} · ${draft.asset.value}/${draft.quote.value} · $executionTime")
                        draft.unitPrice?.let { Text("Execution / acquisition evidence: ${quotePrice(it, draft.quote)} per unit") }
                        draft.feeUnitPrice?.let {
                            Text(
                                "Fee conversion evidence: ${quotePrice(it, draft.quote)} per ${draft.feeAsset?.value}",
                            )
                        }
                        Text("Fees are separate expense disposals at zero proceeds. Trade consideration excludes fees.")
                    }
                } else {
                    ActivityEvidence(app, host, item.activity.id, if (page == Page.HOME) null else selectedPortfolio)
                }
            },
            { setSelectedActivity(null) },
            edit =
                if (item.original.manual && selectedManualCanEdit && selectedManualDraft?.id == item.activity.id) {
                    {
                        setModelEditingId(item.activity.id.value)
                        setModelEditingDraft(null)
                        setModelManual(true)
                        setModelAdd(true)
                        setSelectedActivity(null)
                    }
                } else {
                    null
                },
            reverse =
                if (item.original.manual) {
                    {
                        execute(
                            "Manual transaction reversed; history retained",
                        ) { app.transactions.reverseManual(item.activity.id) }
                        setSelectedActivity(null)
                    }
                } else {
                    null
                },
        ) { overlay ->
            execute("Correction saved; original evidence preserved") { app.transactions.saveOverlay(overlay) }
            setSelectedActivity(null)
        }
    }
}
