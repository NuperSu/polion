package io.valroot.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.AccountingView
import io.valroot.core.application.AssetListPreference
import io.valroot.core.application.AssetSort
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.HistoryPoint
import io.valroot.core.application.MarketOverview
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.model.AssetId
import io.valroot.core.model.PortfolioId
import io.valroot.ui.model.ChartStyle
import io.valroot.ui.model.ModelAllocationBar
import io.valroot.ui.model.ModelAllocationSlice
import io.valroot.ui.model.ModelChangeTone
import io.valroot.ui.model.ModelHoldingRow
import io.valroot.ui.model.ModelInsights
import io.valroot.ui.model.ModelInsightsAction
import io.valroot.ui.model.ModelMain
import io.valroot.ui.model.ModelMainAction
import io.valroot.ui.model.ModelPortfolioHoldings
import io.valroot.ui.model.ModelPortfolioHoldingsState
import io.valroot.ui.model.ModelPortfolioStats
import io.valroot.ui.model.ModelPortfolioSummary
import io.valroot.ui.model.ModelPortfolioSummaryState
import io.valroot.ui.model.ModelSettings
import io.valroot.ui.model.ModelSettingsState
import io.valroot.ui.model.ModelStat

@Composable
internal fun ColumnScope.ModelPortfolioPages(
    app: PortfolioApplication,
    host: PlatformActions,
    dataRevision: Long,
    data: PortfolioSnapshot,
    page: Page,
    selectedPortfolio: PortfolioId?,
    chart: List<HistoryPoint>,
    chartLoading: Boolean,
    chartError: Boolean,
    period: Int,
    preferences: RefreshPreferences,
    wifiOnlyRefreshSupported: Boolean,
    assetPreference: io.valroot.core.application.AssetListPreference,
    showPortfolioChart: Boolean,
    syncStatus: SyncStatus,
    favorites: Set<io.valroot.core.model.AssetId>,
    chartStyle: ChartStyle,
    amountsHidden: Boolean,
    wide: Boolean,
    short: Boolean,
    settingsContent: (@Composable ColumnScope.(onAdvanced: (Page) -> Unit) -> Unit)?,
    onFavorite: (io.valroot.core.model.AssetId) -> Unit,
    onPeriod: (Int) -> Unit,
    onCurrency: (AssetId) -> Unit,
    onAsset: (AssetValuation?) -> Unit,
    onAssetPreference: (io.valroot.core.application.AssetListPreference) -> Unit,
    onPortfolio: (PortfolioId?) -> Unit,
    onAdvanced: (Page) -> Unit,
    onSyncDetails: () -> Unit,
    onToggleAmounts: () -> Unit,
    onAutomatic: (Boolean) -> Unit,
    onWifi: (Boolean) -> Unit,
    onMarketRefresh: (maxAgeMillis: Long) -> Unit,
    onAccountChanged: () -> Unit,
    onAccountTool: (AccountTool) -> Unit,
    onConnectBitcoin: () -> Unit,
    onChartStyle: (ChartStyle) -> Unit,
    hiddenReviewState: MutableState<Boolean>,
    marketOverviewState: MutableState<MarketOverview?>,
    accountingState: MutableState<AccountingView?>,
    currencyMenuState: MutableState<Boolean>,
    sortMenuState: MutableState<Boolean>,
    filterMenuState: MutableState<Boolean>,
    explainState: MutableState<Boolean>,
    worthAssetState: MutableState<String?>,
    insightsDataState: MutableState<InsightsData?>,
) {
    var hiddenReview by hiddenReviewState
    val marketOverview by marketOverviewState
    val accounting by accountingState
    var currencyMenu by currencyMenuState
    var sortMenu by sortMenuState
    var filterMenu by filterMenuState
    var explain by explainState
    var worthAsset by worthAssetState
    val insightsData by insightsDataState

    when (page) {
        Page.HOME -> {
            ModelMain(
                marketMainState(
                    marketOverview,
                    data.marketCoins,
                    favorites,
                    kotlin.time.Clock.System
                        .now()
                        .toEpochMilliseconds(),
                ),
                wide,
            ) { action ->
                when (action) {
                    is ModelMainAction.ToggleFavorite -> onFavorite(AssetId(action.identity.id))
                    is ModelMainAction.LoadMarkets -> onMarketRefresh(60_000L)
                    is ModelMainAction.OpenCoin -> {
                        val key = AssetId(action.identity.id)
                        val owned = data.valuations.find { it.asset == key }
                        val saved = data.marketCoins.find { it.asset == key }
                        val overview = marketOverview
                        val listing = overview?.markets?.items?.find { overview.assetKeys[it.coinId] == key }
                        val trend = overview?.trending?.items?.find { overview.assetKeys[it.coinId] == key }
                        (
                            owned ?: saved?.detailValuation()
                                ?: listing?.detailValuation(key, overview.currency, overview.markets!!.observedAtMillis)
                                ?: trend?.detailValuation(key, overview.currency, overview.trending!!.observedAtMillis)
                        )?.let(onAsset)
                    }
                }
            }
        }
        Page.OVERVIEW -> {
            PortfolioSelector(data, selectedPortfolio, onPortfolio)
            val summary = pricingSummary(data.valuations)
            // The secondary total is shown only when it covers the same holdings as the main total.
            val secondary =
                data.secondaryCurrency?.let { code ->
                    val other = pricingSummary(data.secondaryValuations)
                    if (other.total != null && other.missing == summary.missing) {
                        money(other.total, code).hiddenIf(amountsHidden)
                    } else {
                        "${code.value} price not available yet"
                    }
                }
            val exactValues = chart.map(::historyTotal)
            val values = exactValues.map { it?.toString()?.toDoubleOrNull() }
            val finite = values.filterNotNull().filter { it.isFinite() }
            val low = finite.minOrNull()
            val high = finite.maxOrNull()
            val points =
                if (low == null ||
                    high == null
                ) {
                    emptyList()
                } else {
                    values.map { value ->
                        value?.takeIf { it.isFinite() }?.let {
                            if (high ==
                                low
                            ) {
                                .5f
                            } else {
                                ((it - low) / (high - low)).toFloat()
                            }
                        }
                    }
                }
            val labels = mapOf(-1 to "1H", 1 to "1D", 7 to "1W", 30 to "1M", 90 to "3M", -2 to "YTD", 365 to "1Y", 0 to "ALL")
            val firstTotal = chart.firstOrNull()?.let(::historyTotal)
            val lastTotal = chart.lastOrNull()?.let(::historyTotal)
            val delta = if (chart.size >= 2 && firstTotal != null && lastTotal != null) lastTotal - firstTotal else null
            ModelPortfolioSummary(
                ModelPortfolioSummaryState(
                    balance = (summary.total?.let { money(it, data.currency) } ?: "—").hiddenIf(amountsHidden),
                    currency = data.currency.value,
                    change =
                        (
                            delta?.let {
                                signedMoney(
                                    io.valroot.core.model.Rational
                                        .fromDecimal(it),
                                    data.currency,
                                )
                            } ?: "—"
                        ).hiddenIf(amountsHidden),
                    changeTone =
                        tone(
                            delta?.let {
                                io.valroot.core.model.Rational
                                    .fromDecimal(it)
                            },
                        ),
                    changeLabel = "Value change · ${labels[period] ?: "Selected period"}",
                    percentChange = portfolioValuePercent(chart),
                    selectedRange = labels[period] ?: "",
                    chartPoints = points,
                    enabledRanges = labels.values.toSet(),
                    ranges = labels.values.toList(),
                    chartTopLabel =
                        (
                            exactValues.filterNotNull().maxOrNull()?.let {
                                money(
                                    it,
                                    data.currency,
                                )
                            } ?: "—"
                        ).hiddenIf(amountsHidden),
                    chartBottomLabel =
                        (
                            exactValues.filterNotNull().minOrNull()?.let {
                                money(
                                    it,
                                    data.currency,
                                )
                            } ?: "—"
                        ).hiddenIf(amountsHidden),
                    amountsHidden = amountsHidden,
                    secondaryBalance = secondary,
                    showChart = showPortfolioChart,
                    chart =
                        remember(
                            chart,
                            data.currency,
                            amountsHidden,
                        ) { portfolioChartData(chart, data.currency, amountsHidden) },
                    chartStyle = chartStyle,
                    loading = chartLoading,
                ),
                wide,
                short,
                { label -> labels.entries.find { it.value == label }?.let { onPeriod(it.key) } },
                onExplain = { explain = true },
                onToggleAmounts = onToggleAmounts,
                onChartStyle = onChartStyle,
                onCurrency = { currencyMenu = true },
                currencyMenuContent = {
                    DropdownMenu(currencyMenu, { currencyMenu = false }) {
                        listOf("USD", "EUR", "GBP").forEach { code ->
                            DropdownMenuItem(text = { Text(code) }, onClick = {
                                currencyMenu = false
                                onCurrency(AssetId(code))
                            })
                        }
                    }
                },
            )
            // Contributions and withdrawals are explained under "What's going on?"; only live status stays here.
            listOfNotNull(
                if (chartLoading) {
                    "Updating history…"
                } else if (chartError) {
                    "History unavailable"
                } else {
                    null
                },
                if (summary.stale > 0) stalePriceMessage(summary.stale) else null,
                if (summary.missing > 0) "${summary.missing} unpriced assets excluded" else null,
            ).takeIf { it.isNotEmpty() }?.let { notes ->
                Text(notes.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            val performance = portfolioPerformance(accounting?.takeIf { it.report.quoteAsset == data.currency })
            if (performance != null) {
                fun amount(value: io.valroot.core.model.Rational?) =
                    (value?.let { signedMoney(it, data.currency) } ?: "—").hiddenIf(amountsHidden)
                ModelPortfolioStats(
                    listOf(
                        ModelStat("Total gains", amount(performance.total), tone = tone(performance.total)),
                        ModelStat(
                            "Unrealized",
                            amount(performance.unrealized),
                            performance.unrealizedPercent.takeIf { it != "—" },
                            tone(performance.unrealized),
                        ),
                        ModelStat("Realized", amount(performance.realized), tone = tone(performance.realized)),
                        ModelStat(
                            "Cost basis",
                            (performance.basis?.asMoney(data.currency) ?: "—").hiddenIf(amountsHidden),
                        ),
                    ),
                    wide,
                    listOfNotNull(
                        "Figures needing a missing classification or cost show —.".takeIf {
                            performance.total == null || performance.basis == null
                        },
                        "Order within a block is provisional.".takeIf { performance.provisional },
                    ).joinToString(" ").ifEmpty { null },
                )
            }
            ModelAllocationBar(
                allocation(data.valuations).let { shares ->
                    val top = shares.take(5)
                    val rest = shares.drop(5).sumOf { it.second }
                    top.map { (asset, share) ->
                        val symbol = asset.metadata?.symbol ?: asset.asset.value
                        ModelAllocationSlice(symbol, share.toFloat(), identity(asset.asset.value, symbol, null).color)
                    } +
                        listOfNotNull(
                            ModelAllocationSlice("Other", rest.toFloat(), MaterialTheme.colorScheme.onSurfaceVariant).takeIf {
                                rest >
                                    0
                            },
                        )
                },
            )
            ModelPortfolioHoldings(
                ModelPortfolioHoldingsState(
                    displayedAssets(data.valuations, assetPreference).map { asset ->
                        val symbol = asset.metadata?.symbol ?: asset.asset.value
                        val quantity =
                            asset.metadata?.let { formatUnits(asset.amount.atomicUnits, it.decimals) }
                                ?: "${asset.amount.atomicUnits} atomic units"
                        val assetHistory =
                            chart.map { point ->
                                point.copy(
                                    valuations =
                                        point.valuations.filter {
                                            it.asset ==
                                                asset.asset
                                        },
                                )
                            }
                        ModelHoldingRow(
                            identity(asset.asset.value, symbol, asset.metadata?.name),
                            "${quantity.hiddenIf(amountsHidden)} $symbol · ${asset.price?.let {
                                quotePrice(it.price.value, it.price.quoteAssetId)
                            } ?: "—"}",
                            (asset.value?.let { money(it, data.currency) } ?: "—").hiddenIf(amountsHidden),
                            portfolioValueDelta(assetHistory, data.currency).hiddenIf(amountsHidden),
                            portfolioValuePercent(assetHistory),
                            assetHistory.firstOrNull()?.let(::historyTotal)?.let { first ->
                                assetHistory.lastOrNull()?.let(::historyTotal)?.let { last ->
                                    if (assetHistory.size >=
                                        2
                                    ) {
                                        tone(
                                            io.valroot.core.model.Rational
                                                .fromDecimal(last - first),
                                        )
                                    } else {
                                        null
                                    }
                                }
                            } ?: io.valroot.ui.model.ModelChangeTone.NEUTRAL,
                        )
                    },
                    if (assetPreference.sort == AssetSort.VALUE) "Highest Holdings" else "Name",
                ),
                onHolding = { coin -> onAsset(data.valuations.find { it.asset.value == coin.id }) },
                onSort = { sortMenu = true },
                onView = { filterMenu = true },
                hiddenCount = data.hiddenAssets.size,
                onHidden = { hiddenReview = true },
                viewMenuContent = {
                    DropdownMenu(filterMenu, { filterMenu = false }) {
                        DropdownMenuItem(text = { Text("Hide empty: ${assetPreference.hideEmpty}") }, onClick = {
                            onAssetPreference(assetPreference.copy(hideEmpty = !assetPreference.hideEmpty))
                            filterMenu = false
                        })
                        DropdownMenuItem(text = {
                            Text(
                                "Hide under 1 ${data.currency.value}: ${assetPreference.hideSmall}",
                            )
                        }, onClick = {
                            onAssetPreference(assetPreference.copy(hideSmall = !assetPreference.hideSmall))
                            filterMenu = false
                        })
                    }
                },
                sortMenuContent = {
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        AssetSort.entries.forEach { sort ->
                            DropdownMenuItem(text = { Text(sort.name.lowercase()) }, onClick = {
                                sortMenu = false
                                onAssetPreference(assetPreference.copy(sort = sort))
                            })
                        }
                    }
                },
            )
            TextButton(onClick = { onAdvanced(Page.OVERVIEW) }) { Text("Portfolio tools and currency") }
            // Room for the floating add button, so it never covers the last holding.
            Spacer(Modifier.height(88.dp))
        }
        Page.REPORTS -> {
            val labels = mapOf(-1 to "1H", 1 to "1D", 7 to "1W", 30 to "1M", 90 to "3M", -2 to "YTD", 365 to "1Y", 0 to "ALL")
            ModelInsights(
                insightsState(
                    data,
                    chart,
                    labels[period] ?: "",
                    labels.values.toList(),
                    data.ownership.portfolios
                        .find { it.id == selectedPortfolio }
                        ?.label ?: "All portfolios",
                    insightsData,
                    accounting,
                    amountsHidden,
                    chartStyle,
                    worthAsset,
                    chartLoading,
                ),
                wide,
            ) { action ->
                when (action) {
                    is ModelInsightsAction.Range -> labels.entries.find { it.value == action.label }?.let { onPeriod(it.key) }
                    is ModelInsightsAction.Style -> onChartStyle(action.style)
                    is ModelInsightsAction.WorthAsset -> worthAsset = action.id
                    is ModelInsightsAction.OpenAsset -> data.valuations.find { it.asset.value == action.id }?.let(onAsset)
                }
            }
            TextButton(onClick = { onAdvanced(Page.REPORTS) }) { Text("Reporting and accounting tools") }
            Spacer(Modifier.height(24.dp))
        }
        Page.ACCOUNTS -> {
            AccountsWorkspace(
                app,
                host,
                data,
                selectedPortfolio,
                dataRevision,
                syncStatus,
                onConnectBitcoin,
                onSyncDetails,
                onAdvanced,
                onAccountChanged,
                onAccountTool,
            )
        }
        Page.SETTINGS if settingsContent != null -> settingsContent(onAdvanced)
        Page.SETTINGS -> {
            ModelSettings(
                ModelSettingsState(
                    preferences.automaticRefresh,
                    preferences.wifiOnly,
                    "Prices and wallet history refresh together. Unchanged wallets are only checked, not downloaded again.",
                    if (wifiOnlyRefreshSupported) {
                        "Background jobs use an unmetered network. Manual refresh uses the current connection."
                    } else {
                        "The Wi-Fi restriction applies to Android background jobs. Desktop refresh uses the current connection."
                    },
                ),
                onAutomatic,
                onWifi,
            )
            TextButton(
                onClick = onSyncDetails,
                modifier = Modifier.testTag("settings-wallet-history"),
            ) { Text("Wallet history and recovery") }
            TextButton(onClick = { onAdvanced(Page.SETTINGS) }) { Text("Appearance, privacy, backup and price settings") }
        }
        else -> Unit
    }
}
