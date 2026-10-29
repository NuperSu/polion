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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.polion.core.application.AssetValuation
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

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
) {
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf(if (type == "sync") data.sourceEndpoint else "") }
    var amount by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var owner by remember {
        mutableStateOf(
            data.ownership.owners
                .firstOrNull()
                ?.id,
        )
    }
    var account by remember {
        mutableStateOf(
            data.ownership.accounts
                .firstOrNull { it.kind != AccountKind.GROUP && "polion:archived" !in it.tags }
                ?.id,
        )
    }
    var parent by remember { mutableStateOf<AccountId?>(null) }
    var selectedKind by remember { mutableStateOf(ActivityKind.DEPOSIT) }
    var matchKind by remember { mutableStateOf(ActivityKind.DEPOSIT) }
    var asset by remember { mutableStateOf(AssetId("bitcoin:btc")) }
    var consent by rememberSaveable { mutableStateOf(data.sourceConsent) }
    var priceConsent by rememberSaveable { mutableStateOf(false) }
    var priceApiKey by remember { mutableStateOf("") }
    var historyDate by rememberSaveable { mutableStateOf("") }
    var fetchingPrice by remember { mutableStateOf(false) }
    var priceMessage by remember { mutableStateOf<String?>(null) }
    var background by remember { mutableStateOf(host.backgroundRefreshEnabled()) }
    var candidates by remember { mutableStateOf<List<ReconciliationCandidate>>(emptyList()) }
    var selectedAccounts by remember { mutableStateOf(emptySet<AccountId>()) }
    var acceptedBackup by rememberSaveable { mutableStateOf(false) }
    var acceptedRecovery by rememberSaveable { mutableStateOf(false) }
    val syncStatus by host.syncStatus.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(type) {
        if (type == "price") priceConsent = withContext(host.dispatcher) { app.priceSourceConsent() }
        if (type == "reconcile") candidates = withContext(host.dispatcher) { app.reconciliationCandidates(86_400_000) }
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
            "rule" -> "Classification rule"
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
                    Text("Create an account first.")
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
                    Choice("Classification", ActivityKind.entries.map { it to it.name.lowercase() }, selectedKind) { selectedKind = it }
                    Field("Bitcoin quantity (e.g. 0.025 or -0.01)", amount) { amount = it }
                    Field("Label (optional)", name) { name = it }
                    Button(onClick = {
                        val units =
                            runCatching {
                                AtomicAmount.fromDecimal(
                                    AssetId("bitcoin:btc"),
                                    ExactDecimal.parse(amount),
                                    8,
                                )
                            }.getOrNull()
                        if (units == null ||
                            units.atomicUnits.sign == 0 ||
                            account == null
                        ) {
                            error = "Enter a nonzero Bitcoin quantity with up to 8 decimal places."
                            return@Button
                        }
                        execute("Manual activity recorded") {
                            val id = ActivityId(uniqueId("manual"))
                            app.saveManualActivity(
                                Activity(
                                    id,
                                    Clock.System.now().toEpochMilliseconds(),
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
                        data.valuations.map { it.asset to (it.metadata?.name ?: it.asset.value) } +
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
                Text("Daily historical prices · Bitcoin", fontWeight = FontWeight.SemiBold)
                Text(
                    "CoinGecko receives the requested coin, currency and date, plus your IP address. Wallet addresses are not sent. Daily quotes are not live prices or acquisition costs. Saved prices remain available offline. Demo access covers the past 365 days; the latest completed day may be unavailable until 00:35 UTC.",
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
                SecretField("Demo API key (session only, optional)", priceApiKey) { priceApiKey = it }
                Field("Historical UTC date (YYYY-MM-DD)", historyDate) { historyDate = it }
                Button(
                    onClick = {
                        val day =
                            runCatching {
                                kotlin.time.Instant
                                    .parse("${historyDate}T00:00:00Z")
                                    .toEpochMilliseconds()
                            }.getOrNull()
                        if (day == null ||
                            day < 0 ||
                            day > Clock.System.now().toEpochMilliseconds() ||
                            day < Clock.System.now().toEpochMilliseconds() - 365L * 86_400_000L
                        ) {
                            error = "Choose a UTC date within the past 365 days."
                        } else {
                            fetchingPrice = true
                            scope.launch {
                                withContext(host.dispatcher) { app.setPriceSourceConsent(priceConsent) }
                                host.fetchHistoricalPrice(AssetId("bitcoin:btc"), data.currency, day, priceApiKey) { message ->
                                    fetchingPrice = false
                                    priceMessage = message
                                    notify(message)
                                }
                            }
                        }
                    },
                    enabled = priceConsent && !fetchingPrice,
                    modifier = Modifier.fillMaxWidth().testTag("fetch-historical-price"),
                ) { Text(if (fetchingPrice) "Fetching daily quote…" else "Fetch Bitcoin daily price") }
                priceMessage?.let { Text(it, fontSize = 13.sp) }
            }
            "sync" -> {
                if (syncStatus.phase == SyncPhase.SYNCING) {
                    Text("Syncing…", fontWeight = FontWeight.SemiBold)
                    Text(syncStatus.message, fontSize = 12.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Your endpoint is saved. Source settings can be changed after this scan finishes.", fontSize = 12.sp)
                }
                Text(
                    "Querying an indexer shares watched public addresses and your IP address with that provider. Choose an HTTPS Esplora endpoint you trust. No cloud account is required.",
                    fontSize = 13.sp,
                )
                Field("Esplora API endpoint", address) { address = it }
                Row {
                    Checkbox(consent, { consent = it }, modifier = Modifier.testTag("bitcoin-source-consent"))
                    Text("I allow public address queries to this endpoint", Modifier.weight(1f).padding(top = 8.dp), fontSize = 13.sp)
                }
                Row {
                    Switch(background, { background = it })
                    Text(
                        "Background refresh (about every 30 minutes)",
                        Modifier.weight(1f).padding(start = 12.dp, top = 8.dp),
                        fontSize = 13.sp,
                    )
                }
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
                        runCatching {
                            withContext(host.dispatcher) {
                                app.setSourceEndpoint(address)
                                app.setSourceConsent(consent)
                                host.setBackgroundRefresh(
                                    background && consent,
                                )
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
                            host.openFile { bytes ->
                                if (bytes !=
                                    null
                                ) {
                                    host.restoreBackup(bytes, password) { notify(it) }
                                } else {
                                    notify("No backup selected")
                                }
                            }
                            close()
                        }
                        else -> {
                            execute("Backup ready") {
                                val secret = password.toCharArray()
                                try {
                                    host.saveFile("polion-backup.polion", app.exportBackup(secret)) { notify(it) }
                                } finally {
                                    secret.fill('\u0000')
                                }
                            }
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
                            host.setBackgroundRefresh(false)
                        }
                        close()
                    },
                    enabled =
                        name == "ERASE",
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Erase this device’s data") }
            }
            "rule" -> {
                Field("Rule name", name) { name = it }
                Choice(
                    "Match original classification",
                    ActivityKind.entries.map { it to it.name.lowercase() },
                    matchKind,
                ) { matchKind = it }
                Choice("Classify as", ActivityKind.entries.map { it to it.name.lowercase() }, selectedKind) { selectedKind = it }
                Text(
                    "Rules apply in priority order; activity-specific corrections take precedence. Source evidence remains unchanged.",
                    fontSize = 12.sp,
                )
                Button(onClick = {
                    if (name.isBlank()) {
                        error = "Enter a rule name."
                        return@Button
                    }
                    execute("Classification rule saved") { app.saveRule(ClassificationRule(name.trim(), 100, selectedKind, matchKind)) }
                    close()
                }) { Text("Save rule") }
            }
            "reconcile" -> {
                Text(
                    "These opposite movements may be internal transfers. Accepting a link preserves both source records; it does not suppress a duplicate or change quantities.",
                    fontSize = 13.sp,
                )
                if (candidates.isEmpty()) Text("No transfer candidates in the current history.")
                candidates.take(50).forEach { candidate ->
                    HorizontalDivider()
                    Text(candidate.first.value, fontSize = 11.sp)
                    Text("↔ ${candidate.second.value}", fontSize = 11.sp)
                    Text(
                        "Score ${candidate.score}${if (candidate.ambiguous) " · ambiguous; inspect source details" else ""}",
                        fontSize = 12.sp,
                    )
                    Row {
                        TextButton(onClick = {
                            execute("Transfer link accepted") { app.setDecision(candidate.first, candidate.second, LinkState.ACCEPTED) }
                            close()
                        }) { Text("Link") }
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
internal fun ActivityDialog(
    item: EffectiveActivity,
    data: PortfolioSnapshot,
    close: () -> Unit,
    save: (ActivityOverlay) -> Unit,
) {
    var label by remember { mutableStateOf(item.label.orEmpty()) }
    var note by remember { mutableStateOf(item.note.orEmpty()) }
    var kind by remember { mutableStateOf(item.activity.kind) }
    val corrections = remember(item.activity.id) { mutableStateMapOf<EventId, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog("Activity details", close) {
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
internal fun AssetDialog(
    asset: AssetValuation,
    data: PortfolioSnapshot,
    close: () -> Unit,
    price: () -> Unit,
) {
    FormDialog(asset.metadata?.name ?: asset.asset.value, close) {
        Text(asset.asset.value, fontSize = 12.sp)
        Text(
            "${asset.metadata?.let { "${asset.amount.decimal(it.decimals)} ${it.symbol}" } ?: "${asset.amount.atomicUnits} atomic units"}",
            fontSize = 25.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text("Price status: ${asset.priceStatus.name.lowercase()}")
        asset.price?.let { p ->
            Text("${p.price.value} ${data.currency.value} per whole coin")
            Text(
                "${if (p.manual) "Manual observation" else p.providerId?.value}\nEffective ${kotlin.time.Instant.fromEpochMilliseconds(
                    p.validAtMillis,
                )}\nObserved ${kotlin.time.Instant.fromEpochMilliseconds(p.observedAtMillis)}",
                fontSize = 12.sp,
            )
        }
        Text(
            "Confirmed quantities are reconstructed from the same effective ledger as reports. Pending movements are excluded. Missing prices remain unknown.",
            fontSize = 13.sp,
        )
        Button(onClick = price) { Text("Set a manual price") }
    }
}

@Composable
internal fun LockScreen(host: PlatformActions) {
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
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
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

@Composable private fun <T> Choice(
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
