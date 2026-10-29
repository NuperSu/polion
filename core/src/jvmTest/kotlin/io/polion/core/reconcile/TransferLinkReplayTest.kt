package io.polion.core.reconcile

import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.SourceRecordId
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferLinkReplayTest {
    private fun movement(
        id: String,
        amount: String,
        account: String,
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
        setOf(SourceRecordId(id)),
        "1",
    )

    @Test
    fun reviewedRelationshipSurvivesReopenAndRejectWithoutChangingMovements() {
        val file = Files.createTempFile("polion-transfer-links", ".sqlite")
        val outgoing = activity("out", movement("out-leg", "-100", "a"), movement("fee", "-2", "a", MovementRole.FEE))
        val incoming = activity("in", movement("in-leg", "100", "b"))
        val inputs = listOf(outgoing, incoming)
        val expected = TransferLink(outgoing.id, EventId("out-leg"), incoming.id, EventId("in-leg"))
        try {
            JvmSqliteDatabase(file.toString()).use { db ->
                Store(db)
                val repo = OverlayRepository(db)
                repo.setDecision(incoming.id, outgoing.id, LinkState.ACCEPTED)
                val result = repo.replayForLedger(inputs)
                assertEquals(listOf(expected), result.transferLinks)
                assertTrue(result.transferLinkConflicts.isEmpty())
                assertEquals(inputs, result.activities.map { it.activity })
                assertEquals(inputs, result.activities.map { it.original })
                assertTrue(result.suppressed.isEmpty())
                assertEquals(result, repo.replayForLedger(inputs))
            }
            JvmSqliteDatabase(file.toString()).use { db ->
                val repo = OverlayRepository(db)
                assertEquals(listOf(expected), repo.replayForLedger(inputs).transferLinks)
                repo.setDecision(outgoing.id, incoming.id, LinkState.REJECTED)
                assertTrue(repo.replayForLedger(inputs).transferLinks.isEmpty())
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun missingInactiveAmbiguousAndStaleCorrectionsRemainConflicts() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repo = OverlayRepository(db)
            val outgoing = activity("out", movement("out-leg", "-100", "a"))
            val incoming = activity("in", movement("in-leg", "100", "b"))
            repo.setDecision(outgoing.id, incoming.id, LinkState.ACCEPTED)

            fun assertConflict(
                reason: String,
                vararg inputs: Activity,
            ) {
                val result = repo.replayForLedger(inputs.toList())
                assertTrue(result.transferLinks.isEmpty())
                assertEquals(reason, result.transferLinkConflicts.single().reason)
                assertEquals(inputs.toList(), result.activities.map { it.activity })
            }
            assertConflict("activity missing", outgoing)
            assertConflict("activity inactive", outgoing, incoming.copy(status = ActivityStatus.REVERSED))
            assertConflict("activity inactive", outgoing.copy(status = ActivityStatus.SUPERSEDED), incoming)
            assertConflict("no matching movements", outgoing, incoming.copy(movements = listOf(movement("in-leg", "99", "b"))))
            assertConflict("no matching movements", outgoing, incoming.copy(movements = listOf(movement("in-leg", "100", "a"))))
            assertConflict(
                "no matching movements",
                outgoing,
                incoming.copy(movements = listOf(movement("in-leg", "100", "b", MovementRole.FEE))),
            )
            assertConflict(
                "ambiguous movement matches",
                outgoing,
                incoming.copy(movements = listOf(movement("first", "100", "b"), movement("second", "100", "b"))),
            )
            repo.saveOverlay(ActivityOverlay(outgoing.id, amounts = mapOf(EventId("removed") to outgoing.movements.single().amount)))
            assertConflict("unresolved movement correction", outgoing, incoming)
        }
    }

    @Test
    fun competingLinksConflictTogetherAndResolveAfterRejection() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repo = OverlayRepository(db)
            val outgoing = activity("out", movement("out-leg", "-100", "a"))
            val first = activity("first", movement("first-leg", "100", "b"))
            val second = activity("second", movement("second-leg", "100", "c"))
            val inputs = listOf(outgoing, first, second)
            repo.setDecision(outgoing.id, first.id, LinkState.ACCEPTED)
            repo.setDecision(outgoing.id, second.id, LinkState.ACCEPTED)
            val result = repo.replayForLedger(inputs)
            assertTrue(result.transferLinks.isEmpty())
            assertEquals(
                listOf(
                    "movement claimed by multiple links",
                    "movement claimed by multiple links",
                ),
                result.transferLinkConflicts.map {
                    it.reason
                },
            )
            assertEquals(result.transferLinkConflicts, repo.replayForLedger(inputs.reversed()).transferLinkConflicts)
            repo.setDecision(outgoing.id, second.id, LinkState.REJECTED)
            assertEquals(1, repo.replayForLedger(inputs).transferLinks.size)
            assertTrue(repo.replayForLedger(inputs).transferLinkConflicts.isEmpty())
        }
    }

    @Test
    fun duplicateSuppressionAndCorrectionsAreAppliedBeforeResolvingLinks() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repo = OverlayRepository(db)
            val outgoing = activity("out", movement("out-leg", "-100", "a"))
            val canonical = activity("canonical", movement("canonical-leg", "100", "b"))
            val duplicate = activity("duplicate", movement("duplicate-leg", "100", "b"))
            val inputs = listOf(outgoing, canonical, duplicate)
            repo.setDecision(outgoing.id, duplicate.id, LinkState.ACCEPTED)
            repo.setDuplicateDecision(canonical.id, duplicate.id, canonical.id, LinkState.ACCEPTED)
            val result = repo.replayForLedger(inputs)
            assertEquals(1, result.suppressed.size)
            assertTrue(result.transferLinks.isEmpty())
            assertEquals("no matching movements", result.transferLinkConflicts.single().reason)
            repo.setDecision(outgoing.id, duplicate.id, LinkState.REJECTED)
            repo.setDecision(outgoing.id, canonical.id, LinkState.ACCEPTED)
            assertEquals(1, repo.replayForLedger(inputs).transferLinks.size)
            repo.saveOverlay(
                ActivityOverlay(
                    outgoing.id,
                    amounts =
                        mapOf(
                            EventId("out-leg") to AtomicAmount(AssetId("btc"), ExactInteger.parse("-99")),
                        ),
                ),
            )
            val corrected = repo.replayForLedger(inputs)
            assertTrue(corrected.transferLinks.isEmpty())
            assertEquals("no matching movements", corrected.transferLinkConflicts.single().reason)
        }
    }
}
