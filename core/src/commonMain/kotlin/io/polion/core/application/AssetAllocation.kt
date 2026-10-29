package io.polion.core.application

import io.polion.core.holdings.BalanceCoverage
import io.polion.core.market.PriceStatus
import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import io.polion.core.model.Rational
import io.polion.core.model.Rounding

enum class AllocationExclusionReason { MISSING_PRICE, NON_POSITIVE_AMOUNT, NON_POSITIVE_VALUE }

data class AllocationExclusion(
    val valuation: AssetValuation,
    val reason: AllocationExclusionReason,
)

data class AssetAllocationSlice(
    val valuation: AssetValuation,
    val share: Rational,
    val displayedValue: ExactDecimal,
    val percentage: ExactDecimal,
)

data class AssetAllocationProjection(
    val currency: AssetId,
    val coverage: BalanceCoverage,
    val slices: List<AssetAllocationSlice>,
    val excluded: List<AllocationExclusion>,
    val pricedSubtotal: ExactDecimal,
    val displayedPricedSubtotal: ExactDecimal,
)

/** Allocation of positive priced holdings in an already selected ownership scope. */
object AssetAllocation {
    fun project(
        valuations: List<AssetValuation>,
        currency: AssetId,
        coverage: BalanceCoverage,
        displayScale: Int = 2,
    ): AssetAllocationProjection {
        require(displayScale >= 0)
        require(valuations.map { it.asset }.distinct().size == valuations.size) { "Expected one scoped valuation per asset" }
        val excluded = mutableListOf<AllocationExclusion>()
        val included =
            valuations.sortedBy { it.asset.value }.filter { row ->
                val reason =
                    when {
                        row.amount.atomicUnits.sign <= 0 -> AllocationExclusionReason.NON_POSITIVE_AMOUNT
                        row.priceStatus == PriceStatus.MISSING || row.value == null -> AllocationExclusionReason.MISSING_PRICE
                        row.value.coefficient.sign <= 0 -> AllocationExclusionReason.NON_POSITIVE_VALUE
                        else -> null
                    }
                if (reason != null) excluded.add(AllocationExclusion(row, reason))
                reason == null
            }
        val subtotal = included.fold(ExactDecimal.parse("0")) { sum, row -> sum + row.value!! }
        val displayedSubtotal = subtotal.rescale(displayScale, Rounding.HALF_UP)
        if (included.isEmpty()) {
            return AssetAllocationProjection(currency, coverage, emptyList(), excluded, subtotal, displayedSubtotal)
        }
        val commonScale = included.maxOf { it.value!!.scale }
        val weights = included.map { it.value!!.rescale(commonScale, Rounding.DOWN).coefficient }
        val total = weights.fold(ExactInteger.ZERO) { sum, weight -> sum + weight }
        val percentages = distribute(weights, total, ExactInteger.parse("10000"))
        val displayed = included.map { it.value!!.rescale(displayScale, Rounding.DOWN).coefficient }.toMutableList()
        var remaining = displayedSubtotal.coefficient - displayed.fold(ExactInteger.ZERO) { sum, value -> sum + value }
        val remainderOrder =
            included.indices.sortedWith { a, b ->
                val aRemainder = included[a].value!! - ExactDecimal(displayed[a], displayScale)
                val bRemainder = included[b].value!! - ExactDecimal(displayed[b], displayScale)
                val comparison = bRemainder.compareTo(aRemainder)
                if (comparison == 0) included[a].asset.value.compareTo(included[b].asset.value) else comparison
            }
        for (index in remainderOrder) {
            if (remaining.sign == 0) break
            displayed[index] += ONE
            remaining -= ONE
        }
        check(remaining.sign == 0)
        val slices =
            included.indices
                .map { index ->
                    AssetAllocationSlice(
                        included[index],
                        Rational(weights[index], total),
                        ExactDecimal(displayed[index], displayScale),
                        ExactDecimal(percentages[index], 2),
                    )
                }.sortedWith { a, b ->
                    val comparison = b.valuation.value!!.compareTo(a.valuation.value!!)
                    if (comparison == 0) {
                        a.valuation.asset.value
                            .compareTo(b.valuation.asset.value)
                    } else {
                        comparison
                    }
                }
        return AssetAllocationProjection(currency, coverage, slices, excluded, subtotal, displayedSubtotal)
    }

    /** Largest remainders and asset-id input order make rounded shares add to exactly 100.00%. */
    private fun distribute(
        weights: List<ExactInteger>,
        total: ExactInteger,
        units: ExactInteger,
    ): List<ExactInteger> {
        val divisions = weights.map { divide(it * units, total) }
        val result = divisions.map { it.first }.toMutableList()
        var remaining = units - result.fold(ExactInteger.ZERO) { sum, value -> sum + value }
        val order =
            weights.indices.sortedWith { a, b ->
                divisions[b].second.compareTo(divisions[a].second).takeIf { it != 0 }
                    ?: a.compareTo(b)
            }
        for (index in order) {
            if (remaining.sign == 0) break
            result[index] += ONE
            remaining -= ONE
        }
        check(remaining.sign == 0)
        return result
    }

    private fun divide(
        numerator: ExactInteger,
        denominator: ExactInteger,
    ): Pair<ExactInteger, ExactInteger> {
        var remainder = ExactInteger.ZERO
        val quotient = StringBuilder()
        for (digit in numerator.canonical) {
            remainder = remainder.timesPowerOfTen(1) + ExactInteger.parse(digit.toString())
            var next = 0
            while (remainder >= denominator) {
                remainder -= denominator
                next++
            }
            quotient.append(next)
        }
        return ExactInteger.parse(quotient.toString()) to remainder
    }

    private val ONE = ExactInteger.parse("1")
}
