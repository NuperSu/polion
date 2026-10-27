package io.polion.core.model

/** Canonical signed base-10 integer with arbitrary precision. */
class ExactInteger private constructor(
    val canonical: String,
) : Comparable<ExactInteger> {
    val sign: Int get() =
        if (canonical == "0") {
            0
        } else if (canonical[0] == '-') {
            -1
        } else {
            1
        }
    private val magnitude: String get() = canonical.removePrefix("-")

    override fun toString(): String = canonical

    override fun equals(other: Any?): Boolean = other is ExactInteger && canonical == other.canonical

    override fun hashCode(): Int = canonical.hashCode()

    override fun compareTo(other: ExactInteger): Int {
        if (sign != other.sign) return sign.compareTo(other.sign)
        return sign * compareMagnitude(magnitude, other.magnitude)
    }

    operator fun unaryMinus(): ExactInteger = if (sign == 0) this else parse(if (sign < 0) magnitude else "-$canonical")

    operator fun minus(other: ExactInteger): ExactInteger = this + -other

    operator fun plus(other: ExactInteger): ExactInteger {
        if (sign == 0) return other
        if (other.sign == 0) return this
        if (sign == other.sign) return signed(sign, addMagnitude(magnitude, other.magnitude))
        val cmp = compareMagnitude(magnitude, other.magnitude)
        if (cmp == 0) return ZERO
        return if (cmp >
            0
        ) {
            signed(sign, subtractMagnitude(magnitude, other.magnitude))
        } else {
            signed(other.sign, subtractMagnitude(other.magnitude, magnitude))
        }
    }

    operator fun times(other: ExactInteger): ExactInteger {
        if (sign == 0 || other.sign == 0) return ZERO
        val out = IntArray(magnitude.length + other.magnitude.length)
        for (i in magnitude.indices.reversed()) {
            for (j in other.magnitude.indices.reversed()) {
                val k = i + j + 1
                val n = (magnitude[i] - '0') * (other.magnitude[j] - '0') + out[k]
                out[k] = n % 10
                out[k - 1] += n / 10
            }
        }
        return signed(sign * other.sign, out.joinToString(""))
    }

    fun timesPowerOfTen(power: Int): ExactInteger {
        require(power >= 0)
        return if (sign == 0) this else parse(canonical + "0".repeat(power))
    }

    companion object {
        val ZERO = ExactInteger("0")

        fun parse(text: String): ExactInteger {
            require(text.matches(Regex("-?[0-9]+"))) { "Expected a base-10 integer" }
            val magnitude = text.removePrefix("-").trimStart('0').ifEmpty { "0" }
            return ExactInteger(if (text.startsWith('-') && magnitude != "0") "-$magnitude" else magnitude)
        }

        private fun signed(
            sign: Int,
            value: String,
        ) = parse(if (sign < 0) "-$value" else value)

        private fun compareMagnitude(
            a: String,
            b: String,
        ): Int = if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)

        private fun addMagnitude(
            a: String,
            b: String,
        ): String {
            var carry = 0
            val out = StringBuilder()
            for (i in 0 until maxOf(a.length, b.length)) {
                val n = (a.getOrNull(a.length - 1 - i)?.minus('0') ?: 0) + (b.getOrNull(b.length - 1 - i)?.minus('0') ?: 0) + carry
                out.append(('0'.code + n % 10).toChar())
                carry = n / 10
            }
            if (carry != 0) out.append('1')
            return out.reverse().toString()
        }

        private fun subtractMagnitude(
            a: String,
            b: String,
        ): String {
            var borrow = 0
            val out = StringBuilder()
            for (i in a.indices) {
                var n = (a[a.length - 1 - i] - '0') - (b.getOrNull(b.length - 1 - i)?.minus('0') ?: 0) - borrow
                borrow = if (n < 0) 1 else 0
                if (n < 0) n += 10
                out.append(('0'.code + n).toChar())
            }
            return out
                .reverse()
                .toString()
                .trimStart('0')
                .ifEmpty { "0" }
        }
    }
}

enum class Rounding { DOWN, UP, HALF_UP, HALF_EVEN }

/** Decimal coefficient and scale; parsing and rendering never use locale or floating point. */
data class ExactDecimal(
    val coefficient: ExactInteger,
    val scale: Int,
) : Comparable<ExactDecimal> {
    init {
        require(scale >= 0)
    }

    override fun toString(): String {
        val negative = coefficient.sign < 0
        val digits = coefficient.toString().removePrefix("-").padStart(scale + 1, '0')
        val value = if (scale == 0) digits else digits.dropLast(scale) + "." + digits.takeLast(scale)
        return if (negative) "-$value" else value
    }

    override fun compareTo(other: ExactDecimal): Int {
        val common = maxOf(scale, other.scale)
        return coefficient.timesPowerOfTen(common - scale).compareTo(other.coefficient.timesPowerOfTen(common - other.scale))
    }

    operator fun plus(other: ExactDecimal): ExactDecimal {
        val common = maxOf(scale, other.scale)
        return ExactDecimal(coefficient.timesPowerOfTen(common - scale) + other.coefficient.timesPowerOfTen(common - other.scale), common)
    }

    operator fun minus(other: ExactDecimal) = this + ExactDecimal(-other.coefficient, other.scale)

    operator fun times(other: ExactDecimal) = ExactDecimal(coefficient * other.coefficient, MathScale.add(scale, other.scale))

    fun rescale(
        newScale: Int,
        rounding: Rounding,
    ): ExactDecimal {
        require(newScale >= 0)
        if (newScale >= scale) return ExactDecimal(coefficient.timesPowerOfTen(newScale - scale), newScale)
        val cut = scale - newScale
        val digits = coefficient.toString().removePrefix("-").padStart(cut + 1, '0')
        val kept = digits.dropLast(cut)
        val discarded = digits.takeLast(cut)
        val nonzero = discarded.any { it != '0' }
        val aboveHalf = discarded[0] > '5' || (discarded[0] == '5' && discarded.drop(1).any { it != '0' })
        val half = discarded[0] == '5' && discarded.drop(1).all { it == '0' }
        val increment =
            when (rounding) {
                Rounding.DOWN -> false
                Rounding.UP -> nonzero
                Rounding.HALF_UP -> aboveHalf || half
                Rounding.HALF_EVEN -> aboveHalf || (half && (kept.last() - '0') % 2 != 0)
            }
        val absolute = ExactInteger.parse(kept) + if (increment) ExactInteger.parse("1") else ExactInteger.ZERO
        return ExactDecimal(if (coefficient.sign < 0) -absolute else absolute, newScale)
    }

    companion object {
        fun parse(text: String): ExactDecimal {
            require(text.matches(Regex("-?[0-9]+(\\.[0-9]+)?"))) { "Expected a plain decimal" }
            val point = text.indexOf('.')
            return ExactDecimal(ExactInteger.parse(text.replace(".", "")), if (point < 0) 0 else text.length - point - 1)
        }
    }
}

private object MathScale {
    fun add(
        a: Int,
        b: Int,
    ): Int {
        require(a <= Int.MAX_VALUE - b)
        return a + b
    }
}

data class AtomicAmount(
    val assetId: AssetId,
    val atomicUnits: ExactInteger,
) {
    operator fun plus(other: AtomicAmount): AtomicAmount {
        require(assetId == other.assetId)
        return copy(atomicUnits = atomicUnits + other.atomicUnits)
    }

    operator fun minus(other: AtomicAmount): AtomicAmount = this + other.copy(atomicUnits = -other.atomicUnits)

    fun decimal(decimals: Int): ExactDecimal = ExactDecimal(atomicUnits, decimals)

    companion object {
        fun fromDecimal(
            assetId: AssetId,
            value: ExactDecimal,
            decimals: Int,
        ): AtomicAmount {
            require(decimals >= 0)
            val scaled = value.rescale(decimals, Rounding.DOWN)
            require(scaled.compareTo(value) == 0) { "Amount has fractional atomic units" }
            return AtomicAmount(assetId, scaled.coefficient)
        }
    }
}

data class Price(
    val baseAssetId: AssetId,
    val quoteAssetId: AssetId,
    val value: ExactDecimal,
) {
    init {
        require(baseAssetId != quoteAssetId)
        require(value.coefficient.sign >= 0)
    }

    fun valueOf(
        amount: AtomicAmount,
        assetDecimals: Int,
    ): ExactDecimal {
        require(amount.assetId == baseAssetId)
        return amount.decimal(assetDecimals) * value
    }
}
