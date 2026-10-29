package io.polion.core.application

import io.polion.core.holdings.BalanceCoverage
import io.polion.core.market.PriceStatus
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import io.polion.core.model.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AssetAllocationTest {
    private val currency = AssetId("fiat:usd")

    @Test
    fun scopedValuesAndCoverageArePreservedWithoutAddingOtherHoldings() {
        val scoped = listOf(row("a", "12.50"), row("b", "37.50", status = PriceStatus.STALE))
        val result = AssetAllocation.project(scoped, currency, BalanceCoverage.PARTIAL)
        assertEquals(currency, result.currency)
        assertEquals(BalanceCoverage.PARTIAL, result.coverage)
        assertEquals(listOf("b", "a"), result.slices.map { it.valuation.asset.value })
        assertEquals("50.00", result.pricedSubtotal.toString())
        assertEquals(listOf("75.00", "25.00"), result.slices.map { it.percentage.toString() })
        assertEquals(Rational.of("3") / Rational.of("4"), result.slices.first().share)
        assertEquals(
            PriceStatus.STALE,
            result.slices
                .first()
                .valuation.priceStatus,
        )
        assertTrue(result.excluded.isEmpty())
    }

    @Test
    fun missingAndNonpositivePositionsHaveExplicitReasonsAndNoInventedValue() {
        val result =
            AssetAllocation.project(
                listOf(
                    row("included", "5"),
                    row("missing", null),
                    row("negative", "-10", units = "-1"),
                    row("zeroAmount", "0", units = "0"),
                    row("zeroQuote", "0"),
                    row("negativeQuote", "-1"),
                    row("missingStatus", "50", status = PriceStatus.MISSING),
                ),
                currency,
                BalanceCoverage.UNKNOWN,
            )
        assertEquals("5", result.pricedSubtotal.toString())
        assertEquals("5.00", result.displayedPricedSubtotal.toString())
        assertEquals(listOf("included"), result.slices.map { it.valuation.asset.value })
        assertEquals(
            "100.00",
            result.slices
                .single()
                .percentage
                .toString(),
        )
        val reasons = result.excluded.associate { it.valuation.asset.value to it.reason }
        assertEquals(AllocationExclusionReason.MISSING_PRICE, reasons["missing"])
        assertEquals(AllocationExclusionReason.MISSING_PRICE, reasons["missingStatus"])
        assertEquals(AllocationExclusionReason.NON_POSITIVE_AMOUNT, reasons["negative"])
        assertEquals(AllocationExclusionReason.NON_POSITIVE_AMOUNT, reasons["zeroAmount"])
        assertEquals(AllocationExclusionReason.NON_POSITIVE_VALUE, reasons["zeroQuote"])
        assertEquals(AllocationExclusionReason.NON_POSITIVE_VALUE, reasons["negativeQuote"])
    }

    @Test
    fun roundingReconcilesToTheRoundedSubtotalAndOneHundredPercentDeterministically() {
        val input = listOf(row("c", "0.005"), row("b", "0.005"), row("a", "0.005"))
        val result = AssetAllocation.project(input, currency, BalanceCoverage.COMPLETE)
        assertEquals("0.015", result.pricedSubtotal.toString())
        assertEquals("0.02", result.displayedPricedSubtotal.toString())
        assertEquals(listOf("a", "b", "c"), result.slices.map { it.valuation.asset.value })
        assertEquals(listOf("0.01", "0.01", "0.00"), result.slices.map { it.displayedValue.toString() })
        assertEquals(listOf("33.34", "33.33", "33.33"), result.slices.map { it.percentage.toString() })
        assertEquals("0.02", result.slices.fold(ExactDecimal.parse("0.00")) { sum, slice -> sum + slice.displayedValue }.toString())
        assertEquals("100.00", result.slices.fold(ExactDecimal.parse("0.00")) { sum, slice -> sum + slice.percentage }.toString())
        assertEquals(result, AssetAllocation.project(input.reversed(), currency, BalanceCoverage.COMPLETE))
    }

    @Test
    fun largeExactValuesAndTinyHoldingsDoNotRequireFloatingPoint() {
        val result =
            AssetAllocation.project(
                listOf(row("large", "999999999999999999999999999999.999999999"), row("tiny", "0.000000001")),
                currency,
                BalanceCoverage.COMPLETE,
            )
        assertEquals("1000000000000000000000000000000.000000000", result.pricedSubtotal.toString())
        assertEquals("1000000000000000000000000000000.00", result.displayedPricedSubtotal.toString())
        assertEquals(
            "100.00",
            result.slices
                .first()
                .percentage
                .toString(),
        )
        assertEquals(
            "0.00",
            result.slices
                .last()
                .percentage
                .toString(),
        )
        assertTrue(result.slices.last().share > Rational.ZERO)
    }

    @Test
    fun emptyAndEntirelyExcludedInputsHaveNoSlicesOrPercentageDenominator() {
        for (rows in listOf(emptyList(), listOf(row("missing", null), row("zero", "0")))) {
            val result = AssetAllocation.project(rows, currency, BalanceCoverage.UNKNOWN)
            assertTrue(result.slices.isEmpty())
            assertEquals("0.00", result.displayedPricedSubtotal.toString())
            assertEquals(rows.size, result.excluded.size)
        }
    }

    @Test
    fun duplicateAssetsCannotDoubleCountAnAlreadyAggregatedScope() {
        assertFailsWith<IllegalArgumentException> {
            AssetAllocation.project(listOf(row("a", "1"), row("a", "1")), currency, BalanceCoverage.COMPLETE)
        }
    }

    private fun row(
        id: String,
        value: String?,
        units: String = "1",
        status: PriceStatus = if (value == null) PriceStatus.MISSING else PriceStatus.FRESH,
    ): AssetValuation {
        val asset = AssetId(id)
        return AssetValuation(asset, AtomicAmount(asset, ExactInteger.parse(units)), null, status, value?.let(ExactDecimal::parse), null)
    }
}
