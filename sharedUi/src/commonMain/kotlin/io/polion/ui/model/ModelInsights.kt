package io.polion.ui.model

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

data class ModelPerformanceGroup(
    val label: String,
    val values: List<Float>,
)

data class ModelDonutState(
    val label: String = "Unavailable",
    val amount: String = "—",
    val fraction: Float = 0f,
    val legend: String = "Unavailable",
)

data class ModelGainRow(
    val asset: String,
    val owned: String,
    val realized: String,
    val unrealized: String,
)

data class ModelInsightsState(
    val balance: String = "—",
    val historyRange: String = "1Y",
    val history: List<Float?> = emptyList(),
    val historyTopLabel: String = "—",
    val historyBottomLabel: String = "—",
    val smoothChart: Boolean = false,
    val performance: List<ModelPerformanceGroup> = emptyList(),
    val performanceLegend: String = "Unavailable",
    val portfolio: String = "Unavailable",
    val decisionsAvailable: Boolean = false,
    val diversity: ModelDonutState = ModelDonutState(),
    val gains: List<ModelGainRow> = emptyList(),
    val exchange: String = "Unavailable",
    val location: ModelDonutState = ModelDonutState(),
    val tradeCount: String = "—",
    val assetCount: String = "—",
    val fees: String = "—",
    val feesPeriod: String = "All time",
    val assetWorth: List<Float?> = emptyList(),
    val assetWorthTopLabel: String = "—",
    val assetWorthBottomLabel: String = "—",
    val assetWorthLegend: String = "Unavailable",
)

sealed interface ModelInsightsAction {
    data object SelectHistoryRange : ModelInsightsAction
}

@Composable
fun ModelInsights(
    state: ModelInsightsState,
    wide: Boolean,
    onAction: (ModelInsightsAction) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val purple = MaterialTheme.colorScheme.tertiary
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        MarketHeading("History")
        MarketCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Balance", color = muted, fontSize = 12.sp)
                    Text(state.balance, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                Text(
                    "${state.historyRange}  ⌄",
                    color = muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clip(
                                CircleShape,
                            ).border(1.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                )
            }
            MarketChart(
                state.history,
                Modifier.fillMaxWidth().height(180.dp),
                MaterialTheme.colorScheme.error,
                topLabel = state.historyTopLabel,
                bottomLabel = state.historyBottomLabel,
                smooth = state.smoothChart,
            )
        }
        MarketHeading("Portfolio Performance")
        MarketCard {
            val colors = listOf(purple, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiaryContainer)
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                val baseline = size.height * .55f
                if (state.performance.isNotEmpty()) {
                    drawLine(Color.Gray, Offset(0f, baseline), Offset(size.width, baseline), 1f)
                    state.performance.forEachIndexed { group, item ->
                        item.values.take(3).forEachIndexed { bar, value ->
                            val x = size.width * (.07f + group * .25f) + bar * 12.dp.toPx()
                            drawRoundRect(
                                colors[bar],
                                Offset(
                                    x,
                                    if (value >
                                        0
                                    ) {
                                        baseline - value * size.height
                                    } else {
                                        baseline
                                    },
                                ),
                                Size(9.dp.toPx(), abs(value) * size.height),
                                CornerRadius(3.dp.toPx()),
                            )
                        }
                    }
                }
            }
            if (state.performance.isEmpty()) {
                Text(
                    "Unavailable",
                    color = muted,
                    fontSize = 12.sp,
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    state.performance.forEach { Text(it.label, color = muted, fontSize = 11.sp) }
                }
            }
            Text(state.performanceLegend, color = muted, fontSize = 11.sp)
        }
        MarketHeading("Decisions")
        MarketCard(color = MaterialTheme.colorScheme.secondaryContainer) {
            Text("Decisions", color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(state.portfolio, fontSize = 14.sp)
            if (state.decisionsAvailable) {
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("↗  Good\n    decisions", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                    Text("Bad  ↘\ndecisions", color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
                }
            } else {
                Text("Unavailable", color = muted, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
        MarketHeading("Portfolio Diversity")
        MarketCard {
            InsightDonut(state.diversity, Modifier.align(Alignment.CenterHorizontally).size(if (wide) 280.dp else 220.dp))
            Text(state.diversity.legend, color = muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        MarketHeading("Gains Reporting")
        MarketCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("Asset", "Owned", "Realized", "Unrealized").forEach { Text(it, color = muted) }
            }
            if (state.gains.isEmpty()) Text("Unavailable", color = muted, fontSize = 12.sp)
            state.gains.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.asset, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(row.owned, fontSize = 11.sp, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End)
                    Text(row.realized, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text(
                        row.unrealized,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1.1f),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MarketCard(Modifier.weight(1f)) {
                Text("Most Used\nExchanges", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(55.dp))
                Text(state.exchange, color = muted)
                Spacer(Modifier.height(55.dp))
            }
            MarketCard(Modifier.weight(1f)) {
                Text("Asset Location", fontWeight = FontWeight.Bold)
                InsightDonut(state.location, Modifier.fillMaxWidth().height(170.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MarketCard(Modifier.weight(1f)) {
                Text("Trade Statistics", fontWeight = FontWeight.Bold)
                Text(state.tradeCount, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("/ ${state.assetCount} assets", color = muted, fontSize = 12.sp)
            }
            MarketCard(Modifier.weight(1f)) {
                Text("Fees", fontWeight = FontWeight.Bold)
                Text(state.fees, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(state.feesPeriod, color = muted, fontSize = 12.sp)
            }
        }
        MarketHeading("Asset Worth")
        MarketCard {
            MarketChart(
                state.assetWorth,
                Modifier.fillMaxWidth().height(160.dp),
                purple,
                topLabel = state.assetWorthTopLabel,
                bottomLabel = state.assetWorthBottomLabel,
                smooth = state.smoothChart,
            )
            Text(state.assetWorthLegend, color = muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun InsightDonut(
    state: ModelDonutState,
    modifier: Modifier,
) {
    val purple = MaterialTheme.colorScheme.tertiary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val diameter = minOf(size.width, size.height) * .78f
            if (state.fraction >
                0f
            ) {
                drawArc(
                    purple,
                    .5f,
                    359f * state.fraction.coerceIn(0f, 1f),
                    false,
                    Offset(
                        (size.width - diameter) / 2,
                        (size.height - diameter) / 2,
                    ),
                    Size(diameter, diameter),
                    style = Stroke(diameter * .24f),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(state.amount, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
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
