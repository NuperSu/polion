package io.valroot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.valroot.core.application.HistoryPoint

@Composable
internal fun ValueChart(
    points: List<HistoryPoint>,
    modifier: Modifier,
    dark: Boolean,
    loading: Boolean,
    error: Boolean,
) {
    // Conversion to Float is exclusively for drawing coordinates; authoritative values remain exact.
    val values =
        points.map { point ->
            historyTotal(point)
                ?.toString()
                ?.toDoubleOrNull()
        }
    if (values.none { it != null }) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                when {
                    loading -> "Loading history…"
                    error -> "History could not be loaded"
                    points.isEmpty() -> "No history available yet"
                    points.all { it.valuations.isEmpty() } -> "No tracked holdings in this period"
                    else -> "Fresh historical prices are needed to show value over time"
                },
                color = if (dark) Color(0xFFAAC6B7) else Muted,
                fontSize = 12.sp,
            )
        }
    } else {
        val chartStroke = if (dark) Color(0xFFA9D6B6) else Teal
        Canvas(
            modifier.semantics {
                contentDescription =
                    "Portfolio value history chart; ${values.count {
                        it != null
                    }} available points, ${values.count { it == null }} unavailable points"
            },
        ) {
            val finite = values.filterNotNull().filter { it.isFinite() }
            if (finite.isEmpty()) return@Canvas
            val min = finite.min()
            val max = finite.max()
            val range = (max - min).takeIf { it > 0 } ?: 1.0
            val stroke = chartStroke
            repeat(3) { row ->
                val y = size.height * row / 2
                drawLine(stroke.copy(alpha = 0.13f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            val path = Path()
            var pen = false
            values.forEachIndexed { index, v ->
                if (v == null || !v.isFinite()) {
                    pen = false
                } else {
                    val x = size.width * index / (values.size - 1).coerceAtLeast(1)
                    val y = (size.height * (0.9 - 0.8 * ((v - min) / range))).toFloat()
                    if (pen) path.lineTo(x, y) else path.moveTo(x, y)
                    pen = true
                    drawCircle(stroke, 2.5f, Offset(x, y))
                }
            }
            drawPath(path, stroke, style = Stroke(width = 2.5.dp.toPx()))
        }
    }
}
