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
import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal
import kotlin.time.Instant

/** The displayed total is available only when every holding at that point is priced. */
internal fun historyTotal(point: HistoryPoint): ExactDecimal? {
    if (point.valuations.isEmpty() || point.valuations.any { it.value == null }) return null
    return point.valuations.fold(ExactDecimal.parse("0")) { sum, valuation -> sum + requireNotNull(valuation.value) }
}

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
        Text(
            if (value == null) "$timestamp UTC · Value unavailable" else "$timestamp UTC · $value ${currency.value}",
            color = tint,
            fontSize = 12.sp,
            modifier = Modifier.testTag("history-point"),
        )
    }
}
