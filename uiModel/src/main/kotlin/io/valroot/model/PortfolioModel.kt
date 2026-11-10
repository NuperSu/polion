package io.valroot.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.ui.model.ModelAccountRow
import io.valroot.ui.model.ModelAccounts
import io.valroot.ui.model.ModelAccountsState
import io.valroot.ui.model.ModelAlertEditor
import io.valroot.ui.model.ModelAlertEditorState
import io.valroot.ui.model.ModelAlertRule
import io.valroot.ui.model.ModelAssetOption
import io.valroot.ui.model.ModelCapsule
import io.valroot.ui.model.ModelChangeTone
import io.valroot.ui.model.ModelCoinDetail
import io.valroot.ui.model.ModelCoinDetailActions
import io.valroot.ui.model.ModelCoinDetailState
import io.valroot.ui.model.ModelCoinIdentity
import io.valroot.ui.model.ModelCoinTab
import io.valroot.ui.model.ModelDonutState
import io.valroot.ui.model.ModelDraftField
import io.valroot.ui.model.ModelGainRow
import io.valroot.ui.model.ModelHoldingRow
import io.valroot.ui.model.ModelInsights
import io.valroot.ui.model.ModelInsightsState
import io.valroot.ui.model.ModelMain
import io.valroot.ui.model.ModelMainAction
import io.valroot.ui.model.ModelMainState
import io.valroot.ui.model.ModelMarketCoin
import io.valroot.ui.model.ModelMetric
import io.valroot.ui.model.ModelOrderRow
import io.valroot.ui.model.ModelPerformanceGroup
import io.valroot.ui.model.ModelPortfolioHoldings
import io.valroot.ui.model.ModelPortfolioHoldingsState
import io.valroot.ui.model.ModelPortfolioSummary
import io.valroot.ui.model.ModelPortfolioSummaryState
import io.valroot.ui.model.ModelSettings
import io.valroot.ui.model.ModelSettingsState
import io.valroot.ui.model.ModelShell
import io.valroot.ui.model.ModelShellState
import io.valroot.ui.model.ModelTheme
import io.valroot.ui.model.ModelTransactionCard
import io.valroot.ui.model.ModelTransactionDialog
import io.valroot.ui.model.ModelTransactionDialogActions
import io.valroot.ui.model.ModelTransactionDialogState
import io.valroot.ui.model.ModelTransactionType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

private val White = Color(0xFFF5F5F5)
private val Muted = Color(0xFF969696)
private val Card = Color(0xFF171717)
private val Pill = Color(0xFF252525)
private val Green = Color(0xFF27D569)
private val Red = Color(0xFFFF3558)
private val Purple = Color(0xFF8057FF)
private val Gold = Color(0xFFFFCE58)

/** Static design fixtures, deliberately independent of portfolio repositories and providers. */
private data class Coin(
    val ticker: String,
    val name: String,
    val price: String,
    val change: String,
    val color: Color,
    val mark: String,
)

private val coins =
    listOf(
        Coin("BTC", "Bitcoin", "$78,035.62", "+0.99%", Color(0xFFFF9910), "₿"),
        Coin("ETH", "Ethereum", "$2,271.91", "+1.34%", Color(0xFF637EE9), ""),
        Coin("XRP", "XRP", "$1.25", "+0.71%", Color(0xFF242424), ""),
        Coin("SOL", "Solana", "$184.32", "−0.10%", Color(0xFF2EC6B7), ""),
        Coin("BNB", "BNB", "$705.88", "+0.69%", Color(0xFFF4BB25), ""),
        Coin("HYPE", "Hyperliquid", "$38.42", "−0.06%", Color(0xFF073A36), "≈"),
        Coin("USDT", "Tether", "$1.00", "+0.06%", Color(0xFF269E82), "₮"),
    )

typealias ModelPage = io.valroot.ui.model.ModelPage

private val LocalCoinClick = staticCompositionLocalOf<(Coin) -> Unit> { {} }

/** Independent visual prototype. Interaction changes preview state only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioModel(initialPage: ModelPage = ModelPage.PORTFOLIO) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var coinTicker by rememberSaveable { mutableStateOf<String?>(null) }
    val coin = coins.firstOrNull { it.ticker == coinTicker }
    var auxiliary by rememberSaveable { mutableStateOf<String?>(null) }
    var transaction by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var portfolio by rememberSaveable { mutableStateOf("Main Portfolio") }
    val scope = rememberCoroutineScope()
    val refresh: () -> Unit = {
        if (!refreshing) {
            scope.launch {
                refreshing = true
                delay(900)
                refreshing = false
            }
        }
    }
    val navigate: (ModelPage) -> Unit = {
        page = it
        coinTicker = null
        auxiliary = null
    }
    ModelTheme {
        CompositionLocalProvider(LocalCoinClick provides { coinTicker = it.ticker }) {
            ModelShell(
                state =
                    ModelShellState(
                        page,
                        coin?.name ?: auxiliary ?: page.label,
                        coin != null || auxiliary != null,
                        refreshing,
                        sidebarFooter = "UI MODEL\nIllustrative crypto portfolio",
                    ),
                onPage = navigate,
                onBack = {
                    coinTicker = null
                    auxiliary = null
                },
                onMenu = { auxiliary = it },
                onRefresh = refresh,
                onAdd = if (page == ModelPage.PORTFOLIO && coin == null && auxiliary == null) ({ transaction = true }) else null,
            ) { wide, short ->
                val selected = coin
                when {
                    auxiliary == "Settings" -> {
                        var automatic by rememberSaveable { mutableStateOf(true) }
                        var wifi by rememberSaveable { mutableStateOf(false) }
                        ModelSettings(
                            ModelSettingsState(
                                automatic,
                                wifi,
                                "Pull down to refresh prices across portfolios. Wallet history is a separate action. " +
                                    "Automatic jobs follow provider consent and platform scheduling.",
                                "These controls demonstrate the design only.",
                            ),
                            {
                                automatic =
                                    it
                            },
                            { wifi = it },
                        )
                    }
                    auxiliary == "Accounts" ->
                        ModelAccounts(
                            ModelAccountsState(listOf(ModelAccountRow("My Wallet", "Bitcoin · Main Portfolio", "Last refreshed just now"))),
                            onConnect = {},
                        )
                    selected != null -> CoinDetail(selected, portfolio) { transaction = true }
                    page == ModelPage.MAIN -> MainContent(wide)
                    page == ModelPage.PORTFOLIO -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Main Portfolio", "Long term").forEach { name ->
                                ModelCapsule(name, portfolio == name) { portfolio = name }
                            }
                        }
                        PortfolioContent(wide, short)
                    }
                    else -> InsightsContent(wide)
                }
                Text("VALROOT · DESIGN PREVIEW · SAMPLE DATA", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            if (transaction) TransactionForm(coin ?: coins.first(), coin != null) { transaction = false }
        }
    }
}

@Composable
private fun ColumnScope.PortfolioContent(
    wide: Boolean,
    short: Boolean,
) {
    var range by rememberSaveable { mutableStateOf("1D") }
    ModelPortfolioSummary(
        ModelPortfolioSummaryState(
            balance = "77.52",
            currency = "USD ⇄",
            change = "+0.76",
            percentChange = "+0.99%",
            selectedRange = range,
            changeTone = ModelChangeTone.POSITIVE,
            smoothChart = true,
            chartPoints =
                listOf(
                    .02f,
                    .04f,
                    .07f,
                    .13f,
                    .23f,
                    .45f,
                    .35f,
                    .27f,
                    .14f,
                    .5f,
                    .53f,
                    .58f,
                    .63f,
                    .59f,
                    .54f,
                    .5f,
                    .49f,
                    .49f,
                    .6f,
                    .48f,
                    .5f,
                    .54f,
                    .59f,
                    .64f,
                    .66f,
                    .71f,
                    .72f,
                    .73f,
                    .8f,
                    .91f,
                    .98f,
                    .97f,
                    .94f,
                ),
            chartTopLabel = "$77.53",
            chartBottomLabel = "$76.70",
        ),
        wide = wide,
        short = short,
        onRangeSelected = { range = it },
    )
    val first = coins.first()
    val open = LocalCoinClick.current
    ModelPortfolioHoldings(
        ModelPortfolioHoldingsState(
            listOf(
                ModelHoldingRow(
                    ModelCoinIdentity(first.ticker, first.name, first.color, first.mark),
                    "0.0009934 | $78,035.04",
                    "$77.52",
                    "+0.76",
                    first.change,
                    ModelChangeTone.POSITIVE,
                ),
            ),
            "Highest Holdings  ⌄",
        ),
        onHolding = { identity -> coins.find { it.ticker == identity.ticker }?.let(open) },
        onSort = {},
        onView = {},
    )
}

@Composable
private fun MainContent(wide: Boolean) {
    val open = LocalCoinClick.current
    val fixture =
        coins.map {
            ModelMarketCoin(ModelCoinIdentity(it.ticker, it.name, it.color, it.mark), it.price, it.change, fixtureChart, smoothChart = true)
        }
    ModelMain(
        ModelMainState(
            fixture.take(4),
            listOf(fixture[0], fixture[1], fixture[3]),
            listOf(fixture[5], fixture[3], fixture[4], fixture[1]),
            listOf(fixture[1], fixture[0], fixture[2], fixture[4]),
            listOf(fixture[3], fixture[5]),
        ),
        wide,
    ) { action ->
        when (action) {
            is ModelMainAction.OpenCoin -> coins.find { it.ticker == action.identity.ticker }?.let(open)
            is ModelMainAction.ToggleFavorite -> Unit
        }
    }
}

@Composable
private fun InsightsContent(wide: Boolean) {
    val groups =
        listOf(-.22f, -.13f, .48f, -.07f).mapIndexed { index, value ->
            ModelPerformanceGroup(
                "Q${index + 1} '26",
                listOf(
                    value * .25f,
                    value,
                    value,
                ),
            )
        }
    ModelInsights(
        ModelInsightsState(
            balance = "77.52 $",
            smoothChart = true,
            history =
                List(65) { i ->
                    if (i <
                        48
                    ) {
                        .03f
                    } else {
                        .8f + sin(i * 2.4f) * .025f
                    }
                },
            historyTopLabel = "$86.00",
            historyBottomLabel = "$0.00",
            performance = groups,
            performanceLegend = "● Main Portfolio    ● BTC    ● Crypto market",
            decisionsAvailable = true,
            portfolio = "Main Portfolio",
            diversity = ModelDonutState("BTC", "100%", 1f, "● Currency"),
            gains = listOf(ModelGainRow("₿ BTC", "0.0009934", "$0.00", "+$7.52")),
            exchange = "Self-custody",
            location = ModelDonutState("Wallet", "100%", 1f),
            tradeCount = "12",
            assetCount = "3",
            fees = "$0.84",
            assetWorth = fixtureChart,
            assetWorthTopLabel = "$77.53",
            assetWorthBottomLabel = "$76.70",
            assetWorthLegend = "● BTC",
        ),
        wide,
    ) {}
}

private val fixtureChart =
    listOf(
        .02f,
        .04f,
        .07f,
        .13f,
        .23f,
        .45f,
        .35f,
        .27f,
        .14f,
        .5f,
        .53f,
        .58f,
        .63f,
        .59f,
        .54f,
        .5f,
        .49f,
        .49f,
        .6f,
        .48f,
        .5f,
        .54f,
        .59f,
        .64f,
        .66f,
        .71f,
        .72f,
        .73f,
        .8f,
        .91f,
        .98f,
        .97f,
        .94f,
    )

@Composable
private fun ColumnScope.CoinDetail(
    coin: Coin,
    portfolio: String,
    add: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(ModelCoinTab.GENERAL) }
    var breakdown by rememberSaveable { mutableStateOf(true) }
    var range by rememberSaveable { mutableStateOf("1D") }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var editingAlert by rememberSaveable { mutableStateOf(false) }
    var threshold by rememberSaveable { mutableStateOf("5") }
    var savedThreshold by rememberSaveable { mutableStateOf("5") }
    val transactions =
        listOf("Deposit" to "4 Sep 2026 · Wallet", "Buy" to "2 Sep 2026 · Manual", "Fee" to "2 Sep 2026 · Manual").map { (kind, date) ->
            ModelTransactionCard(
                kind,
                kind,
                date,
                if (kind ==
                    "Deposit"
                ) {
                    "Other wallet"
                } else {
                    "Manual entry"
                },
                "My Wallet",
                if (kind ==
                    "Fee"
                ) {
                    "0.000010"
                } else {
                    "0.0009934"
                },
                "$0.84",
                "$77.52",
                "$70.00",
            )
        }
    ModelCoinDetail(
        ModelCoinDetailState(
            ticker = coin.ticker,
            mark = coin.mark,
            logoColor = coin.color,
            portfolio = portfolio,
            metrics =
                listOf(
                    ModelMetric("Owned", "0.0009934"),
                    ModelMetric("Market Value", "$77.52"),
                    ModelMetric("Total Gains", "+$7.52", ModelChangeTone.POSITIVE),
                    ModelMetric("Total Cost Basis", "$70.00"),
                    ModelMetric("Unrealized Gain", "+$7.52", ModelChangeTone.POSITIVE),
                    ModelMetric("Realized Gain", "$0.00"),
                ),
            breakdownExpanded = breakdown,
            tab = tab,
            price = coin.price,
            change = "+24.41%",
            priceSource = "Global average · ${coin.ticker}/USD",
            selectedRange = range,
            chartPoints = fixtureChart,
            chartTopLabel = "$87,351.93",
            chartBottomLabel = "$62,250.78",
            transactionMetrics =
                listOf(
                    ModelMetric("Avg. Buy Price", "$70,466.14"),
                    ModelMetric("Avg. Sell Price", "—"),
                    ModelMetric("Transactions", "3"),
                ),
            transactions = transactions,
            alerts =
                listOf(
                    ModelAlertRule(
                        "preview",
                        "Significant Price Change",
                        "When ${coin.ticker} moves $savedThreshold% since your last visit",
                        enabled,
                        "${coin.ticker}/USD · Global average",
                    ),
                ),
            smoothChart = true,
            bidHeader = "Bid (USD)",
            askHeader = "Ask (USD)",
            orderSource = "${coin.ticker}/USD · Illustrative order book",
            orderRows = List(8) { n -> ModelOrderRow("0.0${12 + n}", "${78035 - n * 12}.00", "${78036 + n * 12}.00", "0.0${18 + n}") },
            canAddAlert = true,
        ),
        ModelCoinDetailActions(onBreakdown = {
            breakdown = it
        }, onTab = { tab = it }, onRange = { range = it }, onAddTransaction = add, onAddAlert = {
            editingAlert = true
        }, onAlertEnabled = { _, value -> enabled = value }),
    )
    if (editingAlert && tab == ModelCoinTab.ALERTS) {
        ModelAlertEditor(
            state = ModelAlertEditorState(threshold),
            onThreshold = { threshold = it },
            onSave = {
                savedThreshold = threshold
                editingAlert = false
            },
            onCancel = { editingAlert = false },
        )
    }
}

@Composable
private fun TransactionForm(
    initial: Coin,
    direct: Boolean,
    close: () -> Unit,
) {
    var manual by rememberSaveable { mutableStateOf(direct) }
    var selected by rememberSaveable { mutableStateOf(initial.ticker) }
    var type by rememberSaveable { mutableStateOf(ModelTransactionType.BUY) }
    var amount by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("78157.96") }
    var fee by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("1 Nov 2026, 21:00") }
    ModelTransactionDialog(
        ModelTransactionDialogState(
            manual = manual,
            assets =
                coins.take(3).map {
                    ModelAssetOption(it.ticker, it.ticker, it.name)
                },
            selectedAsset = selected,
            type = type,
            exchange = "Global average",
            pair = "$selected/USD",
            date = date,
            price = price,
            amount = amount,
            fee = fee,
            notes = notes,
            canConnectWallet = false,
            canImport = false,
            canSave = true,
            saveLabel = "Done · preview only",
            priceLabel = "Price per coin (USD)",
            feeLabel = "Transaction fee (USD)",
        ),
        ModelTransactionDialogActions(onClose = close, onWallet = {
        }, onManual = { manual = true }, onImport = {}, onAsset = { selected = it }, onType = {
            type =
                it
        }, onField = { field, value ->
            when (field) {
                ModelDraftField.DATE -> date = value
                ModelDraftField.PRICE -> price = value
                ModelDraftField.AMOUNT ->
                    amount =
                        value
                ; ModelDraftField.FEE -> fee = value
                ModelDraftField.NOTES -> notes = value
            }
        }, onSave = close),
    )
}
