package io.valroot.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.valroot.core.alerts.AlertCondition
import io.valroot.core.application.AccountingView
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.HistoryPoint
import io.valroot.core.application.MarketOverview
import io.valroot.core.application.MarketPricePoint
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.market.EvmPlatform
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.AssetId
import io.valroot.core.model.PortfolioId
import io.valroot.core.reconcile.EffectiveActivity
import io.valroot.ui.model.ChartData
import io.valroot.ui.model.ChartStyle
import io.valroot.ui.model.ModelAlertEditor
import io.valroot.ui.model.ModelAlertEditorState
import io.valroot.ui.model.ModelChainRow
import io.valroot.ui.model.ModelChangeTone
import io.valroot.ui.model.ModelCoinDetail
import io.valroot.ui.model.ModelCoinDetailActions
import io.valroot.ui.model.ModelCoinDetailState
import io.valroot.ui.model.ModelCoinTab
import io.valroot.ui.model.ModelMetric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ColumnScope.ModelCoinBranch(
    app: PortfolioApplication,
    host: PlatformActions,
    data: PortfolioSnapshot,
    selectedAsset: AssetValuation,
    selectedPortfolio: PortfolioId?,
    chart: List<HistoryPoint>,
    chartStyle: ChartStyle,
    timelineFilter: ActivityFilter,
    amountsHidden: Boolean,
    transactionScope: CoroutineScope,
    onChartStyle: (ChartStyle) -> Unit,
    onAdvanced: (Page) -> Unit,
    onManual: (AssetValuation?) -> Unit,
    onTransaction: (EffectiveActivity) -> Unit,
    changeCoin: (onDone: () -> Unit, onFailure: (Exception) -> Unit, change: () -> Unit) -> Unit,
    updateEditor: ((ModelAlertEditorState) -> ModelAlertEditorState) -> Unit,
    saveAlert: (String, ModelAlertEditorState) -> Unit,
    setAlertEnabled: (String, Boolean) -> Unit,
    deleteAlert: (ModelAlertEditorState, String) -> Unit,
    coinAssetsState: MutableState<Set<AssetId>>,
    chainChoicesState: MutableState<List<EvmPlatform>>,
    marketOverviewState: MutableState<MarketOverview?>,
    coinMarketState: MutableState<CoinMarketLoad?>,
    alertEditorState: MutableState<ModelAlertEditorState?>,
    alertNoticeState: MutableState<String?>,
    orderNoticeState: MutableState<String?>,
    transactionSearchState: MutableState<String>,
    transactionTypeState: MutableState<String>,
    transactionStatusState: MutableState<String>,
    transactionOffsetState: MutableState<Int>,
    timelineState: MutableState<TransactionTimeline?>,
    timelineErrorState: MutableState<Boolean>,
    timelineRetryState: MutableState<Int>,
    timelineLoadingState: MutableState<Boolean>,
    transactionExplanationState: MutableState<Boolean>,
    exportMessageState: MutableState<String?>,
    priceDetailsState: MutableState<Boolean>,
    priceLoadingState: MutableState<Boolean>,
    priceErrorState: MutableState<Boolean>,
    priceRetryState: MutableState<Int>,
    accountingLoadingState: MutableState<Boolean>,
    accountingErrorState: MutableState<Boolean>,
    tabState: MutableState<String>,
    expandedState: MutableState<Boolean>,
    coinRangeState: MutableState<String>,
    accountingState: MutableState<AccountingView?>,
    pricesState: MutableState<List<MarketPricePoint>>,
) {
    val coinAssets by coinAssetsState
    val chainChoices by chainChoicesState
    val marketOverview by marketOverviewState
    val coinMarket by coinMarketState
    var alertEditor by alertEditorState
    var alertNotice by alertNoticeState
    var orderNotice by orderNoticeState
    var transactionSearch by transactionSearchState
    var transactionType by transactionTypeState
    var transactionStatus by transactionStatusState
    var transactionOffset by transactionOffsetState
    val timeline by timelineState
    val timelineError by timelineErrorState
    var timelineRetry by timelineRetryState
    val timelineLoading by timelineLoadingState
    var transactionExplanation by transactionExplanationState
    var exportMessage by exportMessageState
    var priceDetails by priceDetailsState
    val priceLoading by priceLoadingState
    val priceError by priceErrorState
    var priceRetry by priceRetryState
    val accountingLoading by accountingLoadingState
    val accountingError by accountingErrorState
    var tab by tabState
    var expanded by expandedState
    var coinRange by coinRangeState
    val accounting by accountingState
    val prices by pricesState

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
            onAlertEnabled = setAlertEnabled,
            onOrderConsent = {
                changeCoin(
                    { orderNotice = null },
                    { orderNotice = "Could not turn on Kraken order books. Try again." },
                ) { app.market.setOrderBookConsent(true) }
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
                            val accounts = app.transactions.accounts(exportFilter.portfolio)
                            val ids =
                                app
                                    .transactions
                                    .entries(exportFilter)
                                    .filter {
                                        exportType == "All" ||
                                            transactionKind(it, asset.asset, accounts) == exportType
                                    }.map { it.activity.id }
                                    .toSet()
                            val csv = app.csv.export(exportFilter, ids)
                            host.saveFile("valroot-${asset.asset.value}-transactions.csv", csv.encodeToByteArray()) {
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
                ". Valroot records transactions for Bitcoin only.",
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
}
