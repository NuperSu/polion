package io.polion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import io.polion.core.application.AccountingView
import io.polion.core.application.AssetListPreference
import io.polion.core.application.AssetSort
import io.polion.core.application.AssetValuation
import io.polion.core.application.HistoryPoint
import io.polion.core.application.MarketPricePoint
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.market.PriceStatus
import io.polion.core.model.AssetId
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@Composable
internal fun ModelWorkspace(
    app: PortfolioApplication,
    host: PlatformActions,
    dataRevision: Long,
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
    onCurrency: (AssetId) -> Unit,
    onAssetPreference: (AssetListPreference) -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onAdvanced: (Page) -> Unit,
    onRefreshDetails: () -> Unit,
    onSyncDetails: () -> Unit,
    onAutomatic: (Boolean) -> Unit,
    onWifi: (Boolean) -> Unit,
    onManual: (AssetValuation?) -> Unit,
) {
    var priceDetails by rememberSaveable { mutableStateOf(false) }
    var priceLoading by remember { mutableStateOf(false) }
    var priceError by remember { mutableStateOf(false) }
    var priceRetry by remember { mutableStateOf(0) }
    var accountingLoading by remember { mutableStateOf(false) }
    var accountingError by remember { mutableStateOf(false) }
    var explain by rememberSaveable { mutableStateOf(false) }
    var currencyMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var movementSummary by remember(app, selectedPortfolio, period) { mutableStateOf("Loading accepted movements…") }
    LaunchedEffect(explain, app, selectedPortfolio, period, dataRevision, chart) {
        val start = chart.firstOrNull()?.atMillis
        val end = chart.lastOrNull()?.atMillis
        if (explain && (start == null || end == null)) {
            movementSummary = "History boundaries are unavailable; contribution details cannot be established."
        }
        if (explain && start != null && end != null) {
            try {
                movementSummary =
                    withContext(host.dispatcher) {
                        portfolioMovementSummary(app.analytics(start, selectedPortfolio), app.analytics(end, selectedPortfolio))
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                movementSummary = "Movement details could not be loaded."
            }
        }
    }
    if (explain && snapshot != null) {
        AlertDialog(onDismissRequest = { explain = false }, title = { Text("Portfolio value") }, text = {
            Text(
                portfolioValueChange(chart, snapshot.currency, "Selected period") +
                    "\nValue changes include contributions, withdrawals and price movements; they are not investment returns." +
                    "\n${pricingSummary(
                        snapshot.valuations,
                    ).missing} unpriced positions are excluded. Saved history gaps remain unavailable." +
                    "\n$movementSummary\nAccepted local records · history coverage ${snapshot.coverage.name.lowercase()}",
            )
        }, confirmButton = { TextButton(onClick = { explain = false }) { Text("Close") } })
    }
    var tab by rememberSaveable { mutableStateOf(ModelCoinTab.GENERAL.name) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var coinRange by rememberSaveable { mutableStateOf("1D") }
    var accounting by remember(app, selectedPortfolio, snapshot?.currency) { mutableStateOf<AccountingView?>(null) }
    LaunchedEffect(app, selectedPortfolio, snapshot?.currency, selectedAsset?.asset, dataRevision) {
        if (selectedAsset != null && snapshot?.valuations?.any { it.asset == selectedAsset.asset } == true) {
            accountingLoading = true
            accountingError = false
            try {
                accounting = withContext(host.dispatcher) { app.accountingReport(selectedPortfolio) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                accountingError = true
            } finally {
                accountingLoading = false
            }
        }
    }
    var prices by remember(app, selectedAsset?.asset, snapshot?.currency, coinRange) {
        mutableStateOf<List<MarketPricePoint>>(emptyList())
    }
    LaunchedEffect(app, selectedAsset?.asset, snapshot?.currency, coinRange, dataRevision, priceRetry) {
        val asset = selectedAsset?.asset
        val quote = snapshot?.currency
        if (asset != null && quote != null) {
            val duration =
                when (coinRange) {
                    "1H" -> 3_600_000L
                    "1D" -> 86_400_000L
                    "1W" -> 7 * 86_400_000L
                    "3M" -> 90 * 86_400_000L
                    "1Y" -> 365 * 86_400_000L
                    else -> 30 * 86_400_000L
                }
            val end = app.evaluationTimeMillis()
            val start = (end - duration).coerceAtLeast(0)
            val span = end - start
            priceLoading = true
            priceError = false
            try {
                prices =
                    withContext(host.dispatcher) {
                        app.priceSeries(asset, quote, (0..60).map { start + span * it / 60 })
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                priceError = true
            } finally {
                priceLoading = false
            }
        }
    }
    if (priceDetails) {
        AlertDialog(onDismissRequest = { priceDetails = false }, title = { Text("Saved unit-price observations") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                prices.mapNotNull { it.price }.distinctBy { it.id }.sortedByDescending { it.validAtMillis }.forEach { observation ->
                    Text("${observation.price.value} ${observation.price.quoteAssetId.value} per unit")
                    val valid = kotlin.time.Instant.fromEpochMilliseconds(observation.validAtMillis)
                    val observedAt = kotlin.time.Instant.fromEpochMilliseconds(observation.observedAtMillis)
                    Text("${observation.providerId?.value ?: "Manual"} · valid $valid")
                    Text("Observed $observedAt · ${observation.id}")
                }
                if (prices.none { it.price != null }) Text("No eligible saved observations in this range.")
            }
        }, confirmButton = { TextButton(onClick = { priceDetails = false }) { Text("Close") } })
    }
    val observed =
        prices.map { point ->
            point
                .takeIf { it.priceStatus == PriceStatus.FRESH }
                ?.price
                ?.price
                ?.value
        }
    val observationCount =
        prices
            .filter { it.priceStatus == PriceStatus.FRESH }
            .mapNotNull { it.price?.id }
            .distinct()
            .size
    val observedNumbers = observed.map { it?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite) }
    val lowest = observedNumbers.filterNotNull().minOrNull()
    val highest = observedNumbers.filterNotNull().maxOrNull()
    val priceCoordinates =
        if (observationCount < 2) {
            emptyList()
        } else {
            observedNumbers.map { value ->
                if (value == null || lowest == null || highest == null) {
                    null
                } else if (lowest == highest) {
                    .5f
                } else {
                    ((value - lowest) / (highest - lowest)).toFloat()
                }
            }
        }
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
                        ) +
                            if (tracked) {
                                coinAccountingMetrics(accounting, asset)
                            } else {
                                listOf(
                                    ModelMetric("Total Gains", "—"),
                                    ModelMetric("Total Cost Basis", "—"),
                                    ModelMetric("Unrealized Gain", "—"),
                                    ModelMetric("Realized Gain", "—"),
                                )
                            },
                    breakdownExpanded = expanded,
                    tab = ModelCoinTab.valueOf(tab),
                    price = asset.price?.let { "${it.price.value} ${it.price.quoteAssetId.value}" } ?: "—",
                    change =
                        if (observationCount >= 2) {
                            unitPriceChange(observed.firstOrNull(), observed.lastOrNull(), coinRange)
                        } else {
                            "Price change · $coinRange · —"
                        },
                    priceSource =
                        asset.price?.let {
                            val observedAt =
                                kotlin.time.Instant
                                    .fromEpochMilliseconds(it.observedAtMillis)
                                    .toString()
                            val provider = if (it.manual) "Manual quote" else it.providerId?.value ?: "Provider"
                            "$provider · ${asset.priceStatus.name.lowercase()} · observed $observedAt"
                        } ?: "Price unavailable",
                    selectedRange = coinRange,
                    chartPoints = priceCoordinates,
                    chartTopLabel = observed.filterNotNull().maxOrNull()?.toString() ?: "—",
                    chartBottomLabel = observed.filterNotNull().minOrNull()?.toString() ?: "—",
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
                    enabledRanges = setOf("1H", "1D", "1W", "1M", "3M", "1Y"),
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
            if (ModelCoinTab.valueOf(tab) == ModelCoinTab.GENERAL) {
                Text(
                    "Saved as-of unit prices · ${prices.mapNotNull { it.price?.id }.distinct().size} observations",
                    modifier =
                        Modifier.testTag(
                            if (!priceLoading &&
                                prices.isNotEmpty()
                            ) {
                                "coin-price-history-ready"
                            } else {
                                "coin-price-history-loading"
                            },
                        ),
                )
                Text("Missing or stale prices remain gaps.")
                if (observationCount < 2) Text("At least two saved observations are needed to resolve movement in this range.")
                if (priceLoading) Text("Loading saved price history…")
                if (priceError) TextButton(onClick = { priceRetry++ }) { Text("Retry saved price history") }
                TextButton(onClick = { priceDetails = true }) { Text("Price observations") }
                if (tracked && accountingLoading) Text("Updating cost basis and gains…")
                if (tracked && accountingError) Text("Accounting refresh failed; saved figures are retained.")
                if (tracked) {
                    TextButton(
                        onClick = { onAdvanced(Page.REPORTS) },
                        modifier =
                            Modifier.testTag(
                                if (accounting != null &&
                                    !accountingLoading
                                ) {
                                    "coin-accounting-ready"
                                } else {
                                    "coin-accounting-loading"
                                },
                            ),
                    ) {
                        Text("Accounting evidence")
                    }
                }
            }
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
                    val labels = mapOf(-1 to "1H", 1 to "1D", 7 to "1W", 30 to "1M", 90 to "3M", -2 to "YTD", 365 to "1Y", 0 to "ALL")
                    ModelPortfolioSummary(
                        ModelPortfolioSummaryState(
                            balance = summary.total?.let { money(it, data.currency) } ?: "—",
                            currency = data.currency.value,
                            change = portfolioValueChange(chart, data.currency, labels[period] ?: "Selected period"),
                            percentChange = portfolioValuePercent(chart),
                            selectedRange = labels[period] ?: "",
                            chartPoints = points,
                            enabledRanges = labels.values.toSet(),
                            ranges = labels.values.toList(),
                            chartTopLabel = exactValues.filterNotNull().maxOrNull()?.let { money(it, data.currency) } ?: "—",
                            chartBottomLabel = exactValues.filterNotNull().minOrNull()?.let { money(it, data.currency) } ?: "—",
                        ),
                        wide,
                        short,
                        { label -> labels.entries.find { it.value == label }?.let { onPeriod(it.key) } },
                        onExplain = { explain = true },
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
                    Text("Portfolio value change includes deposits and withdrawals; it is not investment return.")
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
                                    "$quantity $symbol · ${asset.price?.price?.value ?: "—"} ${data.currency.value}",
                                    asset.value?.let { money(it, data.currency) } ?: "—",
                                    portfolioValueDelta(assetHistory, data.currency),
                                    portfolioValuePercent(assetHistory),
                                )
                            },
                            if (assetPreference.sort == AssetSort.VALUE) "Highest Holdings" else "Name",
                        ),
                        onHolding = { coin -> onAsset(data.valuations.find { it.asset.value == coin.id }) },
                        onSort = { sortMenu = true },
                        onView = { filterMenu = true },
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
