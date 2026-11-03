package io.polion.ui

import io.polion.core.application.AccountingView
import io.polion.core.application.AssetValuation
import io.polion.core.model.Rational
import io.polion.ui.model.ModelMetric

/** Scoped accounting evidence supplies figures only when treatments and acquisition basis permit them. */
internal fun coinAccountingMetrics(
    view: AccountingView?,
    asset: AssetValuation,
): List<ModelMetric> {
    val uncertain = view == null || view.missingTreatments.isNotEmpty() || view.invalidTreatments.isNotEmpty()

    fun total(values: List<Rational?>): Rational? =
        if (uncertain || values.any { it == null }) null else values.filterNotNull().fold(Rational.ZERO) { a, b -> a + b }
    val lots =
        view
            ?.report
            ?.lots
            .orEmpty()
            .filter { it.asset == asset.asset }
    val valuations =
        view
            ?.report
            ?.valuations
            .orEmpty()
            .filter { it.lot.asset == asset.asset }
    val basis = if (lots.isEmpty() && asset.amount.atomicUnits.sign != 0) null else total(lots.map { it.basis })
    val unrealized = if (valuations.isEmpty() && asset.amount.atomicUnits.sign != 0) null else total(valuations.map { it.unrealizedGain })
    val realized =
        total(
            view
                ?.report
                ?.disposals
                .orEmpty()
                .filter { it.asset == asset.asset }
                .map { it.gain },
        )
    val gains = if (unrealized == null || realized == null) null else unrealized + realized
    val suffix = if (view?.orderingUncertain?.isNotEmpty() == true) " · provisional" else ""

    fun figure(value: Rational?): String = value?.let { "${it.displayAmount()} ${view?.report?.quoteAsset?.value}$suffix" } ?: "—"
    return listOf(
        ModelMetric("Total Gains", figure(gains)),
        ModelMetric("Total Cost Basis", figure(basis)),
        ModelMetric("Unrealized Gain", figure(unrealized)),
        ModelMetric("Realized Gain", figure(realized)),
    )
}
