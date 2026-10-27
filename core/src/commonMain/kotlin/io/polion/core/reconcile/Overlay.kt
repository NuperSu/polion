package io.polion.core.reconcile

import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.MovementRole
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.SourceRecordId

/** A complete user-authored snapshot; saving a new snapshot does not modify decoded data. */
data class ActivityOverlay(
    val activityId: ActivityId,
    val label: String? = null,
    val note: String? = null,
    val kind: ActivityKind? = null,
    val amounts: Map<EventId, AtomicAmount> = emptyMap(),
) {
    init {
        require(label == null || label.isNotBlank())
    }
}

data class ClassificationRule(
    val id: String,
    val priority: Int,
    val result: ActivityKind,
    val matchKind: ActivityKind? = null,
    val matchAsset: AssetId? = null,
    val matchSource: SourceRecordId? = null,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank())
    }

    fun matches(activity: Activity): Boolean =
        enabled &&
            (matchKind == null || activity.kind == matchKind) &&
            (matchAsset == null || activity.movements.any { it.amount.assetId == matchAsset }) &&
            (matchSource == null || matchSource in activity.sources)
}

enum class ClassificationOrigin { ORIGINAL, RULE, USER }

data class EffectiveActivity(
    val original: Activity,
    val activity: Activity,
    val label: String?,
    val note: String?,
    val classificationOrigin: ClassificationOrigin,
    val ruleId: String?,
    val ruleRevision: Int?,
    val overlayRevision: Int?,
    val unresolvedMovementIds: Set<EventId>,
)

enum class LinkState { ACCEPTED, REJECTED }

data class LinkDecision(
    val first: ActivityId,
    val second: ActivityId,
    val state: LinkState,
)

data class ReconciliationCandidate(
    val first: ActivityId,
    val second: ActivityId,
    val score: Int,
    val matchedMovements: Int,
    val ambiguous: Boolean,
)

/** Suggestions require review: matching never deletes or merges source records. */
object Reconciliation {
    fun suggest(
        activities: Collection<Activity>,
        maxWindowMillis: Long,
    ): List<ReconciliationCandidate> {
        require(maxWindowMillis >= 0)
        require(activities.map { it.id }.distinct().size == activities.size)
        val ordered =
            activities
                .filter {
                    it.status == ActivityStatus.CONFIRMED || it.status == ActivityStatus.PENDING
                }.sortedBy { it.id.value }
        val candidates = mutableListOf<ReconciliationCandidate>()
        for (i in ordered.indices) {
            for (j in i + 1 until ordered.size) {
                val first = ordered[i]
                val second = ordered[j]
                val later = maxOf(first.occurredAtEpochMillis, second.occurredAtEpochMillis)
                val earlier = minOf(first.occurredAtEpochMillis, second.occurredAtEpochMillis)
                val distance = later.toULong() - earlier.toULong()
                if (distance > maxWindowMillis.toULong()) continue
                val pairs =
                    first.movements.sumOf { left ->
                        second.movements.count { right ->
                            left.role != MovementRole.FEE &&
                                right.role != MovementRole.FEE &&
                                left.accountId != right.accountId &&
                                left.amount.assetId == right.amount.assetId &&
                                left.amount.atomicUnits.sign != 0 &&
                                left.amount.atomicUnits == -right.amount.atomicUnits
                        }
                    }
                if (pairs == 0) continue
                val score =
                    80 + (if (distance <= 3_600_000UL) 10 else 0) + (if (first.sources.intersect(second.sources).isNotEmpty()) 10 else 0)
                candidates += ReconciliationCandidate(first.id, second.id, score, pairs, pairs > 1)
            }
        }
        val counts = candidates.flatMap { listOf(it.first, it.second) }.groupingBy { it }.eachCount()
        return candidates
            .map { it.copy(ambiguous = it.ambiguous || counts.getValue(it.first) > 1 || counts.getValue(it.second) > 1) }
            .sortedWith(compareByDescending<ReconciliationCandidate> { it.score }.thenBy { it.first.value }.thenBy { it.second.value })
    }
}

/** Reviewer-selected canonical representation of two source activities. */
data class DuplicateDecision(
    val first: ActivityId,
    val second: ActivityId,
    val canonical: ActivityId,
    val state: LinkState,
)

data class SuppressedMovement(
    val canonicalActivity: ActivityId,
    val canonicalEvent: EventId,
    val duplicateActivity: ActivityId,
    val duplicateEvent: EventId,
)

data class DuplicateConflict(
    val first: ActivityId,
    val second: ActivityId,
    val reason: String,
)

data class EffectiveLedger(
    val activities: List<EffectiveActivity>,
    val suppressed: List<SuppressedMovement>,
    val conflicts: List<DuplicateConflict>,
)
