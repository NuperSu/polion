package io.polion.ui

import io.polion.core.application.HistoryPoint
import io.polion.core.model.AssetId

/** Change compares complete endpoint valuations within the displayed period. */
internal fun portfolioValueChange(
    points: List<HistoryPoint>,
    currency: AssetId,
    period: String,
): String {
    val first = points.firstOrNull()?.let(::historyTotal)
    val last = points.lastOrNull()?.let(::historyTotal)
    return "Value change · $period · " +
        if (points.size >= 2 && first != null && last != null) money(last - first, currency) else "—"
}

internal fun portfolioValuePercent(points: List<HistoryPoint>): String {
    val first = points.firstOrNull()?.let(::historyTotal) ?: return "—"
    val last = points.lastOrNull()?.let(::historyTotal) ?: return "—"
    if (points.size < 2 || first.coefficient.sign <= 0) return "—"
    val change =
        (
            io.polion.core.model.Rational
                .fromDecimal(last - first) /
                io.polion.core.model.Rational
                    .fromDecimal(first)
        ) *
            io.polion.core.model.Rational
                .of("100")
    return "${change.displayAmount()}%"
}

internal fun unitPriceChange(
    first: io.polion.core.model.ExactDecimal?,
    last: io.polion.core.model.ExactDecimal?,
    period: String,
): String {
    if (first == null || last == null || first.coefficient.sign <= 0) return "Price change · $period · —"
    val ratio =
        (
            io.polion.core.model.Rational
                .fromDecimal(last - first) /
                io.polion.core.model.Rational
                    .fromDecimal(first)
        ) *
            io.polion.core.model.Rational
                .of("100")
    return "Price change · $period · ${ratio.displayAmount()}%"
}

internal fun portfolioValueDelta(
    points: List<HistoryPoint>,
    currency: AssetId,
): String {
    val first = points.firstOrNull()?.let(::historyTotal) ?: return "—"
    val last = points.lastOrNull()?.let(::historyTotal) ?: return "—"
    return if (points.size >= 2) money(last - first, currency) else "—"
}
