package io.valroot.core.exchange

import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.SourceRecordId

/**
 * Collects the activities of one exchange decode. Each activity's ID is `<exchange>:<uid>:<key>`; a record that moves
 * a coin without an asset is held back and its coin reported, and an amount finer than the asset's decimals fails
 * that record with a note. [totals] and [changes] keep what was recorded per coin, for balance adjustments.
 */
internal class ExchangeLedgerBuilder(
    private val kind: ExchangeKind,
    private val account: AccountId,
    private val uid: String,
    private val assets: Map<String, AssetId>,
    private val decimalsOf: (AssetId) -> Int,
    private val version: String,
) {
    val activities = mutableListOf<Activity>()
    val transfers = mutableMapOf<ActivityId, ExchangeTransfer>()
    val unresolved = mutableSetOf<String>()
    val failures = mutableListOf<String>()
    val totals = mutableMapOf<String, ExactDecimal>()

    /** Every recorded change: coin, time, ordering key and amount. */
    val changes = mutableListOf<Change>()

    data class Change(
        val coin: String,
        val atMillis: Long,
        val order: String,
        val amount: ExactDecimal,
    )

    fun result() = ExchangeDecoded(activities.sortedBy { it.orderingKey }, transfers, unresolved, failures)

    /** Builds one activity from [block]'s movements; returns null when it moved nothing or could not be built. */
    fun build(
        key: String,
        atMillis: Long,
        sources: Set<SourceRecordId>,
        block: ExchangeMovements.() -> ActivityKind?,
    ): ActivityId? {
        val movements = ExchangeMovements()
        val chosen = movements.block()
        val missing = movements.items.map { it.first }.filter { it !in assets }
        if (missing.isNotEmpty()) {
            unresolved += missing
            return null
        }
        val id = ActivityId("${kind.id}:$uid:$key")
        val built =
            try {
                movements.items.filter { it.second.coefficient.sign != 0 }.mapIndexed { index, (coin, amount, role) ->
                    Movement(
                        EventId("${id.value}:$index"),
                        account,
                        assets.getValue(coin).let { AtomicAmount.fromDecimal(it, amount, decimalsOf(it)) },
                        role,
                    )
                }
            } catch (_: IllegalArgumentException) {
                failures += "An amount in ${kind.label} record $key is finer than its coin's decimals"
                return null
            }
        if (built.isEmpty()) return null
        val order = "${kind.id}:${atMillis.toString().padStart(20, '0')}:$key"
        movements.items.forEach { (coin, amount, _) ->
            totals[coin] = (totals[coin] ?: ZERO) + amount
            if (amount.coefficient.sign != 0) changes += Change(coin, atMillis, order, amount)
        }
        val legs = built.filter { it.role == MovementRole.TRADE_LEG }
        val activityKind =
            chosen ?: when {
                legs.any { it.amount.atomicUnits.sign < 0 } &&
                    legs.any { it.amount.atomicUnits.sign > 0 } &&
                    legs.map { it.amount.assetId }.distinct().size > 1 -> ActivityKind.TRADE
                else -> ActivityKind.OTHER
            }
        val final =
            if (activityKind == ActivityKind.TRADE || legs.isEmpty()) {
                built
            } else {
                built.map { if (it.role == MovementRole.TRADE_LEG) it.copy(role = MovementRole.TRANSFER) else it }
            }
        activities += Activity(id, atMillis, order, activityKind, ActivityStatus.CONFIRMED, final, sources, version)
        return id
    }

    private companion object {
        val ZERO = ExactDecimal.parse("0")
    }
}

/** Movements of one activity under construction, as coin, signed amount and role. */
internal class ExchangeMovements {
    val items = mutableListOf<Triple<String, ExactDecimal, MovementRole>>()

    fun add(
        coin: String,
        amount: ExactDecimal,
        role: MovementRole,
    ) {
        items += Triple(coin, amount, role)
    }

    /** A paid fee (positive) is a fee movement; a rebate (negative fee) is a reward. */
    fun fee(
        coin: String,
        fee: ExactDecimal,
    ) {
        when {
            fee.coefficient.sign > 0 -> add(coin, ExactDecimal(-fee.coefficient, fee.scale), MovementRole.FEE)
            fee.coefficient.sign < 0 -> add(coin, ExactDecimal(-fee.coefficient, fee.scale), MovementRole.REWARD)
        }
    }
}
