package io.polion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.bitcoin.bitcoinMainnetAddress
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.market.PriceObservation
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import io.polion.core.model.NetworkId
import io.polion.core.model.OwnerId
import io.polion.core.model.PortfolioId
import io.polion.core.model.Price
import io.polion.core.model.SourceRecordId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.Portfolio
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import io.polion.core.reconcile.ActivityOverlay
import io.polion.core.reconcile.ClassificationRule
import io.polion.core.reconcile.EffectiveActivity
import io.polion.core.reconcile.LinkState
import io.polion.core.reconcile.ReconciliationCandidate
import io.polion.core.reconcile.TransferLink
import io.polion.core.reconcile.TransferLinkConflict
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

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
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf(if (type == "sync") data.sourceEndpoint else "") }
    var amount by rememberSaveable { mutableStateOf("") }
    var manualFee by rememberSaveable { mutableStateOf("") }
    var manualTime by rememberSaveable { mutableStateOf("") }
    var manualAccountName by rememberSaveable { mutableStateOf("Manual account") }
    var creatingManualAccount by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var owner by rememberSaveable(stateSaver = ownerIdSaver) {
        mutableStateOf(
            data.ownership.owners
                .firstOrNull()
                ?.id,
        )
    }
    var account by rememberSaveable(stateSaver = accountIdSaver) {
        mutableStateOf(
            data.ownership.accounts
                .firstOrNull { it.kind != AccountKind.GROUP && "polion:archived" !in it.tags }
                ?.id,
        )
    }
    var parent by rememberSaveable(stateSaver = accountIdSaver) { mutableStateOf<AccountId?>(null) }
    var selectedKind by rememberSaveable(stateSaver = activityKindSaver) { mutableStateOf(ActivityKind.DEPOSIT) }
    var rules by remember { mutableStateOf<List<ClassificationRule>>(emptyList()) }
    var rulesLoaded by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<ClassificationRule?>(null) }
    var showRuleEditor by remember { mutableStateOf(false) }
    var asset by rememberSaveable(stateSaver = assetIdSaver) { mutableStateOf(initialAsset ?: AssetId("bitcoin:btc")) }
    var consent by rememberSaveable { mutableStateOf(data.sourceConsent) }
    var priceConsent by rememberSaveable { mutableStateOf(false) }
    var automaticPriceConsent by remember { mutableStateOf(false) }
    var automaticPortfolioPrices by remember { mutableStateOf(false) }
    var automaticPriceStatus by remember { mutableStateOf<String?>(null) }
    var priceDays by rememberSaveable { mutableIntStateOf(90) }
    var fetchingPrice by remember { mutableStateOf(false) }
    var priceMessage by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<ReconciliationCandidate>>(emptyList()) }
    var transferLinks by remember { mutableStateOf<List<TransferLink>>(emptyList()) }
    var summaries by remember { mutableStateOf<Map<io.polion.core.model.ActivityId, String>>(emptyMap()) }
    var transferConflicts by remember { mutableStateOf<List<TransferLinkConflict>>(emptyList()) }
    var transfersLoaded by remember { mutableStateOf(false) }
    var selectedAccounts by remember { mutableStateOf(emptySet<AccountId>()) }
    var acceptedBackup by rememberSaveable { mutableStateOf(false) }
    var acceptedRecovery by rememberSaveable { mutableStateOf(false) }
    val syncStatus by host.syncStatus.collectAsState()
    val priceRefreshStatus by host.priceRefreshStatus.collectAsState()
    val priceRevision by host.dataRevision.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(type) {
        if (type == "rule") {
            rules = withContext(host.dispatcher) { app.rules() }
            rulesLoaded = true
        }
        if (type == "price") {
            priceConsent = withContext(host.dispatcher) { app.priceSourceConsent() }
            automaticPriceConsent = withContext(host.dispatcher) { app.automaticPriceConsent() }
            automaticPortfolioPrices = withContext(host.dispatcher) { app.automaticPortfolioPriceConsent() }
        }
        if (type == "reconcile") {
            withContext(host.dispatcher) {
                candidates = app.reconciliationCandidates(86_400_000)
                transferLinks = app.transferLinks()
                transferConflicts = app.transferLinkConflicts()
                summaries =
                    (
                        candidates.take(50).flatMap { listOf(it.first, it.second) } +
                            transferLinks.flatMap { listOf(it.outgoingActivity, it.incomingActivity) } +
                            transferConflicts.flatMap { listOf(it.first, it.second) }
                    ).distinct().mapNotNull { id -> app.activitySummary(id)?.let { id to it } }.toMap()
            }
            transfersLoaded = true
        }
    }
    LaunchedEffect(type, priceRevision, automaticPriceConsent) {
        if (type == "price") automaticPriceStatus = withContext(host.dispatcher) { app.automaticPriceStatus() }
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
            "owner", "group", "portfolio", "account" -> {
                Text(
                    if (type ==
                        "account"
                    ) {
                        "Add a public Bitcoin mainnet address. Private keys, seeds, xpubs and descriptors are not accepted in this address workflow."
                    } else {
                        "Organize accounts without moving funds."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Field("${if (type == "account") "Account" else "Display"} name", name) { name = it }
                if (type == "account" || type == "group") {
                    if (data.ownership.owners.isNotEmpty()) {
                        Choice("Owner", data.ownership.owners.map { it.id to it.label }, owner) {
                            owner =
                                it
                        }
                    } else {
                        Text("A personal owner will be created for this account.", fontSize = 12.sp)
                    }
                    val groups =
                        data.ownership.accounts.filter {
                            it.kind == AccountKind.GROUP &&
                                it.ownerId == owner &&
                                "polion:archived" !in it.tags
                        }
                    if (groups.isNotEmpty()) {
                        Choice("Group", listOf(null to "No group") + groups.map { it.id to it.label }, parent) {
                            parent =
                                it
                        }
                    }
                }
                if (type == "account") Field("Public Bitcoin address", address) { address = it }
                if (type == "portfolio") {
                    Text("Select accounts. Leave empty to include every account.", fontSize = 12.sp)
                    data.ownership.accounts.filter { "polion:archived" !in it.tags }.forEach { a ->
                        Row {
                            Checkbox(a.id in selectedAccounts, { checked ->
                                selectedAccounts =
                                    if (checked) selectedAccounts + a.id else selectedAccounts - a.id
                            })
                            ; Text(a.label, Modifier.padding(top = 12.dp))
                        }
                    }
                }
                Button(onClick = {
                    if (name.isBlank()) {
                        error = "Enter a name."
                        return@Button
                    }
                    if (type == "account" &&
                        runCatching { bitcoinMainnetAddress(address.trim()) }.isFailure
                    ) {
                        error =
                            "Enter a valid Bitcoin mainnet address. Seeds and private keys must never be entered."
                        return@Button
                    }
                    execute("${if (type == "account") "Account" else "Organization"} saved on this device") {
                        val id = uniqueId(type)
                        when (type) {
                            "owner" -> app.saveOwner(Owner(OwnerId(id), name.trim()))
                            "portfolio" ->
                                app.savePortfolio(
                                    Portfolio(PortfolioId(id), name.trim(), data.currency.value, accountIds = selectedAccounts),
                                )
                            else -> {
                                val ownerId = owner ?: OwnerId(uniqueId("owner")).also { app.saveOwner(Owner(it, "Personal")) }
                                val accountId = AccountId(id)
                                val value =
                                    Account(
                                        accountId,
                                        ownerId,
                                        name.trim(),
                                        if (type ==
                                            "group"
                                        ) {
                                            AccountKind.GROUP
                                        } else {
                                            AccountKind.WALLET
                                        },
                                        parent,
                                    )
                                if (type ==
                                    "account"
                                ) {
                                    app.saveAccountAndTarget(
                                        value,
                                        TrackingTarget(
                                            TrackingTargetId(uniqueId("address")),
                                            accountId,
                                            TargetIdentity(
                                                NetworkId("bitcoin-mainnet"),
                                                "address",
                                                bitcoinMainnetAddress(address.trim()),
                                            ),
                                            TargetRole.LEAF,
                                            "Bitcoin mainnet",
                                        ),
                                    )
                                } else {
                                    app.saveAccount(value)
                                }
                            }
                        }
                    }
                    close()
                }, modifier = Modifier.fillMaxWidth()) { Text("Save ${if (type == "account") "account" else "changes"}") }
            }
            "activity" -> {
                Text(
                    "Manual entries affect your local ledger. A positive quantity adds funds; a negative quantity removes them. This does not send a transaction.",
                    fontSize = 13.sp,
                )
                if (data.ownership.accounts.none { it.kind != AccountKind.GROUP && "polion:archived" !in it.tags }) {
                    Text("Create a local account for your manual entries. No provider connection is required.")
                    Field("Account name", manualAccountName) { manualAccountName = it }
                    Button(onClick = {
                        if (manualAccountName.isBlank()) {
                            error = "Enter an account name."
                            return@Button
                        }
                        creatingManualAccount = true
                        execute("Manual account created") {
                            try {
                                val ownerId =
                                    data.ownership.owners
                                        .firstOrNull()
                                        ?.id ?: OwnerId(uniqueId("owner")).also {
                                        app.saveOwner(Owner(it, "Personal"))
                                    }
                                val accountId = AccountId(uniqueId("manual-account"))
                                app.saveAccount(Account(accountId, ownerId, manualAccountName.trim(), AccountKind.WALLET))
                                account = accountId
                            } finally {
                                creatingManualAccount = false
                            }
                        }
                    }, enabled = !creatingManualAccount) { Text("Create manual account") }
                } else {
                    Choice(
                        "Account",
                        data.ownership.accounts
                            .filter { it.kind != AccountKind.GROUP && "polion:archived" !in it.tags }
                            .map { it.id to it.label },
                        account,
                    ) {
                        account =
                            it
                    }
                    val manualKinds = listOf(ActivityKind.DEPOSIT, ActivityKind.PAYMENT, ActivityKind.REWARD)
                    Choice("Classification", manualKinds.map { it to it.name.lowercase() }, selectedKind) { selectedKind = it }
                    Text(
                        "Deposits and rewards use positive quantities; payments use negative quantities. Buy/sell trades and transfers between accounts are unavailable in this form.",
                        fontSize = 12.sp,
                    )
                    val manualAssets =
                        (data.valuations + data.marketCoins.map { it.detailValuation() })
                            .distinctBy { it.asset }
                            .filter { it.metadata != null }
                    val manualChoices =
                        (
                            manualAssets.map { it.asset to (it.metadata?.name ?: it.asset.value) } +
                                (AssetId("bitcoin:btc") to "Bitcoin")
                        ).distinctBy { it.first }
                    Choice("Asset", manualChoices, asset) { asset = it }
                    val metadata = manualAssets.find { it.asset == asset }?.metadata
                    val decimals = metadata?.decimals ?: if (asset == AssetId("bitcoin:btc")) 8 else null
                    val symbol = metadata?.symbol ?: if (asset == AssetId("bitcoin:btc")) "BTC" else asset.value
                    Field("$symbol quantity (positive or negative)", amount) { amount = it }
                    Field("Fee in $symbol (optional, positive)", manualFee) { manualFee = it }
                    Field("Date & time (UTC, ISO 8601; empty = now)", manualTime) { manualTime = it }
                    Field("Label (optional)", name) { name = it }
                    Button(onClick = {
                        val units =
                            runCatching {
                                AtomicAmount.fromDecimal(
                                    asset,
                                    ExactDecimal.parse(amount),
                                    requireNotNull(decimals),
                                )
                            }.getOrNull()
                        if (units == null ||
                            units.atomicUnits.sign == 0 ||
                            account == null ||
                            data.ownership.accounts.none {
                                it.id == account && it.kind != AccountKind.GROUP && "polion:archived" !in it.tags
                            }
                        ) {
                            error = "Enter a nonzero $symbol quantity with up to ${decimals ?: 0} decimal places."
                            return@Button
                        }
                        if (selectedKind !in manualKinds ||
                            (selectedKind == ActivityKind.PAYMENT && units.atomicUnits.sign >= 0) ||
                            (selectedKind != ActivityKind.PAYMENT && units.atomicUnits.sign <= 0)
                        ) {
                            error = "Use a positive quantity for deposits/rewards or a negative quantity for payments."
                            return@Button
                        }
                        val fee =
                            if (manualFee.isBlank()) {
                                null
                            } else {
                                runCatching {
                                    AtomicAmount.fromDecimal(asset, ExactDecimal.parse(manualFee), requireNotNull(decimals))
                                }.getOrNull()
                            }
                        if (manualFee.isNotBlank() && (fee == null || fee.atomicUnits.sign <= 0)) {
                            error = "Enter a positive fee in $symbol or leave the fee empty."
                            return@Button
                        }
                        val occurredAt =
                            if (manualTime.isBlank()) {
                                Clock.System.now().toEpochMilliseconds()
                            } else {
                                runCatching { Instant.parse(manualTime.trim()).toEpochMilliseconds() }.getOrNull()
                            }
                        if (occurredAt == null || occurredAt < 0) {
                            error = "Enter a UTC date and time such as 2026-11-01T12:00:00Z."
                            return@Button
                        }
                        execute("Manual activity recorded") {
                            val id = ActivityId(uniqueId("manual"))
                            app.saveManualActivity(
                                Activity(
                                    id,
                                    occurredAt,
                                    id.value,
                                    selectedKind,
                                    ActivityStatus.CONFIRMED,
                                    listOf(
                                        Movement(
                                            EventId("${id.value}:0"),
                                            account!!,
                                            units,
                                            if (selectedKind ==
                                                ActivityKind.REWARD
                                            ) {
                                                MovementRole.REWARD
                                            } else {
                                                MovementRole.TRANSFER
                                            },
                                        ),
                                    ) +
                                        listOfNotNull(
                                            fee?.let {
                                                Movement(
                                                    EventId("${id.value}:fee"),
                                                    account!!,
                                                    AtomicAmount(asset, -it.atomicUnits),
                                                    MovementRole.FEE,
                                                )
                                            },
                                        ),
                                    emptySet(),
                                    null,
                                    true,
                                ),
                            )
                            if (name.isNotBlank()) app.saveOverlay(ActivityOverlay(id, label = name.trim()))
                        }
                        close()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Record locally") }
                }
            }
            "price" -> {
                Text(
                    "A manual quote is a saved observation, not a market feed. It is used for valuations from its effective time, and remains traceable in asset details.",
                    fontSize = 13.sp,
                )
                val assets =
                    (
                        (data.valuations + data.marketCoins.map { it.detailValuation() }).map {
                            it.asset to
                                (it.metadata?.name ?: it.asset.value)
                        } +
                            (AssetId("bitcoin:btc") to "Bitcoin")
                    ).distinctBy { it.first }.filter { it.first != data.currency }
                Choice("Asset", assets, asset) { asset = it }
                Field("Price per whole coin in ${data.currency.value}", amount) { amount = it }
                Field("Effective date (UTC, YYYY-MM-DD; empty = now)", address) { address = it }
                Button(onClick = {
                    val price = runCatching { ExactDecimal.parse(amount) }.getOrNull()
                    val at =
                        if (address.isBlank()) {
                            Clock.System.now().toEpochMilliseconds()
                        } else {
                            runCatching {
                                kotlin.time.Instant
                                    .parse("${address}T00:00:00Z")
                                    .toEpochMilliseconds()
                            }.getOrNull()
                        }
                    if (price == null ||
                        price.coefficient.sign < 0 ||
                        at == null ||
                        at > Clock.System.now().toEpochMilliseconds()
                    ) {
                        error =
                            "Enter a nonnegative price and a valid date in the past or today."
                        return@Button
                    }
                    execute("Manual price saved") {
                        app.recordManualPrice(
                            PriceObservation(
                                uniqueId("manual-price"),
                                Price(asset, data.currency, price),
                                null,
                                at,
                                Clock.System.now().toEpochMilliseconds(),
                                true,
                            ),
                        )
                    }
                    close()
                }, modifier = Modifier.fillMaxWidth()) { Text("Save price observation") }
                HorizontalDivider()
                Text("Automatic Bitcoin prices", fontWeight = FontWeight.SemiBold)
                Text(
                    "Kraken receives Bitcoin pairs for your USD, EUR and GBP portfolio currencies and your IP address; wallet addresses " +
                        "are not sent. This switch also turns on automatic wallet history refresh. " +
                        "Closed hourly and daily prices refresh while scheduling is available. No API key is needed.",
                    fontSize = 13.sp,
                )
                Row {
                    Checkbox(automaticPriceConsent, { accepted ->
                        automaticPriceConsent = accepted
                        if (accepted) automaticPortfolioPrices = true
                        execute("Refresh settings saved") { applyAutomaticRefresh(app, host, accepted) }
                    }, modifier = Modifier.testTag("automatic-price-consent"))
                    Text("Allow automatic Kraken prices and wallet refresh", modifier = Modifier.padding(top = 12.dp))
                }
                if (automaticPriceConsent) {
                    Row {
                        Checkbox(automaticPortfolioPrices, { accepted ->
                            automaticPortfolioPrices = accepted
                            execute("Price currencies saved") { app.setAutomaticPortfolioPriceConsent(accepted) }
                        }, modifier = Modifier.testTag("automatic-portfolio-price-consent"))
                        Text("Include other portfolio currencies", modifier = Modifier.padding(top = 12.dp))
                    }
                    Text(
                        automaticPriceStatus ?: "Waiting for the first price refresh.",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    when (priceRefreshStatus) {
                        PriceRefreshPhase.QUEUED -> Text("Price refresh queued.", color = Muted, fontSize = 12.sp)
                        PriceRefreshPhase.RUNNING -> Text("Refreshing prices…", color = Muted, fontSize = 12.sp)
                        PriceRefreshPhase.IDLE -> Unit
                    }
                    OutlinedButton(
                        onClick = {
                            host.refreshAutomaticPrices { message ->
                                priceMessage = message
                                notify(message)
                            }
                        },
                        enabled = priceRefreshStatus == PriceRefreshPhase.IDLE,
                        modifier = Modifier.testTag("refresh-prices-now"),
                    ) { Text("Refresh prices now") }
                }
                Text("Bitcoin price history", fontWeight = FontWeight.SemiBold)
                Text(
                    "CoinGecko receives the coin, currency and time range, plus your IP address. Wallet addresses are not sent. Saved quotes are available offline and do not establish acquisition cost. The 90-day range includes hourly points; the 365-day range uses daily points.",
                    fontSize = 13.sp,
                )
                Row {
                    Checkbox(
                        priceConsent,
                        { accepted ->
                            priceConsent = accepted
                            scope.launch { withContext(host.dispatcher) { app.setPriceSourceConsent(accepted) } }
                        },
                        enabled = !fetchingPrice,
                        modifier = Modifier.testTag("price-source-consent"),
                    )
                    Text("Allow CoinGecko price requests", modifier = Modifier.padding(top = 12.dp))
                }
                Text("An optional CoinGecko API key from Settings › API Keys raises the request limit.", fontSize = 13.sp)
                Choice("Price history range", listOf(90 to "90 days · hourly", 365 to "365 days · daily"), priceDays) { priceDays = it }
                Button(
                    onClick = {
                        fetchingPrice = true
                        scope.launch {
                            val apiKey =
                                withContext(host.dispatcher) {
                                    app.setPriceSourceConsent(priceConsent)
                                    app.coingeckoApiKey().orEmpty()
                                }
                            host.fetchPriceHistory(AssetId("bitcoin:btc"), data.currency, priceDays, apiKey) { message ->
                                fetchingPrice = false
                                priceMessage = message
                                notify(message)
                            }
                        }
                    },
                    enabled = priceConsent && !fetchingPrice,
                    modifier = Modifier.fillMaxWidth().testTag("fetch-historical-price"),
                ) { Text(if (fetchingPrice) "Fetching price history…" else "Fetch Bitcoin price history") }
                priceMessage?.let { Text(it, fontSize = 13.sp) }
            }
            "sync" -> {
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
                Row {
                    Checkbox(consent, { consent = it }, modifier = Modifier.testTag("bitcoin-source-consent"))
                    Text("I allow public address queries to this endpoint", Modifier.weight(1f).padding(top = 8.dp), fontSize = 13.sp)
                }
                Text(
                    "Automatic refresh of prices and wallet history is set in Settings.",
                    fontSize = 13.sp,
                )
                Text(
                    "Android may defer background work. Desktop refresh runs while Polion is open. Network errors preserve saved data. Pending activity is provisional, and source history is not independently verified.",
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
                                app.setSourceEndpoint(address)
                                app.setSourceConsent(consent)
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
                    "Use recovery when Polion cannot connect the saved chain checkpoint to the provider's current chain. " +
                        "It downloads all history for active watched addresses from the selected endpoint " +
                        "and uses extra provider traffic. " +
                        "Saved records remain available until the replacement scan passes validation. " +
                        "Coverage remains provider-based and unverified.",
                    fontSize = 12.sp,
                )
                Row {
                    Checkbox(
                        acceptedRecovery,
                        { acceptedRecovery = it },
                        enabled = syncStatus.phase != SyncPhase.SYNCING,
                        modifier = Modifier.testTag("bitcoin-recovery-consent"),
                    )
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
            "backup", "restore", "lock" -> {
                Text(
                    when (type) {
                        "restore" ->
                            "Choose an encrypted Polion backup. After authentication and schema validation, " +
                                "it replaces this device’s portfolio. Save a backup of your current data first."
                        "lock" ->
                            "This password hides the app screen. It does not encrypt the local database. " +
                                "Use device or disk encryption for storage protection."
                        else ->
                            "Your portable backup is encrypted and authenticated. " +
                                "Keep the password somewhere safe; it cannot be recovered."
                    },
                    fontSize = 13.sp,
                )
                SecretField("Password (at least 8 characters)", password) { password = it }
                if (type != "restore") SecretField("Confirm password", confirmation) { confirmation = it }
                if (type ==
                    "restore"
                ) {
                    Row {
                        Checkbox(acceptedBackup, { acceptedBackup = it })
                        Text(
                            "Replace the current local portfolio after validating the backup",
                            Modifier.weight(1f).padding(top = 8.dp),
                            fontSize = 13.sp,
                        )
                    }
                }
                Button(onClick = {
                    if (password.length < 8 ||
                        (type != "restore" && password != confirmation)
                    ) {
                        error = "Use at least 8 characters and matching passwords."
                        return@Button
                    }
                    when (type) {
                        "lock" ->
                            host.setLockPassword(password) {
                                notify(it)
                                close()
                            }
                        "restore" -> {
                            if (!acceptedBackup) {
                                error = "Confirm that this backup will replace the local portfolio."
                                return@Button
                            }
                            host.restoreBackup(password) { notify(it) }
                            close()
                        }
                        else -> {
                            host.saveBackup(password) { notify(it) }
                            close()
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when (type) {
                            "restore" -> "Choose backup & restore"
                            "lock" -> "Enable privacy lock"
                            else -> "Save encrypted backup"
                        },
                    )
                }
            }
            "wipe" -> {
                Text(
                    "This removes owners, accounts, activity, prices, corrections and settings from this device. Export a backup first. Funds on the blockchain are unaffected.",
                )
                Field("Type ERASE to confirm", name) { name = it }
                Button(
                    onClick = {
                        execute("Local portfolio data erased") {
                            app.wipe()
                            applyAutomaticRefresh(app, host, false)
                        }
                        close()
                    },
                    enabled =
                        name == "ERASE",
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Erase this device’s data") }
            }
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
                            app.saveRule(value)
                            rules = app.rules()
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
                                    app.saveRule(rule.copy(enabled = !rule.enabled))
                                    rules = app.rules()
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
            "reconcile" -> {
                Text(
                    "Link a withdrawal from one of your accounts with the matching deposit into another, for example an exchange " +
                        "withdrawal and the wallet that received it. Linked coins keep their original cost and age instead of " +
                        "counting as a sale and a new purchase. Both records stay as they are, and a link can be removed.",
                    fontSize = 13.sp,
                )
                Text("Accepted relationships", fontWeight = FontWeight.SemiBold)
                if (!transfersLoaded) Text("Loading transfer review…")
                if (transfersLoaded && transferLinks.isEmpty()) Text("No accepted transfer relationships.")
                transferLinks.forEach { link ->
                    HorizontalDivider()
                    Text("Out: ${summaries[link.outgoingActivity] ?: link.outgoingActivity.value}", fontSize = 12.sp)
                    Text("In: ${summaries[link.incomingActivity] ?: link.incomingActivity.value}", fontSize = 12.sp)
                    TextButton(onClick = {
                        execute("Transfer link removed; decision marked rejected") {
                            app.setDecision(link.outgoingActivity, link.incomingActivity, LinkState.REJECTED)
                            transferLinks = app.transferLinks()
                            transferConflicts = app.transferLinkConflicts()
                            candidates = app.reconciliationCandidates(86_400_000)
                        }
                    }, modifier = Modifier.testTag("remove-transfer-${link.outgoingActivity.value}-${link.incomingActivity.value}")) {
                        Text("Remove link")
                    }
                }
                Text("Accepted decisions needing review", fontWeight = FontWeight.SemiBold)
                if (transfersLoaded && transferConflicts.isEmpty()) Text("No transfer link conflicts.")
                transferConflicts.forEach { conflict ->
                    HorizontalDivider()
                    Text(
                        "${summaries[conflict.first] ?: conflict.first.value} ↔ ${summaries[conflict.second] ?: conflict.second.value}",
                        fontSize = 12.sp,
                    )
                    Text("Unresolved: ${conflict.reason}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    TextButton(onClick = {
                        execute("Transfer decision rejected") {
                            app.setDecision(conflict.first, conflict.second, LinkState.REJECTED)
                            transferLinks = app.transferLinks()
                            transferConflicts = app.transferLinkConflicts()
                            candidates = app.reconciliationCandidates(86_400_000)
                        }
                    }, modifier = Modifier.testTag("reject-transfer-conflict-${conflict.first.value}-${conflict.second.value}")) {
                        Text("Reject decision")
                    }
                }
                Text("Suggestions", fontWeight = FontWeight.SemiBold)
                if (transfersLoaded && candidates.isEmpty()) Text("No transfer candidates in the current history.")
                candidates.take(50).forEach { candidate ->
                    HorizontalDivider()
                    Text(summaries[candidate.first] ?: candidate.first.value, fontSize = 12.sp)
                    Text("↔ ${summaries[candidate.second] ?: candidate.second.value}", fontSize = 12.sp)
                    Text(
                        "Match ${candidate.score}%${if (candidate.ambiguous) " · several possible matches; check the amounts and dates" else ""}",
                        fontSize = 12.sp,
                    )
                    Row {
                        TextButton(
                            onClick = {
                                execute("Transfer link accepted") { app.setDecision(candidate.first, candidate.second, LinkState.ACCEPTED) }
                                close()
                            },
                            modifier =
                                Modifier.testTag(
                                    "accept-transfer-${candidate.first.value}-${candidate.second.value}",
                                ),
                        ) { Text("Link") }
                        TextButton(onClick = {
                            execute("Suggestion rejected") { app.setDecision(candidate.first, candidate.second, LinkState.REJECTED) }
                            close()
                        }) { Text("Reject") }
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}

@Composable
private fun RuleEditor(
    existing: ClassificationRule?,
    existingIds: Set<String>,
    cancel: () -> Unit,
    save: (ClassificationRule) -> Unit,
) {
    var id by remember(existing) { mutableStateOf(existing?.id.orEmpty()) }
    var priority by remember(existing) { mutableStateOf((existing?.priority ?: 100).toString()) }
    var enabled by remember(existing) { mutableStateOf(existing?.enabled ?: true) }
    var result by remember(existing) { mutableStateOf(existing?.result ?: ActivityKind.DEPOSIT) }
    var matchKind by remember(existing) { mutableStateOf(existing?.matchKind) }
    var matchAsset by remember(existing) { mutableStateOf(existing?.matchAsset?.value.orEmpty()) }
    var matchSource by remember(existing) { mutableStateOf(existing?.matchSource?.value.orEmpty()) }
    var error by remember(existing) { mutableStateOf<String?>(null) }
    if (existing == null) Field("Rule ID", id) { id = it } else Text("Rule ID: ${existing.id}")
    Field("Priority", priority) { priority = it }
    Row {
        Text("Enabled")
        Switch(enabled, { enabled = it }, modifier = Modifier.testTag("rule-enabled"))
    }
    Choice(
        "Match original classification",
        listOf(null to "Any classification") +
            ActivityKind.entries.map {
                it to it.name.lowercase()
            },
        matchKind,
    ) {
        matchKind =
            it
    }
    Field("Match asset ID (blank for any)", matchAsset) { matchAsset = it }
    Field("Match source record ID (blank for any)", matchSource) { matchSource = it }
    Choice("Classify as", ActivityKind.entries.map { it to it.name.lowercase() }, result) { result = it }
    Text(
        "Filters all apply together. Rules change classification; source evidence and accounting treatments remain explicit.",
        fontSize = 12.sp,
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row {
        TextButton(onClick = cancel) { Text("Back to rules") }
        Button(onClick = {
            val parsedPriority = priority.toIntOrNull()
            if (id.isBlank() || parsedPriority == null) {
                error = "Enter a rule ID and a whole-number priority."
            } else if (existing == null && id.trim() in existingIds) {
                error = "That rule ID exists. Edit it from the rule list."
            } else {
                save(
                    ClassificationRule(
                        existing?.id ?: id.trim(),
                        parsedPriority,
                        result,
                        matchKind,
                        matchAsset.takeIf { it.isNotBlank() }?.let(::AssetId),
                        matchSource.takeIf { it.isNotBlank() }?.let(::SourceRecordId),
                        enabled,
                    ),
                )
            }
        }) { Text("Save rule") }
    }
}

@Composable
internal fun ActivityDialog(
    item: EffectiveActivity,
    data: PortfolioSnapshot,
    evidence: @Composable () -> Unit,
    close: () -> Unit,
    edit: (() -> Unit)? = null,
    reverse: (() -> Unit)? = null,
    save: (ActivityOverlay) -> Unit,
) {
    var label by remember { mutableStateOf(item.label.orEmpty()) }
    var note by remember { mutableStateOf(item.note.orEmpty()) }
    var kind by remember { mutableStateOf(item.activity.kind) }
    val corrections = remember(item.activity.id) { mutableStateMapOf<EventId, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReverse by remember { mutableStateOf(false) }
    FormDialog("Activity details", close) {
        if (item.original.manual && item.activity.status == ActivityStatus.CONFIRMED) {
            edit?.let { action ->
                TextButton(onClick = action, modifier = Modifier.testTag("edit-manual-transaction")) { Text("Edit manual transaction") }
            }
            reverse?.let { action ->
                if (confirmReverse) {
                    Text("Reverse this entry? Its movements stop affecting balances; its history is retained.")
                    TextButton(onClick = action) { Text("Confirm reversal") }
                    TextButton(onClick = { confirmReverse = false }) { Text("Keep entry") }
                } else {
                    TextButton(onClick = { confirmReverse = true }) { Text("Reverse manual transaction") }
                }
            }
        }
        Text(item.activity.id.value, fontSize = 11.sp)
        Text("${item.activity.status.name} · ${item.activity.kind.name}", fontWeight = FontWeight.SemiBold)
        Text(
            "Classification: ${item.classificationOrigin.name.lowercase()}${item.ruleId?.let { " · rule $it" }.orEmpty()}",
            fontSize = 12.sp,
        )
        item.activity.movements.forEach { movement ->
            Text("${movement.amount.atomicUnits} atomic ${movement.amount.assetId.value} · ${movement.role.name}", fontSize = 12.sp)
            Text(
                data.ownership.accounts
                    .find { it.id == movement.accountId }
                    ?.label ?: movement.accountId.value,
                fontSize = 12.sp,
            )
        }
        HorizontalDivider()
        Text("Source evidence", fontWeight = FontWeight.SemiBold)
        Text(
            if (item.original.manual) {
                "Manual entry"
            } else {
                "Decoder ${item.original.decoderVersion}\n${item.original.sources.joinToString(
                    "\n",
                ) { it.value }}"
            },
            fontSize = 11.sp,
        )
        Text(
            "Original quantities: ${item.original.movements.joinToString { it.amount.atomicUnits.toString() }} atomic units",
            fontSize = 11.sp,
        )
        evidence()
        if (item.unresolvedMovementIds.isNotEmpty()) {
            Text(
                "Correction conflict: some original movement IDs could not be matched.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Field("Label", label) { label = it }
        Field("Private note", note) { note = it }
        Choice("Classification correction", ActivityKind.entries.map { it to it.name.lowercase() }, kind) { kind = it }
        item.activity.movements.forEachIndexed { index, movement ->
            val metadata = data.valuations.find { it.asset == movement.amount.assetId }?.metadata
            val unit = if (metadata == null) "atomic units" else "whole ${metadata.symbol}"
            Field("Correct quantity ${index + 1} ($unit; empty = keep)", corrections[movement.id].orEmpty()) {
                corrections[movement.id] = it
            }
            item.original.movements.find { it.id == movement.id }?.let { original ->
                TextButton(onClick = {
                    corrections[movement.id] = metadata?.let { original.amount.decimal(it.decimals).toString() }
                        ?: original.amount.atomicUnits.toString()
                }) { Text("Reset quantity ${index + 1} to source") }
            }
        }
        Text("Corrections are saved separately. Original source amounts and provenance remain available.", fontSize = 12.sp)
        Button(onClick = {
            val amounts = mutableMapOf<EventId, AtomicAmount>()
            for (movement in item.activity.movements) {
                val input = corrections[movement.id].orEmpty()
                val original = item.original.movements.find { it.id == movement.id }
                val amount =
                    if (input.isBlank()) {
                        movement.amount
                    } else {
                        runCatching {
                            val decimals =
                                data.valuations
                                    .find { it.asset == movement.amount.assetId }
                                    ?.metadata
                                    ?.decimals
                            if (decimals == null) {
                                AtomicAmount(movement.amount.assetId, ExactInteger.parse(input))
                            } else {
                                AtomicAmount.fromDecimal(movement.amount.assetId, ExactDecimal.parse(input), decimals)
                            }
                        }.getOrNull()
                    }
                if (amount == null) {
                    error = "Enter an exact quantity in the units shown for each movement."
                    return@Button
                }
                if (original?.amount != amount) amounts[movement.id] = amount
            }
            save(ActivityOverlay(item.activity.id, label.trim().ifEmpty { null }, note.trim().ifEmpty { null }, kind, amounts))
        }, modifier = Modifier.fillMaxWidth()) { Text("Save correction") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun LockScreen(host: PlatformActions) {
    var forgot by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("polion", fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            Text("Welcome back", style = MaterialTheme.typography.headlineMedium)
            Text("Unlock your local portfolio.")
            SecretField("Privacy lock password", password) {
                password = it
                error = null
            }
            Button(onClick = {
                busy = true
                host.unlock(password) { result ->
                    busy = false
                    error = if (result.success) null else result.message ?: "Incorrect password. Your data has not been changed."
                    if (result.success) password = ""
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Unlocking…" else "Unlock") }
            if (remember { host.biometricUnlock() } == BiometricUnlock.ON) {
                OutlinedButton(onClick = {
                    busy = true
                    error = null
                    host.unlockWithBiometrics { result ->
                        busy = false
                        if (!result.success) error = result.message
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("lock-biometric")) { Text("Unlock with fingerprint") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { forgot = true }, enabled = !busy, modifier = Modifier.testTag("lock-forgot")) {
                Text("Forgot password?")
            }
        }
    }
    if (forgot) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) forgot = false },
            title = { Text("Erase this device's Polion data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "The password cannot be recovered. You can erase this device's Polion data and remove the lock, " +
                            "then restore an encrypted backup if you have one. Funds on the blockchain are unaffected.",
                    )
                    OutlinedTextField(typed, { typed = it }, label = { Text("Type ERASE to confirm") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typed == "ERASE" && !busy,
                    onClick = {
                        busy = true
                        host.eraseAndRemoveLock { message ->
                            busy = false
                            forgot = false
                            error = message.takeUnless { it.startsWith("Local data erased") }
                        }
                    },
                    modifier = Modifier.testTag("lock-erase-confirm"),
                ) { Text("Erase and remove lock") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { forgot = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FormDialog(
    title: String,
    close: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = close) {
        Surface(
            shape =
                androidx.compose.foundation.shape
                    .RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 760.dp),
        ) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = close) { Text("Close") }
                }
                content()
            }
        }
    }
}

@Composable private fun Field(
    label: String,
    value: String,
    change: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        change,
        label = {
            Text(label, fontSize = 12.sp)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(12.dp),
    )
}

@Composable private fun SecretField(
    label: String,
    value: String,
    change: (String) -> Unit,
) {
    OutlinedTextField(value, change, label = {
        Text(label, fontSize = 12.sp)
    }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
}

@Composable internal fun <T> Choice(
    label: String,
    choices: List<Pair<T, String>>,
    selected: T,
    change: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 11.sp)
        Box {
            OutlinedButton(onClick = {
                expanded =
                    true
            }, modifier = Modifier.fillMaxWidth()) {
                Text(choices.find { it.first == selected }?.second ?: "Select")
                Text(" ▾")
            }
            ; DropdownMenu(expanded, {
                expanded =
                    false
            }) {
                choices.forEach { (id, name) ->
                    DropdownMenuItem({ Text(name) }, {
                        change(id)
                        expanded = false
                    })
                }
            }
        }
    }
}

private fun uniqueId(prefix: String) = "$prefix-${Clock.System.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt(1_000_000)}"
