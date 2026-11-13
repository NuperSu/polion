package io.valroot.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.Rounding
import kotlin.time.Instant

internal fun formatUnits(
    units: ExactInteger,
    decimals: Int,
): String =
    ExactDecimal(units, decimals).toString().let {
        if ('.' in
            it
        ) {
            it.trimEnd('0').trimEnd('.')
        } else {
            it
        }
    }

/** Large market figures such as market cap, rounded to three significant digits with a T, B or M suffix. */
internal fun compactMoney(
    value: ExactDecimal,
    currency: AssetId,
): String {
    val amount = value.toString().toDouble()
    val (divisor, suffix) =
        listOf(1e12 to "T", 1e9 to "B", 1e6 to "M").firstOrNull { kotlin.math.abs(amount) >= it.first } ?: return money(value, currency)
    val scaled = amount / divisor
    val digits =
        if (kotlin.math.abs(scaled) >= 100) {
            0
        } else if (kotlin.math.abs(scaled) >= 10) {
            1
        } else {
            2
        }
    val factor = listOf(1.0, 10.0, 100.0)[digits]
    val rounded = kotlin.math.round(scaled * factor) / factor
    val text =
        if (digits ==
            0
        ) {
            rounded.toLong().toString()
        } else {
            rounded.toString().let { it + "0".repeat(maxOf(0, digits - it.substringAfter('.').length)) }
        }
    val prefix =
        when (currency.value) {
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            else -> ""
        }
    return if (prefix.isEmpty()) "$text$suffix ${currency.value}" else "$prefix$text$suffix"
}

internal fun money(
    value: ExactDecimal,
    currency: AssetId,
): String {
    if (currency.value == "USD" && value.coefficient.sign != 0) {
        val magnitude = if (value.coefficient.sign < 0) ExactDecimal(-value.coefficient, value.scale) else value
        if (magnitude < ExactDecimal.parse("0.01")) return (if (value.coefficient.sign < 0) "-" else "") + "<$0.01"
    }
    val prefix =
        when (currency.value) {
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            else -> "${currency.value} "
        }
    val rounded = value.rescale(2, Rounding.HALF_EVEN).toString()
    val parts = rounded.removePrefix("-").split('.')
    val grouped =
        parts
            .first()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
    return (if (rounded.startsWith('-')) "-" else "") + prefix + grouped + "." + parts.last()
}

internal fun date(millis: Long) = Instant.fromEpochMilliseconds(millis).toString().take(10)
