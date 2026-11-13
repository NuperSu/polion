package io.valroot.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.bitcoin.bitcoinMainnetAddress
import io.valroot.core.model.AccountId
import io.valroot.core.model.NetworkId
import io.valroot.core.model.OwnerId
import io.valroot.core.model.PortfolioId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.Portfolio
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget

@Composable
internal fun ColumnScope.OwnershipWorkflow(
    type: String,
    data: PortfolioSnapshot,
    app: PortfolioApplication,
    nameState: MutableState<String>,
    addressState: MutableState<String>,
    ownerState: MutableState<OwnerId?>,
    parentState: MutableState<AccountId?>,
    selectedAccountsState: MutableState<Set<AccountId>>,
    errorState: MutableState<String?>,
    execute: (String, () -> Unit) -> Unit,
    close: () -> Unit,
) {
    var name by nameState
    var address by addressState
    var owner by ownerState
    var parent by parentState
    var selectedAccounts by selectedAccountsState
    var error by errorState

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
                    "valroot:archived" !in it.tags
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
        data.ownership.accounts.filter { "valroot:archived" !in it.tags }.forEach { a ->
            Row(
                Modifier.toggleable(value = a.id in selectedAccounts, role = Role.Checkbox) { checked ->
                    selectedAccounts =
                        if (checked) selectedAccounts + a.id else selectedAccounts - a.id
                },
            ) {
                Checkbox(checked = a.id in selectedAccounts, onCheckedChange = null)
                Text(a.label, Modifier.padding(top = 12.dp))
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
                "owner" -> app.accounts.saveOwner(Owner(OwnerId(id), name.trim()))
                "portfolio" ->
                    app.accounts.savePortfolio(
                        Portfolio(PortfolioId(id), name.trim(), data.currency.value, accountIds = selectedAccounts),
                    )
                else -> {
                    val ownerId = owner ?: OwnerId(uniqueId("owner")).also { app.accounts.saveOwner(Owner(it, "Personal")) }
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
                        app.accounts.saveAccountAndTarget(
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
                        app.accounts.saveAccount(value)
                    }
                }
            }
        }
        close()
    }, modifier = Modifier.fillMaxWidth()) { Text("Save ${if (type == "account") "account" else "changes"}") }
}
