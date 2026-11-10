package io.valroot.core.reconcile

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
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.SourceRecordId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconciliationTest {
    private fun activity(
        id: String,
        account: String,
        amount: String,
        time: Long,
        role: MovementRole = MovementRole.TRANSFER,
    ) = Activity(
        ActivityId(id),
        time,
        id,
        ActivityKind.TRANSFER,
        ActivityStatus.CONFIRMED,
        listOf(Movement(EventId(id), AccountId(account), AtomicAmount(AssetId("btc"), ExactInteger.parse(amount)), role)),
        setOf(SourceRecordId(id)),
        "1",
    )

    @Test
    fun exactMatchesAreSuggestedAndAmbiguousMatchesRequireReview() {
        val outgoing = activity("out", "a", "-100", 1_000)
        val incoming = activity("in", "b", "100", 2_000)
        val candidate = Reconciliation.suggest(listOf(outgoing, incoming), 1_000).single()
        assertEquals(90, candidate.score)
        assertFalse(candidate.ambiguous)
        val competing = activity("in2", "c", "100", 2_001)
        assertTrue(Reconciliation.suggest(listOf(outgoing, incoming, competing), 5_000).all { it.ambiguous })
        assertTrue(Reconciliation.suggest(listOf(outgoing, incoming), 999).isEmpty())
        assertTrue(Reconciliation.suggest(listOf(outgoing.copy(status = ActivityStatus.REVERSED), incoming), 5_000).isEmpty())
        assertTrue(
            Reconciliation
                .suggest(
                    listOf(outgoing.copy(movements = listOf(outgoing.movements.single().copy(role = MovementRole.FEE))), incoming),
                    5_000,
                ).isEmpty(),
        )
    }

    @Test
    fun extremeTimestampsDoNotOverflow() {
        val early = activity("early", "a", "-1", Long.MIN_VALUE)
        val late = activity("late", "b", "1", Long.MAX_VALUE)
        assertTrue(Reconciliation.suggest(listOf(early, late), Long.MAX_VALUE).isEmpty())
    }
}
