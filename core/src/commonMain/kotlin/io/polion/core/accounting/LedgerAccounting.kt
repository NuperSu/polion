package io.polion.core.accounting

import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityStatus
import io.polion.core.model.EventId
import io.polion.core.model.Rational

/** Decisions bind every movement to an accounting treatment and total quote amount. */
sealed interface Treatment {
    data class Acquire(
        val movement: EventId,
        val cost: Rational?,
        val opening: Boolean = false,
    ) : Treatment

    data class Dispose(
        val movement: EventId,
        val proceeds: Rational?,
        val fee: Boolean = false,
    ) : Treatment

    data class InternalTransfer(
        val outgoing: EventId,
        val incoming: EventId,
    ) : Treatment
}

object LedgerAccounting {
    fun actions(
        activity: Activity,
        treatments: List<Treatment>,
    ): List<AccountingAction> {
        if (activity.status != ActivityStatus.CONFIRMED) return emptyList()
        val movements = activity.movements.associateBy { it.id }
        val consumed = mutableSetOf<EventId>()

        fun movement(id: EventId) =
            requireNotNull(movements[id]) {
                "Unknown movement"
            }.also { require(consumed.add(id)) { "Movement already classified" } }
        val result =
            treatments.mapIndexed { index, treatment ->
                val order = activity.orderingKey + ":" + index.toString().padStart(8, '0')
                when (treatment) {
                    is Treatment.Acquire -> {
                        val m = movement(treatment.movement)
                        require(m.amount.atomicUnits.sign > 0)
                        AccountingAction.Acquisition(
                            activity.id,
                            activity.occurredAtEpochMillis,
                            order,
                            m.accountId,
                            m.amount.assetId,
                            m.amount.atomicUnits,
                            treatment.cost,
                            treatment.opening,
                        )
                    }
                    is Treatment.Dispose -> {
                        val m = movement(treatment.movement)
                        require(m.amount.atomicUnits.sign < 0)
                        AccountingAction.Disposal(
                            activity.id,
                            activity.occurredAtEpochMillis,
                            order,
                            m.accountId,
                            m.amount.assetId,
                            -m.amount.atomicUnits,
                            treatment.proceeds,
                            treatment.fee,
                        )
                    }
                    is Treatment.InternalTransfer -> {
                        val out = movement(treatment.outgoing)
                        val into = movement(treatment.incoming)
                        require(
                            out.amount.atomicUnits.sign < 0 &&
                                into.amount.atomicUnits.sign > 0 &&
                                out.amount.assetId == into.amount.assetId &&
                                -out.amount.atomicUnits == into.amount.atomicUnits,
                        )
                        AccountingAction.Transfer(
                            activity.id,
                            activity.occurredAtEpochMillis,
                            order,
                            out.accountId,
                            into.accountId,
                            into.amount.assetId,
                            into.amount.atomicUnits,
                        )
                    }
                }
            }
        require(consumed == movements.keys) { "Every movement needs an explicit accounting treatment" }
        return result
    }
}
