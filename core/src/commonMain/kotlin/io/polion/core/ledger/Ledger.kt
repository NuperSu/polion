package io.polion.core.ledger

import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.SourceRecordId

enum class ActivityStatus { PENDING, CONFIRMED, REVERSED, SUPERSEDED }

enum class ActivityKind { DEPOSIT, PAYMENT, TRANSFER, TRADE, REWARD, OTHER }

enum class MovementRole { TRANSFER, TRADE_LEG, FEE, REWARD }

data class Movement(
    val id: EventId,
    val accountId: AccountId,
    val amount: AtomicAmount,
    val role: MovementRole,
    val counterparty: String? = null,
)

enum class FeeAllocationReason { MULTIPLE_TRACKED_PAYERS, MIXED_TRACKED_EXTERNAL_PAYERS }

/** Observed transaction fee whose payer allocation is unknown; it does not change ledger balances. */
data class UnallocatedFee(
    val amount: AtomicAmount,
    val reason: FeeAllocationReason,
    val candidateAccounts: Set<AccountId>,
) {
    init {
        require(amount.atomicUnits.sign > 0) { "An observed fee must be positive" }
        require(candidateAccounts.isNotEmpty()) { "An unallocated fee needs a tracked payer candidate" }
        require(reason != FeeAllocationReason.MULTIPLE_TRACKED_PAYERS || candidateAccounts.size > 1) {
            "Multiple tracked payers require distinct candidate accounts"
        }
    }
}

data class Activity(
    val id: ActivityId,
    val occurredAtEpochMillis: Long,
    val orderingKey: String,
    val kind: ActivityKind,
    val status: ActivityStatus,
    val movements: List<Movement>,
    val sources: Set<SourceRecordId>,
    val decoderVersion: String?,
    val manual: Boolean = false,
    val unallocatedFees: List<UnallocatedFee> = emptyList(),
) {
    init {
        require(unallocatedFees.distinct().size == unallocatedFees.size) { "Duplicate unallocated fee" }
        require(movements.isNotEmpty())
        require(movements.map { it.id }.distinct().size == movements.size)
        require(orderingKey.isNotBlank())
        require(if (manual) sources.isEmpty() && decoderVersion == null else sources.isNotEmpty() && !decoderVersion.isNullOrBlank())
    }
}

/** Computes signed ledger deltas without assigning accounting or valuation meaning. */
object LedgerDeltas {
    fun calculate(
        activities: Iterable<Activity>,
        includePending: Boolean = false,
    ): Map<AccountId, Map<io.polion.core.model.AssetId, ExactInteger>> {
        val unique = activities.toList()
        require(unique.map { it.id }.distinct().size == unique.size) { "Duplicate activity identity" }
        val result = mutableMapOf<AccountId, MutableMap<io.polion.core.model.AssetId, ExactInteger>>()
        unique
            .filter { it.status == ActivityStatus.CONFIRMED || includePending && it.status == ActivityStatus.PENDING }
            .forEach { activity ->
                activity.movements.forEach { movement ->
                    val balances = result.getOrPut(movement.accountId) { mutableMapOf() }
                    val asset = movement.amount.assetId
                    balances[asset] = (balances[asset] ?: ExactInteger.parse("0")) + movement.amount.atomicUnits
                }
            }
        return result
    }
}
