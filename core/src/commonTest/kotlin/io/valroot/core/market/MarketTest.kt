package io.valroot.core.market

import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.NetworkId
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MarketTest {
    @Test
    fun invalidIdentitiesAndMissingPricesAreNotConvertedToZero() {
        assertFailsWith<IllegalArgumentException> { AssetIdentity(AssetKind.TOKEN, null, "contract") }
        assertFailsWith<IllegalArgumentException> { AssetIdentity(AssetKind.NFT, NetworkId("chain"), "contract") }
        assertFailsWith<IllegalArgumentException> { AssetMetadata("X", "x", -1, 0) }
        assertNull(PriceResult(PriceStatus.MISSING, null).value(AtomicAmount(AssetId("x"), ExactInteger.parse("1")), 0))
    }
}
