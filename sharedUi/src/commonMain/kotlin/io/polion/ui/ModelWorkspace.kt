package io.polion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.polion.core.alerts.AlertCondition
import io.polion.core.application.AccountingView
import io.polion.core.application.ActivityFilter
import io.polion.core.application.AssetListPreference
import io.polion.core.application.AssetSort
import io.polion.core.application.AssetValuation
import io.polion.core.application.HistoryPoint
import io.polion.core.application.MarketOverview
import io.polion.core.application.MarketPricePoint
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.PortfolioSnapshot
import io.polion.core.ledger.ActivityStatus
import io.polion.core.market.EvmPlatform
import io.polion.core.market.PriceStatus
import io.polion.core.model.AssetId
import io.polion.core.model.PortfolioId
import io.polion.core.reconcile.EffectiveActivity
import io.polion.ui.model.ChartData
import io.polion.ui.model.ChartStyle
import io.polion.ui.model.LocalCoinLogos
import io.polion.ui.model.ModelAlertEditor
import io.polion.ui.model.ModelAlertEditorState
import io.polion.ui.model.ModelAllocationBar
import io.polion.ui.model.ModelAllocationSlice
import io.polion.ui.model.ModelChainRow
import io.polion.ui.model.ModelChangeTone
import io.polion.ui.model.ModelCoinDetail
import io.polion.ui.model.ModelCoinDetailActions
import io.polion.ui.model.ModelCoinDetailState
import io.polion.ui.model.ModelCoinIdentity
import io.polion.ui.model.ModelCoinTab
import io.polion.ui.model.ModelHoldingRow
import io.polion.ui.model.ModelInsights
import io.polion.ui.model.ModelInsightsAction
import io.polion.ui.model.ModelMain
import io.polion.ui.model.ModelMainAction
import io.polion.ui.model.ModelMetric
import io.polion.ui.model.ModelPage
import io.polion.ui.model.ModelPortfolioHoldings
import io.polion.ui.model.ModelPortfolioHoldingsState
import io.polion.ui.model.ModelPortfolioStats
import io.polion.ui.model.ModelPortfolioSummary
import io.polion.ui.model.ModelPortfolioSummaryState
import io.polion.ui.model.ModelSettings
import io.polion.ui.model.ModelSettingsState
import io.polion.ui.model.ModelShell
import io.polion.ui.model.ModelShellState
import io.polion.ui.model.ModelStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
    showPortfolioChart: Boolean = true,
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
    onConnectBitcoin: () -> Unit = onAdd,
    onAccountChanged: () -> Unit = {},
    /** Reports a change to hidden or shown tokens; the message is shown to the user. */
    onAssetChanged: (String) -> Unit = {},
    onAccountTool: (AccountTool) -> Unit = {},
    onTransaction: (EffectiveActivity) -> Unit = {},
    statusContent: @Composable () -> Unit = {},
    onMarketRefresh: (maxAgeMillis: Long) -> Unit = {},
    onToggleAmounts: () -> Unit = {},
    chartStyle: ChartStyle = ChartStyle.LINE,
    onChartStyle: (ChartStyle) -> Unit = {},
    settingsContent: (@Composable ColumnScope.(onAdvanced: (Page) -> Unit) -> Unit)? = null,
) {
    val transactionScope = rememberCoroutineScope()
    val amountsHidden = LocalAmountsHidden.current
    // Asset IDs that make up the selected coin, and chain names for its per-chain breakdown.
    var coinAssets by remember(app) { mutableStateOf<Set<AssetId>>(emptySet()) }
    var chainChoices by remember(app) { mutableStateOf<List<EvmPlatform>>(emptyList()) }
    var hiddenReview by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(app, dataRevision, selectedAsset?.asset) {
        val coin = selectedAsset?.asset
        if (coin == null) {
            coinAssets = emptySet()
            return@LaunchedEffect
        }
        val (ids, choices) =
            withContext(host.dispatcher) {
                val coinId = app.marketCoinId(coin)
                (coinId?.let { app.assetsForCoin(it) }?.toSet() ?: emptySet()) to app.evmChainChoices()
            }
        coinAssets = ids
        chainChoices = choices
    }
    var marketOverview by remember(app) { mutableStateOf<MarketOverview?>(null) }
    LaunchedEffect(app, dataRevision, snapshot?.currency) {
        marketOverview =
            try {
                withContext(host.dispatcher) { app.marketOverview() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                marketOverview
            }
    }
    // With Auto-sync on, opening Main refreshes market lists older than the scheduled age, once per launch.
    var marketOpenChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(page, marketOverview, preferences.automaticRefresh) {
        val overview = marketOverview
        if (page == Page.HOME && overview != null && !marketOpenChecked && overview.consent && preferences.automaticRefresh) {
            marketOpenChecked = true
            val observed = overview.markets?.observedAtMillis
            val now =
                kotlin.time.Clock.System
                    .now()
                    .toEpochMilliseconds()
            if (observed == null || now - observed >= MARKET_SCHEDULED_AGE_MILLIS) onMarketRefresh(MARKET_SCHEDULED_AGE_MILLIS)
        }
    }
    // Coin pages load alerts and consent on the host dispatcher. A load for another asset is ignored, so the page never
    // shows the previous coin's alerts.
    val selectedCoin = selectedAsset?.asset
    var coinMarket by remember { mutableStateOf<CoinMarketLoad?>(null) }
    var coinReload by remember { mutableStateOf(0) }
    var alertEditor by remember(selectedCoin) { mutableStateOf<ModelAlertEditorState?>(null) }
    var alertNotice by remember(selectedCoin) { mutableStateOf<String?>(null) }
    var orderNotice by remember(selectedCoin) { mutableStateOf<String?>(null) }
    LaunchedEffect(app, selectedCoin, dataRevision, coinReload) {
        if (selectedCoin != null) {
            try {
                coinMarket = loadCoinMarket(app, host, selectedCoin)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keeps the previous load; the Alerts tab stays empty until a later load succeeds.
            }
        }
    }

    /** Runs one change on the host dispatcher, then reloads the coin page. A failure is passed on, never dropped. */
    fun changeCoin(
        onDone: () -> Unit = {},
        onFailure: (Exception) -> Unit,
        change: () -> Unit,
    ) {
        transactionScope.launch {
            try {
                withContext(host.dispatcher) { change() }
                coinReload++
                onDone()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
            }
        }
    }

    fun updateEditor(update: (ModelAlertEditorState) -> ModelAlertEditorState) {
        alertEditor = alertEditor?.let(update)
    }

    fun saveAlert(
        coinId: String,
        editor: ModelAlertEditorState,
    ) {
        val threshold =
            try {
                parseAlertThreshold(editor.threshold)
            } catch (invalid: IllegalArgumentException) {
                alertEditor = editor.copy(error = invalid.message)
                return
            }
        alertEditor = editor.copy(saving = true, error = null)
        changeCoin(
            onDone = {
                alertEditor = null
                alertNotice = null
                host.notificationSettingsChanged()
            },
            onFailure = { failure ->
                alertEditor =
                    editor.copy(error = (failure as? IllegalArgumentException)?.message ?: "Could not save this alert. Try again.")
            },
        ) { app.savePriceAlert(coinId, editor.condition, threshold, editor.alertId) }
    }

    fun setAlertEnabled(
        id: String,
        enabled: Boolean,
    ) {
        changeCoin(
            onDone = {
                alertNotice = null
                host.notificationSettingsChanged()
            },
            onFailure = { alertNotice = "Could not change this alert. Try again." },
        ) { app.setPriceAlertEnabled(id, enabled) }
    }

    fun deleteAlert(
        editor: ModelAlertEditorState,
        id: String,
    ) {
        alertEditor = editor.copy(saving = true, error = null)
        changeCoin(
            onDone = {
                alertEditor = null
                alertNotice = null
                host.notificationSettingsChanged()
            },
            onFailure = { alertEditor = editor.copy(error = "Could not delete this alert. Try again.") },
        ) { app.deletePriceAlert(id) }
    }
    var transactionSearch by rememberSaveable { mutableStateOf("") }
    var transactionType by rememberSaveable { mutableStateOf("All") }
    var transactionStatus by rememberSaveable { mutableStateOf("All") }
    var transactionOffset by rememberSaveable { mutableStateOf(0) }
    var savedTransactionScope by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val activeTransactionScope = selectedAsset?.let { listOf(it.asset.value, selectedPortfolio?.value.orEmpty()) }
    LaunchedEffect(activeTransactionScope) {
        if (activeTransactionScope != null) {
            if (savedTransactionScope != null && savedTransactionScope != activeTransactionScope) {
                transactionSearch = ""
                transactionType = "All"
                transactionStatus = "All"
                transactionOffset = 0
            }
            savedTransactionScope = activeTransactionScope
        }
    }
    val timelineFilter =
        ActivityFilter(
            search = transactionSearch,
            portfolio = selectedPortfolio,
            asset = selectedAsset?.asset,
            offset = transactionOffset,
            limit = 20,
            status =
                transactionStatus
                    .takeIf {
                        it !=
                            "All"
                    }?.let(ActivityStatus::valueOf),
        )
    var timeline by remember(
        app,
        selectedAsset?.asset,
        selectedPortfolio,
        snapshot?.currency,
        transactionSearch,
        transactionType,
        transactionStatus,
        transactionOffset,
    ) {
        mutableStateOf<TransactionTimeline?>(null)
    }
    var timelineError by remember { mutableStateOf(false) }
    var timelineRetry by remember { mutableStateOf(0) }
    var timelineLoading by remember { mutableStateOf(false) }
    var transactionExplanation by rememberSaveable { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(app, timelineFilter, transactionType, dataRevision, snapshot, timelineRetry) {
        val data = snapshot
        if (selectedAsset != null && data != null) {
            timelineLoading = true
            timelineError = false
            try {
                timeline = withContext(host.dispatcher) { transactionTimeline(app, data, timelineFilter, transactionType) }
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (_: Exception) {
                timelineError = true
            } finally {
                timelineLoading = false
            }
        }
    }
    if (transactionExplanation) {
        AlertDialog(onDismissRequest = { transactionExplanation = false }, title = { Text("Transaction values") }, text = {
            Text(
                "Buy and sell averages divide explicit total consideration by executed base quantity. They exclude separate fees and unconfirmed/reversed records. Only trades with valid evidence in the selected quote qualify; partial coverage is labeled. Worth Now uses fresh current quotes; Worth Then uses retained execution evidence or fresh saved historical quotes. Both value the displayed gross transaction quantity, not investment return. Missing counterparties, acquisition costs or quotes remain unknown.",
            )
        }, confirmButton = { TextButton(onClick = { transactionExplanation = false }) { Text("Close") } })
    }
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
    // The same report feeds Portfolio performance, Insights gains and an owned coin's cost and gains.
    LaunchedEffect(app, selectedPortfolio, snapshot?.currency, selectedAsset?.asset, dataRevision, page) {
        val ownedCoin = selectedAsset != null && snapshot?.valuations?.any { it.asset == selectedAsset.asset } == true
        if (ownedCoin || ((page == Page.OVERVIEW || page == Page.REPORTS) && selectedAsset == null && snapshot != null)) {
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
    var worthAsset by rememberSaveable { mutableStateOf<String?>(null) }
    var insightsData by remember(app, selectedPortfolio) { mutableStateOf<InsightsData?>(null) }
    val rangeStart = chart.firstOrNull()?.atMillis
    val rangeEnd = chart.lastOrNull()?.atMillis
    // Insights calculations follow the history range on screen, so every section covers the same period.
    LaunchedEffect(app, page, selectedPortfolio, rangeStart, rangeEnd, dataRevision) {
        if (page == Page.REPORTS && rangeStart != null && rangeEnd != null && rangeEnd > rangeStart) {
            insightsData =
                withContext(host.dispatcher) {
                    InsightsData(
                        runCatching { app.moneyWeightedReturn(selectedPortfolio, rangeStart, rangeEnd) }.getOrNull(),
                        runCatching { app.activityInsights(selectedPortfolio, rangeStart, rangeEnd) }.getOrNull(),
                        runCatching { app.analytics(rangeEnd, selectedPortfolio) }.getOrNull(),
                    )
                }
        }
    }
    var prices by remember(app, selectedAsset?.asset, snapshot?.currency, coinRange) {
        mutableStateOf<List<MarketPricePoint>>(emptyList())
    }
    LaunchedEffect(app, selectedAsset?.asset, snapshot?.currency, coinRange, dataRevision, priceRetry) {
        val asset = selectedAsset?.asset
        val quote = snapshot?.currency
        // Market-only coins have no saved prices; their page uses the CoinGecko listing instead.
        if (asset != null && quote != null && !asset.value.startsWith("coingecko:")) {
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
                    Text("${quotePrice(observation.price.value, observation.price.quoteAssetId)} per unit")
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
    val logoLoader = remember(app, host) { CoinLogoLoader(app, host.dispatcher) }
    // Logos load only from feeds fetched with market data consent; each coin's page and rows share one image, and linked
    // EVM assets use their coin's logo.
    val logoUrls =
        remember(marketOverview) {
            val overview = marketOverview
            if (overview == null || !overview.consent) {
                emptyMap()
            } else {
                (
                    overview.trending?.items.orEmpty().mapNotNull { coin ->
                        coin.image?.let { overview.assetKeys[coin.coinId]?.value to it }
                    } +
                        overview.markets?.items.orEmpty().mapNotNull { coin ->
                            coin.image?.let {
                                overview.assetKeys[coin.coinId]?.value to
                                    it
                            }
                        }
                ).mapNotNull { (key, url) -> key?.let { it to url } }.toMap() +
                    overview.assetLogos.mapKeys { it.key.value }
            }
        }
    if (hiddenReview) {
        snapshot?.let { data ->
            HiddenAssetsDialog(app, host, data, onAssetChanged) { hiddenReview = false }
        }
    }
    CompositionLocalProvider(LocalCoinLogos provides logoLoader.source(logoUrls)) {
        ModelShell(
            ModelShellState(
                modelPage,
                title,
                showBack = selectedAsset != null || page == Page.ACCOUNTS || page == Page.SETTINGS,
                refreshing = refreshing,
                refreshQueued = refreshQueued,
                menuItems = listOf("Settings"),
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
                    "Settings" -> onPage(Page.SETTINGS)
                    else -> onRefreshDetails()
                }
            },
            onRefresh = onRefresh,
            onAdd = if (page == Page.OVERVIEW && selectedAsset == null) onAdd else null,
            statusContent = statusContent,
        ) { wide, short ->
            val data = snapshot
            if (data?.sampleData == true) {
                Text(
                    "EXAMPLE DATA · stored only on this device",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (page != Page.ACCOUNTS && shouldShowSyncBanner(syncStatus, data?.sourceStatus)) {
                SyncBanner(syncStatus, data?.sourceStatus, onSyncDetails)
            }
            if (data == null) {
                Text("Opening your portfolio")
            } else if (selectedAsset != null) {
                val asset = selectedAsset
                val symbol = asset.metadata?.symbol ?: asset.asset.value
                val tracked = data.valuations.any { it.asset == asset.asset }
                // Held assets of this coin on other chains, such as ETH on Base next to ETH on Ethereum.
                val coinHoldings =
                    if (tracked) {
                        data.valuations.filter {
                            it.asset == asset.asset || it.asset in coinAssets
                        }
                    } else {
                        emptyList()
                    }
                val coinSummary = if (coinHoldings.size > 1) coinTotals(coinHoldings, data.currency, chainChoices) else null
                val listing =
                    marketOverview?.let { overview -> overview.markets?.items?.find { overview.assetKeys[it.coinId] == asset.asset } }
                // Without two saved observations, the CoinGecko 7-day sparkline draws the chart.
                val useListing =
                    listing != null && marketOverview?.currency == data.currency && observationCount < 2 && listing.sparkline.size >= 2
                val listingPoints = if (useListing) sparklinePoints(listing.sparkline, 61) else emptyList()
                // Prices are public, so hidden amounts never mask this chart.
                val coinChart =
                    remember(useListing, listing, prices, data.currency, marketOverview?.markets?.observedAtMillis) {
                        if (useListing) {
                            val observedAt = marketOverview?.markets?.observedAtMillis ?: 0L
                            val count = listing.sparkline.size
                            ChartData(
                                listing.sparkline,
                                List(count) { observedAt - (count - 1 - it) * 3_600_000L },
                                { sparklinePrice(it, data.currency) },
                                { chartTime(it, 7 * 86_400_000L) },
                            )
                        } else if (observationCount >= 2) {
                            val span = (prices.lastOrNull()?.atMillis ?: 0L) - (prices.firstOrNull()?.atMillis ?: 0L)
                            ChartData(
                                observedNumbers,
                                prices.map { it.atMillis },
                                { sparklinePrice(it, data.currency) },
                                { chartTime(it, span) },
                            )
                        } else {
                            null
                        }
                    }
                val coinLoad = coinMarket?.takeIf { it.asset == asset.asset }
                val coinId = coinLoad?.coinId
                // Alerts and order books need the coin in the cached list for the display currency.
                val listedCoin =
                    coinId
                        ?.let { id -> marketOverview?.markets?.items?.find { it.coinId == id } }
                        ?.takeIf { marketOverview?.currency == data.currency }
                val alertBlock = coinLoad?.let { alertBlockReason(coinId, it.marketConsent, listedCoin != null) }
                val orderConsent = coinLoad?.orderConsent == true
                val orderBook =
                    rememberCoinOrderBook(
                        host,
                        coinId,
                        active = orderConsent && ModelCoinTab.valueOf(tab) == ModelCoinTab.ORDER_BOOK,
                    )
                val orderBookData = orderBook.book
                val quantity =
                    if (!tracked) {
                        "—"
                    } else if (coinSummary != null) {
                        coinSummary.quantity?.let { "${formatUnits(it.coefficient, it.scale)} $symbol" } ?: "—"
                    } else {
                        asset.metadata?.let { "${formatUnits(asset.amount.atomicUnits, it.decimals)} ${it.symbol}" }
                            ?: "${asset.amount.atomicUnits} atomic units"
                    }
                ModelCoinDetail(
                    ModelCoinDetailState(
                        ticker = symbol,
                        mark = symbol.take(1),
                        logoColor = identity(asset.asset.value, symbol, null).color,
                        coinId = asset.asset.value,
                        portfolio =
                            data.ownership.portfolios
                                .find { it.id == selectedPortfolio }
                                ?.label ?: "All portfolios",
                        metrics =
                            listOf(
                                ModelMetric("Owned", quantity.hiddenIf(amountsHidden)),
                                ModelMetric(
                                    "Market Value",
                                    (
                                        when {
                                            !tracked -> "—"
                                            coinSummary != null -> coinSummary.value?.let { money(it, data.currency) } ?: "—"
                                            else -> asset.value?.let { money(it, data.currency) } ?: "—"
                                        }
                                    ).hiddenIf(amountsHidden),
                                ),
                            ) +
                                if (tracked) {
                                    // Gains and cost basis are amounts; a percentage would stay visible.
                                    coinAccountingMetrics(accounting, asset).map {
                                        if (it.value.endsWith("%")) it else it.copy(value = it.value.hiddenIf(amountsHidden))
                                    }
                                } else if (listing != null) {
                                    listOf(
                                        ModelMetric("24h Change", percentChange(listing.change24h)),
                                        ModelMetric("7d Change", percentChange(listing.change7d)),
                                        ModelMetric("Market Cap", listing.marketCap?.let { compactMoney(it, data.currency) } ?: "—"),
                                        ModelMetric("Market Cap Rank", listing.rank?.let { "#$it" } ?: "—"),
                                    )
                                } else {
                                    listOf(
                                        ModelMetric("Total Gains", "—"),
                                        ModelMetric("Total Cost Basis", "—"),
                                        ModelMetric("Unrealized Gain", "—"),
                                        ModelMetric("Realized Gain", "—"),
                                    )
                                },
                        chainRows =
                            coinSummary
                                ?.chains
                                ?.map {
                                    ModelChainRow(it.chain, it.quantity.hiddenIf(amountsHidden), it.value.hiddenIf(amountsHidden))
                                }.orEmpty(),
                        breakdownExpanded = expanded,
                        tab = ModelCoinTab.valueOf(tab),
                        price =
                            asset.price?.let {
                                if (asset.asset.value.startsWith("coingecko:")) {
                                    marketPrice(it.price.value, it.price.quoteAssetId)
                                } else {
                                    quotePrice(it.price.value, it.price.quoteAssetId)
                                }
                            } ?: "—",
                        change =
                            if (useListing) {
                                "Price change · 7D · ${percentChange(listing?.change7d)}"
                            } else if (observationCount >= 2) {
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
                        selectedRange = if (useListing) "1W" else coinRange,
                        chartPoints = if (useListing) listingPoints else priceCoordinates,
                        chartTopLabel =
                            if (useListing) {
                                listing?.sparkline?.maxOrNull()?.let { sparklinePrice(it, data.currency) } ?: "—"
                            } else {
                                observed.filterNotNull().maxOrNull()?.let { quotePrice(it, data.currency) } ?: "—"
                            },
                        chartBottomLabel =
                            if (useListing) {
                                listing?.sparkline?.minOrNull()?.let { sparklinePrice(it, data.currency) } ?: "—"
                            } else {
                                observed.filterNotNull().minOrNull()?.let { quotePrice(it, data.currency) } ?: "—"
                            },
                        transactionMetrics =
                            timeline?.metrics
                                ?: listOf(
                                    ModelMetric("Avg. Buy Price", "—"),
                                    ModelMetric("Avg. Sell Price", "—"),
                                    ModelMetric("Transactions", "—"),
                                ),
                        transactions =
                            timeline?.cards.orEmpty().map { card ->
                                if (!amountsHidden) {
                                    card
                                } else {
                                    card.copy(
                                        amount = HIDDEN_AMOUNT,
                                        fee = card.fee.hiddenIf(true),
                                        worthNow = card.worthNow.hiddenIf(true),
                                        worthThen = card.worthThen.hiddenIf(true),
                                    )
                                }
                            },
                        alerts = coinLoad?.alerts.orEmpty().map(::coinAlertRule),
                        orderSource =
                            when {
                                orderBookData != null -> orderBookSource(orderBookData)
                                coinId != null && orderConsent && orderBook.noMarket == null && orderBook.issue == null ->
                                    "Loading Kraken order book…"
                                else -> ""
                            },
                        orderRows = orderBookRows(orderBookData),
                        enabledRanges = if (useListing) setOf("1W") else setOf("1H", "1D", "1W", "1M", "3M", "1Y"),
                        canAddTransaction = !asset.asset.value.startsWith("coingecko:"),
                        chart = coinChart,
                        chartStyle = chartStyle,
                        transactionEmpty = if (timelineLoading) "Loading saved transactions…" else "No matching transactions",
                        alertsUnavailable = alertBlock,
                        alertNotice = alertNotice,
                        canAddAlert = coinLoad != null && alertBlock == null,
                        orderUnavailable =
                            when {
                                coinLoad == null -> null
                                coinId == null -> "Order books are available for coins with market data."
                                else -> orderBook.noMarket
                            },
                        orderConsentNeeded = coinId != null && coinLoad?.orderConsent == false,
                        orderIssue = orderBook.issue,
                        orderOutOfDate = orderBook.outOfDate,
                        orderNotice = orderNotice,
                        changeTone = ModelChangeTone.NEUTRAL,
                    ),
                    ModelCoinDetailActions(
                        onBreakdown = { expanded = it },
                        onTab = { tab = it.name },
                        onRange = { coinRange = it },
                        onChartStyle = onChartStyle,
                        onAddTransaction = { onManual(asset) },
                        onTransaction = { id -> timeline?.entries?.find { it.activity.id.value == id }?.let(onTransaction) },
                        onAddAlert = {
                            alertEditor =
                                ModelAlertEditorState(
                                    alertId = null,
                                    currency = data.currency.value,
                                    currentPrice = listedCoin?.let { marketPrice(it.price, data.currency) },
                                    condition = AlertCondition.ABOVE,
                                    threshold = "",
                                )
                        },
                        onAlertOpen = { id ->
                            coinLoad?.alerts?.find { it.id == id }?.let { alert ->
                                alertEditor =
                                    ModelAlertEditorState(
                                        alertId = alert.id,
                                        currency = data.currency.value,
                                        currentPrice = listedCoin?.let { marketPrice(it.price, data.currency) },
                                        condition = alert.condition,
                                        threshold = alert.threshold.toString(),
                                    )
                            }
                        },
                        onAlertEnabled = ::setAlertEnabled,
                        onOrderConsent = {
                            changeCoin(
                                onDone = { orderNotice = null },
                                onFailure = { orderNotice = "Could not turn on Kraken order books. Try again." },
                            ) { app.setOrderBookConsent(true) }
                        },
                        onOrderRetry = orderBook.retry,
                    ),
                    transactionControls = {
                        OutlinedTextField(transactionSearch, {
                            transactionSearch = it
                            transactionOffset = 0
                        }, label = { Text("Search transactions") }, modifier = Modifier.fillMaxWidth().testTag("coin-transaction-search"))
                        TransactionChoice(
                            "Transaction type",
                            listOf("All", "Buy", "Sell", "Transfer", "Deposit", "Withdrawal", "Reward", "Fee", "Adjustment", "Other").map {
                                it to
                                    it
                            },
                            transactionType,
                        ) {
                            transactionType = it
                            transactionOffset = 0
                        }
                        TransactionChoice(
                            "Status",
                            listOf("All" to "All") + ActivityStatus.entries.map { it.name to it.name.lowercase() },
                            transactionStatus,
                        ) {
                            transactionStatus =
                                it
                            ; transactionOffset = 0
                        }
                        TextButton(onClick = { transactionExplanation = true }) { Text("How transaction values are calculated") }
                        if (timelineLoading) Text("Updating saved transactions…")
                        if (timelineError) TextButton(onClick = { timelineRetry++ }) { Text("Retry transactions") }
                        TextButton(onClick = {
                            val exportFilter = timelineFilter
                            val exportType = transactionType
                            transactionScope.launch {
                                try {
                                    withContext(host.dispatcher) {
                                        val accounts = app.transactionAccounts(exportFilter.portfolio)
                                        val ids =
                                            app
                                                .transactionEntries(exportFilter)
                                                .filter {
                                                    exportType == "All" ||
                                                        transactionKind(it, asset.asset, accounts) == exportType
                                                }.map { it.activity.id }
                                                .toSet()
                                        val csv = app.exportCsv(exportFilter, ids)
                                        host.saveFile("polion-${asset.asset.value}-transactions.csv", csv.encodeToByteArray()) {
                                            exportMessage =
                                                it
                                        }
                                    }
                                } catch (
                                    cancelled: CancellationException,
                                ) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    exportMessage = "Could not export transactions. Try again."
                                }
                            }
                        }) { Text("Export matching transactions") }
                        exportMessage?.let { Text(it) }
                    },
                    transactionFooter = {
                        Text(
                            "${if (timeline?.total == 0) 0 else transactionOffset + 1}–${minOf(
                                transactionOffset + (timeline?.cards?.size ?: 0),
                                timeline?.total ?: 0,
                            )} of ${timeline?.total ?: 0}",
                        )
                        TextButton(enabled = transactionOffset > 0, onClick = {
                            transactionOffset = maxOf(0, transactionOffset - 20)
                        }) { Text("Previous transactions") }
                        TextButton(
                            enabled = transactionOffset + 20 < (timeline?.total ?: 0),
                            onClick = { transactionOffset += 20 },
                        ) { Text("More transactions") }
                    },
                )
                alertEditor?.let { editor ->
                    ModelAlertEditor(
                        state = editor,
                        onCondition = { condition -> updateEditor { it.copy(condition = condition, error = null) } },
                        onThreshold = { text -> updateEditor { it.copy(threshold = text, error = null) } },
                        onSave = { coinId?.let { saveAlert(it, editor) } },
                        onDelete = { editor.alertId?.let { deleteAlert(editor, it) } },
                        onCancel = { alertEditor = null },
                    )
                }
                if (ModelCoinTab.valueOf(tab) == ModelCoinTab.GENERAL && asset.asset.value.startsWith("coingecko:")) {
                    Text(
                        "Price, 7-day chart and market figures from CoinGecko, updated " +
                            (marketOverview?.markets?.observedAtMillis?.let(::relativeAgo) ?: "earlier") +
                            ". Polion records transactions for Bitcoin only.",
                        modifier = Modifier.testTag("coin-market-source"),
                    )
                } else if (ModelCoinTab.valueOf(tab) == ModelCoinTab.GENERAL) {
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
            } else {
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
                                                io.polion.core.model.Rational
                                                    .fromDecimal(it),
                                                data.currency,
                                            )
                                        } ?: "—"
                                    ).hiddenIf(amountsHidden),
                                changeTone =
                                    tone(
                                        delta?.let {
                                            io.polion.core.model.Rational
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
                            fun amount(value: io.polion.core.model.Rational?) =
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
                                                        io.polion.core.model.Rational
                                                            .fromDecimal(last - first),
                                                    )
                                                } else {
                                                    null
                                                }
                                            }
                                        } ?: io.polion.ui.model.ModelChangeTone.NEUTRAL,
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
        }
    }
}

/** Bitcoin keeps its orange mark; other coins get a stable colour derived from their ID. */
internal fun identity(
    id: String,
    symbol: String?,
    name: String?,
) = ModelCoinIdentity(
    symbol ?: id,
    name ?: symbol ?: id,
    if (symbol == "BTC") Color(0xFFF7931A) else Color.hsv((id.hashCode().mod(360)).toFloat(), .55f, .8f),
    (
        symbol
            ?: id
    ).take(1),
    id,
)
