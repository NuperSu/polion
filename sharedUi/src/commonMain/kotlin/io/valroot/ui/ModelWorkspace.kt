package io.valroot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import io.valroot.core.application.AccountingView
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.AssetListPreference
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.HistoryPoint
import io.valroot.core.application.MarketOverview
import io.valroot.core.application.MarketPricePoint
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.market.EvmPlatform
import io.valroot.core.model.AssetId
import io.valroot.core.model.PortfolioId
import io.valroot.core.reconcile.EffectiveActivity
import io.valroot.ui.model.ChartStyle
import io.valroot.ui.model.LocalCoinLogos
import io.valroot.ui.model.ModelAlertEditorState
import io.valroot.ui.model.ModelCoinIdentity
import io.valroot.ui.model.ModelCoinTab
import io.valroot.ui.model.ModelPage
import io.valroot.ui.model.ModelShell
import io.valroot.ui.model.ModelShellState
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
    assetPreference: io.valroot.core.application.AssetListPreference,
    showPortfolioChart: Boolean = true,
    refreshing: Boolean,
    refreshQueued: Boolean,
    syncStatus: SyncStatus,
    favorites: Set<io.valroot.core.model.AssetId>,
    onFavorite: (io.valroot.core.model.AssetId) -> Unit,
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
    val coinAssetsState = remember(app) { mutableStateOf<Set<AssetId>>(emptySet()) }
    var coinAssets by coinAssetsState
    val chainChoicesState = remember(app) { mutableStateOf<List<EvmPlatform>>(emptyList()) }
    var chainChoices by chainChoicesState
    val hiddenReviewState = rememberSaveable { mutableStateOf(false) }
    var hiddenReview by hiddenReviewState
    LaunchedEffect(app, dataRevision, selectedAsset?.asset) {
        val coin = selectedAsset?.asset
        if (coin == null) {
            coinAssets = emptySet()
            return@LaunchedEffect
        }
        val (ids, choices) =
            withContext(host.dispatcher) {
                val coinId = app.market.coinId(coin)
                (coinId?.let { app.assets.forCoin(it) }?.toSet() ?: emptySet()) to app.evm.chainChoices()
            }
        coinAssets = ids
        chainChoices = choices
    }
    val marketOverviewState = remember(app) { mutableStateOf<MarketOverview?>(null) }
    var marketOverview by marketOverviewState
    LaunchedEffect(app, dataRevision, snapshot?.currency) {
        marketOverview =
            try {
                withContext(host.dispatcher) { app.market.overview() }
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
    val coinMarketState = remember { mutableStateOf<CoinMarketLoad?>(null) }
    var coinMarket by coinMarketState
    var coinReload by remember { mutableStateOf(0) }
    val alertEditorState = remember(selectedCoin) { mutableStateOf<ModelAlertEditorState?>(null) }
    var alertEditor by alertEditorState
    val alertNoticeState = remember(selectedCoin) { mutableStateOf<String?>(null) }
    var alertNotice by alertNoticeState
    val orderNoticeState = remember(selectedCoin) { mutableStateOf<String?>(null) }
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
        ) { app.notifications.saveAlert(coinId, editor.condition, threshold, editor.alertId) }
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
        ) { app.notifications.setAlertEnabled(id, enabled) }
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
        ) { app.notifications.deleteAlert(id) }
    }
    val transactionSearchState = rememberSaveable { mutableStateOf("") }
    var transactionSearch by transactionSearchState
    val transactionTypeState = rememberSaveable { mutableStateOf("All") }
    var transactionType by transactionTypeState
    val transactionStatusState = rememberSaveable { mutableStateOf("All") }
    var transactionStatus by transactionStatusState
    val transactionOffsetState = rememberSaveable { mutableStateOf(0) }
    var transactionOffset by transactionOffsetState
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
    val timelineState =
        remember(
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
    var timeline by timelineState
    val timelineErrorState = remember { mutableStateOf(false) }
    var timelineError by timelineErrorState
    val timelineRetryState = remember { mutableStateOf(0) }
    var timelineRetry by timelineRetryState
    val timelineLoadingState = remember { mutableStateOf(false) }
    var timelineLoading by timelineLoadingState
    val transactionExplanationState = rememberSaveable { mutableStateOf(false) }
    var transactionExplanation by transactionExplanationState
    val exportMessageState = remember { mutableStateOf<String?>(null) }
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
    val priceDetailsState = rememberSaveable { mutableStateOf(false) }
    var priceDetails by priceDetailsState
    val priceLoadingState = remember { mutableStateOf(false) }
    var priceLoading by priceLoadingState
    val priceErrorState = remember { mutableStateOf(false) }
    var priceError by priceErrorState
    val priceRetryState = remember { mutableStateOf(0) }
    var priceRetry by priceRetryState
    val accountingLoadingState = remember { mutableStateOf(false) }
    var accountingLoading by accountingLoadingState
    val accountingErrorState = remember { mutableStateOf(false) }
    var accountingError by accountingErrorState
    val explainState = rememberSaveable { mutableStateOf(false) }
    var explain by explainState
    val currencyMenuState = remember { mutableStateOf(false) }
    val sortMenuState = remember { mutableStateOf(false) }
    val filterMenuState = remember { mutableStateOf(false) }
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
                        portfolioMovementSummary(
                            app.analytics.report(start, selectedPortfolio),
                            app.analytics.report(end, selectedPortfolio),
                        )
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
    val tabState = rememberSaveable { mutableStateOf(ModelCoinTab.GENERAL.name) }
    var tab by tabState
    val expandedState = rememberSaveable { mutableStateOf(true) }
    val coinRangeState = rememberSaveable { mutableStateOf("1D") }
    var coinRange by coinRangeState
    val accountingState = remember(app, selectedPortfolio, snapshot?.currency) { mutableStateOf<AccountingView?>(null) }
    var accounting by accountingState
    // The same report feeds Portfolio performance, Insights gains and an owned coin's cost and gains.
    LaunchedEffect(app, selectedPortfolio, snapshot?.currency, selectedAsset?.asset, dataRevision, page) {
        val ownedCoin = selectedAsset != null && snapshot?.valuations?.any { it.asset == selectedAsset.asset } == true
        if (ownedCoin || ((page == Page.OVERVIEW || page == Page.REPORTS) && selectedAsset == null && snapshot != null)) {
            accountingLoading = true
            accountingError = false
            try {
                accounting = withContext(host.dispatcher) { app.accounting.report(selectedPortfolio) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                accountingError = true
            } finally {
                accountingLoading = false
            }
        }
    }
    val worthAssetState = rememberSaveable { mutableStateOf<String?>(null) }
    val insightsDataState = remember(app, selectedPortfolio) { mutableStateOf<InsightsData?>(null) }
    var insightsData by insightsDataState
    val rangeStart = chart.firstOrNull()?.atMillis
    val rangeEnd = chart.lastOrNull()?.atMillis
    // Insights calculations follow the history range on screen, so every section covers the same period.
    LaunchedEffect(app, page, selectedPortfolio, rangeStart, rangeEnd, dataRevision) {
        if (page == Page.REPORTS && rangeStart != null && rangeEnd != null && rangeEnd > rangeStart) {
            insightsData =
                withContext(host.dispatcher) {
                    InsightsData(
                        runCatching { app.analytics.moneyWeightedReturn(selectedPortfolio, rangeStart, rangeEnd) }.getOrNull(),
                        runCatching { app.analytics.insights(selectedPortfolio, rangeStart, rangeEnd) }.getOrNull(),
                        runCatching { app.analytics.report(rangeEnd, selectedPortfolio) }.getOrNull(),
                    )
                }
        }
    }
    val pricesState =
        remember(app, selectedAsset?.asset, snapshot?.currency, coinRange) {
            mutableStateOf<List<MarketPricePoint>>(emptyList())
        }
    var prices by pricesState
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
            val end = app.analytics.evaluationTimeMillis()
            val start = (end - duration).coerceAtLeast(0)
            val span = end - start
            priceLoading = true
            priceError = false
            try {
                prices =
                    withContext(host.dispatcher) {
                        app.analytics.priceSeries(asset, quote, (0..60).map { start + span * it / 60 })
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
                ModelCoinBranch(
                    app = app,
                    host = host,
                    data = data,
                    selectedAsset = selectedAsset,
                    selectedPortfolio = selectedPortfolio,
                    chart = chart,
                    chartStyle = chartStyle,
                    timelineFilter = timelineFilter,
                    amountsHidden = amountsHidden,
                    transactionScope = transactionScope,
                    onChartStyle = onChartStyle,
                    onAdvanced = onAdvanced,
                    onManual = onManual,
                    onTransaction = onTransaction,
                    changeCoin = ::changeCoin,
                    updateEditor = ::updateEditor,
                    saveAlert = ::saveAlert,
                    setAlertEnabled = ::setAlertEnabled,
                    deleteAlert = ::deleteAlert,
                    coinAssetsState = coinAssetsState,
                    chainChoicesState = chainChoicesState,
                    marketOverviewState = marketOverviewState,
                    coinMarketState = coinMarketState,
                    alertEditorState = alertEditorState,
                    alertNoticeState = alertNoticeState,
                    orderNoticeState = orderNoticeState,
                    transactionSearchState = transactionSearchState,
                    transactionTypeState = transactionTypeState,
                    transactionStatusState = transactionStatusState,
                    transactionOffsetState = transactionOffsetState,
                    timelineState = timelineState,
                    timelineErrorState = timelineErrorState,
                    timelineRetryState = timelineRetryState,
                    timelineLoadingState = timelineLoadingState,
                    transactionExplanationState = transactionExplanationState,
                    exportMessageState = exportMessageState,
                    priceDetailsState = priceDetailsState,
                    priceLoadingState = priceLoadingState,
                    priceErrorState = priceErrorState,
                    priceRetryState = priceRetryState,
                    accountingLoadingState = accountingLoadingState,
                    accountingErrorState = accountingErrorState,
                    tabState = tabState,
                    expandedState = expandedState,
                    coinRangeState = coinRangeState,
                    accountingState = accountingState,
                    pricesState = pricesState,
                )
            } else {
                ModelPortfolioPages(
                    app = app,
                    host = host,
                    dataRevision = dataRevision,
                    data = data,
                    page = page,
                    selectedPortfolio = selectedPortfolio,
                    chart = chart,
                    chartLoading = chartLoading,
                    chartError = chartError,
                    period = period,
                    preferences = preferences,
                    wifiOnlyRefreshSupported = wifiOnlyRefreshSupported,
                    assetPreference = assetPreference,
                    showPortfolioChart = showPortfolioChart,
                    syncStatus = syncStatus,
                    favorites = favorites,
                    chartStyle = chartStyle,
                    amountsHidden = amountsHidden,
                    wide = wide,
                    short = short,
                    settingsContent = settingsContent,
                    onFavorite = onFavorite,
                    onPeriod = onPeriod,
                    onCurrency = onCurrency,
                    onAsset = onAsset,
                    onAssetPreference = onAssetPreference,
                    onPortfolio = onPortfolio,
                    onAdvanced = onAdvanced,
                    onSyncDetails = onSyncDetails,
                    onToggleAmounts = onToggleAmounts,
                    onAutomatic = onAutomatic,
                    onWifi = onWifi,
                    onMarketRefresh = onMarketRefresh,
                    onAccountChanged = onAccountChanged,
                    onAccountTool = onAccountTool,
                    onConnectBitcoin = onConnectBitcoin,
                    onChartStyle = onChartStyle,
                    hiddenReviewState = hiddenReviewState,
                    marketOverviewState = marketOverviewState,
                    accountingState = accountingState,
                    currencyMenuState = currencyMenuState,
                    sortMenuState = sortMenuState,
                    filterMenuState = filterMenuState,
                    explainState = explainState,
                    worthAssetState = worthAssetState,
                    insightsDataState = insightsDataState,
                )
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
