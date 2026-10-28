package io.polion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.model.AccountId
import io.polion.core.model.OwnerId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** The host runs discovery on its serial database dispatcher using its configured transport. */
@Composable
internal fun WalletDialog(
    data: PortfolioSnapshot,
    app: PortfolioApplication,
    host: PlatformActions,
    close: () -> Unit,
    onSaved: (String) -> Unit,
    discover: (TrackingTargetId, (String) -> Unit) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }
    var expression by rememberSaveable { mutableStateOf("") }
    val accounts = data.ownership.accounts.filter { it.kind != AccountKind.GROUP && "polion:archived" !in it.tags }
    var account by remember { mutableStateOf<AccountId?>(null) }
    var owner by remember {
        mutableStateOf(
            data.ownership.owners
                .firstOrNull()
                ?.id,
        )
    }
    var expanded by remember { mutableStateOf(false) }
    var ownerExpanded by remember { mutableStateOf(false) }
    var consent by rememberSaveable { mutableStateOf(data.sourceConsent) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) close() }) {
        Surface(
            shape =
                androidx.compose.foundation.shape
                    .RoundedCornerShape(24.dp),
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 760.dp),
        ) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Track a Bitcoin wallet", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Use a mainnet xpub or an addr, pkh, wpkh, or sh(wpkh) descriptor. A bare xpub scans native SegWit receive and change branches. Never enter a private key or seed.",
                )
                OutlinedTextField(
                    label,
                    { label = it },
                    label = { Text("Wallet name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    expression,
                    { expression = it },
                    label = { Text("Public wallet expression") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                OutlinedButton(onClick = { expanded = true }, enabled = !busy) {
                    Text(accounts.firstOrNull { it.id == account }?.label ?: "New account")
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("New account") }, onClick = {
                            account = null
                            expanded = false
                        })
                        accounts.forEach { item ->
                            DropdownMenuItem(text = { Text(item.label) }, onClick = {
                                account = item.id
                                expanded = false
                            })
                        }
                    }
                }
                if (account == null) {
                    Text("A new account will use this wallet name.")
                    if (data.ownership.owners.isEmpty()) {
                        Text("A personal owner will be created for this account.")
                    } else {
                        OutlinedButton(onClick = { ownerExpanded = true }, enabled = !busy) {
                            Text(
                                data.ownership.owners
                                    .firstOrNull { it.id == owner }
                                    ?.label ?: "Select an owner",
                            )
                            DropdownMenu(expanded = ownerExpanded, onDismissRequest = { ownerExpanded = false }) {
                                data.ownership.owners.forEach { item ->
                                    DropdownMenuItem(text = { Text(item.label) }, onClick = {
                                        owner = item.id
                                        ownerExpanded = false
                                    })
                                }
                            }
                        }
                    }
                }
                Text(
                    "Discovery queries derived addresses at ${data.sourceEndpoint}. It stops after 20 unused addresses per branch; activity beyond that gap may be missed.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(consent, { consent = it }, enabled = !busy, modifier = Modifier.testTag("wallet-source-consent"))
                    Text("Allow public address queries to this provider.")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = close, enabled = !busy) { Text("Cancel") }
                    Button(onClick = {
                        if (label.isBlank() || expression.isBlank()) {
                            error = "Enter a name and public wallet expression."
                            return@Button
                        }
                        busy = true
                        val suffix = "${Clock.System.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt(1_000_000)}"
                        val id = TrackingTargetId("wallet-$suffix")
                        val selected = account ?: AccountId("wallet-account-$suffix")
                        val ownerId = owner ?: OwnerId("wallet-owner-$suffix")
                        scope.launch {
                            runCatching {
                                withContext(host.dispatcher) {
                                    app.saveBitcoinWallet(
                                        TrackingTarget(
                                            id,
                                            selected,
                                            TargetIdentity(BITCOIN_MAINNET, "wallet", expression.trim()),
                                            TargetRole.WALLET,
                                            label.trim(),
                                        ),
                                        newAccount =
                                            if (account ==
                                                null
                                            ) {
                                                Account(selected, ownerId, label.trim(), AccountKind.WALLET)
                                            } else {
                                                null
                                            },
                                        newOwner = if (account == null && owner == null) Owner(ownerId, "Personal") else null,
                                        consentAccepted = consent,
                                    )
                                }
                            }.fold({
                                onSaved("Wallet saved. Discovering public addresses…")
                                discover(id) { message ->
                                    busy = false
                                    onSaved(message)
                                    close()
                                }
                            }, {
                                busy = false
                                error = it.message ?: "Could not save this wallet."
                            })
                        }
                    }, enabled = !busy && consent) { Text(if (busy) "Discovering…" else "Save & discover") }
                }
            }
        }
    }
}
