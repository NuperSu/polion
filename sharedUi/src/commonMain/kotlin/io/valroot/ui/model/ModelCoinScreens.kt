package io.valroot.ui.model

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ModelCoinTab(
    val label: String,
) {
    GENERAL("General"),
    TRANSACTIONS("Transactions"),
    ALERTS("Alerts"),
    ORDER_BOOK("Order book"),
}

data class ModelMetric(
    val label: String,
    val value: String,
    val tone: ModelChangeTone = ModelChangeTone.NEUTRAL,
)

data class ModelTransactionCard(
    val id: String,
    val kind: String,
    val dateAndSource: String,
    val from: String,
    val to: String,
    val amount: String,
    val fee: String,
    val worthNow: String,
    val worthThen: String,
)

data class ModelAlertRule(
    val id: String,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val source: String,
)

data class ModelOrderRow(
    val amountBid: String,
    val bid: String,
    val ask: String,
    val amountAsk: String,
)

data class ModelCoinDetailState(
    val ticker: String,
    val mark: String,
    val logoColor: Color,
    /** Coin ID used to find the provider logo. */
    val coinId: String = "",
    val portfolio: String,
    val metrics: List<ModelMetric>,
    val breakdownExpanded: Boolean,
    val tab: ModelCoinTab,
    val price: String,
    val change: String,
    val priceSource: String,
    val selectedRange: String,
    val chartPoints: List<Float?>,
    val chartTopLabel: String,
    val chartBottomLabel: String,
    val transactionMetrics: List<ModelMetric>,
    val transactions: List<ModelTransactionCard>,
    val alerts: List<ModelAlertRule>,
    val orderSource: String,
    val orderRows: List<ModelOrderRow>,
    val enabledRanges: Set<String> = setOf("1H", "1D", "1W", "1M", "3M", "1Y"),
    val transactionEmpty: String = "No transactions",
    /** Why new alerts cannot be added; shown in the Alerts tab. */
    val alertsUnavailable: String? = null,
    /** Result of the last alert change that failed. */
    val alertNotice: String? = null,
    val orderUnavailable: String? = null,
    /** True while the user must allow Kraken order books before any are requested. */
    val orderConsentNeeded: Boolean = false,
    /** Result of the last order book setting change that failed. */
    val orderNotice: String? = null,
    /** The last order book request failed; the message is shown above the last loaded rows. */
    val orderIssue: String? = null,
    /** The shown book is older than 30 seconds or the last request failed. */
    val orderOutOfDate: Boolean = false,
    val canAddTransaction: Boolean = true,
    val canAddAlert: Boolean = false,
    val changeTone: ModelChangeTone = ModelChangeTone.POSITIVE,
    val bidHeader: String = "Bid",
    val askHeader: String = "Ask",
    val coinName: String = ticker,
    val smoothChart: Boolean = false,
    /** Prices and times for the interactive chart; without it the plain [chartPoints] line is drawn. */
    val chart: ChartData? = null,
    val chartStyle: ChartStyle = ChartStyle.LINE,
    /** The held coin on each chain, shown under the portfolio name when the coin is held on more than one chain. */
    val chainRows: List<ModelChainRow> = emptyList(),
)

data class ModelChainRow(
    val chain: String,
    val quantity: String,
    val value: String,
)

data class ModelCoinDetailActions(
    val onBreakdown: (Boolean) -> Unit,
    val onTab: (ModelCoinTab) -> Unit,
    val onRange: (String) -> Unit,
    val onAddTransaction: () -> Unit,
    val onTransaction: (String) -> Unit = {},
    val onAddAlert: () -> Unit = {},
    val onChartStyle: ((ChartStyle) -> Unit)? = null,
    val onAlertEnabled: (String, Boolean) -> Unit = { _, _ -> },
    /** Opens the editor for an existing alert. */
    val onAlertOpen: (String) -> Unit = {},
    val onOrderConsent: () -> Unit = {},
    val onOrderRetry: () -> Unit = {},
)

/** Footer under the alert list: how often prices are checked while Valroot is open. */
internal const val ALERT_CHECK_NOTE =
    "Checked against CoinGecko's top 250 list about every 15 minutes on Android and every 5 minutes on desktop while Valroot " +
        "is open. Your phone may delay checks to save battery."

internal const val ORDER_BOOK_CONSENT_NOTE = "Order books come from Kraken. Kraken receives the trading pair and your IP address."

@Composable
private fun DetailMetric(
    metric: ModelMetric,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(metric.label, color = c.onSurfaceVariant, fontSize = 11.sp)
        Text(
            metric.value,
            color =
                when (metric.tone) {
                    ModelChangeTone.POSITIVE -> c.primary
                    ModelChangeTone.NEGATIVE -> c.error
                    else -> c.onSurface
                },
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MetricRows(metrics: List<ModelMetric>) {
    metrics.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { DetailMetric(it, Modifier.weight(1f)) }
            repeat(
                3 - row.size,
            ) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
fun ColumnScope.ModelCoinDetail(
    state: ModelCoinDetailState,
    actions: ModelCoinDetailActions,
    transactionControls: @Composable () -> Unit = {},
    transactionFooter: @Composable () -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        ModelCoinLogo(ModelCoinIdentity(state.ticker, state.coinName, state.logoColor, state.mark, state.coinId.ifEmpty { state.ticker }))
        Text("  ${state.ticker}", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    }
    Text(state.portfolio, color = c.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
    if (state.chainRows.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().testTag("coin-chains"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("On each chain", color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            state.chainRows.forEach { row ->
                Row(Modifier.fillMaxWidth().testTag("coin-chain-${row.chain}"), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(row.chain, fontSize = 14.sp)
                        Text(row.quantity, color = c.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Text(row.value, fontSize = 14.sp)
                }
            }
        }
    }
    if (state.breakdownExpanded) MetricRows(state.metrics)
    TextButton(onClick = {
        actions.onBreakdown(!state.breakdownExpanded)
    }) { Text(if (state.breakdownExpanded) "Hide cost & gains breakdown  ⌃" else "Show cost & gains breakdown  ⌄", color = c.onSurface) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelCoinTab.entries.forEach { tab ->
            val selected = state.tab == tab
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (selected) c.surfaceVariant else Color.Transparent)
                    .border(1.dp, c.surfaceVariant, CircleShape)
                    .selectable(selected = selected, role = Role.Tab) {
                        actions.onTab(tab)
                    }.testTag("detail-${tab.label}")
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    color = if (selected) c.onSurface else c.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
    }
    when (state.tab) {
        ModelCoinTab.GENERAL -> {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(state.price, fontSize = 36.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("coin-unit-price"))
                val changeColor =
                    when (state.changeTone) {
                        ModelChangeTone.POSITIVE -> c.primary
                        ModelChangeTone.NEGATIVE -> c.error
                        else -> c.onSurfaceVariant
                    }
                Text(
                    state.change,
                    color = changeColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .background(
                                changeColor.copy(alpha = .17f),
                                RoundedCornerShape(4.dp),
                            ).padding(horizontal = 5.dp, vertical = 3.dp),
                )
                Text(state.priceSource, color = c.onSurfaceVariant, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("1H", "1D", "1W", "1M", "3M", "1Y").forEach { range ->
                    Text(
                        range,
                        color =
                            if (range in state.enabledRanges && range == state.selectedRange) {
                                c.onSurface
                            } else {
                                c.onSurfaceVariant
                            },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .background(
                                    if (range ==
                                        state.selectedRange
                                    ) {
                                        c.surfaceVariant
                                    } else {
                                        Color.Transparent
                                    },
                                ).selectable(
                                    selected = range == state.selectedRange,
                                    enabled =
                                        range in state.enabledRanges,
                                    role = Role.RadioButton,
                                ) { actions.onRange(range) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                    )
                }
            }
            actions.onChartStyle?.let { onStyle ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ChartStyleToggle(state.chartStyle, onStyle) }
            }
            if (state.chart != null) {
                ModelInteractiveChart(
                    state.chart,
                    state.chartStyle,
                    Modifier.fillMaxWidth().height(220.dp).testTag("coin-chart"),
                    smooth = state.smoothChart,
                )
            } else {
                DetailChart(state.chartPoints, state.chartTopLabel, state.chartBottomLabel, state.smoothChart)
            }
        }
        ModelCoinTab.TRANSACTIONS -> {
            MetricRows(state.transactionMetrics)
            transactionControls()
            TextButton(
                onClick = actions.onAddTransaction,
                enabled = state.canAddTransaction,
                modifier = Modifier.testTag("detail-add-transaction"),
            ) {
                Text(
                    "＋  New Transaction",
                    color = if (state.canAddTransaction) c.onSurface else c.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (state.transactions.isEmpty()) Text(state.transactionEmpty, color = c.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                state.transactions.forEach { transaction ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                transaction.kind,
                                color = c.onSurface,
                                fontSize = 12.sp,
                                modifier =
                                    Modifier
                                        .border(1.dp, c.tertiary, CircleShape)
                                        .padding(horizontal = 13.dp, vertical = 7.dp),
                            )
                            Text(transaction.dateAndSource, color = c.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(c.surface, RoundedCornerShape(14.dp))
                                .clickable(role = Role.Button) {
                                    actions.onTransaction(transaction.id)
                                }.testTag("transaction-card-${transaction.id}")
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            listOf(
                                listOf("From" to transaction.from, "Amount" to transaction.amount),
                                listOf("To" to transaction.to, "Fee" to transaction.fee),
                                listOf("Worth Now" to transaction.worthNow, "Worth Then" to transaction.worthThen),
                            ).forEach { pair ->
                                Row(Modifier.fillMaxWidth()) {
                                    pair.forEach { (label, value) ->
                                        DetailMetric(ModelMetric(label, value), Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            transactionFooter()
        }
        ModelCoinTab.ALERTS -> {
            TextButton(onClick = actions.onAddAlert, enabled = state.canAddAlert, modifier = Modifier.testTag("alert-add")) {
                Text("＋  Add New Alert", color = if (state.canAddAlert) c.onSurface else c.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            state.alertsUnavailable?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp) }
            state.alertNotice?.let { Text(it, color = c.error, fontSize = 12.sp) }
            state.alerts.forEach { rule ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable(role = Role.Button) { actions.onAlertOpen(rule.id) }
                            .testTag("alert-row-${rule.id}")
                            .padding(end = 12.dp),
                    ) {
                        Text(rule.title, fontWeight = FontWeight.SemiBold)
                        Text(rule.description, color = c.onSurfaceVariant, fontSize = 12.sp)
                        Text(rule.source, color = c.onSurfaceVariant, fontSize = 12.sp)
                    }
                    // The switch sits beside the row's clickable text, so it carries its own name instead of merging with it.
                    Switch(
                        rule.enabled,
                        { actions.onAlertEnabled(rule.id, it) },
                        modifier =
                            Modifier
                                .semantics { contentDescription = "Enable ${rule.title} alert" }
                                .testTag("alert-switch-${rule.id}"),
                    )
                }
            }
            Text(
                ALERT_CHECK_NOTE,
                color = c.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        ModelCoinTab.ORDER_BOOK -> {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                state.orderNotice?.let { Text(it, color = c.error, fontSize = 12.sp) }
                if (state.orderConsentNeeded) {
                    Text(ORDER_BOOK_CONSENT_NOTE, color = c.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                    TextButton(onClick = actions.onOrderConsent, modifier = Modifier.testTag("order-consent")) {
                        Text("Load Kraken order book", color = c.onSurface, fontWeight = FontWeight.Bold)
                    }
                }
                state.orderUnavailable?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp) }
                if (state.orderSource.isNotEmpty()) Text(state.orderSource, color = c.onSurfaceVariant, fontSize = 12.sp)
                if (state.orderOutOfDate || state.orderIssue != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (state.orderOutOfDate) {
                                Text("Out of date", color = c.error, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            state.orderIssue?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp) }
                        }
                        TextButton(onClick = actions.onOrderRetry, modifier = Modifier.testTag("order-retry")) { Text("Retry") }
                    }
                }
                if (state.orderRows.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Amount", fontSize = 12.sp)
                        Text(state.bidHeader, color = c.primary, fontSize = 12.sp)
                        Text(state.askHeader, color = c.error, fontSize = 12.sp)
                        Text("Amount", fontSize = 12.sp)
                    }
                }
                state.orderRows.forEachIndexed { index, row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (index % 2 == 0) c.surface else Color.Transparent)
                            .padding(vertical = 5.dp)
                            .testTag("order-row-$index"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(row.amountBid, fontSize = 12.sp)
                        Text(row.bid, color = c.primary, fontSize = 12.sp)
                        Text(row.ask, color = c.error, fontSize = 12.sp)
                        Text(row.amountAsk, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailChart(
    points: List<Float?>,
    top: String,
    bottom: String,
    smooth: Boolean,
) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(220.dp)) {
        Canvas(Modifier.fillMaxSize().padding(vertical = 20.dp)) {
            repeat(2) { row ->
                val y = size.height * row
                var x = 0f
                while (x < size.width) {
                    drawLine(c.onSurfaceVariant, Offset(x, y), Offset(x + 1, y), 1f)
                    x += 4.dp.toPx()
                }
            }
            var start = 0
            while (start < points.size) {
                while (start < points.size && points[start] == null) start++
                var end = start
                while (end < points.size && points[end] != null) end++
                if (end - start >= 2) {
                    val path = Path()
                    for (i in start until end) {
                        val x = size.width * i / points.lastIndex
                        val y = size.height * (1f - points[i]!!)
                        if (i == start) {
                            path.moveTo(x, y)
                        } else {
                            val px = size.width * (i - 1) / points.lastIndex
                            val py = size.height * (1f - points[i - 1]!!)
                            if (smooth) path.cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y) else path.lineTo(x, y)
                        }
                    }
                    val area =
                        Path().apply {
                            addPath(path)
                            lineTo(size.width * (end - 1) / points.lastIndex, size.height)
                            lineTo(
                                size.width * start / points.lastIndex,
                                size.height,
                            )
                            close()
                        }
                    drawPath(area, Brush.verticalGradient(listOf(c.primary.copy(alpha = .2f), c.primary.copy(alpha = 0f))))
                    drawPath(path, c.primary, style = Stroke(1.6.dp.toPx()))
                }
                start = end + 1
            }
        }
        Text(top, color = c.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
        Text(bottom, color = c.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
    }
}
