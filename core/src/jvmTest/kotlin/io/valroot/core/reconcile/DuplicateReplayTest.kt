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
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.storage.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateReplayTest {
    private fun movement(
        id: String,
        amount: String,
        account: String = "wallet",
        role: MovementRole = MovementRole.TRANSFER,
    ) = Movement(EventId(id), AccountId(account), AtomicAmount(AssetId("btc"), ExactInteger.parse(amount)), role)

    private fun activity(
        id: String,
        vararg movements: Movement,
    ) = Activity(
        ActivityId(id),
        100,
        id,
        ActivityKind.TRANSFER,
        ActivityStatus.CONFIRMED,
        movements.toList(),
        setOf(SourceRecordId("source-$id")),
        "1",
    )

    @Test
    fun reviewedDuplicateRemovesOnlyMatchingLegAndRetainsFeesAndSourceAudit() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repo = OverlayRepository(db)
            val canonical = activity("a", movement("a-leg", "100"))
            val duplicate = activity("b", movement("b-leg", "100"), movement("b-fee", "-2", role = MovementRole.FEE))
            val transfer = activity("c", movement("c-leg", "-100", "other"))
            assertEquals(3, repo.replayForLedger(listOf(canonical, duplicate, transfer)).activities.size)
            repo.setDecision(canonical.id, transfer.id, LinkState.ACCEPTED)
            repo.setDuplicateDecision(canonical.id, duplicate.id, canonical.id, LinkState.ACCEPTED)
            val result = repo.replayForLedger(listOf(canonical, duplicate, transfer))
            assertTrue(result.conflicts.isEmpty())
            assertEquals(listOf(SuppressedMovement(canonical.id, EventId("a-leg"), duplicate.id, EventId("b-leg"))), result.suppressed)
            assertEquals(
                setOf("a-leg", "b-fee", "c-leg"),
                result.activities
                    .flatMap { it.activity.movements }
                    .map { it.id.value }
                    .toSet(),
            )
            assertEquals(
                setOf("b-leg", "b-fee"),
                result.activities
                    .single {
                        it.original.id == duplicate.id
                    }.original.movements
                    .map { it.id.value }
                    .toSet(),
            )
            repo.setDuplicateDecision(canonical.id, duplicate.id, canonical.id, LinkState.REJECTED)
            assertTrue(repo.replayForLedger(listOf(canonical, duplicate, transfer)).suppressed.isEmpty())
        }
    }

    @Test
    fun ambiguousAndMissingMatchesRemainVisibleAndNeverSuppress() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repo = OverlayRepository(db)
            val canonical = activity("a", movement("first", "100"), movement("second", "100"))
            val duplicate = activity("b", movement("matched", "100"))
            repo.setDuplicateDecision(canonical.id, duplicate.id, canonical.id, LinkState.ACCEPTED)
            val ambiguous = repo.replayForLedger(listOf(canonical, duplicate))
            assertEquals("ambiguous movement matches", ambiguous.conflicts.single().reason)
            assertTrue(ambiguous.suppressed.isEmpty())
            val missing = repo.replayForLedger(listOf(canonical))
            assertEquals("activity missing", missing.conflicts.single().reason)
            assertTrue(missing.suppressed.isEmpty())
        }
    }
}
