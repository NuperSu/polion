package io.valroot.core.accounting

import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.model.Rational

data class QuoteValue(
    val asset: AssetId,
    val amount: Rational,
)

/** Valuations are total quote amounts; null means unknown, never zero. */
sealed interface AccountingAction {
    val activityId: ActivityId
    val at: Long
    val order: String

    data class Acquisition(
        override val activityId: ActivityId,
        override val at: Long,
        override val order: String,
        val account: AccountId,
        val asset: AssetId,
        val quantity: ExactInteger,
        val totalCost: QuoteValue?,
        val opening: Boolean = false,
    ) : AccountingAction

    data class Disposal(
        override val activityId: ActivityId,
        override val at: Long,
        override val order: String,
        val account: AccountId,
        val asset: AssetId,
        val quantity: ExactInteger,
        val proceeds: QuoteValue?,
        val fee: Boolean = false,
    ) : AccountingAction

    data class Transfer(
        override val activityId: ActivityId,
        override val at: Long,
        override val order: String,
        val from: AccountId,
        val to: AccountId,
        /** What arrives: the incoming asset and its units. */
        val asset: AssetId,
        val quantity: ExactInteger,
        /**
         * What leaves, when it is a different asset of the same coin (an exchange balance withdrawn as a token): the
         * same amount in that asset's units, so the two quantities differ only by a power of ten.
         */
        val fromAsset: AssetId = asset,
        val fromQuantity: ExactInteger = quantity,
    ) : AccountingAction
}

/**
 * The power of ten that turns [from] units into [to] units of the same amount: positive when [to] has more decimals.
 * Null when the two are not the same amount at any power of ten.
 */
internal fun decimalShift(
    from: ExactInteger,
    to: ExactInteger,
): Int? {
    if (from.sign <= 0 || to.sign <= 0) return null
    val a = from.toString()
    val b = to.toString()
    return when {
        a.length <= b.length && from.timesPowerOfTen(b.length - a.length) == to -> b.length - a.length
        b.length < a.length && to.timesPowerOfTen(a.length - b.length) == from -> b.length - a.length
        else -> null
    }
}

/** [units] at a power of ten, rounding down when [shift] removes digits. */
internal fun shiftUnits(
    units: ExactInteger,
    shift: Int,
): ExactInteger =
    if (shift >= 0) {
        units.timesPowerOfTen(shift)
    } else {
        ExactInteger.parse(units.toString().dropLast(-shift).ifEmpty { "0" })
    }

data class Lot(
    val id: String,
    val owner: OwnerId,
    val account: AccountId,
    val asset: AssetId,
    val acquiredAt: Long,
    val order: String,
    val quantity: ExactInteger,
    val basis: Rational?,
    val opening: Boolean,
    val acquisitionActivityId: ActivityId? = null,
)

data class Consumption(
    val lotId: String?,
    val quantity: ExactInteger,
    val basis: Rational?,
    val acquisitionActivityId: ActivityId? = null,
)

data class DisposalReport(
    val activityId: ActivityId,
    val account: AccountId,
    val asset: AssetId,
    val quantity: ExactInteger,
    val proceeds: Rational?,
    val basis: Rational?,
    val gain: Rational?,
    val fee: Boolean,
    val consumed: List<Consumption>,
)

data class LotValuation(
    val lot: Lot,
    val value: Rational?,
    val unrealizedGain: Rational?,
)

data class AccountingReport(
    val quoteAsset: AssetId,
    val lots: List<Lot>,
    val disposals: List<DisposalReport>,
    val valuations: List<LotValuation>,
)

/** FIFO is scoped by owner, account and asset. Transfer preserves original lot age and basis. */
object FifoAccounting {
    fun calculate(
        actions: List<AccountingAction>,
        owners: Map<AccountId, OwnerId>,
        quoteAsset: AssetId,
        unitPrices: Map<AssetId, QuoteValue?> = emptyMap(),
        replayOrder: Comparator<AccountingAction> = compareBy({ it.at }, { it.order }, { it.activityId.value }),
    ): AccountingReport {
        require(
            unitPrices.values.filterNotNull().all {
                it.asset == quoteAsset && it.amount >= Rational.ZERO
            },
        ) { "Invalid price quote asset or value" }
        actions.forEach { action ->
            val value =
                when (action) {
                    is AccountingAction.Acquisition -> action.totalCost
                    is AccountingAction.Disposal -> action.proceeds
                    is AccountingAction.Transfer -> null
                }
            require(value == null || value.asset == quoteAsset) { "Accounting quote assets must match" }
        }
        val lots = mutableListOf<Lot>()
        val disposals = mutableListOf<DisposalReport>()
        val seen = mutableSetOf<String>()
        val lotRanks = mutableMapOf<String, Int>()

        fun take(
            account: AccountId,
            asset: AssetId,
            amount: ExactInteger,
        ): List<Pair<Lot?, Pair<ExactInteger, Rational?>>> {
            var remaining = amount
            val chunks = mutableListOf<Pair<Lot?, Pair<ExactInteger, Rational?>>>()
            val candidates =
                lots
                    .filter {
                        it.account == account && it.asset == asset
                    }.sortedWith(compareBy({ lotRanks.getValue(it.id) }, { it.id }))
            for (lot in candidates) {
                if (remaining.sign == 0) break
                val qty = if (lot.quantity <= remaining) lot.quantity else remaining
                val basis = lot.basis?.times(Rational.units(qty) / Rational.units(lot.quantity))
                lots.remove(lot)
                val left = lot.quantity - qty
                if (left.sign >
                    0
                ) {
                    lots.add(lot.copy(quantity = left, basis = if (lot.basis != null && basis != null) lot.basis - basis else null))
                }
                chunks += lot to (qty to basis)
                remaining -= qty
            }
            if (remaining.sign > 0) chunks += null to (remaining to null)
            return chunks
        }
        actions.sortedWith(replayOrder).forEachIndexed { rank, action ->
            require(action.order.isNotBlank())
            val key = "${action.activityId.value}:${action.order}"
            require(seen.add(key)) { "Duplicate accounting action" }
            when (action) {
                is AccountingAction.Acquisition -> {
                    require(action.quantity.sign > 0 && (action.totalCost == null || action.totalCost.amount >= Rational.ZERO))
                    val owner = requireNotNull(owners[action.account]) { "Missing owner" }
                    lotRanks[key] = rank
                    lots +=
                        Lot(
                            key,
                            owner,
                            action.account,
                            action.asset,
                            action.at,
                            action.order,
                            action.quantity,
                            action.totalCost?.amount,
                            action.opening,
                            action.activityId,
                        )
                }
                is AccountingAction.Disposal -> {
                    require(action.quantity.sign > 0 && (action.proceeds == null || action.proceeds.amount >= Rational.ZERO))
                    require(owners.containsKey(action.account))
                    val taken = take(action.account, action.asset, action.quantity)
                    val consumed = taken.map { (lot, part) -> Consumption(lot?.id, part.first, part.second, lot?.acquisitionActivityId) }
                    val basis =
                        if (consumed.all { it.basis != null }) {
                            consumed.fold(
                                Rational.ZERO,
                            ) { sum, item -> sum + item.basis!! }
                        } else {
                            null
                        }
                    disposals +=
                        DisposalReport(
                            action.activityId,
                            action.account,
                            action.asset,
                            action.quantity,
                            action.proceeds?.amount,
                            basis,
                            if (basis !=
                                null &&
                                action.proceeds != null
                            ) {
                                action.proceeds.amount - basis
                            } else {
                                null
                            },
                            action.fee,
                            consumed,
                        )
                }
                is AccountingAction.Transfer -> {
                    require(action.quantity.sign > 0 && action.from != action.to)
                    val owner = requireNotNull(owners[action.from])
                    require(owners[action.to] == owner) { "Transfer requires same owner" }
                    val shift = requireNotNull(decimalShift(action.fromQuantity, action.quantity)) { "Transfer amounts differ" }
                    // Lots keep their basis and dates; each part's units follow the incoming asset's decimals, and
                    // the last part takes what rounding left, so the parts add up to the incoming quantity exactly.
                    var taken = ExactInteger.ZERO
                    var given = ExactInteger.ZERO
                    take(action.from, action.fromAsset, action.fromQuantity).forEachIndexed { index, (lot, sent) ->
                        taken += sent.first
                        val received = shiftUnits(taken, shift) - given
                        given += received
                        val part = received to sent.second
                        if (received.sign == 0) return@forEachIndexed
                        lotRanks["$key:$index"] = lot?.let { lotRanks.getValue(it.id) } ?: rank
                        lots +=
                            Lot(
                                "$key:$index",
                                owner,
                                action.to,
                                action.asset,
                                lot?.acquiredAt ?: action.at,
                                lot?.order ?: action.order,
                                part.first,
                                part.second,
                                lot?.opening ?: false,
                                lot?.acquisitionActivityId,
                            )
                    }
                }
            }
        }
        val ordered = lots.sortedWith(compareBy({ lotRanks.getValue(it.id) }, { it.id }))
        return AccountingReport(
            quoteAsset,
            ordered,
            disposals,
            ordered.map { lot ->
                val value = unitPrices[lot.asset]?.amount?.times(Rational.units(lot.quantity))
                LotValuation(lot, value, if (value != null && lot.basis != null) value - lot.basis else null)
            },
        )
    }
}
