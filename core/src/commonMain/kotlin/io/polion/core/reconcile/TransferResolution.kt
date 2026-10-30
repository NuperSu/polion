package io.polion.core.reconcile

import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.MovementRole

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
                left.amount.assetId != right.amount.assetId ||
                left.amount.atomicUnits.sign == 0 ||
                left.amount.atomicUnits != -right.amount.atomicUnits
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
