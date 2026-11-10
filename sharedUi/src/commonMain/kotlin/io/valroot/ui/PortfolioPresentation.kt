package io.valroot.ui

import io.valroot.core.application.AccountingView
import io.valroot.core.application.AssetValuation
import io.valroot.core.application.HistoryPoint
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.Rational
import io.valroot.ui.model.ChartData
import io.valroot.ui.model.ModelChangeTone
import kotlin.math.roundToLong

/** Chart tooltips work with floating-point values; amounts are rounded to cents only for display. */
internal fun Double.asMoney(currency: AssetId): String =
    money(ExactDecimal(ExactInteger.parse((this * 100).roundToLong().toString()), 2), currency)

internal fun Rational.asMoney(currency: AssetId): String = money(ExactDecimal.parse(displayAmount()), currency)

/** The portfolio value series with its sample times, ready for the interactive chart. */
internal fun portfolioChartData(
    points: List<HistoryPoint>,
    currency: AssetId,
    hideValues: Boolean,
): ChartData? {
    if (points.size < 2) return null
    val span = points.last().atMillis - points.first().atMillis
    return ChartData(
        points.map { point -> historyTotal(point)?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite) },
        points.map { it.atMillis },
        { it.asMoney(currency) },
        { chartTime(it, span) },
        hideValues,
    )
}

/** A signed amount: "+$1,234.00" for gains, "-$12.00" for losses. */
internal fun signedMoney(
    value: Rational,
    currency: AssetId,
): String = (if (value > Rational.ZERO) "+" else "") + value.asMoney(currency)

internal fun tone(value: Rational?): ModelChangeTone =
    when {
        value == null || value == Rational.ZERO -> ModelChangeTone.NEUTRAL
        value > Rational.ZERO -> ModelChangeTone.POSITIVE
        else -> ModelChangeTone.NEGATIVE
    }

/** Portfolio-wide FIFO results; each figure is null when any treatment or acquisition basis it needs is missing. */
internal data class PortfolioPerformance(
    val basis: Rational?,
    val unrealized: Rational?,
    val realized: Rational?,
    val provisional: Boolean,
) {
    val total: Rational? get() = if (unrealized == null || realized == null) null else unrealized + realized

    /** Unrealized gain as a percentage of the open cost basis. */
    val unrealizedPercent: String
        get() {
            if (basis == null || unrealized == null || basis <= Rational.ZERO) return "—"
            val percent = unrealized / basis * Rational.of("100")
            return (if (percent > Rational.ZERO) "+" else "") + percent.displayAmount() + "%"
        }
}

internal fun portfolioPerformance(view: AccountingView?): PortfolioPerformance? {
    if (view == null) return null
    val uncertain = view.missingTreatments.isNotEmpty() || view.invalidTreatments.isNotEmpty()

    fun total(values: List<Rational?>): Rational? =
        if (uncertain || values.any { it == null }) null else values.filterNotNull().fold(Rational.ZERO) { a, b -> a + b }
    return PortfolioPerformance(
        total(view.report.lots.map { it.basis }),
        total(view.report.valuations.map { it.unrealizedGain }),
        total(view.report.disposals.map { it.gain }),
        view.orderingUncertain.isNotEmpty(),
    )
}

/** Priced holdings with their share of the priced total, largest first. */
internal fun allocation(valuations: List<AssetValuation>): List<Pair<AssetValuation, Double>> {
    val priced = valuations.filter { it.amount.atomicUnits.sign > 0 && it.value != null && it.value!!.coefficient.sign > 0 }
    val values = priced.associateWith { it.value!!.toString().toDouble() }
    val total = values.values.sum()
    if (total <= 0) return emptyList()
    return values.entries.sortedByDescending { it.value }.map { it.key to it.value / total }
}
