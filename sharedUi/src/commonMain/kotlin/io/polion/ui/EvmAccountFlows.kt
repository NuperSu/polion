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

/** Chains preselected in the connect flow: Ethereum, BNB Smart Chain, Base, Polygon, Arbitrum One and OP Mainnet. */
internal val DEFAULT_EVM_CHAIN_IDS: List<Long> = listOf(1L, 56L, 8453L, 137L, 42161L, 10L)

/** Explains the Alchemy key in Settings and in the connect flow. */
internal const val ALCHEMY_KEY_HELP =
    "Free key from alchemy.com (create an app, then enable the networks you use, such as BNB Smart Chain, Base and Polygon). " +
        "Polion reads the history of EVM addresses with it. Stays on this device."

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
 * Stores or removes the Alchemy key and returns the core's message when it is rejected, otherwise null. Call on the
 * host dispatcher.
 */
internal fun saveAlchemyKey(
    app: PortfolioApplication,
    key: String?,
): String? =
    try {
        app.setAlchemyApiKey(key)
        null
    } catch (rejected: IllegalArgumentException) {
        rejected.message ?: "That does not look like an Alchemy API key."
    }

/** The chains Alchemy serves, read on the host dispatcher. Names come from CoinGecko when its catalog is cached. */
@Composable
internal fun rememberEvmChainChoices(
    app: PortfolioApplication,
    host: PlatformActions,
): List<EvmPlatform> {
    var choices by remember(app) { mutableStateOf<List<EvmPlatform>>(emptyList()) }
    LaunchedEffect(app) { choices = withContext(host.dispatcher) { app.evmChainChoices() } }
    return choices
}

/** Alchemy key status and entry, shared by Settings and the connect flow. [save] returns the rejection message or null. */
@Composable
internal fun AlchemyKeyDialog(
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
        title = { Text("Alchemy API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ALCHEMY_KEY_HELP)
                OutlinedTextField(
                    key,
                    {
                        key = it
                        error = null
                    },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    label = { Text("Alchemy API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("alchemy-key-field"),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("alchemy-key-error")) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.isNotBlank() && !busy,
                onClick = { submit(key.trim()) },
                modifier = Modifier.testTag("alchemy-key-save"),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (hasKey) {
                    TextButton(
                        enabled = !busy,
                        onClick = { submit(null) },
                        modifier = Modifier.testTag("alchemy-key-remove"),
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
 * Searchable multi-select of the chains Alchemy serves. [locked] chains are already tracked for the address and cannot be
 * unticked.
 */
@Composable
internal fun EvmChainPicker(
    choices: List<EvmPlatform>,
    selected: Set<Long>,
    locked: Set<Long>,
    enabled: Boolean,
    onToggle: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Text("Chains", style = MaterialTheme.typography.titleSmall)
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
        choices.filter { it.name.contains(needle, ignoreCase = true) || it.chainId.toString().startsWith(needle) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listed.forEach { choice ->
            val id = choice.chainId
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
                    Text(choice.name)
                    Text("Chain ID $id", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (locking) Text("Tracked", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (choices.isNotEmpty() && listed.isEmpty()) Text("No chain matches \"$needle\"")
}

/**
 * Track an EVM address: account name, address, Alchemy key, chains and consent. Saves the account and its chains,
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
    val choices = rememberEvmChainChoices(app, host)
    var label by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var portfolio by rememberSaveable { mutableStateOf(initialPortfolio?.value.orEmpty()) }
    var owner by rememberSaveable { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(DEFAULT_EVM_CHAIN_IDS.toSet()) }
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
            keyPresent != true -> "Add an Alchemy key first. Alchemy reads the address history."
            selected.isEmpty() -> "Choose at least one chain."
            owners.isNotEmpty() && ownerChoice == null -> "No owner in this portfolio can hold the address. Choose another portfolio."
            !consent -> "Tick the consent box to let Alchemy read this address."
            else -> null
        }

    LaunchedEffect(Unit) {
        val (hasKey, consented) = withContext(host.dispatcher) { (app.alchemyApiKey() != null) to app.evmConsent() }
        keyPresent = hasKey
        consent = consented
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
            true -> Text("Alchemy key: added on this device.", modifier = Modifier.testTag("evm-key-status"))
            false -> {
                Text(
                    "Alchemy reads address history and needs a free key. Add one here, or later under Settings, then API Keys.",
                    modifier = Modifier.testTag("evm-key-status"),
                )
                OutlinedButton(enabled = editable, onClick = { keyDialog = true }, modifier = Modifier.testTag("evm-key-add")) {
                    Text("Add Alchemy key")
                }
            }
            null -> Unit
        }
        EvmChainPicker(choices, selected, locked = emptySet(), enabled = editable) { id ->
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
            Text("Allow Alchemy to receive this address and your IP address to read its history.", Modifier.weight(1f))
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
        AlchemyKeyDialog(
            hasKey = keyPresent == true,
            save = { key ->
                val problem = withContext(host.dispatcher) { saveAlchemyKey(app, key) }
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
    val choices = rememberEvmChainChoices(app, host)
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
                Text(evmChainName(choices, state.chainId), fontWeight = FontWeight.SemiBold)
                Text(
                    "Last sync: ${state.lastSuccessMillis?.let { evmSyncTime(it) } ?: "not yet"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val error = state.error
                val status =
                    when {
                        error != null -> error
                        state.balanceDifference != null -> "Balance differs from Alchemy"
                        state.lastSuccessMillis == null -> "Not synced yet"
                        else -> "Balances match Alchemy"
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
                state.coverage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("evm-coverage-${state.chainId}"),
                    )
                }
            }
        }
        TextButton(onClick = { adding = true }, modifier = Modifier.testTag("evm-add-chains")) { Text("Add chains") }
    }
    if (adding) {
        AddEvmChainsDialog(
            app = app,
            host = host,
            choices = choices,
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
    choices: List<EvmPlatform>,
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
        EvmChainPicker(choices, selected, locked = tracked, enabled = !busy) { id ->
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
