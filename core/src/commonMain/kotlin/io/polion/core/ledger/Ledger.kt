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
) {
    init {
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
