package io.polion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.evm.EVM_FAMILY
import io.polion.core.evm.EvmChainState
import io.polion.core.evm.eip55
import io.polion.core.evm.evmAddress
import io.polion.core.market.EvmPlatform
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
import kotlin.time.Instant

/** Chains preselected when the catalog lists them: Ethereum, Base, Arbitrum One, OP Mainnet, Polygon and BNB Smart Chain. */
internal val DEFAULT_EVM_CHAIN_IDS: List<Long> = listOf(1L, 8453L, 42161L, 10L, 137L, 56L)

/** Explains the Blockscout key in Settings and in the connect flow. */
internal const val BLOCKSCOUT_KEY_HELP =
    "Free key from dev.blockscout.com. Polion uses it to read the history of EVM addresses (Ethereum, Base, Arbitrum and other chains). Stays on this device."

private const val EVM_REPLY_WAIT_MILLIS = 3_000L

/** The reason an EVM address cannot be tracked, or null when it is valid or still empty. */
internal fun evmAddressProblem(input: String): String? {
    if (input.isBlank()) return null
    return runCatching { evmAddress(input) }.exceptionOrNull()?.let { it.message ?: "Enter a valid EVM address." }
}

internal fun evmChainName(
    choices: List<EvmPlatform>,
    chainId: Long,
): String = choices.firstOrNull { it.chainId == chainId }?.name ?: "Chain $chainId"

/** The EIP-55 address shortened for lists, for example `0xd8dA6B…6045`. */
internal fun shortEvmAddress(address: String): String {
    val display = eip55(address)
    return display.take(8) + "…" + display.takeLast(4)
}

/** Sync time in UTC, to the minute. */
internal fun evmSyncTime(millis: Long): String =
    Instant
        .fromEpochMilliseconds(millis)
        .toString()
        .take(16)
        .replace('T', ' ') + " UTC"

/**
 * Stores or removes the Blockscout key and returns the core's message when it is rejected, otherwise null. Call on the
 * host dispatcher.
 */
internal fun saveBlockscoutKey(
    app: PortfolioApplication,
    key: String?,
): String? =
    try {
        app.setBlockscoutApiKey(key)
        null
    } catch (rejected: IllegalArgumentException) {
        rejected.message ?: "That does not look like a Blockscout API key."
    }

/** Chain list for the pickers. [choices] is null until the cached catalog has been read. */
internal data class EvmCatalog(
    val choices: List<EvmPlatform>?,
    val loading: Boolean,
    /** Why the list is missing: market data is off, or CoinGecko could not be reached. Null when the list is loaded. */
    val notice: String?,
)

/**
 * Reads the cached chain catalog. When it is missing and [fetchIfMissing] is set, asks the host to load it, which needs
 * market data consent.
 */
@Composable
internal fun rememberEvmCatalog(
    app: PortfolioApplication,
    host: PlatformActions,
    fetchIfMissing: Boolean = true,
): EvmCatalog {
    val scope = rememberCoroutineScope()
    var choices by remember(app) { mutableStateOf<List<EvmPlatform>?>(null) }
    var loading by remember(app) { mutableStateOf(false) }
    var notice by remember(app) { mutableStateOf<String?>(null) }
    LaunchedEffect(app) {
        val cached = withContext(host.dispatcher) { app.evmChainChoices() }
        choices = cached
        if (cached.isEmpty() && fetchIfMissing) {
            if (withContext(host.dispatcher) { app.marketDataConsent() }) {
                loading = true
                host.refreshEvmCatalog { message ->
                    loading = false
                    notice = message
                    scope.launch { choices = withContext(host.dispatcher) { app.evmChainChoices() } }
                }
            } else {
                notice = "Turn on market data on Main to list chains."
            }
        }
    }
    return EvmCatalog(choices, loading || choices == null, notice)
}

/** Blockscout key status and entry, shared by Settings and the connect flow. [save] returns the rejection message or null. */
@Composable
internal fun BlockscoutKeyDialog(
    hasKey: Boolean,
    save: suspend (String?) -> String?,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit(value: String?) {
        busy = true
        scope.launch {
            val problem = save(value)
            busy = false
            if (problem == null) close() else error = problem
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text("Blockscout API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(BLOCKSCOUT_KEY_HELP)
                OutlinedTextField(
                    key,
                    {
                        key = it
                        error = null
                    },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    label = { Text("Blockscout API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("blockscout-key-field"),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("blockscout-key-error")) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.isNotBlank() && !busy,
                onClick = { submit(key.trim()) },
                modifier = Modifier.testTag("blockscout-key-save"),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (hasKey) {
                    TextButton(
                        enabled = !busy,
                        onClick = { submit(null) },
                        modifier = Modifier.testTag("blockscout-key-remove"),
                    ) { Text("Remove") }
                }
                TextButton(enabled = !busy, onClick = close) { Text("Cancel") }
            }
        },
    )
}

/** First step of Connect Wallet: the kind of source to track. */
@Composable
internal fun WalletKindDialog(
    onBitcoin: () -> Unit,
    onEvm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What do you want to track?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBitcoin, modifier = Modifier.fillMaxWidth().testTag("connect-kind-bitcoin")) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Bitcoin", style = MaterialTheme.typography.titleSmall)
                        Text("Public address, descriptor or xpub", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = onEvm, modifier = Modifier.fillMaxWidth().testTag("connect-kind-evm")) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("EVM address (Ethereum and other chains)", style = MaterialTheme.typography.titleSmall)
                        Text("One public address on Ethereum, Base, Arbitrum and other chains", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Searchable multi-select of EVM chains. [locked] chains are already tracked for the address and cannot be unticked.
 * A chain number the catalog does not list can be added by hand, so the picker works without the catalog.
 */
@Composable
internal fun EvmChainPicker(
    catalog: EvmCatalog,
    selected: Set<Long>,
    locked: Set<Long>,
    enabled: Boolean,
    onToggle: (Long) -> Unit,
) {
    val choices = catalog.choices.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var number by rememberSaveable { mutableStateOf("") }
    var numberError by remember { mutableStateOf<String?>(null) }
    Text("Chains", style = MaterialTheme.typography.titleSmall)
    if (catalog.loading) Text("Loading chains…", modifier = Modifier.testTag("evm-chains-loading"))
    catalog.notice?.let { Text(it, modifier = Modifier.testTag("evm-chains-notice")) }
    if (choices.isNotEmpty()) {
        OutlinedTextField(
            query,
            { query = it },
            singleLine = true,
            enabled = enabled,
            label = { Text("Search chains") },
            modifier = Modifier.fillMaxWidth().testTag("evm-chain-search"),
        )
    }
    val needle = query.trim()
    val listed =
        choices
            .filter { it.name.contains(needle, ignoreCase = true) || it.chainId.toString().startsWith(needle) }
            .map { it.chainId to it.name }
    // Chains chosen by number that the catalog does not list.
    val typed = selected.filter { id -> choices.none { it.chainId == id } }.sorted().map { it to "Chain $it" }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        (listed + typed).forEach { (id, name) ->
            val checked = id in selected
            val locking = id in locked
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = checked, enabled = enabled && !locking, role = Role.Checkbox) { onToggle(id) }
                    .padding(vertical = 6.dp)
                    .testTag("evm-chain-$id"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled && !locking)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(name)
                    Text("Chain ID $id", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (locking) Text("Tracked", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (choices.isNotEmpty() && listed.isEmpty()) Text("No chain matches \"$needle\"")
    if (enabled) {
        OutlinedTextField(
            number,
            {
                number = it
                numberError = null
            },
            singleLine = true,
            label = { Text("Chain number") },
            isError = numberError != null,
            supportingText = { numberError?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth().testTag("evm-chain-number"),
        )
        OutlinedButton(
            enabled = number.isNotBlank(),
            onClick = {
                val id = number.trim().toLongOrNull()?.takeIf { it > 0 }
                if (id == null) {
                    numberError = "Enter a chain number, like 8453 for Base."
                } else {
                    if (id !in selected) onToggle(id)
                    number = ""
                }
            },
            modifier = Modifier.testTag("evm-chain-add-number"),
        ) { Text("Add chain number") }
    }
}

/**
 * Track an EVM address: account name, address, Blockscout key, chains and consent. Saves the account and its chains,
 * then refreshes history and closes when the refresh succeeds.
 */
@Composable
internal fun EvmConnectDialog(
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
    val catalog = rememberEvmCatalog(app, host)
    var label by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var portfolio by rememberSaveable { mutableStateOf(initialPortfolio?.value.orEmpty()) }
    var owner by rememberSaveable { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(DEFAULT_EVM_CHAIN_IDS.toSet()) }
    var chainsTouched by remember { mutableStateOf(false) }
    var keyPresent by remember { mutableStateOf<Boolean?>(null) }
    var keyDialog by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The saved address. It is set once the account exists, so a retry after a failed chain save does not add a second account.
    var tracked by remember { mutableStateOf<String?>(null) }
    var requested by remember { mutableStateOf(false) }
    var baseline by remember { mutableStateOf<SyncStatus?>(null) }
    var hostReply by remember { mutableStateOf<String?>(null) }
    var replyUnanswered by remember { mutableStateOf(false) }
    val selectedPortfolio = data.ownership.portfolios.find { it.id.value == portfolio }
    val owners =
        data.ownership.owners.filter {
            selectedPortfolio == null || selectedPortfolio.ownerIds.isEmpty() || it.id in selectedPortfolio.ownerIds
        }
    val ownerChoice = owners.firstOrNull { it.id.value == owner }?.id ?: owners.firstOrNull()?.id
    val canonical = runCatching { evmAddress(address) }.getOrNull()
    val addressProblem = evmAddressProblem(address)
    // Only a refresh started after the save counts as progress; an earlier status with the same text does not.
    val refresh = syncStatus.takeIf { requested && it != baseline && it.operation == SyncOperation.HISTORY }
    val failed = requested && (refresh?.phase == SyncPhase.ERROR || (refresh == null && replyUnanswered))
    val blocker =
        when {
            label.isBlank() -> "Enter an account name."
            canonical == null -> "Enter a valid EVM address."
            keyPresent != true -> "Add a Blockscout key first. Blockscout reads the address history."
            selected.isEmpty() -> "Choose at least one chain."
            owners.isNotEmpty() && ownerChoice == null -> "No owner in this portfolio can hold the address. Choose another portfolio."
            !consent -> "Tick the consent box to let Blockscout read this address."
            else -> null
        }

    LaunchedEffect(Unit) {
        val (hasKey, consented) = withContext(host.dispatcher) { (app.blockscoutApiKey() != null) to app.evmConsent() }
        keyPresent = hasKey
        consent = consented
    }
    // Once the catalog loads, only default chains it lists stay ticked. Until then the defaults are ticked by number.
    LaunchedEffect(catalog.choices) {
        val choices = catalog.choices
        if (!chainsTouched && !choices.isNullOrEmpty()) {
            selected = DEFAULT_EVM_CHAIN_IDS.filter { id -> choices.any { it.chainId == id } }.toSet()
        }
    }
    LaunchedEffect(hostReply, refresh == null) {
        replyUnanswered = false
        if (requested && hostReply != null && refresh == null) {
            delay(EVM_REPLY_WAIT_MILLIS)
            replyUnanswered = true
        }
    }
    LaunchedEffect(refresh) {
        if (refresh?.phase == SyncPhase.SUCCESS) {
            notify(refresh.message)
            close()
        }
    }

    fun requestRefresh() {
        baseline = syncStatus
        hostReply = null
        requested = true
        host.refresh { hostReply = it }
    }

    fun submit(valid: String) {
        saving = true
        error = null
        val suffix =
            kotlin.random.Random
                .nextLong()
                .toString()
                .removePrefix("-")
        val accountId = AccountId("evm-account-$suffix")
        val ownerId = ownerChoice ?: OwnerId("owner-$suffix")
        val title = label.trim()
        scope.launch {
            try {
                val address =
                    tracked ?: withContext(host.dispatcher) {
                        if (data.ownership.owners.isEmpty()) app.saveOwner(Owner(ownerId, "Personal"))
                        app.saveAccountAndTarget(
                            Account(accountId, ownerId, title, AccountKind.WALLET),
                            TrackingTarget(
                                TrackingTargetId("evm-$suffix"),
                                accountId,
                                TargetIdentity(EVM_FAMILY, "address", valid),
                                TargetRole.LEAF,
                                title,
                            ),
                        )
                        selectedPortfolio
                            ?.takeIf { it.accountIds.isNotEmpty() }
                            ?.let { app.savePortfolio(it.copy(accountIds = it.accountIds + accountId)) }
                        valid
                    }
                tracked = address
                withContext(host.dispatcher) {
                    app.setEvmConsent(true)
                    app.addEvmChains(address, selected)
                }
                onSaved()
                requestRefresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: IllegalArgumentException) {
                error = rejected.message ?: "Could not track this address. Check the input and retry."
            } catch (_: Exception) {
                error = "Could not save this address. Check available storage, then retry."
            } finally {
                saving = false
            }
        }
    }

    AccountFlowDialog("Connect EVM address", saving, close) {
        val editable = tracked == null && !saving
        Text("Watch-only · Ethereum and other EVM chains")
        OutlinedTextField(
            label,
            { label = it },
            enabled = editable,
            singleLine = true,
            label = { Text("Account name") },
            modifier = Modifier.fillMaxWidth().testTag("evm-connect-name"),
        )
        OutlinedTextField(
            address,
            { address = it },
            enabled = editable,
            singleLine = true,
            isError = addressProblem != null,
            label = { Text("EVM address") },
            supportingText = {
                addressProblem?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("evm-connect-address-error"),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("evm-connect-address"),
        )
        canonical?.let { Text("Address: ${eip55(it)}", style = MaterialTheme.typography.bodySmall) }
        Text("Public address only. Never enter a private key or recovery phrase.")
        when (keyPresent) {
            true -> Text("Blockscout key: added on this device.", modifier = Modifier.testTag("evm-key-status"))
            false -> {
                Text(
                    "Blockscout reads address history and needs a free key. Add one here, or later under Settings, then API Keys.",
                    modifier = Modifier.testTag("evm-key-status"),
                )
                OutlinedButton(enabled = editable, onClick = { keyDialog = true }, modifier = Modifier.testTag("evm-key-add")) {
                    Text("Add Blockscout key")
                }
            }
            null -> Unit
        }
        EvmChainPicker(catalog, selected, locked = emptySet(), enabled = editable) { id ->
            chainsTouched = true
            selected = if (id in selected) selected - id else selected + id
        }
        TransactionChoice(
            "Portfolio",
            listOf("" to "All portfolios") + data.ownership.portfolios.map { it.id.value to it.label },
            portfolio,
        ) {
            portfolio = it
            owner = ""
        }
        if (owners.isNotEmpty()) {
            TransactionChoice("Owner", owners.map { it.id.value to it.label }, ownerChoice?.value.orEmpty()) { owner = it }
        }
        Row(
            Modifier.toggleable(value = consent, enabled = editable, role = Role.Checkbox) { consent = it }.testTag("evm-connect-consent"),
        ) {
            Checkbox(checked = consent, onCheckedChange = null, enabled = editable)
            Spacer(Modifier.width(4.dp))
            Text("Allow Blockscout to receive this address and your IP address to read its history.", Modifier.weight(1f))
        }
        if (!requested) {
            blocker?.let { Text(it, modifier = Modifier.testTag("evm-connect-blocker")) }
        } else {
            Text(
                refresh?.message ?: hostReply ?: "Tracked. Loading history…",
                color = if (refresh?.phase == SyncPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("evm-connect-status"),
            )
        }
        Button(
            enabled = if (!requested) !saving && blocker == null else failed,
            modifier = Modifier.fillMaxWidth().testTag("evm-connect-confirm"),
            onClick = {
                if (requested) requestRefresh() else canonical?.let { submit(it) }
            },
        ) {
            Text(
                when {
                    saving -> "Saving…"
                    !requested -> "Track address"
                    failed -> "Retry refresh"
                    else -> "Refreshing…"
                },
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("evm-connect-error")) }
    }
    if (keyDialog) {
        BlockscoutKeyDialog(
            hasKey = keyPresent == true,
            save = { key ->
                val problem = withContext(host.dispatcher) { saveBlockscoutKey(app, key) }
                if (problem == null) keyPresent = key != null
                problem
            },
            close = { keyDialog = false },
        )
    }
}

/**
 * The tracked address on an account: its shortened EIP-55 form, one status line per chain and an action to add chains.
 */
@Composable
internal fun EvmAddressDetails(
    app: PortfolioApplication,
    host: PlatformActions,
    address: String,
    revision: Long,
    onChanged: () -> Unit,
) {
    val catalog = rememberEvmCatalog(app, host, fetchIfMissing = false)
    var states by remember(address) { mutableStateOf<List<EvmChainState>>(emptyList()) }
    var expanded by remember(address) { mutableStateOf(emptySet<Long>()) }
    var adding by remember(address) { mutableStateOf(false) }
    LaunchedEffect(address, revision) {
        states = withContext(host.dispatcher) { app.evmChainStates(address) }.sortedBy { it.chainId }
    }
    Column(Modifier.fillMaxWidth().testTag("evm-address-details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("EVM address ${shortEvmAddress(address)}", fontWeight = FontWeight.SemiBold)
        if (states.isEmpty()) Text("No chains yet. Add chains to load this address's history.")
        states.forEach { state ->
            val open = state.chainId in expanded
            Column(Modifier.fillMaxWidth().testTag("evm-chain-state-${state.chainId}")) {
                Text(evmChainName(catalog.choices.orEmpty(), state.chainId), fontWeight = FontWeight.SemiBold)
                Text(
                    "Last sync: ${state.lastSuccessMillis?.let { evmSyncTime(it) } ?: "not yet"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val error = state.error
                val status =
                    when {
                        error != null -> error
                        state.balanceDifference != null -> "Balance differs from Blockscout"
                        state.lastSuccessMillis == null -> "Not synced yet"
                        else -> "Balances match Blockscout"
                    }
                Text(
                    status,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (state.balanceDifference != null) {
                    TextButton(
                        onClick = {
                            expanded = if (open) expanded - state.chainId else expanded + state.chainId
                        },
                        modifier = Modifier.testTag("evm-difference-${state.chainId}"),
                    ) { Text(if (open) "Hide difference" else "Show difference") }
                    if (open) Text(state.balanceDifference.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton(onClick = { adding = true }, modifier = Modifier.testTag("evm-add-chains")) { Text("Add chains") }
    }
    if (adding) {
        AddEvmChainsDialog(
            app = app,
            host = host,
            catalog = catalog,
            address = address,
            tracked = states.map { it.chainId }.toSet(),
            close = { adding = false },
            onSaved = {
                adding = false
                onChanged()
            },
        )
    }
}

/** Adds chains to an address already tracked. Chains it has stay ticked and locked. */
@Composable
private fun AddEvmChainsDialog(
    app: PortfolioApplication,
    host: PlatformActions,
    catalog: EvmCatalog,
    address: String,
    tracked: Set<Long>,
    close: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selected by remember(address) { mutableStateOf(tracked) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val added = selected - tracked
    AccountFlowDialog("Add chains", busy, close) {
        Text("Address ${shortEvmAddress(address)}. Chains already tracked stay as they are.")
        EvmChainPicker(catalog, selected, locked = tracked, enabled = !busy) { id ->
            selected = if (id in selected) selected - id else selected + id
        }
        Text("History for new chains loads on the next refresh.", style = MaterialTheme.typography.bodySmall)
        Button(
            enabled = added.isNotEmpty() && !busy,
            modifier = Modifier.fillMaxWidth().testTag("evm-add-chains-confirm"),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        withContext(host.dispatcher) { app.addEvmChains(address, added) }
                        onSaved()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "Could not add these chains. Check available storage, then retry."
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("Add chains") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("evm-add-chains-error")) }
    }
}
