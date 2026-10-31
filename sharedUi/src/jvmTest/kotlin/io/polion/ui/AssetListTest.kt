package io.polion.ui

import io.polion.core.application.AssetListPreference
import io.polion.core.application.AssetSort
import io.polion.core.application.AssetValuation
import io.polion.core.market.PriceStatus
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class AssetListTest {
    @Test
    fun hidingSmallKeepsUnknownStaleNegativeAndBoundaryPositions() {
        fun asset(
            id: String,
            value: String?,
            status: PriceStatus,
            amount: String = "1",
        ) = AssetValuation(
            AssetId(id),
            AtomicAmount(AssetId(id), ExactInteger.parse(amount)),
            null,
            status,
            value?.let(ExactDecimal::parse),
            null,
        )
        val input =
            listOf(
                asset("small", "0.999999999", PriceStatus.FRESH),
                asset("boundary", "1", PriceStatus.FRESH),
                asset("stale", "0.1", PriceStatus.STALE),
                asset("missing", null, PriceStatus.MISSING),
                asset("negative", "-0.1", PriceStatus.FRESH, "-1"),
                asset("zero", "0", PriceStatus.FRESH, "0"),
            )
        assertEquals(
            setOf("boundary", "stale", "missing", "negative", "zero"),
            displayedAssets(input, AssetListPreference(hideSmall = true)).map { it.asset.value }.toSet(),
        )
        assertEquals(6, input.size)
    }

    @Test
    fun hidingEmptyPreservesNegativeAndUnpricedPositions() {
        fun asset(
            id: String,
            units: String,
            value: String?,
        ) = AssetValuation(
            AssetId(id),
            AtomicAmount(AssetId(id), ExactInteger.parse(units)),
            null,
            if (value == null) PriceStatus.MISSING else PriceStatus.FRESH,
            value?.let(ExactDecimal::parse),
            null,
        )
        val input =
            listOf(asset("zero", "0", "0"), asset("negative", "-2", "-10"), asset("unknown", "1", null), asset("positive", "2", "100"))
        val visible = displayedAssets(input, AssetListPreference(hideEmpty = true))
        assertEquals(listOf("positive", "negative", "unknown"), visible.map { it.asset.value })
        assertEquals(4, input.size)
        assertEquals(
            listOf("negative", "positive", "unknown", "zero"),
            displayedAssets(input, AssetListPreference(AssetSort.NAME)).map {
                it.asset.value
            },
        )
    }
}
