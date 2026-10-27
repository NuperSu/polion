package io.polion.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExactMoneyTest {
    @Test fun largeArithmeticAndRoundTrip() {
        val huge = ExactInteger.parse("115792089237316195423570985008687907853269984665640564039457584007913129639935")
        assertEquals(
            "115792089237316195423570985008687907853269984665640564039457584007913129639936",
            (huge + ExactInteger.parse("1")).toString(),
        )
        assertEquals("-24691357802469135780", (ExactInteger.parse("-12345678901234567890") * ExactInteger.parse("2")).toString())
        assertEquals("0", ExactInteger.parse("-000").toString())
        for (scale in listOf(8, 18, 24, 80)) {
            val amount = AtomicAmount(AssetId("chain:asset"), huge)
            assertEquals(amount, AtomicAmount.fromDecimal(amount.assetId, ExactDecimal.parse(amount.decimal(scale).toString()), scale))
        }
    }

    @Test fun randomizedIntegerArithmetic() {
        val random = Random(4187)
        repeat(2000) {
            val a = random.nextLong(-1000000000, 1000000000)
            val b = random.nextLong(-1000000000, 1000000000)
            val x = ExactInteger.parse(a.toString())
            val y = ExactInteger.parse(b.toString())
            assertEquals((a + b).toString(), (x + y).toString())
            assertEquals((a - b).toString(), (x - y).toString())
            assertEquals((a * b).toString(), (x * y).toString())
            assertEquals(a.compareTo(b), x.compareTo(y).coerceIn(-1, 1))
        }
    }

    @Test fun roundingAndAssetBoundaries() {
        assertEquals("-1.24", ExactDecimal.parse("-1.235").rescale(2, Rounding.HALF_EVEN).toString())
        assertEquals("1.22", ExactDecimal.parse("1.225").rescale(2, Rounding.HALF_EVEN).toString())
        assertEquals("0.01", ExactDecimal.parse("0.001").rescale(2, Rounding.UP).toString())
        assertEquals("0.00", ExactDecimal.parse("-0.001").rescale(2, Rounding.DOWN).toString())
        assertFailsWith<IllegalArgumentException> { ExactDecimal.parse("1,25") }
        assertFailsWith<IllegalArgumentException> { AtomicAmount.fromDecimal(AssetId("btc"), ExactDecimal.parse("0.000000001"), 8) }
        assertFailsWith<IllegalArgumentException> {
            AtomicAmount(AssetId("btc"), ExactInteger.ZERO) +
                AtomicAmount(AssetId("eth"), ExactInteger.ZERO)
        }
        assertEquals(
            "-2.500",
            Price(
                AssetId("btc"),
                AssetId("usd"),
                ExactDecimal.parse("2.50"),
            ).valueOf(AtomicAmount(AssetId("btc"), ExactInteger.parse("-10")), 1).toString(),
        )
    }
}
