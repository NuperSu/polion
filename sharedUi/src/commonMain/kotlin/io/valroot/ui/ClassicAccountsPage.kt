package io.valroot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget

/** Classic Accounts page: data health, watch-only accounts, archived accounts, and portfolios. */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.classicAccountsItems(
    setDialog: (String?) -> Unit,
    setMessage: (String?) -> Unit,
    setRemoveAccount: (Account?) -> Unit,
    setAddressWallet: (TrackingTarget?) -> Unit,
    setSourceScanTarget: (TrackingTarget?) -> Unit,
    onRevision: () -> Unit,
    app: PortfolioApplication,
    host: PlatformActions,
    data: PortfolioSnapshot,
    busy: Boolean,
    syncStatus: SyncStatus,
    execute: (String, () -> Unit) -> Unit,
) {
    item { HealthCard(data, { setDialog("sync") }, { setDialog("utxo") }) }
    item { OutlinedButton(onClick = { setDialog("sync") }) { Text("Refresh wallet history") } }
    item { SectionTitle("Watch-only accounts", "+ Add") { setDialog("add") } }
    item {
        Text(
            "Manage accounts and their public sources across all portfolios. Archive an " +
                "account to pause tracking and hide it from active portfolios; saved history stays on this device.",
            color = Muted,
        )
    }
    if (data.ownership.accounts.isEmpty()) {
        item {
            EmptyCard(
                "Your keys stay yours",
                "Choose a supported public Bitcoin source. Valroot never needs your recovery " +
                    "phrase or private keys.",
                "Add your first source",
            ) {
                setDialog("add")
            }
        }
    }
    items(
        data.ownership.accounts.filter { "valroot:archived" !in it.tags },
        key = { it.id.value },
    ) { account ->
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoinBadge(if (account.kind == AccountKind.GROUP) "▣" else "₿")
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(account.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${data.ownership.owners
                            .find { it.id == account.ownerId }
                            ?.label ?: "Owner"} · ${account.kind.name.lowercase()}",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
            }
            data.ownership.targets
                .filter { it.accountId == account.id && it.walletId == null }
                .forEach { target ->
                    HorizontalDivider(color = Line)
                    Text(target.label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    TextButton(
                        onClick = { setSourceScanTarget(target) },
                    ) { Text("View scan details") }
                    if (target.role ==
                        io.valroot.core.ownership.TargetRole.WALLET
                    ) {
                        TextButton(
                            onClick = { setAddressWallet(target) },
                        ) { Text("View derived addresses") }
                    }
                    if (target.role ==
                        io.valroot.core.ownership.TargetRole.WALLET
                    ) {
                        TextButton(onClick = {
                            host.discoverWallet(target.id) {
                                setMessage(it)
                                ; onRevision()
                            }
                        }, enabled = syncStatus.phase != SyncPhase.SYNCING) {
                            Text(
                                "Discover wallet addresses",
                            )
                        }
                    }
                    Text(target.identity.canonicalKey, color = Muted, fontSize = 12.sp)
                    StatusPill(
                        if (target.enabled) "Watching · ${target.identity.kind}" else "Paused",
                        target.enabled,
                    )
                }
            TextButton(
                onClick = { setRemoveAccount(account) },
                enabled = !busy && syncStatus.phase != SyncPhase.SYNCING,
                modifier = Modifier.testTag("archive-account-${account.id.value}"),
            ) { Text("Archive account", fontSize = 12.sp) }
            val accountUnits = data.balances.quantities.filterKeys { it.account == account.id }
            accountUnits.forEach { (key, units) ->
                Text(
                    data.valuations.find { it.asset == key.asset }?.metadata?.let {
                        "${formatUnits(units, it.decimals)} ${it.symbol}"
                    } ?: "$units atomic units (${key.asset.value})",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
    if (data.ownership.accounts.any { "valroot:archived" in it.tags }) {
        item { SectionTitle("Archived accounts") }
        items(
            data.ownership.accounts.filter { "valroot:archived" in it.tags },
            key = { "archived:${it.id.value}" },
        ) { archived ->
            Panel {
                Text(archived.label, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tracking paused. Saved activity and accounting decisions are retained.",
                    fontSize = 12.sp,
                    color = Muted,
                )
                OutlinedButton(
                    onClick = {
                        execute(
                            "Account restored",
                        ) { app.accounts.archiveAccount(archived.id, false) }
                    },
                    enabled = !busy && syncStatus.phase != SyncPhase.SYNCING,
                    modifier = Modifier.testTag("restore-account-${archived.id.value}"),
                ) { Text("Restore account") }
            }
        }
    }
    item { SectionTitle("Organize your space") }
    item {
        WrapActions(
            listOf(
                "Add owner" to { setDialog("owner") },
                "Account group" to { setDialog("group") },
                "New portfolio" to { setDialog("portfolio") },
            ),
        )
    }
    items(data.ownership.portfolios) { portfolio ->
        Panel {
            Text(portfolio.label, fontWeight = FontWeight.SemiBold)
            Text(
                "${portfolio.valuationCurrency} · " +
                    if (portfolio.accountIds.isEmpty()) {
                        "All accounts"
                    } else {
                        "${portfolio.accountIds.size} accounts"
                    },
                color = Muted,
            )
        }
    }
}
