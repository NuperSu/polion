package io.valroot.core.application

import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import kotlin.math.abs
import kotlin.math.pow

/** Why a money-weighted return is or is not shown. */
enum class ReturnStatus {
    OK,

    /** Nothing was invested at the start or during the period. */
    NO_INVESTMENT,

    /** A start or end value, or a contribution or withdrawal, has no price; the rate would be a guess. */
    MISSING_PRICES,

    /** The cash flows allow more than one rate. */
    NO_UNIQUE_RATE,

    /** The rate could not be found within the search bounds. */
    NO_CONVERGENCE,
}

/**
 * The money-weighted return (internal rate of return) of a portfolio over a period. Contributions and withdrawals are
 * transfers into or out of the portfolio's accounts, valued at their time; trades, fees and rewards are part of the
 * return. [periodReturn] is the rate compounded over the period; [annualReturn] is shown only for periods of a year or
 * more, because annualising a shorter period exaggerates it.
 */
data class MoneyWeightedReturn(
    val startMillis: Long,
    val endMillis: Long,
    val currency: AssetId,
    val startValue: ExactDecimal?,
    val endValue: ExactDecimal?,
    val contributions: ExactDecimal?,
    val withdrawals: ExactDecimal?,
    /** End value minus start value minus net contributions: what the portfolio earned, in money. */
    val gain: ExactDecimal?,
    val periodReturn: Double?,
    val annualReturn: Double?,
    val status: ReturnStatus,
    /** Contributions or withdrawals without a price at their time. */
    val unpricedFlows: Int = 0,
    val flowCount: Int = 0,
)

/** Fees paid in one asset over a period, in that asset's atomic units, with their value when it is known. */
data class FeeTotal(
    val asset: AssetId,
    val atomicUnits: ExactInteger,
    val decimals: Int?,
    val value: ExactDecimal?,
    val unpriced: Int,
)

data class AccountUse(
    val account: AccountId,
    val label: String,
    /** Confirmed activities in the period with a movement in this account. */
    val activities: Int,
)

/** Counts over confirmed activities in a period, for the accounts in a portfolio. */
data class ActivityInsights(
    val trades: Int,
    val tradedAssets: Int,
    val fees: List<FeeTotal>,
    val accounts: List<AccountUse>,
)

/** A dated investor cash flow: negative when money goes into the portfolio, positive when it comes out. */
data class DatedFlow(
    val years: Double,
    val amount: Double,
)

/** Solves for the rate r with Σ amount × (1 + r)^-years = 0, rejecting inputs without one reliable answer. */
object InternalRate {
    private const val LOW = -0.9999
    private const val HIGH = 1_000_000.0

    fun npv(
        flows: List<DatedFlow>,
        rate: Double,
    ): Double = flows.sumOf { it.amount * (1 + rate).pow(-it.years) }

    fun solve(flows: List<DatedFlow>): Pair<Double?, ReturnStatus> {
        if (flows.none { it.amount < 0 } || flows.none { it.amount > 0 }) return null to ReturnStatus.NO_INVESTMENT
        // With one change of sign in time order the rate is unique (Descartes' rule); otherwise look for several roots.
        val ordered = flows.sortedBy { it.years }.filter { it.amount != 0.0 }
        val changes = ordered.zipWithNext().count { (a, b) -> (a.amount < 0) != (b.amount < 0) }
        if (changes > 1) {
            val grid = (0..400).map { LOW + (it / 400.0).pow(3) * (100.0 - LOW) }
            val roots = grid.zipWithNext().count { (a, b) -> npv(flows, a).let { x -> npv(flows, b).let { y -> x == 0.0 || x * y < 0 } } }
            if (roots > 1) return null to ReturnStatus.NO_UNIQUE_RATE
        }
        var low = LOW
        var high = 1.0
        var lowValue = npv(flows, low)
        var highValue = npv(flows, high)
        while (lowValue * highValue > 0 && high < HIGH) {
            high *= 4
            highValue = npv(flows, high)
        }
        if (!lowValue.isFinite() || !highValue.isFinite() || lowValue * highValue > 0) return null to ReturnStatus.NO_CONVERGENCE
        repeat(300) {
            val middle = (low + high) / 2
            val value = npv(flows, middle)
            if (!value.isFinite()) return null to ReturnStatus.NO_CONVERGENCE
            if (abs(high - low) < 1e-12 || value == 0.0) return middle to ReturnStatus.OK
            if (value * lowValue < 0) {
                high = middle
                highValue = value
            } else {
                low = middle
                lowValue = value
            }
        }
        return (low + high) / 2 to ReturnStatus.OK
    }
}
