package io.polion.ui.model

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Preformatted values and normalized chart observations supplied by the host. */
data class ModelPortfolioSummaryState(
    val balance: String,
    val currency: String,
    val change: String,
    val percentChange: String,
    val selectedRange: String,
    val chartPoints: List<Float?>,
    val changeTone: ModelChangeTone = ModelChangeTone.NEUTRAL,
    val smoothChart: Boolean = false,
    val enabledRanges: Set<String> = setOf("1H", "1D", "1W", "1M", "YTD", "1Y", "ALL"),
    val chartTopLabel: String,
    val chartBottomLabel: String,
    val ranges: List<String> = listOf("1H", "1D", "1W", "1M", "YTD", "1Y", "ALL"),
)

enum class ModelChangeTone { POSITIVE, NEGATIVE, NEUTRAL }

data class ModelSummaryColors(
    val foreground: Color = Color(0xFFF5F5F5),
    val muted: Color = Color(0xFF969696),
    val capsule: Color = Color(0xFF252525),
    val positive: Color = Color(0xFF27D569),
    val negative: Color = Color(0xFFFF3558),
)

@Composable
fun ModelPortfolioSummary(
    state: ModelPortfolioSummaryState,
    wide: Boolean,
    short: Boolean,
    onRangeSelected: (String) -> Unit,
    onExplain: (() -> Unit)? = null,
    onCurrency: (() -> Unit)? = null,
    currencyMenuContent: @Composable () -> Unit = {},
    colors: ModelSummaryColors =
        ModelSummaryColors(
            foreground = MaterialTheme.colorScheme.onSurface,
            muted = MaterialTheme.colorScheme.onSurfaceVariant,
            capsule = MaterialTheme.colorScheme.surfaceVariant,
            positive = MaterialTheme.colorScheme.primary,
            negative = MaterialTheme.colorScheme.error,
        ),
) {
    val changeColor =
        when (state.changeTone) {
            ModelChangeTone.POSITIVE -> colors.positive
            ModelChangeTone.NEGATIVE -> colors.negative
            ModelChangeTone.NEUTRAL -> colors.muted
        }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "✧  What's going on?",
            color = colors.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .then(
                        if (onExplain !=
                            null
                        ) {
                            Modifier.clickable(onClick = onExplain)
                        } else {
                            Modifier
                        },
                    ).clip(CircleShape)
                    .border(1.dp, colors.capsule, CircleShape)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(state.balance, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = colors.foreground, letterSpacing = (-1).sp)
            Box {
                Text(
                    state.currency,
                    color = colors.muted,
                    fontSize = 17.sp,
                    modifier =
                        Modifier
                            .then(
                                if (onCurrency !=
                                    null
                                ) {
                                    Modifier.clickable(onClick = onCurrency)
                                } else {
                                    Modifier
                                },
                            ).padding(bottom = 10.dp),
                )
                currencyMenuContent()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(state.change, color = changeColor, fontSize = 17.sp)
            Text(
                state.percentChange,
                color = changeColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .clip(
                            RoundedCornerShape(3.dp),
                        ).background(changeColor.copy(alpha = .17f))
                        .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(
                if (wide) {
                    280.dp
                } else if (short) {
                    100.dp
                } else {
                    200.dp
                },
            ),
        ) {
            Canvas(Modifier.fillMaxSize().padding(vertical = 20.dp)) {
                val values = state.chartPoints
                if (values.size >= 2) {
                    repeat(2) { row ->
                        val y = size.height * row
                        var x = 0f
                        while (x < size.width) {
                            drawLine(colors.muted, Offset(x, y), Offset(x + 1, y), 1f)
                            x += 4.dp.toPx()
                        }
                    }
                    var start = 0
                    while (start < values.size) {
                        while (start < values.size && values[start] == null) start++
                        var end = start
                        while (end < values.size && values[end] != null) end++
                        if (end - start >= 2) {
                            val path = Path()
                            for (i in start until end) {
                                val x = size.width * i / values.lastIndex
                                val y = size.height * (1 - values[i]!!)
                                if (i == start) {
                                    path.moveTo(x, y)
                                } else {
                                    val px = size.width * (i - 1) / values.lastIndex
                                    val py = size.height * (1 - values[i - 1]!!)
                                    if (state.smoothChart) path.cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y) else path.lineTo(x, y)
                                }
                            }
                            val area =
                                Path().apply {
                                    addPath(path)
                                    lineTo(size.width * (end - 1) / values.lastIndex, size.height)
                                    lineTo(size.width * start / values.lastIndex, size.height)
                                    close()
                                }
                            drawPath(
                                area,
                                Brush.verticalGradient(listOf(colors.positive.copy(alpha = .2f), colors.positive.copy(alpha = 0f))),
                            )
                            drawPath(path, colors.positive, style = Stroke(1.6.dp.toPx()))
                        }
                        start = end + 1
                    }
                }
            }
            Text(state.chartTopLabel, color = colors.muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
            Text(state.chartBottomLabel, color = colors.muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
        }
        Row(
            Modifier.fillMaxWidth().then(
                if (!wide && LocalDensity.current.fontScale > 1f) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
            ),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            state.ranges.forEach { label ->
                Text(
                    label,
                    color = if (label in state.enabledRanges && label == state.selectedRange) colors.foreground else colors.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .background(
                                if (label ==
                                    state.selectedRange
                                ) {
                                    colors.capsule
                                } else {
                                    Color.Transparent
                                },
                            ).selectable(
                                selected = label == state.selectedRange,
                                role = Role.Tab,
                                enabled = label in state.enabledRanges,
                            ) { onRangeSelected(label) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
        }
    }
}
