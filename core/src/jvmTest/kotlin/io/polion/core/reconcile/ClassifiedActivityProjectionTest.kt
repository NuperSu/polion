package io.polion.core.reconcile

import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityRepository
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class ClassifiedActivityProjectionTest {
    @Test
    fun pagesAndRandomAccessMatchClassificationReplayAndCleanup() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val ledger = ActivityRepository(db)
            val overlay = OverlayRepository(db)
            val all =
                (0 until 260).map { index ->
                    val id = "tx-${index.toString().padStart(3, '0')}"
                    Activity(
                        ActivityId(id),
                        index.toLong(),
                        id,
                        ActivityKind.DEPOSIT,
                        ActivityStatus.CONFIRMED,
                        listOf(
                            Movement(
                                EventId("event-$id"),
                                AccountId("account"),
                                AtomicAmount(AssetId("btc"), ExactInteger.parse("10")),
                                MovementRole.TRANSFER,
                            ),
                        ),
                        emptySet(),
                        null,
                        manual = true,
                    )
                }
            all.forEach(ledger::save)
            overlay.saveRule(ClassificationRule("rule", 1, ActivityKind.REWARD, matchKind = ActivityKind.DEPOSIT))
            overlay.saveOverlay(
                ActivityOverlay(
                    all[128].id,
                    label = "Adjusted",
                    kind = ActivityKind.PAYMENT,
                    amounts = mapOf(all[128].movements.single().id to AtomicAmount(AssetId("btc"), ExactInteger.parse("-999"))),
                ),
            )
            overlay.saveOverlay(
                ActivityOverlay(
                    all.last().id,
                    amounts =
                        mapOf(
                            EventId("missing") to AtomicAmount(AssetId("btc"), ExactInteger.parse("1")),
                        ),
                ),
            )
            val expected = overlay.replay(all)

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
            val projection = ClassifiedActivityProjection(db)
            projection.withProjection(pages()) { stage ->
                val actual = mutableListOf<EffectiveActivity>()
                var cursor: ActivityId? = null
                while (true) {
                    val page = stage.page(cursor, 128)
                    if (page.isEmpty()) break
                    actual.addAll(page)
                    cursor = page.last().activity.id
                }
                assertEquals(expected, actual)
                assertEquals(expected[128], stage.get(all[128].id))
                assertNull(stage.get(ActivityId("unknown")))
            }
            assertFails { projection.page() }
            assertEquals(emptyList(), db.query("SELECT name FROM sqlite_temp_master WHERE name LIKE 'classified_%'"))
            assertFails { projection.withProjection(pages()) { error("consumer failure") } }
            assertEquals(emptyList(), db.query("SELECT name FROM sqlite_temp_master WHERE name LIKE 'classified_%'"))
            assertFails {
                projection.withProjection(sequenceOf(listOf(all.first().copy(status = ActivityStatus.REVERSED)))) { }
            }
            assertEquals(emptyList(), db.query("SELECT name FROM sqlite_temp_master WHERE name LIKE 'classified_%'"))
            db.execute("CREATE TEMP TABLE classified_projection (sentinel TEXT)")
            assertFails { projection.withProjection(pages()) { } }
            assertEquals(1, db.query("SELECT name FROM sqlite_temp_master WHERE name='classified_projection'").size)
            db.execute("DROP TABLE temp.classified_projection")
        }
    }
}
