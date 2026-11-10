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
import io.valroot.core.model.SourceRecordId
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayRepositoryTest {
    @Test
    fun batchedClassificationMatchesReplayAndRollsBackConsumerWrites() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val overlays = OverlayRepository(db)
            overlays.saveRule(ClassificationRule("rule", 1, ActivityKind.REWARD, matchKind = ActivityKind.TRANSFER))
            val activities = (0 until 260).map { base().copy(id = ActivityId("tx-$it")) }
            overlays.saveOverlay(ActivityOverlay(activities.first().id, label = "Edited", kind = ActivityKind.PAYMENT))
            overlays.saveOverlay(
                ActivityOverlay(
                    activities.last().id,
                    amounts =
                        mapOf(
                            EventId("missing") to AtomicAmount(AssetId("btc"), ExactInteger.parse("9")),
                        ),
                ),
            )
            val expected = overlays.replay(activities)
            val actual = mutableListOf<EffectiveActivity>()
            overlays.replayClassificationsBatched(activities.chunked(128).asSequence()) { actual.addAll(it) }
            assertEquals(expected, actual)
            assertEquals(setOf(EventId("missing")), actual.last().unresolvedMovementIds)
            db.execute("CREATE TABLE batch_fixture (id TEXT PRIMARY KEY)")
            assertFailsWith<IllegalStateException> {
                overlays.replayClassificationsBatched(activities.chunked(128).asSequence()) { batch ->
                    batch.forEach { db.execute("INSERT INTO batch_fixture VALUES (?)", listOf(it.activity.id.value)) }
                    if (batch.first().activity.id == activities[128].id) error("interrupted")
                }
            }
            assertEquals("0", db.query("SELECT COUNT(*) FROM batch_fixture").single().single())
            assertFailsWith<IllegalArgumentException> {
                overlays.replayClassificationsBatched(sequenceOf(listOf(activities.first()), listOf(activities.first()))) { }
            }
            assertFailsWith<IllegalArgumentException> {
                overlays.replayClassificationsBatched(sequenceOf(List(501) { activities.first() })) { }
            }
        }
    }

    private fun base() =
        Activity(
            ActivityId("tx"),
            100,
            "1",
            ActivityKind.TRANSFER,
            ActivityStatus.CONFIRMED,
            listOf(
                Movement(
                    EventId("event"),
                    AccountId("wallet"),
                    AtomicAmount(AssetId("btc"), ExactInteger.parse("-20")),
                    MovementRole.TRANSFER,
                ),
            ),
            setOf(SourceRecordId("source")),
            "decoder:1",
        )

    @Test
    fun editsSurviveRedecodeReopenAndRuleReplayWithConflictsVisible() {
        val file = Files.createTempFile("valroot-overlays", ".sqlite")
        try {
            val initial = base()
            JvmSqliteDatabase(file.toString()).use { db ->
                Store(db)
                val ledger = ActivityRepository(db)
                val overlays = OverlayRepository(db)
                ledger.save(initial)
                val low = ClassificationRule("low", 1, ActivityKind.REWARD, matchKind = ActivityKind.TRANSFER)
                val high = ClassificationRule("high", 9, ActivityKind.PAYMENT, matchAsset = AssetId("btc"))
                assertEquals(0, overlays.saveRule(low))
                assertEquals(0, overlays.saveRule(high))
                val first = overlays.replay(ledger.current()).single()
                assertEquals(ActivityKind.PAYMENT, first.activity.kind)
                assertEquals(ClassificationOrigin.RULE, first.classificationOrigin)
                assertEquals("high", first.ruleId)
                assertEquals(0, first.ruleRevision)
                val edit =
                    ActivityOverlay(
                        initial.id,
                        "Custom label",
                        "keep this",
                        ActivityKind.TRADE,
                        mapOf(
                            EventId("event") to AtomicAmount(AssetId("btc"), ExactInteger.parse("-21")),
                        ),
                    )
                assertEquals(0, overlays.saveOverlay(edit))
                assertEquals(0, overlays.saveOverlay(edit))
                val edited = overlays.replay(ledger.current()).single()
                assertEquals(ActivityKind.TRADE, edited.activity.kind)
                assertEquals(ClassificationOrigin.USER, edited.classificationOrigin)
                assertNull(edited.ruleId)
                assertEquals(
                    "-21",
                    edited.activity.movements
                        .single()
                        .amount.atomicUnits
                        .toString(),
                )
                assertEquals(
                    "-20",
                    edited.original.movements
                        .single()
                        .amount.atomicUnits
                        .toString(),
                )
                ledger.save(initial.copy(decoderVersion = "decoder:2", kind = ActivityKind.DEPOSIT))
                assertEquals("Custom label", overlays.replay(ledger.current()).single().label)
                assertEquals(1, overlays.saveRule(high.copy(enabled = false)))
                assertEquals(0, overlays.setDecision(initial.id, ActivityId("peer"), LinkState.ACCEPTED))
                assertEquals(0, overlays.setDecision(ActivityId("peer"), initial.id, LinkState.ACCEPTED))
            }
            JvmSqliteDatabase(file.toString()).use { db ->
                Store(db)
                val ledger = ActivityRepository(db)
                val overlays = OverlayRepository(db)
                val reopened = overlays.replay(ledger.current()).single()
                assertEquals("keep this", reopened.note)
                assertEquals(ActivityKind.TRADE, reopened.activity.kind)
                assertEquals("decoder:2", reopened.original.decoderVersion)
                assertEquals(listOf(LinkDecision(ActivityId("peer"), ActivityId("tx"), LinkState.ACCEPTED)), overlays.decisions())
                val removed =
                    reopened.original.copy(
                        decoderVersion = "decoder:3",
                        movements =
                            listOf(
                                reopened.original.movements
                                    .single()
                                    .copy(id = EventId("new")),
                            ),
                    )
                ledger.save(removed)
                val changed = overlays.replay(ledger.current()).single()
                assertEquals(setOf(EventId("event")), changed.unresolvedMovementIds)
                assertEquals(
                    "-20",
                    changed.activity.movements
                        .single()
                        .amount.atomicUnits
                        .toString(),
                )
                assertTrue(ledger.history(initial.id).size == 3)
                overlays.saveOverlay(ActivityOverlay(initial.id, label = "Custom label"))
                val withoutManualKind = overlays.replay(listOf(initial)).single()
                assertEquals(ActivityKind.REWARD, withoutManualKind.activity.kind)
                assertEquals("low", withoutManualKind.ruleId)
                assertEquals(1, overlays.setDecision(initial.id, ActivityId("peer"), LinkState.REJECTED))
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
