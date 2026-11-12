package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.AssetAllocation
import io.valroot.core.application.AssetListPreference
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.HistoryPoint
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.model.AssetId
import io.valroot.core.model.PortfolioId

/** Classic Overview page: portfolio selector, balance, and assets. */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.classicOverviewItems(
    setPage: (Page) -> Unit,
    selectedPortfolio: PortfolioId?,
    setSelectedPortfolio: (PortfolioId?) -> Unit,
    setSelectedAsset: (AssetValuation?) -> Unit,
    period: Int,
    setPeriod: (Int) -> Unit,
    setDialog: (String?) -> Unit,
    chart: List<HistoryPoint>,
    app: PortfolioApplication,
    data: PortfolioSnapshot,
    compact: Boolean,
    wide: Boolean,
    presenter: ProductPresenter,
    assetPreference: AssetListPreference,
    busy: Boolean,
    execute: (String, () -> Unit) -> Unit,
    onAssetPreference: (AssetListPreference) -> Unit,
) {
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f),
            ) { PortfolioSelector(data, selectedPortfolio) { setSelectedPortfolio(it) } }
            var currencyMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { currencyMenu = true }) { Text("${data.currency.value} ⌄") }
                DropdownMenu(currencyMenu, { currencyMenu = false }) {
                    listOf("USD", "EUR", "GBP").forEach { currency ->
                        DropdownMenuItem(text = { Text(currency) }, onClick = {
                            currencyMenu = false
                            execute(
                                "Display currency saved",
                            ) { app.assets.setCurrency(AssetId(currency)) }
                        })
                    }
                }
            }
        }
    }
    item {
        OutlinedButton(
            onClick = { setPage(Page.ACCOUNTS) },
            modifier = Modifier.testTag("manage-accounts"),
        ) { Text("Manage accounts") }
    }
    val balance: @Composable () -> Unit = {
        BalanceHero(
            data,
            chart,
            presenter.state.chartLoading,
            presenter.state.chartError,
            compact,
            period,
            { setPeriod(it) },
            { setDialog("price") },
        )
    }
    val holdings: @Composable () -> Unit = {
        AssetsCard(
            data,
            assetPreference,
            busy,
            onAssetPreference,
            { setSelectedAsset(it) },
            { setDialog("price") },
        )
    }
    if (wide) {
        item(key = "portfolio-grid") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) { balance() }
                Column(Modifier.weight(1.2f)) { holdings() }
            }
        }
    } else {
        item(key = "portfolio-balance") { balance() }
        item(key = "portfolio-holdings") { holdings() }
    }
    item { Spacer(Modifier.height(64.dp)) }
}

/** Classic Reports page: returns disclaimer, balance, allocation, analytics, accounting, and reconciliation. */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.classicReportsItems(
    selectedPortfolio: PortfolioId?,
    setSelectedPortfolio: (PortfolioId?) -> Unit,
    setSelectedAsset: (AssetValuation?) -> Unit,
    period: Int,
    setPeriod: (Int) -> Unit,
    setDialog: (String?) -> Unit,
    setMessage: (String?) -> Unit,
    chart: List<HistoryPoint>,
    revision: Int,
    onRevision: () -> Unit,
    app: PortfolioApplication,
    host: PlatformActions,
    data: PortfolioSnapshot,
    compact: Boolean,
    presenter: ProductPresenter,
    hostRevision: Long,
    execute: (String, () -> Unit) -> Unit,
) {
    item {
        Panel {
            Text("Investment returns · —", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "Money-flow-adjusted performance is unavailable. Portfolio value changes include deposits and withdrawals.",
                color = Muted,
            )
        }
    }
    item { PortfolioSelector(data, selectedPortfolio) { setSelectedPortfolio(it) } }
    item {
        BalanceHero(
            data,
            chart,
            presenter.state.chartLoading,
            presenter.state.chartError,
            compact,
            period,
            {
                setPeriod(it)
            },
            { setDialog("price") },
        )
    }
    item {
        AllocationPanel(AssetAllocation.project(data.valuations, data.currency, data.coverage)) {
            setSelectedAsset(it)
        }
    }
    item { AnalyticsPanel(app, host, selectedPortfolio, revision + hostRevision.toInt()) }
    item {
        AccountingPanel(
            app,
            host,
            selectedPortfolio,
            revision + hostRevision.toInt(),
        ) { onRevision() }
    }
    item {
        Panel {
            Text("Understand your cost basis", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(
                "FIFO follows the oldest available lots. An incoming transfer does not " +
                    "establish a purchase price. Unknown purchase costs remain unknown, and pending activity is excluded from confirmed reports.",
                color = Muted,
                lineHeight = 22.sp,
            )
            Text(
                "Values use saved price observations at each point in time. Changes in the " +
                    "chart may reflect deposits and withdrawals, not investment performance.",
                color = Muted,
                fontSize = 13.sp,
            )
            OutlinedButton(onClick = {
                execute("Export ready") {
                    val csv = app.csv.export(ActivityFilter(portfolio = selectedPortfolio))
                    host.saveFile("valroot-activities.csv", csv.encodeToByteArray()) {
                        setMessage(it)
                    }
                }
            }) {
                Text(
                    if (selectedPortfolio ==
                        null
                    ) {
                        "↓  Export all active activity CSV"
                    } else {
                        "↓  Export selected portfolio CSV"
                    },
                )
            }
        }
    }
    item { SectionTitle("Reconciliation", "Review") { setDialog("reconcile") } }
    item {
        Text(
            "${data.duplicateConflicts.size} unresolved duplicate conflicts. Transfer " +
                "suggestions always require your review.",
            color = Muted,
        )
    }
}
