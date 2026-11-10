package io.valroot.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One performance figure: a label, a formatted value, and an optional signed detail such as a percentage. */
data class ModelStat(
    val label: String,
    val value: String,
    val detail: String? = null,
    val tone: ModelChangeTone = ModelChangeTone.NEUTRAL,
)

data class ModelAllocationSlice(
    val label: String,
    val share: Float,
    val color: Color,
)

/** Two cards per row on phones, four on wide screens. */
@Composable
fun ModelPortfolioStats(
    stats: List<ModelStat>,
    wide: Boolean,
    note: String? = null,
) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("portfolio-stats")) {
        MarketHeading("Performance", "COST BASIS AND GAINS · FIFO")
        stats.chunked(if (wide) 4 else 2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { stat ->
                    val tint =
                        when (stat.tone) {
                            ModelChangeTone.POSITIVE -> c.primary
                            ModelChangeTone.NEGATIVE -> c.error
                            ModelChangeTone.NEUTRAL -> c.onSurface
                        }
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.surface)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stat.label, color = c.onSurfaceVariant, fontSize = 12.sp)
                        Text(
                            stat.value,
                            color = tint,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        stat.detail?.let {
                            Text(
                                it,
                                color =
                                    if (stat.tone ==
                                        ModelChangeTone.NEUTRAL
                                    ) {
                                        c.onSurfaceVariant
                                    } else {
                                        tint
                                    },
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                repeat((if (wide) 4 else 2) - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        note?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp) }
    }
}

/** A single stacked bar of holdings by value, with a legend of the largest holdings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelAllocationBar(slices: List<ModelAllocationSlice>) {
    if (slices.isEmpty()) return
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("portfolio-allocation")) {
        MarketHeading("Allocation", "SHARE OF PRICED VALUE")
        Row(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            slices.forEach { slice ->
                Box(Modifier.weight(slice.share.coerceAtLeast(0.005f)).height(12.dp).background(slice.color))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(slice.color))
                    Text(slice.label, color = c.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(percentLabel(slice.share), color = c.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun percentLabel(share: Float): String {
    val tenths = (share * 1000).toInt()
    return if (tenths < 1) "<0.1%" else "${tenths / 10}.${tenths % 10}%"
}
