package io.polion.ui.model

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One labelled share of a whole, such as an asset's share of priced value. */
data class ModelSlice(
    val id: String,
    val label: String,
    val share: Float,
    val value: String,
    val color: Color,
)

/** The money-weighted return card, already formatted; [status] explains a missing rate. */
data class ModelReturnState(
    val periodReturn: String = "—",
    val annualReturn: String? = null,
    val tone: ModelChangeTone = ModelChangeTone.NEUTRAL,
    val rows: List<ModelMetric> = emptyList(),
    val status: String? = null,
)

data class ModelGainRow(
    val id: String,
    val asset: String,
    val owned: String,
    val realized: String,
    val unrealized: String,
    val unrealizedTone: ModelChangeTone = ModelChangeTone.NEUTRAL,
)

data class ModelFeeRow(
    val amount: String,
    val value: String,
)

data class ModelInsightsState(
    val balance: String = "—",
    val portfolio: String = "All portfolios",
    val range: String = "1M",
    val ranges: List<String> = emptyList(),
    val history: ChartData? = null,
    val chartStyle: ChartStyle = ChartStyle.LINE,
    val performance: ModelReturnState = ModelReturnState(),
    val diversity: List<ModelSlice> = emptyList(),
    val diversityNote: String? = null,
    val gains: List<ModelGainRow> = emptyList(),
    val gainsNote: String? = null,
    val location: List<ModelSlice> = emptyList(),
    val accountUse: List<ModelMetric> = emptyList(),
    val trades: String = "—",
    val tradedAssets: String = "—",
    val fees: List<ModelFeeRow> = emptyList(),
    val feesTotal: String? = null,
    val worthAssets: List<ModelSlice> = emptyList(),
    val worthSelected: String? = null,
    val worth: ChartData? = null,
    val loading: Boolean = false,
)

sealed interface ModelInsightsAction {
    data class Range(
        val label: String,
    ) : ModelInsightsAction

    data class Style(
        val style: ChartStyle,
    ) : ModelInsightsAction

    data class OpenAsset(
        val id: String,
    ) : ModelInsightsAction

    data class WorthAsset(
        val id: String,
    ) : ModelInsightsAction
}

@Composable
fun ModelInsights(
    state: ModelInsightsState,
    wide: Boolean,
    onAction: (ModelInsightsAction) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text(state.portfolio, color = c.onSurfaceVariant, fontSize = 12.sp)
        MarketSection("History", "PORTFOLIO VALUE") {
            MarketCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Balance", color = c.onSurfaceVariant, fontSize = 12.sp)
                        Text(state.balance, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                    ChartStyleToggle(state.chartStyle) { onAction(ModelInsightsAction.Style(it)) }
                }
                state.history?.let {
                    ModelInteractiveChart(
                        it,
                        state.chartStyle,
                        Modifier.fillMaxWidth().height(if (wide) 240.dp else 180.dp).testTag("insights-history"),
                    )
                } ?: Text("History unavailable", color = c.onSurfaceVariant)
                RangeRow(state.ranges, state.range) { onAction(ModelInsightsAction.Range(it)) }
            }
        }
        MarketSection("Portfolio Performance", "MONEY-WEIGHTED RETURN · ${state.range}") {
            MarketCard(Modifier.testTag("insights-performance")) {
                val tint =
                    when (state.performance.tone) {
                        ModelChangeTone.POSITIVE -> c.primary
                        ModelChangeTone.NEGATIVE -> c.error
                        ModelChangeTone.NEUTRAL -> c.onSurface
                    }
                Text(state.performance.periodReturn, color = tint, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                state.performance.annualReturn?.let { Text(it, color = tint, fontSize = 14.sp) }
                state.performance.status?.let { Text(it, color = c.onSurfaceVariant, fontSize = 13.sp) }
                MetricRows(state.performance.rows)
                Text(
                    "Deposits and withdrawals count by when they happened, so the rate reflects your timing. Trades, fees and " +
                        "rewards are part of the return.",
                    color = c.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        MarketSection("Portfolio Diversity", "SHARE OF PRICED VALUE") {
            MarketCard(Modifier.testTag("insights-diversity")) {
                SliceDonut(state.diversity, Modifier.align(Alignment.CenterHorizontally).size(if (wide) 240.dp else 200.dp))
                SliceLegend(state.diversity) { onAction(ModelInsightsAction.OpenAsset(it)) }
                state.diversityNote?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp) }
            }
        }
        MarketSection("Gains Reporting", "FIFO · REALIZED AND UNREALIZED") {
            MarketCard(Modifier.testTag("insights-gains")) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("Asset" to 1f, "Owned" to 1.2f, "Realized" to 1.1f, "Unrealized" to 1.1f).forEachIndexed {
                        index,
                        (label, weight),
                        ->
                        Text(
                            label,
                            color = c.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(weight),
                            textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                        )
                    }
                }
                if (state.gains.isEmpty()) Text("No holdings or disposals", color = c.onSurfaceVariant, fontSize = 12.sp)
                state.gains.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onAction(ModelInsightsAction.OpenAsset(row.id)) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.asset, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(
                            row.owned,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1.2f),
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(row.realized, fontSize = 12.sp, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End)
                        Text(
                            row.unrealized,
                            color =
                                when (row.unrealizedTone) {
                                    ModelChangeTone.POSITIVE -> c.primary
                                    ModelChangeTone.NEGATIVE -> c.error
                                    ModelChangeTone.NEUTRAL -> c.onSurface
                                },
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1.1f),
                            textAlign = TextAlign.End,
                        )
                    }
                }
                state.gainsNote?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp) }
            }
        }
        MarketSection("Asset Location", "VALUE BY ACCOUNT") {
            MarketCard(Modifier.testTag("insights-location")) {
                SliceDonut(state.location, Modifier.align(Alignment.CenterHorizontally).size(if (wide) 220.dp else 180.dp))
                SliceLegend(state.location) {}
                Text("Most used accounts · ${state.range}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                if (state.accountUse.isEmpty()) Text("No activity in this period", color = c.onSurfaceVariant, fontSize = 12.sp)
                MetricRows(state.accountUse)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MarketCard(Modifier.weight(1f).testTag("insights-trades")) {
                Text("Trade Statistics", fontWeight = FontWeight.Bold)
                Text(state.trades, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("trades · ${state.tradedAssets} assets · ${state.range}", color = c.onSurfaceVariant, fontSize = 12.sp)
            }
            MarketCard(Modifier.weight(1f).testTag("insights-fees")) {
                Text("Fees", fontWeight = FontWeight.Bold)
                Text(state.feesTotal ?: if (state.fees.isEmpty()) "None" else "—", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                state.fees.forEach { Text("${it.amount} · ${it.value}", color = c.onSurfaceVariant, fontSize = 12.sp) }
                Text(state.range, color = c.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        MarketSection("Asset Worth", "POSITION VALUE · QUANTITY × PRICE") {
            MarketCard(Modifier.testTag("insights-worth")) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.worthAssets.forEach { asset ->
                        val chosen = asset.id == state.worthSelected
                        Text(
                            asset.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (chosen) c.onSurface else c.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .clip(CircleShape)
                                    .background(if (chosen) c.surfaceVariant else Color.Transparent)
                                    .selectable(chosen, role = Role.RadioButton) { onAction(ModelInsightsAction.WorthAsset(asset.id)) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
                state.worth?.let {
                    ModelInteractiveChart(it, state.chartStyle, Modifier.fillMaxWidth().height(160.dp), tint = c.tertiary)
                } ?: Text("No position in this period", color = c.onSurfaceVariant)
                Text(
                    "The value of the position you held: it rises when you buy as well as when the price rises.",
                    color = c.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun RangeRow(
    ranges: List<String>,
    selected: String,
    onRange: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ranges.forEach { range ->
            val chosen = range == selected
            Text(
                range,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (chosen) c.onSurface else c.onSurfaceVariant,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(if (chosen) c.surfaceVariant else Color.Transparent)
                        .selectable(chosen, role = Role.RadioButton) { onRange(range) }
                        .testTag("insights-range-$range")
                        .padding(horizontal = 9.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MetricRows(rows: List<ModelMetric>) {
    val c = MaterialTheme.colorScheme
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.label, color = c.onSurfaceVariant, fontSize = 13.sp)
            Text(row.value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

/** A donut with one arc per slice; an empty list draws nothing. */
@Composable
private fun SliceDonut(
    slices: List<ModelSlice>,
    modifier: Modifier,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val diameter = minOf(size.width, size.height) * .8f
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val stroke = Stroke(diameter * .22f)
            drawArc(track, 0f, 360f, false, topLeft, Size(diameter, diameter), style = stroke)
            var start = -90f
            slices.forEach { slice ->
                val sweep = 360f * slice.share.coerceIn(0f, 1f)
                // A hairline gap separates neighbouring slices.
                drawArc(slice.color, start, (sweep - 1f).coerceAtLeast(0.5f), false, topLeft, Size(diameter, diameter), style = stroke)
                start += sweep
            }
        }
        slices.firstOrNull()?.let { top ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(top.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text(percent(top.share), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun SliceLegend(
    slices: List<ModelSlice>,
    onSlice: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    slices.forEach { slice ->
        Row(
            Modifier.fillMaxWidth().clickable { onSlice(slice.id) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(slice.color))
            Text(slice.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(slice.value, color = c.onSurfaceVariant, fontSize = 13.sp)
            Text(percent(slice.share), fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

private fun percent(share: Float): String {
    val tenths = (share * 1000).toInt()
    return if (share > 0 && tenths < 1) "<0.1%" else "${tenths / 10}.${tenths % 10}%"
}

@Composable
internal fun MarketChart(
    points: List<Float?>,
    modifier: Modifier,
    tint: Color,
    labels: Boolean = true,
    topLabel: String = "—",
    bottomLabel: String = "—",
    smooth: Boolean = false,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().padding(vertical = if (labels) 20.dp else 0.dp)) {
            if (points.size >= 2) {
                if (labels) {
                    repeat(2) { row ->
                        var x = 0f
                        val y =
                            size.height * row
                        while (x < size.width) {
                            drawLine(muted, Offset(x, y), Offset(x + 1, y), 1f)
                            x += 4.dp.toPx()
                        }
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
                            val y = size.height * (1 - points[i]!!)
                            if (i ==
                                start
                            ) {
                                path.moveTo(x, y)
                            } else {
                                val px =
                                    size.width * (i - 1) / points.lastIndex
                                val py =
                                    size.height * (1 - points[i - 1]!!)
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
                        drawPath(area, Brush.verticalGradient(listOf(tint.copy(alpha = .2f), tint.copy(alpha = 0f))))
                        drawPath(path, tint, style = Stroke(1.6.dp.toPx()))
                    }
                    start = end + 1
                }
            }
        }
        if (labels) {
            Text(topLabel, color = muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
            Text(bottomLabel, color = muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}
