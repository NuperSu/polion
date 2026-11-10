package io.valroot.core.reconcile

import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityRepository
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactInteger
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.storage.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoundedDuplicateReplayTest {
    private fun movement(
        id: String,
        units: String,
        role: MovementRole = MovementRole.TRANSFER,
    ) = Movement(EventId(id), AccountId("owner"), AtomicAmount(AssetId("btc"), ExactInteger.parse(units)), role)

    private fun activity(
        id: String,
        vararg movements: Movement,
    ) = Activity(
        ActivityId(id),
        1L,
        id,
        ActivityKind.TRANSFER,
        ActivityStatus.CONFIRMED,
        movements.toList(),
        emptySet(),
        null,
        manual = true,
    )

    @Test
    fun decisionAndSuppressionPagesKeepGlobalOrderAcrossBoundaries() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val ledger = ActivityRepository(db)
            val overlays = OverlayRepository(db)
            repeat(150) { index ->
                val suffix = index.toString().padStart(3, '0')
                val first = activity("a-$suffix", movement("first-$suffix", "10"))
                val second = activity("z-$suffix", movement("second-$suffix", "10"))
                ledger.save(first)
                ledger.save(second)
                overlays.setDuplicateDecision(first.id, second.id, first.id, LinkState.ACCEPTED)
            }
            val expected = overlays.replayForLedger(ledger.current())

            fun pages() =
                sequence {
                    var cursor: ActivityId? = null
                    while (true) {
                        val page = ledger.currentPage(cursor, 128)
                        if (page.isEmpty()) break
                        yield(page)
                        cursor = page.last().id
                    }
                }
            ClassifiedActivityProjection(db).withProjection(pages()) { stage ->
                val actual = mutableListOf<SuppressedMovement>()
                var cursor = 0L
                while (true) {
                    val page = stage.suppressedPage(cursor, 37)
                    if (page.isEmpty()) break
                    actual.addAll(page.map { it.second })
                    cursor = page.last().first
                }
                assertEquals(expected.suppressed, actual)
                assertEquals(150, actual.size)
                assertEquals(150, stage.resolvedPage(limit = 200).size)
            }
        }
    }

    @Test
    fun globalDecisionsMatchOracleAcrossPagesAndConflictCases() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val ledger = ActivityRepository(db)
            val overlays = OverlayRepository(db)
            val a = activity("a", movement("a", "100"))
            val z = activity("z", movement("z", "100"), movement("fee", "-2", MovementRole.FEE))
            val chained = activity("zz", movement("zz", "100"))
            val ambiguous = activity("b", movement("b1", "4"), movement("b2", "4"))
            val ambiguousPeer = activity("y", movement("y", "4"))
            val pending = activity("c", movement("c", "8")).copy(status = ActivityStatus.PENDING)
            val confirmed = activity("x", movement("x", "8"))
            val unresolved = activity("d", movement("d", "9"))
            val unresolvedPeer = activity("w", movement("w", "9"))
            val full = activity("e", movement("e", "5"))
            val fullPeer = activity("v", movement("v", "5"))
            val all =
                listOf(a, z, chained, ambiguous, ambiguousPeer, pending, confirmed, unresolved, unresolvedPeer, full, fullPeer) +
                    (0 until 260).map { index -> activity("f-${index.toString().padStart(3, '0')}", movement("f-$index", "1")) }
            all.forEach(ledger::save)
            overlays.saveOverlay(
                ActivityOverlay(
                    unresolved.id,
                    amounts =
                        mapOf(
                            EventId("missing") to AtomicAmount(AssetId("btc"), ExactInteger.parse("9")),
                        ),
                ),
            )
            overlays.setDuplicateDecision(a.id, z.id, a.id, LinkState.ACCEPTED)
            overlays.setDuplicateDecision(z.id, chained.id, z.id, LinkState.ACCEPTED)
            overlays.setDuplicateDecision(ambiguous.id, ambiguousPeer.id, ambiguous.id, LinkState.ACCEPTED)
            overlays.setDuplicateDecision(pending.id, confirmed.id, pending.id, LinkState.ACCEPTED)
            overlays.setDuplicateDecision(unresolved.id, unresolvedPeer.id, unresolved.id, LinkState.ACCEPTED)
            overlays.setDuplicateDecision(full.id, fullPeer.id, full.id, LinkState.ACCEPTED)
            overlays.setDuplicateDecision(a.id, ActivityId("missing"), a.id, LinkState.ACCEPTED)
            overlays.setDuplicateDecision(full.id, a.id, full.id, LinkState.ACCEPTED)
            overlays.setDuplicateDecision(full.id, a.id, full.id, LinkState.REJECTED)
            val expected = overlays.replayForLedger(ledger.current())

            fun pages() =
                sequence {
                    var cursor: ActivityId? = null
                    while (true) {
                        val page = ledger.currentPage(cursor, 128)
                        if (page.isEmpty()) break
                        yield(page)
                        cursor = page.last().id
                    }
                }
            ClassifiedActivityProjection(db).withProjection(pages()) { stage ->
                val actual = mutableListOf<EffectiveActivity>()
                var cursor: ActivityId? = null
                while (true) {
                    val page = stage.resolvedPage(cursor, 31)
                    if (page.isEmpty()) break
                    actual.addAll(page)
                    cursor = page.last().activity.id
                }
                assertEquals(expected.activities, actual)
                assertNull(stage.resolvedGet(fullPeer.id))
                assertEquals(expected.suppressed, stage.suppressedPage(limit = 128).map { it.second })
                assertEquals(expected.conflicts, stage.duplicateConflictPage(limit = 128).map { it.second })
            }
        }
    }
}
