package io.polion.core.reconcile

import io.polion.core.model.EventId

internal sealed interface DuplicateResolution {
    data class Conflict(
        val reason: String,
    ) : DuplicateResolution

    data class Matches(
        val movements: List<SuppressedMovement>,
    ) : DuplicateResolution
}

/** Resolve one decision against the surviving movements from all earlier decisions. */
internal fun resolveDuplicate(
    canonical: EffectiveActivity?,
    duplicate: EffectiveActivity?,
    canonicalRemoved: Set<EventId>,
    duplicateRemoved: Set<EventId>,
): DuplicateResolution {
    if (canonical == null || duplicate == null) return DuplicateResolution.Conflict("activity missing")
    if (canonical.activity.status != duplicate.activity.status) return DuplicateResolution.Conflict("activity statuses differ")
    if (canonical.unresolvedMovementIds.isNotEmpty() || duplicate.unresolvedMovementIds.isNotEmpty()) {
        return DuplicateResolution.Conflict("unresolved movement correction")
    }
    val canonicalMovements = canonical.activity.movements.filter { it.id !in canonicalRemoved }
    val duplicateMovements = duplicate.activity.movements.filter { it.id !in duplicateRemoved }
    val matches =
        duplicateMovements.mapNotNull { candidate ->
            val possible =
                canonicalMovements.filter {
                    it.accountId == candidate.accountId &&
                        it.amount == candidate.amount &&
                        it.role == candidate.role
                }
            if (possible.size > 1) candidate.id to null else possible.singleOrNull()?.let { candidate.id to it.id }
        }
    if (matches.isEmpty()) return DuplicateResolution.Conflict("no matching movements")
    if (matches.any { it.second == null } || matches.mapNotNull { it.second }.distinct().size != matches.size) {
        return DuplicateResolution.Conflict("ambiguous movement matches")
    }
    return DuplicateResolution.Matches(
        matches.map { (duplicateEvent, canonicalEvent) ->
            SuppressedMovement(canonical.activity.id, requireNotNull(canonicalEvent), duplicate.activity.id, duplicateEvent)
        },
    )
}
