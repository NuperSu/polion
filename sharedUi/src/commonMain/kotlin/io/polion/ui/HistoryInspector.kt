package io.polion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import io.polion.core.application.HistoryPoint
import io.polion.core.market.PriceStatus
import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal
import kotlin.time.Instant

/**
 * Historical chart total: the sum of nonzero holdings with a fresh price at the sampled time. Holdings without one are
 * left out (see [historyUnpriced]), so an asset whose history is not known yet does not blank the rest; the total is
 * unavailable only when no holding has a fresh price.
 */
internal fun historyTotal(point: HistoryPoint): ExactDecimal? {
    if (point.valuations.isEmpty()) return null
    val holdings = point.valuations.filter { it.amount.atomicUnits.sign != 0 }
    val priced = holdings.filter { it.value != null && it.priceStatus == PriceStatus.FRESH }
    if (holdings.isNotEmpty() && priced.isEmpty()) return null
    return priced.fold(ExactDecimal.parse("0")) { sum, valuation -> sum + requireNotNull(valuation.value) }
}

/** Nonzero holdings left out of [historyTotal] because they have no fresh price at the sampled time. */
internal fun historyUnpriced(point: HistoryPoint): Int =
    point.valuations.count { it.amount.atomicUnits.sign != 0 && (it.value == null || it.priceStatus != PriceStatus.FRESH) }

@Composable
internal fun HistoryInspector(
    points: List<HistoryPoint>,
    currency: AssetId,
) {
    if (points.isEmpty()) return
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    val point = points[selected]
    val tint = Color(0xFFD6E9DD)
    Column(Modifier.fillMaxWidth()) {
        Text("Select a historical point", color = tint, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { selected-- },
                enabled = selected > 0,
                colors = ButtonDefaults.textButtonColors(contentColor = tint, disabledContentColor = tint.copy(alpha = .4f)),
                modifier = Modifier.testTag("history-previous"),
            ) { Text("Previous") }
            Text("${selected + 1} / ${points.size}", color = tint, fontSize = 11.sp)
            TextButton(
                onClick = { selected++ },
                enabled = selected < points.lastIndex,
                colors = ButtonDefaults.textButtonColors(contentColor = tint, disabledContentColor = tint.copy(alpha = .4f)),
                modifier = Modifier.testTag("history-next"),
            ) { Text("Next") }
        }
        val timestamp =
            Instant
                .fromEpochMilliseconds(point.atMillis)
                .toString()
                .take(16)
                .replace('T', ' ')
        val value = historyTotal(point)
        val holdings = point.valuations.filter { it.amount.atomicUnits.sign != 0 }
        val unavailable =
            if (holdings.any { it.value == null || it.priceStatus == PriceStatus.MISSING }) {
                "Value unavailable · missing price"
            } else if (holdings.any { it.priceStatus == PriceStatus.STALE }) {
                "Value unavailable · stale price"
            } else {
                "Value unavailable"
            }
        val unpriced = historyUnpriced(point)
        val partial =
            if (unpriced == 1) {
                " · 1 asset has no price"
            } else if (unpriced > 1) {
                " · $unpriced assets have no price"
            } else {
                ""
            }
        Text(
            if (value == null) "$timestamp UTC · $unavailable" else "$timestamp UTC · $value ${currency.value}$partial",
            color = tint,
            fontSize = 12.sp,
            modifier = Modifier.testTag("history-point"),
        )
    }
}
