package io.valroot.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.OwnerId
import io.valroot.core.ownership.AccountKind
import io.valroot.core.reconcile.ClassificationRule
import io.valroot.core.reconcile.ReconciliationCandidate
import io.valroot.core.reconcile.TransferLink
import io.valroot.core.reconcile.TransferLinkConflict
import kotlinx.coroutines.withContext

private val assetIdSaver = Saver<AssetId, String>(save = { it.value }, restore = { AssetId(it) })
private val accountIdSaver =
    Saver<AccountId?, String>(save = { it?.value.orEmpty() }, restore = { it.takeIf(String::isNotEmpty)?.let(::AccountId) })
private val ownerIdSaver =
    Saver<OwnerId?, String>(save = { it?.value.orEmpty() }, restore = { it.takeIf(String::isNotEmpty)?.let(::OwnerId) })
private val activityKindSaver = Saver<ActivityKind, String>(save = { it.name }, restore = ActivityKind::valueOf)

@Composable
internal fun WorkflowDialog(
    type: String,
    data: PortfolioSnapshot,
    app: PortfolioApplication,
    host: PlatformActions,
    close: () -> Unit,
    execute: (String, () -> Unit) -> Unit,
    notify: (String) -> Unit,
    sourceSaved: (String, Boolean) -> Unit,
    initialAsset: AssetId? = null,
) {
    val nameState = rememberSaveable { mutableStateOf("") }
    val addressState = rememberSaveable { mutableStateOf(if (type == "sync") data.sourceEndpoint else "") }
    val amountState = rememberSaveable { mutableStateOf("") }
    val manualFeeState = rememberSaveable { mutableStateOf("") }
    val manualTimeState = rememberSaveable { mutableStateOf("") }
    val manualAccountNameState = rememberSaveable { mutableStateOf("Manual account") }
    val creatingManualAccountState = remember { mutableStateOf(false) }
    val passwordState = remember { mutableStateOf("") }
    val confirmationState = remember { mutableStateOf("") }
    val errorState = remember { mutableStateOf<String?>(null) }
    val ownerState =
        rememberSaveable(stateSaver = ownerIdSaver) {
            mutableStateOf(
                data.ownership.owners
                    .firstOrNull()
                    ?.id,
            )
        }
    val accountState =
        rememberSaveable(stateSaver = accountIdSaver) {
            mutableStateOf(
                data.ownership.accounts
                    .firstOrNull { it.kind != AccountKind.GROUP && "valroot:archived" !in it.tags }
                    ?.id,
            )
        }
    val parentState = rememberSaveable(stateSaver = accountIdSaver) { mutableStateOf<AccountId?>(null) }
    val selectedKindState = rememberSaveable(stateSaver = activityKindSaver) { mutableStateOf(ActivityKind.DEPOSIT) }
    var rules by remember { mutableStateOf<List<ClassificationRule>>(emptyList()) }
    var rulesLoaded by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<ClassificationRule?>(null) }
    var showRuleEditor by remember { mutableStateOf(false) }
    val assetState = rememberSaveable(stateSaver = assetIdSaver) { mutableStateOf(initialAsset ?: AssetId("bitcoin:btc")) }
    val consentState = rememberSaveable { mutableStateOf(data.sourceConsent) }
    val priceConsentState = rememberSaveable { mutableStateOf(false) }
    val automaticPriceConsentState = remember { mutableStateOf(false) }
    val automaticPortfolioPricesState = remember { mutableStateOf(false) }
    val automaticPriceStatusState = remember { mutableStateOf<String?>(null) }
    val priceDaysState = rememberSaveable { mutableIntStateOf(90) }
    val fetchingPriceState = remember { mutableStateOf(false) }
    val priceMessageState = remember { mutableStateOf<String?>(null) }
    val candidatesState = remember { mutableStateOf<List<ReconciliationCandidate>>(emptyList()) }
    val transferLinksState = remember { mutableStateOf<List<TransferLink>>(emptyList()) }
    val summariesState = remember { mutableStateOf<Map<ActivityId, String>>(emptyMap()) }
    val transferConflictsState = remember { mutableStateOf<List<TransferLinkConflict>>(emptyList()) }
    val transfersLoadedState = remember { mutableStateOf(false) }
    val selectedAccountsState = remember { mutableStateOf(emptySet<AccountId>()) }
    val acceptedBackupState = rememberSaveable { mutableStateOf(false) }
    val acceptedRecoveryState = rememberSaveable { mutableStateOf(false) }
    val syncStatus = host.syncStatus.collectAsState()
    val priceRefreshStatus = host.priceRefreshStatus.collectAsState()
    val priceRevision by host.dataRevision.collectAsState()
    val scope = rememberCoroutineScope()
    var priceConsent by priceConsentState
    var automaticPriceConsent by automaticPriceConsentState
    var automaticPortfolioPrices by automaticPortfolioPricesState
    var automaticPriceStatus by automaticPriceStatusState
    var candidates by candidatesState
    var transferLinks by transferLinksState
    var summaries by summariesState
    var transferConflicts by transferConflictsState
    var transfersLoaded by transfersLoadedState
    val error by errorState
    LaunchedEffect(type) {
        if (type == "rule") {
            rules = withContext(host.dispatcher) { app.transactions.rules() }
            rulesLoaded = true
        }
        if (type == "price") {
            priceConsent = withContext(host.dispatcher) { app.prices.sourceConsent() }
            automaticPriceConsent = withContext(host.dispatcher) { app.prices.automaticConsent() }
            automaticPortfolioPrices = withContext(host.dispatcher) { app.prices.automaticPortfolioConsent() }
        }
        if (type == "reconcile") {
            withContext(host.dispatcher) {
                candidates = app.accounting.reconciliationCandidates(86_400_000)
                transferLinks = app.accounting.transferLinks()
                transferConflicts = app.accounting.transferLinkConflicts()
                summaries =
                    (
                        candidates.take(50).flatMap { listOf(it.first, it.second) } +
                            transferLinks.flatMap { listOf(it.outgoingActivity, it.incomingActivity) } +
                            transferConflicts.flatMap { listOf(it.first, it.second) }
                    ).distinct().mapNotNull { id -> app.accounting.activitySummary(id)?.let { id to it } }.toMap()
            }
            transfersLoaded = true
        }
    }
    LaunchedEffect(type, priceRevision, automaticPriceConsent) {
        if (type == "price") automaticPriceStatus = withContext(host.dispatcher) { app.prices.automaticStatus() }
    }
    val title =
        when (type) {
            "account" -> "Track an account"
            "group" -> "Create an account group"
            "owner" -> "Add an owner"
            "portfolio" -> "Create a portfolio"
            "activity" -> "Record an activity"
            "price" -> "Price observations"
            "sync" -> "Sources & sync"
            "backup" -> "Encrypted backup"
            "restore" -> "Restore a backup"
            "wipe" -> "Erase local data"
            "lock" -> "Set a privacy lock"
            "rule" -> "Manage classification rules"
            "reconcile" -> "Review transfers"
            else -> "Details"
        }
    FormDialog(title, close) {
        when (type) {
            "owner", "group", "portfolio", "account" ->
                OwnershipWorkflow(
                    type = type,
                    data = data,
                    app = app,
                    nameState = nameState,
                    addressState = addressState,
                    ownerState = ownerState,
                    parentState = parentState,
                    selectedAccountsState = selectedAccountsState,
                    errorState = errorState,
                    execute = execute,
                    close = close,
                )
            "activity" ->
                ManualActivityWorkflow(
                    data = data,
                    app = app,
                    nameState = nameState,
                    amountState = amountState,
                    manualFeeState = manualFeeState,
                    manualTimeState = manualTimeState,
                    manualAccountNameState = manualAccountNameState,
                    creatingManualAccountState = creatingManualAccountState,
                    errorState = errorState,
                    accountState = accountState,
                    selectedKindState = selectedKindState,
                    assetState = assetState,
                    execute = execute,
                    close = close,
                )
            "price" ->
                PriceWorkflow(
                    data = data,
                    app = app,
                    host = host,
                    scope = scope,
                    notify = notify,
                    execute = execute,
                    close = close,
                    amountState = amountState,
                    addressState = addressState,
                    assetState = assetState,
                    errorState = errorState,
                    priceConsentState = priceConsentState,
                    automaticPriceConsentState = automaticPriceConsentState,
                    automaticPortfolioPricesState = automaticPortfolioPricesState,
                    automaticPriceStatusState = automaticPriceStatusState,
                    priceDaysState = priceDaysState,
                    fetchingPriceState = fetchingPriceState,
                    priceMessageState = priceMessageState,
                    priceRefreshStatusState = priceRefreshStatus,
                )
            "sync" ->
                SourceWorkflow(
                    data = data,
                    app = app,
                    host = host,
                    scope = scope,
                    syncStatusState = syncStatus,
                    addressState = addressState,
                    consentState = consentState,
                    errorState = errorState,
                    acceptedRecoveryState = acceptedRecoveryState,
                    sourceSaved = sourceSaved,
                    notify = notify,
                    close = close,
                )
            "backup", "restore", "lock" ->
                SecurityWorkflow(
                    type = type,
                    host = host,
                    passwordState = passwordState,
                    confirmationState = confirmationState,
                    acceptedBackupState = acceptedBackupState,
                    errorState = errorState,
                    notify = notify,
                    close = close,
                )
            "wipe" ->
                WipeWorkflow(
                    app = app,
                    host = host,
                    nameState = nameState,
                    execute = execute,
                    close = close,
                )
            "rule" -> {
                if (!rulesLoaded) {
                    Text("Loading classification rules…")
                } else if (showRuleEditor) {
                    RuleEditor(
                        editingRule,
                        rules.map { it.id }.toSet(),
                        { showRuleEditor = false },
                    ) { value ->
                        execute("Classification rule saved") {
                            app.transactions.saveRule(value)
                            rules = app.transactions.rules()
                            showRuleEditor = false
                        }
                    }
                } else {
                    Text("Rules run by descending priority, then ID. Activity-specific corrections take precedence.", fontSize = 12.sp)
                    if (rules.isEmpty()) Text("No classification rules saved.")
                    rules.forEach { rule ->
                        HorizontalDivider()
                        Text(rule.id, fontWeight = FontWeight.SemiBold)
                        Text("${if (rule.enabled) "Enabled" else "Disabled"} · Priority ${rule.priority} · ${rule.result.name.lowercase()}")
                        Text(
                            "Original classification: ${rule.matchKind?.name?.lowercase() ?: "any"} · Asset: ${rule.matchAsset?.value ?: "any"} · Source: ${rule.matchSource?.value ?: "any"}",
                            fontSize = 12.sp,
                        )
                        Row {
                            TextButton(onClick = {
                                editingRule = rule
                                showRuleEditor = true
                            }, modifier = Modifier.testTag("edit-rule-${rule.id}")) { Text("Edit") }
                            TextButton(onClick = {
                                execute(if (rule.enabled) "Rule disabled" else "Rule enabled") {
                                    app.transactions.saveRule(rule.copy(enabled = !rule.enabled))
                                    rules = app.transactions.rules()
                                }
                            }, modifier = Modifier.testTag("toggle-rule-${rule.id}")) {
                                Text(if (rule.enabled) "Disable" else "Enable")
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        editingRule = null
                        showRuleEditor = true
                    }) { Text("Add a rule") }
                }
            }
            "reconcile" ->
                ReconcileWorkflow(
                    app = app,
                    execute = execute,
                    close = close,
                    candidatesState = candidatesState,
                    transferLinksState = transferLinksState,
                    summariesState = summariesState,
                    transferConflictsState = transferConflictsState,
                    transfersLoadedState = transfersLoadedState,
                )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}
