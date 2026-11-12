package io.valroot.core.reconcile

import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.ExactDecimal

/**
 * Puts amounts of equivalent assets on one scale: the coin an asset is, and the amount as a decimal. An exchange's
 * USDT and USDT on a chain are one coin, so a withdrawal and its arrival match when their decimal amounts are equal.
 */
fun interface AmountScale {
    fun of(amount: AtomicAmount): Pair<String, ExactDecimal>

    companion object {
        /** Only the same asset matches, unit for unit. */
        val SAME_ASSET = AmountScale { it.assetId.value to ExactDecimal(it.atomicUnits, 0) }
    }
}

/** True when [left] and [right] are opposite, non-zero amounts of the same coin. */
internal fun AmountScale.opposite(
    left: AtomicAmount,
    right: AtomicAmount,
): Boolean {
    if (left.atomicUnits.sign == 0 || left.atomicUnits.sign == right.atomicUnits.sign) return false
    if (left.assetId == right.assetId) return left.atomicUnits == -right.atomicUnits
    val (leftCoin, leftValue) = of(left)
    val (rightCoin, rightValue) = of(right)
    return leftCoin == rightCoin && (leftValue + rightValue).coefficient.sign == 0
}

internal sealed interface TransferResolution {
    data class Conflict(
        val reason: String,
    ) : TransferResolution

    data class Match(
        val link: TransferLink,
    ) : TransferResolution
}

/** Resolve one reviewed relationship after global duplicate suppression. */
internal fun resolveTransfer(
    first: EffectiveActivity?,
    second: EffectiveActivity?,
    firstExists: Boolean,
    secondExists: Boolean,
    scale: AmountScale = AmountScale.SAME_ASSET,
): TransferResolution {
    if (first == null || second == null) {
        return TransferResolution.Conflict(if (!firstExists || !secondExists) "activity missing" else "no matching movements")
    }
    val active = setOf(ActivityStatus.PENDING, ActivityStatus.CONFIRMED)
    if (first.activity.status !in active || second.activity.status !in active) return TransferResolution.Conflict("activity inactive")
    if (first.unresolvedMovementIds.isNotEmpty() || second.unresolvedMovementIds.isNotEmpty()) {
        return TransferResolution.Conflict("unresolved movement correction")
    }
    var match: TransferLink? = null
    for (left in first.activity.movements) {
        for (right in second.activity.movements) {
            if (left.role == MovementRole.FEE ||
                right.role == MovementRole.FEE ||
                left.accountId == right.accountId ||
                !scale.opposite(left.amount, right.amount)
            ) {
                continue
            } else if (left.amount.atomicUnits.sign < 0) {
                if (match != null) return TransferResolution.Conflict("ambiguous movement matches")
                match = TransferLink(first.activity.id, left.id, second.activity.id, right.id)
            } else {
                if (match != null) return TransferResolution.Conflict("ambiguous movement matches")
                match = TransferLink(second.activity.id, right.id, first.activity.id, left.id)
            }
        }
    }
    return match?.let(TransferResolution::Match) ?: TransferResolution.Conflict("no matching movements")
}
