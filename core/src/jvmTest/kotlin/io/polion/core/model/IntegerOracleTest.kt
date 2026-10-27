package io.polion.core.model

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class IntegerOracleTest {
    @Test
    fun randomLargeSignedValuesAgreeWithBigInteger() {
        val rng = Random(71234)
        repeat(1000) {
            fun number(): String {
                val digits =
                    buildString {
                        append(rng.nextInt(1, 10))
                        repeat(rng.nextInt(1, 150)) { append(rng.nextInt(10)) }
                    }
                return if (rng.nextBoolean()) "-$digits" else digits
            }
            val a = number()
            val b = number()
            val x = ExactInteger.parse(a)
            val y = ExactInteger.parse(b)
            val ax = BigInteger(a)
            val by = BigInteger(b)
            assertEquals((ax + by).toString(), (x + y).toString())
            assertEquals((ax - by).toString(), (x - y).toString())
            assertEquals((ax * by).toString(), (x * y).toString())
            assertEquals(ax.compareTo(by), x.compareTo(y).coerceIn(-1, 1))
        }
    }
}
