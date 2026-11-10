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

class BoundedTransferReplayTest {
    private fun activity(
        id: String,
        account: String,
        units: String,
        status: ActivityStatus = ActivityStatus.CONFIRMED,
    ): Activity =
        Activity(
            ActivityId(id),
            1L,
            id,
            ActivityKind.TRANSFER,
            status,
            listOf(
                Movement(
                    EventId("event-$id"),
                    AccountId(account),
                    AtomicAmount(AssetId("btc"), ExactInteger.parse(units)),
                    MovementRole.TRANSFER,
                ),
            ),
            emptySet(),
            null,
            manual = true,
        )

    @Test
    fun globalLinksAndConflictsMatchOracleAcrossPages() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val ledger = ActivityRepository(db)
            val overlays = OverlayRepository(db)
            repeat(150) { index ->
                val suffix = index.toString().padStart(3, '0')
                val out = activity("a-$suffix", "out-$suffix", "-10")
                val incoming = activity("z-$suffix", "in-$suffix", "10")
                ledger.save(out)
                ledger.save(incoming)
                overlays.setDecision(out.id, incoming.id, LinkState.ACCEPTED)
            }
            val shared = activity("shared", "one", "-20")
            val claimA = activity("claim-a", "two", "20")
            val claimB = activity("claim-b", "three", "20")
            val suppressed = activity("suppressed", "wallet", "30")
            val canonical = activity("canonical", "wallet", "30")
            val sender = activity("sender", "other", "-30")
            val inactive = activity("inactive", "old", "-40", ActivityStatus.REVERSED)
            val live = activity("live", "new", "40")
            val stale = activity("stale", "old", "-50")
            val stalePeer = activity("stale-peer", "new", "50")
            listOf(shared, claimA, claimB, suppressed, canonical, sender, inactive, live, stale, stalePeer).forEach(ledger::save)
            overlays.setDecision(shared.id, claimA.id, LinkState.ACCEPTED)
            overlays.setDecision(shared.id, claimB.id, LinkState.ACCEPTED)
            overlays.setDuplicateDecision(canonical.id, suppressed.id, canonical.id, LinkState.ACCEPTED)
            overlays.setDecision(sender.id, suppressed.id, LinkState.ACCEPTED)
            overlays.setDecision(inactive.id, live.id, LinkState.ACCEPTED)
            overlays.saveOverlay(
                ActivityOverlay(
                    stale.id,
                    amounts =
                        mapOf(
                            EventId("missing") to AtomicAmount(AssetId("btc"), ExactInteger.parse("1")),
                        ),
                ),
            )
            overlays.setDecision(stale.id, stalePeer.id, LinkState.ACCEPTED)
            overlays.setDecision(ActivityId("missing"), live.id, LinkState.ACCEPTED)
            // UTF-16 ordering differs from SQLite UTF-8 ordering for these identifiers.
            val bmp = activity("\uE000", "old", "-1")
            val supplementary = activity("\uD800\uDC00", "old", "-1")
            ledger.save(bmp)
            ledger.save(supplementary)
            overlays.setDecision(bmp.id, ActivityId("\uE001"), LinkState.ACCEPTED)
            overlays.setDecision(supplementary.id, ActivityId("\uD800\uDC01"), LinkState.ACCEPTED)
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
                val links = mutableListOf<TransferLink>()
                var cursor = 0L
                while (true) {
                    val page = stage.transferLinksPage(cursor, 37)
                    if (page.isEmpty()) break
                    links.addAll(page.map { it.second })
                    cursor = page.last().first
                }
                val conflicts = mutableListOf<TransferLinkConflict>()
                var after: TransferLinkConflict? = null
                while (true) {
                    val page = stage.transferConflictsPage(after, 2)
                    if (page.isEmpty()) break
                    conflicts.addAll(page)
                    after = page.last()
                }
                assertEquals(expected.transferLinks, links)
                assertEquals(expected.transferLinkConflicts, conflicts)
                assertEquals(150, links.size)
            }
        }
    }
}
