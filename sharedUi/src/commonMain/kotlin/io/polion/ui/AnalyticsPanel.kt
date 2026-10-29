package io.polion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.polion.core.application.AnalyticsSnapshot
import io.polion.core.application.AssetValuation
import io.polion.core.application.PortfolioApplication
import io.polion.core.model.PortfolioId
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnalyticsPanel(
    app: PortfolioApplication,
    host: PlatformActions,
    portfolio: PortfolioId?,
    revision: Int,
) {
    var days by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<AnalyticsSnapshot?>(null) }
    var error by remember { mutableStateOf(false) }
    var allocationPage by remember(portfolio, revision, days) { mutableIntStateOf(0) }
    var accountPage by remember(portfolio, revision, days) { mutableIntStateOf(0) }
    var categoryPage by remember(portfolio, revision, days) { mutableIntStateOf(0) }
    LaunchedEffect(portfolio, revision, days) {
        runCatching {
            val at = Clock.System.now().toEpochMilliseconds() - days * 86_400_000L
            withContext(host.dispatcher) { app.analytics(at, portfolio) }
        }.fold({
            data = it
            error = false
        }, {
            data = null
            error = true
        })
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
            .border(1.dp, Line, RoundedCornerShape(22.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Historical breakdown", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Confirmed holdings and activity through the selected date. Missing prices remain unknown.",
            fontSize = 12.sp,
            color = Muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Today", 7 to "7 days ago", 30 to "30 days ago", 90 to "90 days ago").forEach { (period, label) ->
                FilterChip(days == period, { days = period }, label = { Text(label, fontSize = 11.sp) })
            }
        }
        val result = data
        if (error) {
            Text("Breakdown could not be loaded.", color = MaterialTheme.colorScheme.error)
        } else if (result == null) {
            Text("Loading breakdown…", fontSize = 12.sp)
        } else {
            Text(
                "As of ${Instant.fromEpochMilliseconds(
                    result.atMillis,
                )} · ${result.currency.value} · coverage ${result.coverage.name.lowercase()}",
                fontSize = 11.sp,
                color = Muted,
            )
            Text("Asset allocation", fontWeight = FontWeight.SemiBold)
            if (result.allocation.isEmpty()) Text("No confirmed assets at this date.", fontSize = 12.sp)
            result.allocation
                .drop(allocationPage * 10)
                .take(10)
                .forEach { value -> AnalyticsAsset(value, result.currency.value) }
            if (result.allocation.size > 10) AnalyticsPages(allocationPage, result.allocation.size) { allocationPage = it }
            HorizontalDivider()
            Text("By account", fontWeight = FontWeight.SemiBold)
            if (result.accounts.isEmpty()) Text("No confirmed account balances at this date.", fontSize = 12.sp)
            val accountRows = result.accounts.flatMap { account -> account.valuations.map { account to it } }
            accountRows.drop(accountPage * 10).take(10).forEach { (account, asset) ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(account.label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(account.account.value, fontSize = 10.sp, color = Muted)
                    AnalyticsAsset(asset, result.currency.value)
                }
            }
            if (accountRows.size > 10) AnalyticsPages(accountPage, accountRows.size) { accountPage = it }
            HorizontalDivider()
            Text("Activity categories", fontWeight = FontWeight.SemiBold)
            Text(
                "Cumulative confirmed movements through this date; transfers can appear on both sides.",
                fontSize = 11.sp,
                color = Muted,
            )
            if (result.categories.isEmpty()) Text("No confirmed activity at this date.", fontSize = 12.sp)
            result.categories.drop(categoryPage * 10).take(10).forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "${row.kind.name.lowercase().replaceFirstChar { it.uppercase() }} · ${row.asset.value}",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    )
                    val decimals =
                        result.allocation
                            .firstOrNull { it.asset == row.asset }
                            ?.metadata
                            ?.decimals
                    val incoming =
                        decimals?.let {
                            io.polion.core.model
                                .ExactDecimal(row.incoming, it)
                                .toString()
                        } ?: "${row.incoming} atomic"
                    val outgoing =
                        decimals?.let {
                            io.polion.core.model
                                .ExactDecimal(row.outgoing, it)
                                .toString()
                        } ?: "${row.outgoing} atomic"
                    Text("In $incoming · out $outgoing · ${row.activities} activities", fontSize = 11.sp)
                }
            }
            if (result.categories.size > 10) AnalyticsPages(categoryPage, result.categories.size) { categoryPage = it }
        }
    }
}

@Composable
private fun AnalyticsAsset(
    value: AssetValuation,
    currency: String,
) {
    val amount = value.metadata?.let { value.amount.decimal(it.decimals).toString() } ?: "${value.amount.atomicUnits} atomic"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${value.metadata?.symbol ?: value.asset.value} · $amount", fontSize = 12.sp)
        Text(
            "Value ${value.value?.toString() ?: "unknown"} $currency · price ${value.priceStatus.name.lowercase()}",
            fontSize = 11.sp,
            color = Muted,
        )
        value.price?.let {
            Text("${if (it.manual) "Manual" else it.providerId?.value} · observation ${it.id}", fontSize = 10.sp, color = Muted)
        }
    }
}

@Composable
private fun AnalyticsPages(
    page: Int,
    total: Int,
    change: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { change(page - 1) }, enabled = page > 0) { Text("Previous") }
        TextButton(onClick = { change(page + 1) }, enabled = (page + 1) * 10 < total) { Text("Next") }
        Text("${page + 1} / ${(total + 9) / 10}", fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
    }
}
