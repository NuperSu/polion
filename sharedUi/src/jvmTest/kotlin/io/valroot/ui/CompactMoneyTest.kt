package io.valroot.ui

import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class CompactMoneyTest {
    private fun usd(value: String) = compactMoney(ExactDecimal.parse(value), AssetId("USD"))

    @Test fun largeFiguresUseThreeSignificantDigits() {
        assertEquals("$1.52T", usd("1524823237992"))
        assertEquals("$987B", usd("987400000000"))
        assertEquals("$12.5M", usd("12480000"))
        assertEquals("$1.00M", usd("1000000"))
        assertEquals("€2.30B", compactMoney(ExactDecimal.parse("2300000000"), AssetId("EUR")))
        assertEquals(money(ExactDecimal.parse("999999"), AssetId("USD")), usd("999999"))
    }
}
