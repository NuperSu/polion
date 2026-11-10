package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.AssetValuation
import io.valroot.core.holdings.BalanceView
import io.valroot.core.model.AtomicAmount
import io.valroot.core.ownership.Account

@Composable
internal fun AssetAccountBreakdown(
    asset: AssetValuation,
    balances: BalanceView,
    accounts: List<Account>,
) {
    val labels = remember(accounts) { accounts.associate { it.id to it.label } }
    val entries =
        remember(balances, asset.asset, labels) {
            balances.quantities.entries
                .filter { it.key.asset == asset.asset }
                .sortedWith(compareBy({ labels[it.key.account] ?: it.key.account.value }, { it.key.account.value }))
        }
    var page by remember(asset.asset, balances) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text("Holdings by account", fontWeight = FontWeight.SemiBold)
        Text("History coverage: ${balances.coverage.name.lowercase()} · confirmed movements only", color = Muted, fontSize = 12.sp)
        if (entries.isEmpty()) Text("No recorded account holdings for this asset.", color = Muted, fontSize = 12.sp)
        entries.drop(page * 10).take(10).forEach { (key, units) ->
            val quantity =
                asset.metadata?.let { "${AtomicAmount(asset.asset, units).decimal(it.decimals)} ${it.symbol}" }
                    ?: "$units atomic units"
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(labels[key.account] ?: key.account.value, fontWeight = FontWeight.Medium)
                Text("$quantity · account ${key.account.value}", fontSize = 12.sp)
            }
        }
        if (entries.size > 10) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = page > 0, onClick = { page-- }) { Text("Previous accounts") }
                TextButton(enabled = (page + 1) * 10 < entries.size, onClick = { page++ }) { Text("Next accounts") }
                Text("${page + 1} / ${(entries.size + 9) / 10}", fontSize = 12.sp)
            }
        }
    }
}
