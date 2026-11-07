package io.polion.ui.model

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class ChartStyle(
    val label: String,
) {
    LINE("Line"),
    CANDLES("Candles"),
}

/** Raw values with their times; formatting stays with the caller so the chart never guesses currencies or time zones. */
data class ChartData(
    val values: List<Double?>,
    val times: List<Long>,
    val formatValue: (Double) -> String,
    val formatTime: (Long) -> String,
    /** Amounts are hidden: the tooltip and axis keep percentages and times only. */
    val hideValues: Boolean = false,
) {
    init {
        require(values.size == times.size)
    }

    val known: List<Double> get() = values.filterNotNull()
}

/** One candle built from consecutive samples: first, highest, lowest and last known value in its time bucket. */
data class Candle(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val start: Long,
    val end: Long,
)

/**
 * Groups samples into about [target] candles. Buckets without a known value are skipped, so a gap stays a gap; the
 * candles are only as detailed as the sampled series.
 */
fun buildCandles(
    data: ChartData,
    target: Int = 24,
): List<Candle?> {
    if (data.values.size < 2) return emptyList()
    val per = ceil(data.values.size / target.toDouble()).toInt().coerceAtLeast(1)
    return data.values.indices.chunked(per).map { bucket ->
        val known = bucket.filter { data.values[it] != null }
        if (known.isEmpty()) {
            null
        } else {
            val values = known.map { data.values[it]!! }
            Candle(values.first(), values.max(), values.min(), values.last(), data.times[bucket.first()], data.times[bucket.last()])
        }
    }
}

/** "+3.21%" style change from [from] to [to]; "—" without a positive starting value. */
internal fun chartPercent(
    from: Double?,
    to: Double,
): String {
    if (from == null || from <= 0) return "—"
    val change = (to - from) / from * 100
    val hundredths = (abs(change) * 100).roundToLong()
    val sign =
        if (hundredths == 0L) {
            ""
        } else if (change < 0) {
            "-"
        } else {
            "+"
        }
    return "$sign${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}%"
}

private const val HIDDEN = "*****"

/**
 * A line or candle chart that shows the value under the pointer: hover with a mouse, or touch and drag. The tooltip
 * gives the value, its change since the first value in range, and the time.
 */
@Composable
fun ModelInteractiveChart(
    data: ChartData,
    style: ChartStyle,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    falling: Color = MaterialTheme.colorScheme.error,
    smooth: Boolean = false,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val known = data.known
    val first = data.values.firstOrNull { it != null }
    val candles = remember(data, style) { if (style == ChartStyle.CANDLES) buildCandles(data) else emptyList() }
    val low = if (style == ChartStyle.CANDLES) candles.filterNotNull().minOfOrNull { it.low } else known.minOrNull()
    val high = if (style == ChartStyle.CANDLES) candles.filterNotNull().maxOfOrNull { it.high } else known.maxOrNull()
    var selected by remember(data, style) { mutableStateOf<Int?>(null) }
    val slots = if (style == ChartStyle.CANDLES) candles.size else data.values.size

    fun y(
        value: Double,
        height: Float,
    ): Float = if (low == null || high == null || high == low) height / 2 else height * (1 - ((value - low) / (high - low)).toFloat())

    fun label(value: Double?) = value?.let { if (data.hideValues) HIDDEN else data.formatValue(it) } ?: "—"
    BoxWithConstraints(modifier.testTag("interactive-chart")) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(vertical = 20.dp)
                .pointerInput(slots) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val touch = change.type == PointerType.Touch
                            when (event.type) {
                                PointerEventType.Exit -> selected = null
                                PointerEventType.Release -> if (touch) selected = null
                                PointerEventType.Enter, PointerEventType.Press, PointerEventType.Move -> {
                                    if (!touch || change.pressed) {
                                        if (slots >= 2) {
                                            val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                            selected =
                                                if (style == ChartStyle.CANDLES) {
                                                    (fraction * slots).toInt().coerceIn(0, slots - 1)
                                                } else {
                                                    (fraction * (slots - 1)).roundToInt()
                                                }
                                        }
                                        // Scrubbing on a phone should not scroll the page.
                                        if (touch) change.consume()
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                },
        ) {
            repeat(2) { row ->
                val lineY = size.height * row
                var x = 0f
                while (x < size.width) {
                    drawLine(muted, Offset(x, lineY), Offset(x + 1, lineY), 1f)
                    x += 4.dp.toPx()
                }
            }
            if (style == ChartStyle.CANDLES) {
                if (candles.isEmpty()) return@Canvas
                val slot = size.width / candles.size
                candles.forEachIndexed { index, candle ->
                    if (candle == null) return@forEachIndexed
                    val color = if (candle.close >= candle.open) tint else falling
                    val center = slot * index + slot / 2
                    drawLine(color, Offset(center, y(candle.high, size.height)), Offset(center, y(candle.low, size.height)), 1.2.dp.toPx())
                    val top = y(maxOf(candle.open, candle.close), size.height)
                    val bottom = y(minOf(candle.open, candle.close), size.height)
                    drawRect(
                        color,
                        Offset(center - slot * 0.32f, top),
                        Size(slot * 0.64f, (bottom - top).coerceAtLeast(1.5.dp.toPx())),
                    )
                }
                selected?.let { index ->
                    val x = slot * index + slot / 2
                    drawLine(muted, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
            } else {
                val values = data.values
                if (values.size < 2) return@Canvas
                var start = 0
                while (start < values.size) {
                    while (start < values.size && values[start] == null) start++
                    var end = start
                    while (end < values.size && values[end] != null) end++
                    if (end - start >= 2) {
                        val path = Path()
                        for (i in start until end) {
                            val x = size.width * i / values.lastIndex
                            val y = y(values[i]!!, size.height)
                            if (i == start) {
                                path.moveTo(x, y)
                            } else {
                                val px = size.width * (i - 1) / values.lastIndex
                                val py = y(values[i - 1]!!, size.height)
                                if (smooth) path.cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y) else path.lineTo(x, y)
                            }
                        }
                        val area =
                            Path().apply {
                                addPath(path)
                                lineTo(size.width * (end - 1) / values.lastIndex, size.height)
                                lineTo(size.width * start / values.lastIndex, size.height)
                                close()
                            }
                        drawPath(area, Brush.verticalGradient(listOf(tint.copy(alpha = .2f), tint.copy(alpha = 0f))))
                        drawPath(path, tint, style = Stroke(1.6.dp.toPx()))
                    } else if (end - start == 1) {
                        drawCircle(tint, 2.dp.toPx(), Offset(size.width * start / values.lastIndex, y(values[start]!!, size.height)))
                    }
                    start = end + 1
                }
                selected?.let { index ->
                    val x = size.width * index / values.lastIndex
                    drawLine(muted, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    values[index]?.let { drawCircle(tint, 4.dp.toPx(), Offset(x, y(it, size.height))) }
                }
            }
        }
        if (selected == null) {
            Text(label(high), color = muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
            Text(label(low), color = muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
        }
        selected?.let { index ->
            val lines =
                if (style == ChartStyle.CANDLES) {
                    candles.getOrNull(index)?.let { candle ->
                        listOf(
                            "O ${label(candle.open)}  C ${label(candle.close)}",
                            "H ${label(candle.high)}  L ${label(candle.low)}",
                            "${chartPercent(first, candle.close)} · ${data.formatTime(candle.start)} – ${data.formatTime(candle.end)}",
                        )
                    } ?: listOf("No price in this period")
                } else {
                    val value = data.values.getOrNull(index)
                    if (value == null) {
                        listOf("No value", data.formatTime(data.times[index]))
                    } else {
                        listOf("${label(value)} · ${chartPercent(first, value)}", data.formatTime(data.times[index]))
                    }
                }
            val fraction =
                if (slots <= 1) {
                    0f
                } else if (style == ChartStyle.CANDLES) {
                    (index + 0.5f) / slots
                } else {
                    index / (slots - 1f)
                }
            val bubbleWidth = with(LocalDensity.current) { 190.dp.toPx() }
            val x = (fraction * widthPx - bubbleWidth / 2).coerceIn(0f, (widthPx - bubbleWidth).coerceAtLeast(0f))
            Column(
                Modifier
                    .offset { IntOffset(x.roundToInt(), 0) }
                    .widthIn(max = 190.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .testTag("chart-tooltip"),
            ) {
                lines.forEachIndexed { i, text ->
                    Text(
                        text,
                        fontSize = if (i == 0) 13.sp else 11.sp,
                        fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == 0) MaterialTheme.colorScheme.onSurface else muted,
                    )
                }
            }
        }
    }
}

/** Line or candles, as two small pills. */
@Composable
fun ChartStyleToggle(
    style: ChartStyle,
    onStyle: (ChartStyle) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.clip(CircleShape).background(c.surfaceVariant.copy(alpha = .5f)).padding(2.dp)) {
        ChartStyle.entries.forEach { option ->
            val chosen = option == style
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (chosen) c.surfaceVariant else Color.Transparent)
                    .selectable(selected = chosen, role = Role.RadioButton) { onStyle(option) }
                    .testTag("chart-style-${option.name.lowercase()}")
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    option.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (chosen) c.onSurface else c.onSurfaceVariant,
                )
            }
        }
    }
}
