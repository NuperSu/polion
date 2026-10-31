package io.polion.ui

import io.polion.core.application.AssetListPreference
import io.polion.core.application.AssetSort
import io.polion.core.application.AssetValuation
import io.polion.core.market.PriceStatus
import io.polion.core.model.ExactDecimal

internal data class PricingSummary(
    val total: ExactDecimal?,
    val missing: Int,
    val stale: Int,
)

internal fun stalePriceMessage(count: Int): String = "$count asset${if (count == 1) " uses" else "s use"} stale prices"

/** A zero quantity needs no quote; it cannot make an unpriced nonzero holding appear priced. */
internal fun pricingSummary(valuations: List<AssetValuation>): PricingSummary {
    val holdings = valuations.filter { it.amount.atomicUnits.sign != 0 }
    val priced = holdings.filter { it.value != null }
    val total =
        when {
            valuations.isEmpty() -> null
            holdings.isEmpty() -> ExactDecimal.parse("0")
            priced.isEmpty() -> null
            else -> priced.fold(ExactDecimal.parse("0")) { sum, row -> sum + requireNotNull(row.value) }
        }
    return PricingSummary(total, holdings.count { it.value == null }, priced.count { it.priceStatus == PriceStatus.STALE })
}

/** Display filtering never alters the underlying balance projection. */
internal fun displayedAssets(
    valuations: List<AssetValuation>,
    preference: AssetListPreference,
): List<AssetValuation> {
    val one = ExactDecimal.parse("1")
    val visible =
        valuations.filterNot {
            val value = it.value
            (preference.hideEmpty && it.amount.atomicUnits.sign == 0) ||
                (
                    preference.hideSmall &&
                        it.amount.atomicUnits.sign > 0 &&
                        it.priceStatus == PriceStatus.FRESH &&
                        value != null &&
                        value.coefficient.sign > 0 &&
                        value < one
                )
        }
    return if (preference.sort == AssetSort.NAME) {
        visible.sortedWith(compareBy({ (it.metadata?.name ?: it.asset.value).lowercase() }, { it.asset.value }))
    } else {
        visible.sortedWith { a, b ->
            val aValue = a.value
            val bValue = b.value
            when {
                aValue == null && bValue != null -> 1
                aValue != null && bValue == null -> -1
                aValue != null && bValue != null -> bValue.compareTo(aValue).takeIf { it != 0 } ?: a.asset.value.compareTo(b.asset.value)
                else -> a.asset.value.compareTo(b.asset.value)
            }
        }
    }
}
