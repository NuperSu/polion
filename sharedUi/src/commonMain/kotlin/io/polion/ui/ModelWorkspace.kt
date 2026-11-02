package io.polion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.polion.core.application.AssetValuation
import io.polion.core.application.HistoryPoint
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.model.PortfolioId
import io.polion.ui.model.ModelAccountRow
import io.polion.ui.model.ModelAccounts
import io.polion.ui.model.ModelAccountsState
import io.polion.ui.model.ModelAssetOption
import io.polion.ui.model.ModelChangeTone
import io.polion.ui.model.ModelCoinDetail
import io.polion.ui.model.ModelCoinDetailActions
import io.polion.ui.model.ModelCoinDetailState
import io.polion.ui.model.ModelCoinIdentity
import io.polion.ui.model.ModelCoinTab
import io.polion.ui.model.ModelDraftField
import io.polion.ui.model.ModelHoldingRow
import io.polion.ui.model.ModelInsights
import io.polion.ui.model.ModelInsightsState
import io.polion.ui.model.ModelMain
import io.polion.ui.model.ModelMainAction
import io.polion.ui.model.ModelMainState
import io.polion.ui.model.ModelMarketCoin
import io.polion.ui.model.ModelMetric
import io.polion.ui.model.ModelPage
import io.polion.ui.model.ModelPortfolioHoldings
import io.polion.ui.model.ModelPortfolioHoldingsState
import io.polion.ui.model.ModelPortfolioSummary
import io.polion.ui.model.ModelPortfolioSummaryState
import io.polion.ui.model.ModelSettings
import io.polion.ui.model.ModelSettingsState
import io.polion.ui.model.ModelShell
import io.polion.ui.model.ModelShellState
import io.polion.ui.model.ModelTransactionDialog
import io.polion.ui.model.ModelTransactionDialogActions
import io.polion.ui.model.ModelTransactionDialogState
import io.polion.ui.model.ModelTransactionType

@Composable
internal fun ModelWorkspace(
    page: Page,
    snapshot: PortfolioSnapshot?,
    selectedPortfolio: PortfolioId?,
    selectedAsset: AssetValuation?,
    chart: List<HistoryPoint>,
    chartLoading: Boolean,
    chartError: Boolean,
    period: Int,
    appearance: AppearanceTheme,
    preferences: RefreshPreferences,
    wifiOnlyRefreshSupported: Boolean,
    assetPreference: io.polion.core.application.AssetListPreference,
    refreshing: Boolean,
    refreshQueued: Boolean,
    syncStatus: SyncStatus,
    favorites: Set<io.polion.core.model.AssetId>,
    onFavorite: (io.polion.core.model.AssetId) -> Unit,
    onPage: (Page) -> Unit,
    onBackAux: () -> Unit,
    onPortfolio: (PortfolioId?) -> Unit,
    onAsset: (AssetValuation?) -> Unit,
    onPeriod: (Int) -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onAdvanced: (Page) -> Unit,
    onRefreshDetails: () -> Unit,
    onSyncDetails: () -> Unit,
    onAutomatic: (Boolean) -> Unit,
    onWifi: (Boolean) -> Unit,
    onManual: (AssetValuation?) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(ModelCoinTab.GENERAL.name) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var coinRange by rememberSaveable { mutableStateOf("1D") }
    val modelPage =
        when (page) {
            Page.HOME -> ModelPage.MAIN
            Page.REPORTS -> ModelPage.INSIGHTS
            else -> ModelPage.PORTFOLIO
        }
    val dark =
        when (appearance) {
            AppearanceTheme.DARK -> true
            AppearanceTheme.LIGHT -> false
            AppearanceTheme.SYSTEM -> isSystemInDarkTheme()
        }
    val title = selectedAsset?.metadata?.symbol ?: if (selectedAsset != null) selectedAsset.asset.value else page.label
    ModelShell(
        ModelShellState(
            modelPage,
            title,
            showBack = selectedAsset != null || page == Page.ACCOUNTS || page == Page.SETTINGS,
            refreshing = refreshing,
            refreshQueued = refreshQueued,
            menuItems = listOf("Accounts", "Settings", "Advanced tools", "Refresh details"),
            dark = dark,
        ),
        onPage = {
            onAsset(null)
            onPage(
                when (it) {
                    ModelPage.MAIN -> Page.HOME
                    ModelPage.PORTFOLIO -> Page.OVERVIEW
                    ModelPage.INSIGHTS -> Page.REPORTS
                },
            )
        },
        onBack = { if (selectedAsset != null) onAsset(null) else onBackAux() },
        onMenu = {
            when (it) {
                "Accounts" -> onPage(Page.ACCOUNTS)
                "Settings" -> onPage(Page.SETTINGS)
                "Advanced tools" -> onAdvanced(Page.MORE)
                else -> onRefreshDetails()
            }
        },
        onRefresh = onRefresh,
        onAdd = if (page == Page.OVERVIEW && selectedAsset == null) onAdd else null,
    ) { wide, short ->
        val data = snapshot
        if (data?.sampleData == true) {
            Text(
                "EXAMPLE DATA · stored only on this device",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (page == Page.ACCOUNTS || syncStatus.phase != SyncPhase.IDLE || data?.sourceStatus?.interrupted == true) {
            SyncBanner(syncStatus, data?.sourceStatus, onSyncDetails)
        }
        if (data == null) {
            Text("Opening your portfolio")
        } else if (selectedAsset != null) {
            val asset = selectedAsset
            val symbol = asset.metadata?.symbol ?: asset.asset.value
            val tracked = data.valuations.any { it.asset == asset.asset }
            val quantity =
                if (!tracked) {
                    "—"
                } else {
                    asset.metadata?.let { "${formatUnits(asset.amount.atomicUnits, it.decimals)} ${it.symbol}" }
                        ?: "${asset.amount.atomicUnits} atomic units"
                }
            ModelCoinDetail(
                ModelCoinDetailState(
                    ticker = symbol,
                    mark = symbol.take(1),
                    logoColor = Color(0xFFF7931A),
                    portfolio =
                        data.ownership.portfolios
                            .find { it.id == selectedPortfolio }
                            ?.label ?: "All portfolios",
                    metrics =
                        listOf(
                            ModelMetric("Owned", quantity),
                            ModelMetric(
                                "Market Value",
                                if (tracked) asset.value?.let { money(it, data.currency) } ?: "—" else "—",
                            ),
                            ModelMetric("Total Gains", "—"),
                            ModelMetric("Total Cost Basis", "—"),
                            ModelMetric("Unrealized Gain", "—"),
                            ModelMetric("Realized Gain", "—"),
                        ),
                    breakdownExpanded = expanded,
                    tab = ModelCoinTab.valueOf(tab),
                    price = asset.price?.let { "${it.price.value} ${it.price.quoteAssetId.value}" } ?: "—",
                    change = "—",
                    priceSource =
                        asset.price?.let {
                            if (it.manual) {
                                "Manual quote"
                            } else {
                                "${it.providerId?.value ?: "Provider"} · ${asset.priceStatus.name.lowercase()}"
                            }
                        }
                            ?: "Price unavailable",
                    selectedRange = coinRange,
                    chartPoints = emptyList(),
                    chartTopLabel = "—",
                    chartBottomLabel = "—",
                    transactionMetrics =
                        listOf(
                            ModelMetric("Avg. Buy Price", "—"),
                            ModelMetric("Avg. Sell Price", "—"),
                            ModelMetric("Transactions", "—"),
                        ),
                    transactions = emptyList(),
                    alerts = emptyList(),
                    orderSource = "Unavailable",
                    orderRows = emptyList(),
                    enabledRanges = emptySet(),
                    transactionEmpty = "Open activity to review this asset's records.",
                    alertsUnavailable = "Price alerts are unavailable.",
                    orderUnavailable = "Order book data is unavailable.",
                    changeTone = ModelChangeTone.NEUTRAL,
                ),
                ModelCoinDetailActions(
                    onBreakdown = { expanded = it },
                    onTab = { tab = it.name },
                    onRange = { coinRange = it },
                    onAddTransaction = { onManual(asset) },
                ),
            )
            TextButton(onClick = { onAdvanced(Page.ACTIVITY) }) { Text("Review activity and corrections") }
            TextButton(onClick = { onAdvanced(Page.OVERVIEW) }) { Text("Asset details and price tools") }
        } else {
            when (page) {
                Page.HOME -> {
                    val markets =
                        data.marketCoins.map { coin ->
                            ModelMarketCoin(
                                identity(coin.asset.value, coin.metadata?.symbol, coin.metadata?.name),
                                "${coin.price.price.value} ${coin.price.price.quoteAssetId.value} · ${coin.priceStatus.name.lowercase()}",
                                "—",
                            )
                        }
                    ModelMain(
                        ModelMainState(
                            topCoins = markets,
                            favorites =
                                markets.filter { market ->
                                    data.marketCoins.any {
                                        it.asset in favorites &&
                                            it.asset.value == market.identity.id
                                    }
                                },
                            topCoinsTitle = "Saved prices",
                            topCoinsEyebrow = "LOCAL MARKET DATA",
                            topCoinsEmptyMessage = "No saved prices yet",
                            favoritesEmptyMessage = "No favorites yet",
                            canToggleFavorites = true,
                        ),
                        wide,
                    ) { action ->
                        if (action is ModelMainAction.ToggleFavorite) {
                            onFavorite(
                                io.polion.core.model
                                    .AssetId(action.identity.id),
                            )
                        }
                        if (action is ModelMainAction.OpenCoin) {
                            data.marketCoins.find { it.asset.value == action.identity.id }?.let { coin ->
                                onAsset(data.valuations.find { it.asset == coin.asset } ?: coin.detailValuation())
                            }
                        }
                    }
                    TextButton(onClick = { onAdvanced(Page.HOME) }) { Text("Manage saved prices and favorites") }
                }
                Page.OVERVIEW -> {
                    PortfolioSelector(data, selectedPortfolio, onPortfolio)
                    val summary = pricingSummary(data.valuations)
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
                    val labels = mapOf(1 to "1D", 7 to "1W", 30 to "1M", 365 to "1Y", 0 to "ALL")
                    ModelPortfolioSummary(
                        ModelPortfolioSummaryState(
                            balance = summary.total?.let { money(it, data.currency) } ?: "—",
                            currency = data.currency.value,
                            change = "Value change —",
                            percentChange = "—",
                            selectedRange = labels[period] ?: "",
                            chartPoints = points,
                            enabledRanges = labels.values.toSet(),
                            chartTopLabel = exactValues.filterNotNull().maxOrNull()?.let { money(it, data.currency) } ?: "—",
                            chartBottomLabel = exactValues.filterNotNull().minOrNull()?.let { money(it, data.currency) } ?: "—",
                        ),
                        wide,
                        short,
                        { label -> labels.entries.find { it.value == label }?.let { onPeriod(it.key) } },
                    )
                    if (chartLoading) {
                        Text("Updating history…")
                    } else if (chartError) {
                        Text("History unavailable")
                    }
                    if (summary.stale > 0) Text(stalePriceMessage(summary.stale))
                    if (summary.missing > 0) Text("${summary.missing} unpriced assets excluded")
                    ModelPortfolioHoldings(
                        ModelPortfolioHoldingsState(
                            displayedAssets(data.valuations, assetPreference).map { asset ->
                                val symbol = asset.metadata?.symbol ?: asset.asset.value
                                val quantity =
                                    asset.metadata?.let { formatUnits(asset.amount.atomicUnits, it.decimals) }
                                        ?: "${asset.amount.atomicUnits} atomic units"
                                ModelHoldingRow(
                                    identity(asset.asset.value, symbol, asset.metadata?.name),
                                    "$quantity $symbol · ${asset.price?.price?.value ?: "—"} ${data.currency.value}",
                                    asset.value?.let { money(it, data.currency) } ?: "—",
                                    "—",
                                    "—",
                                )
                            },
                            "Sort / filter",
                        ),
                        onHolding = { coin -> onAsset(data.valuations.find { it.asset.value == coin.id }) },
                        onSort = { onAdvanced(Page.OVERVIEW) },
                        onView = { onAdvanced(Page.OVERVIEW) },
                    )
                    TextButton(onClick = { onAdvanced(Page.OVERVIEW) }) { Text("Portfolio tools and currency") }
                }
                Page.REPORTS -> {
                    ModelInsights(
                        ModelInsightsState(
                            balance = pricingSummary(data.valuations).total?.let { money(it, data.currency) } ?: "—",
                            portfolio =
                                data.ownership.portfolios
                                    .find { it.id == selectedPortfolio }
                                    ?.label ?: "All portfolios",
                        ),
                        wide,
                    ) { }
                    TextButton(onClick = { onAdvanced(Page.REPORTS) }) { Text("Reporting and accounting tools") }
                }
                Page.ACCOUNTS -> {
                    ModelAccounts(
                        ModelAccountsState(
                            data.ownership.accounts.map {
                                ModelAccountRow(
                                    it.label,
                                    it.kind.name.lowercase(),
                                    if ("polion:archived" in it.tags) "Archived" else "Refresh details available in account tools",
                                )
                            },
                        ),
                        onAdd,
                    )
                    TextButton(onClick = { onAdvanced(Page.ACCOUNTS) }) { Text("Account and sync tools") }
                }
                Page.SETTINGS -> {
                    ModelSettings(
                        ModelSettingsState(
                            preferences.automaticRefresh,
                            preferences.wifiOnly,
                            "Consented wallet history and prices refresh subject to platform scheduling.",
                            if (wifiOnlyRefreshSupported) {
                                "Background jobs use an unmetered network. Manual refresh uses the current connection."
                            } else {
                                "The Wi-Fi restriction applies to Android background jobs. Desktop refresh uses the current connection."
                            },
                        ),
                        onAutomatic,
                        onWifi,
                    )
                    TextButton(onClick = { onAdvanced(Page.SETTINGS) }) { Text("Appearance, privacy, backup and price settings") }
                }
                else -> Unit
            }
        }
    }
}

private fun identity(
    id: String,
    symbol: String?,
    name: String?,
) = ModelCoinIdentity(
    symbol ?: id,
    name ?: symbol ?: id,
    Color(0xFFF7931A),
    (
        symbol
            ?: id
    ).take(1),
    id,
)

@Composable
internal fun ModelAddDialog(
    data: PortfolioSnapshot,
    manual: Boolean,
    initialAsset: io.polion.core.model.AssetId?,
    onManual: () -> Unit,
    onClose: () -> Unit,
    onWallet: () -> Unit,
    onSupportedManual: (String) -> Unit,
) {
    val assets = (data.valuations + data.marketCoins.map { it.detailValuation() }).distinctBy { it.asset }
    var selected by rememberSaveable {
        mutableStateOf(
            initialAsset?.value ?: assets
                .firstOrNull()
                ?.asset
                ?.value
                .orEmpty(),
        )
    }
    var type by rememberSaveable { mutableStateOf(ModelTransactionType.BUY.name) }
    var date by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var fee by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    ModelTransactionDialog(
        ModelTransactionDialogState(
            manual,
            assets.map {
                ModelAssetOption(
                    it.asset.value,
                    it.metadata?.symbol ?: it.asset.value,
                    it.metadata?.name ?: it.asset.value,
                )
            },
            selected,
            ModelTransactionType.valueOf(type),
            "Unavailable",
            "Unavailable",
            date,
            price,
            amount,
            fee,
            notes,
            canSave = false,
        ),
        ModelTransactionDialogActions(onClose, onWallet, onManual, {}, { selected = it }, { type = it.name }, { field, value ->
            when (field) {
                ModelDraftField.DATE -> date = value
                ModelDraftField.PRICE -> price = value
                ModelDraftField.AMOUNT ->
                    amount =
                        value
                ; ModelDraftField.FEE -> fee = value
                ModelDraftField.NOTES -> notes = value
            }
        }, {}, onSupportedManual = { onSupportedManual(selected) }),
    )
}
