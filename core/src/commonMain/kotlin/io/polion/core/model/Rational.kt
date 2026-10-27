package io.polion.core.model

/** Exact reduced rational amount. Values are not rounded during accounting. */
class Rational(
    numerator: ExactInteger,
    denominator: ExactInteger,
) : Comparable<Rational> {
    val numerator: ExactInteger
    val denominator: ExactInteger

    init {
        require(denominator.sign > 0)
        val divisor = gcd(if (numerator.sign < 0) -numerator else numerator, denominator)
        this.numerator = divideExact(numerator, divisor)
        this.denominator = divideExact(denominator, divisor)
    }

    operator fun plus(other: Rational) =
        Rational(
            numerator * other.denominator + other.numerator * denominator,
            denominator * other.denominator,
        )

    operator fun minus(other: Rational) =
        Rational(
            numerator * other.denominator - other.numerator * denominator,
            denominator * other.denominator,
        )

    operator fun times(other: Rational) = Rational(numerator * other.numerator, denominator * other.denominator)

    operator fun div(other: Rational): Rational {
        require(other.numerator.sign != 0)
        val sign = other.numerator.sign
        return Rational(
            if (sign <
                0
            ) {
                -(numerator * other.denominator)
            } else {
                numerator * other.denominator
            },
            denominator * if (sign < 0) -other.numerator else other.numerator,
        )
    }

    override fun compareTo(other: Rational) = (numerator * other.denominator).compareTo(other.numerator * denominator)

    override fun equals(other: Any?) = other is Rational && numerator == other.numerator && denominator == other.denominator

    override fun hashCode() = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString() = "$numerator/$denominator"

    companion object {
        val ZERO = of("0")

        fun of(integer: String) = Rational(ExactInteger.parse(integer), ExactInteger.parse("1"))

        fun fromDecimal(value: ExactDecimal) = Rational(value.coefficient, ExactInteger.parse("1").timesPowerOfTen(value.scale))

        fun units(value: ExactInteger) = Rational(value, ExactInteger.parse("1"))

        private fun divRem(
            a: ExactInteger,
            b: ExactInteger,
        ): Pair<ExactInteger, ExactInteger> {
            require(a.sign >= 0 && b.sign > 0)
            var remainder = ExactInteger.ZERO
            val quotient = StringBuilder()
            for (digit in a.toString()) {
                remainder = remainder.timesPowerOfTen(1) + ExactInteger.parse(digit.toString())
                var n = 0
                while (remainder >= b) {
                    remainder -= b
                    n++
                }
                quotient.append(n)
            }
            return ExactInteger.parse(quotient.toString()) to remainder
        }

        private fun gcd(
            a: ExactInteger,
            b: ExactInteger,
        ): ExactInteger {
            var left = a
            var right = b
            while (right.sign != 0) {
                val rest = divRem(left, right).second
                left = right
                right = rest
            }
            return left
        }

        private fun divideExact(
            a: ExactInteger,
            b: ExactInteger,
        ): ExactInteger {
            val result = divRem(if (a.sign < 0) -a else a, b)
            check(result.second.sign == 0)
            return if (a.sign < 0) -result.first else result.first
        }
    }
}
