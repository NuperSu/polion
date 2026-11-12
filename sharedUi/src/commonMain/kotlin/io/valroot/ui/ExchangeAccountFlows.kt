package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.exchange.BYBIT_EXCHANGE
import io.valroot.core.exchange.BYBIT_NETWORK
import io.valroot.core.exchange.BybitKeyInfo
import io.valroot.core.exchange.ExchangeAccountStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.OwnerId
import io.valroot.core.model.PortfolioId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val BYBIT_KEY_HELP =
    "In Bybit, open API Management and create a system-generated key with Read-Only permission. " +
        "Binding it to your IP address keeps it from expiring after 90 days. " +
        "Bybit receives requests signed with this key, and your IP address. The key and secret stay on this device and are not included in backups."

private val BYBIT_KEY_TEXT = Regex("[A-Za-z0-9]{10,128}")

/** Key and secret fields for a Bybit API key. */
@Composable
private fun BybitKeyFields(
    key: String,
    secret: String,
    enabled: Boolean,
    onKey: (String) -> Unit,
    onSecret: (String) -> Unit,
) {
    OutlinedTextField(
        key,
        onKey,
        enabled = enabled,
        singleLine = true,
        label = { Text("API key") },
        isError = key.isNotEmpty() && !key.trim().matches(BYBIT_KEY_TEXT),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = SecretKeyboard,
        modifier = Modifier.fillMaxWidth().testTag("bybit-key"),
    )
    OutlinedTextField(
        secret,
        onSecret,
        enabled = enabled,
        singleLine = true,
        label = { Text("API secret") },
        isError = secret.isNotEmpty() && !secret.trim().matches(BYBIT_KEY_TEXT),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = SecretKeyboard,
        modifier = Modifier.fillMaxWidth().testTag("bybit-secret"),
    )
}

/** Why a key cannot be used, or null when it can. */
private fun keyProblem(info: BybitKeyInfo): String? =
    if (info.readOnly) null else "This key can trade or withdraw. Create a key with Read-Only permission."

/**
 * Track a Bybit account through a read-only API key: the key is checked with Bybit first, which also names the account,
 * then the account is saved, the key stored on this device and a refresh started.
 */
@Composable
internal fun BybitConnectDialog(
    app: PortfolioApplication,
    host: PlatformActions,
    data: PortfolioSnapshot,
    initialPortfolio: PortfolioId?,
    close: () -> Unit,
    onSaved: () -> Unit,
    notify: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var label by rememberSaveable { mutableStateOf("Bybit") }
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var portfolio by rememberSaveable { mutableStateOf(initialPortfolio?.value.orEmpty()) }
    var owner by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val selectedPortfolio = data.ownership.portfolios.find { it.id.value == portfolio }
    val owners =
        data.ownership.owners.filter {
            selectedPortfolio == null || selectedPortfolio.ownerIds.isEmpty() || it.id in selectedPortfolio.ownerIds
        }
    val ownerChoice = owners.firstOrNull { it.id.value == owner }?.id ?: owners.firstOrNull()?.id
    val blocker =
        when {
            label.isBlank() -> "Enter an account name."
            !key.trim().matches(BYBIT_KEY_TEXT) || !secret.trim().matches(BYBIT_KEY_TEXT) -> "Enter the API key and its secret."
            owners.isNotEmpty() && ownerChoice == null -> "No owner in this portfolio can hold the account. Choose another portfolio."
            else -> null
        }

    fun submit() {
        saving = true
        error = null
        val suffix =
            kotlin.random.Random
                .nextLong()
                .toString()
                .removePrefix("-")
        val accountId = AccountId("bybit-account-$suffix")
        val ownerId = ownerChoice ?: OwnerId("owner-$suffix")
        val title = label.trim()
        scope.launch {
            try {
                val info = withContext(host.dispatcher) { host.bybitKeyInfo(key.trim(), secret.trim()) }
                keyProblem(info)?.let {
                    error = it
                    return@launch
                }
                val problem =
                    withContext(host.dispatcher) {
                        if (app.bybitTargets().any { it.second == info.uid }) return@withContext "This Bybit account is already tracked."
                        if (data.ownership.owners.isEmpty()) app.saveOwner(Owner(ownerId, "Personal"))
                        app.saveAccountAndTarget(
                            Account(accountId, ownerId, title, AccountKind.ACCOUNT),
                            TrackingTarget(
                                TrackingTargetId("bybit-$suffix"),
                                accountId,
                                TargetIdentity(BYBIT_NETWORK, "uid", info.uid),
                                TargetRole.LEAF,
                                title,
                            ),
                        )
                        app.setBybitKey(accountId, key.trim(), secret.trim())
                        selectedPortfolio
                            ?.takeIf { it.accountIds.isNotEmpty() }
                            ?.let { app.savePortfolio(it.copy(accountIds = it.accountIds + accountId)) }
                        null
                    }
                if (problem != null) {
                    error = problem
                    return@launch
                }
                onSaved()
                host.refreshWithoutBitcoin {}
                notify("Bybit account added. Its history loads in the background.")
                close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: IllegalArgumentException) {
                error = rejected.message ?: "Could not track this account. Check the key and retry."
            } catch (failed: IllegalStateException) {
                error = failed.message ?: "Could not reach Bybit. Check the connection and retry."
            } catch (_: Exception) {
                error = "Could not save this account. Check available storage, then retry."
            } finally {
                saving = false
            }
        }
    }

    AccountFlowDialog("Connect Bybit", saving, close) {
        Text("Read-only · balances and history of your Bybit account")
        OutlinedTextField(
            label,
            { label = it },
            enabled = !saving,
            singleLine = true,
            label = { Text("Account name") },
            modifier = Modifier.fillMaxWidth().testTag("bybit-connect-name"),
        )
        BybitKeyFields(key, secret, !saving, { key = it }, { secret = it })
        Text(BYBIT_KEY_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        blocker?.let { Text(it, modifier = Modifier.testTag("bybit-connect-blocker")) }
        Button(
            enabled = !saving && blocker == null,
            modifier = Modifier.fillMaxWidth().testTag("bybit-connect-confirm"),
            onClick = ::submit,
        ) { Text(if (saving) "Checking key…" else "Connect") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("bybit-connect-error")) }
    }
}

/**
 * A tracked Bybit account: its last refresh or failure, how far back its history reaches, the key's expiry, coins
 * that need the user to choose which coin they are, and replacing the key.
 */
@Composable
internal fun BybitAccountDetails(
    app: PortfolioApplication,
    host: PlatformActions,
    account: AccountId,
    revision: Long,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember(account) { mutableStateOf(ExchangeAccountStatus(null, null, null, null)) }
    var hasKey by remember(account) { mutableStateOf(true) }
    var unresolved by remember(account) { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    var replacing by remember(account) { mutableStateOf(false) }
    var choosing by remember(account) { mutableStateOf<String?>(null) }
    LaunchedEffect(account, revision) {
        withContext(host.dispatcher) {
            Triple(app.exchangeStatus(account), app.hasBybitKey(account), app.unresolvedExchangeCoins(BYBIT_EXCHANGE))
        }.let { (loaded, key, coins) ->
            status = loaded
            hasKey = key
            unresolved = coins
        }
    }
    Column(Modifier.fillMaxWidth().testTag("bybit-account-details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bybit account", fontWeight = FontWeight.SemiBold)
        Text("Last sync: ${status.checkedAtMillis?.let { evmSyncTime(it) } ?: "not yet"}", style = MaterialTheme.typography.bodySmall)
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("bybit-error")) }
        if (!hasKey) Text("Enter this account's API key again to refresh it.", color = MaterialTheme.colorScheme.error)
        status.historyFromMillis?.let {
            Text(
                "History from ${evmSyncTime(it)}. Earlier balances appear as one opening amount per coin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("bybit-coverage"),
            )
        }
        status.keyExpiresAtMillis?.let {
            Text("API key expires ${evmSyncTime(it)}", style = MaterialTheme.typography.bodySmall)
        }
        unresolved.keys.forEach { symbol ->
            Text("$symbol is not matched to a coin yet, so it is not counted.", modifier = Modifier.testTag("bybit-unresolved-$symbol"))
            TextButton(onClick = { choosing = symbol }, modifier = Modifier.testTag("bybit-choose-$symbol")) { Text("Choose coin") }
        }
        TextButton(onClick = { replacing = true }, modifier = Modifier.testTag("bybit-replace-key")) {
            Text(if (hasKey) "Replace API key" else "Enter API key")
        }
    }
    if (replacing) {
        BybitKeyDialog(app, host, account, close = { replacing = false }) {
            replacing = false
            onChanged()
            host.refreshWithoutBitcoin {}
        }
    }
    choosing?.let { symbol ->
        CoinChoiceDialog(symbol, unresolved[symbol].orEmpty(), close = { choosing = null }) { coin ->
            choosing = null
            scope.launch {
                withContext(host.dispatcher) { app.chooseExchangeCoin(BYBIT_EXCHANGE, symbol, coin) }
                onChanged()
                host.refreshWithoutBitcoin {}
            }
        }
    }
}

/** Replaces a tracked account's key; the new key must be read-only and belong to the same Bybit account. */
@Composable
private fun BybitKeyDialog(
    app: PortfolioApplication,
    host: PlatformActions,
    account: AccountId,
    close: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ready = key.trim().matches(BYBIT_KEY_TEXT) && secret.trim().matches(BYBIT_KEY_TEXT)
    AccountFlowDialog("Bybit API key", busy, close) {
        BybitKeyFields(key, secret, !busy, { key = it }, { secret = it })
        Text(BYBIT_KEY_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled = ready && !busy, modifier = Modifier.fillMaxWidth().testTag("bybit-key-save"), onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    val info = withContext(host.dispatcher) { host.bybitKeyInfo(key.trim(), secret.trim()) }
                    val uid = withContext(host.dispatcher) { app.bybitTargets().firstOrNull { it.first == account }?.second }
                    error =
                        keyProblem(info) ?: if (info.uid != uid) "This key belongs to another Bybit account." else null
                    if (error == null) {
                        withContext(host.dispatcher) { app.setBybitKey(account, key.trim(), secret.trim()) }
                        onSaved()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    error = failed.message?.takeIf { failed is IllegalStateException || failed is IllegalArgumentException }
                        ?: "Could not check this key. Retry."
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy) "Checking key…" else "Save key") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("bybit-key-error")) }
    }
}

/** Picks which CoinGecko coin an exchange symbol is, among the coins that carry the symbol. */
@Composable
private fun CoinChoiceDialog(
    symbol: String,
    candidates: List<Pair<String, String>>,
    close: () -> Unit,
    choose: (String) -> Unit,
) {
    AccountFlowDialog("Which coin is $symbol?", false, close) {
        if (candidates.isEmpty()) {
            Text("No known coin uses the symbol $symbol. Once market data is allowed and loaded, the choices appear here.")
        }
        candidates.forEach { (id, name) ->
            TextButton(onClick = { choose(id) }, modifier = Modifier.fillMaxWidth().testTag("bybit-coin-$id")) {
                Text("$name ($id)")
            }
        }
    }
}
