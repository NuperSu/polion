package io.valroot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.HistoryPoint
import io.valroot.core.application.PortfolioSnapshot

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BalanceHero(
    data: PortfolioSnapshot,
    points: List<HistoryPoint>,
    chartLoading: Boolean,
    chartError: Boolean,
    compact: Boolean,
    period: Int,
    onPeriod: (Int) -> Unit,
    onPrice: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Corners)
            .background(MaterialTheme.colorScheme.surface)
            .padding(if (compact) 22.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("PRICED VALUE", color = Muted, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Medium)
            Text(data.currency.value, color = Muted, fontSize = 12.sp)
        }
        val pricing = pricingSummary(data.valuations)
        Text(
            pricing.total?.let { money(it, data.currency) } ?: "—",
            color = Ink,
            fontSize = if (compact) 34.sp else 44.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-1).sp,
            maxLines = 2,
        )
        if (pricing.stale > 0) Text(stalePriceMessage(pricing.stale), color = Muted, fontSize = 12.sp)
        if (chartLoading && points.isNotEmpty()) Text("Updating history…", color = Muted, fontSize = 12.sp)
        val firstValue = points.firstOrNull()?.let(::historyTotal)
        val lastValue = points.lastOrNull()?.let(::historyTotal)
        Text(
            "Value change · " + if (firstValue != null && lastValue != null) money(lastValue - firstValue, data.currency) else "—",
            color = Muted,
            fontSize = 12.sp,
        )
        ValueChart(
            points,
            Modifier.fillMaxWidth().height(if (compact) 112.dp else 150.dp),
            dark = false,
            loading = chartLoading,
            error = chartError,
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1 to "1D", 7 to "1W", 30 to "1M", 90 to "3M", 365 to "1Y", 0 to "ALL").forEach { (days, label) ->
                Text(
                    label,
                    color =
                        if (period ==
                            days
                        ) {
                            Ink
                        } else {
                            Muted
                        },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (period ==
                                    days
                                ) {
                                    Mint
                                } else {
                                    Color.Transparent
                                },
                            ).selectable(selected = period == days, role = Role.RadioButton) { onPeriod(days) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        Text("Historical portfolio value · not investment return", color = Muted, fontSize = 11.sp)
        var chartDetails by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { chartDetails = !chartDetails }) { Text(if (chartDetails) "Hide chart details" else "Chart details") }
        if (chartDetails && !chartLoading && !chartError) HistoryInspector(points, data.currency)
        val missing = pricing.missing
        if (missing >
            0
        ) {
            Text(
                "$missing unpriced asset${if (missing == 1) "" else "s"} excluded · Add a price →",
                color = Orange,
                fontSize = 12.sp,
                modifier = Modifier.clickable(role = Role.Button, onClick = onPrice).padding(vertical = 4.dp),
            )
        }
    }
}
