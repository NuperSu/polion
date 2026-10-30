@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.polion.android

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
import io.polion.core.reconcile.ClassifiedActivityProjection
import io.polion.core.reconcile.LinkState
import io.polion.core.reconcile.OverlayRepository
import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.Store

internal fun verifyBoundedDuplicateNative(db: SqliteDatabase) {
    Store(db)
    val ledger = ActivityRepository(db)
    val overlays = OverlayRepository(db)
    fun activity(id: String, account: String = "account", units: String = "123") = Activity(
        ActivityId(id), 1L, id, ActivityKind.DEPOSIT, ActivityStatus.CONFIRMED,
        listOf(Movement(EventId("event-$id"), AccountId(account), AtomicAmount(AssetId("asset"), ExactInteger.parse(units)), MovementRole.TRANSFER)),
        emptySet(), null, manual = true,
    )
    val first = activity("first")
    val second = activity("second")
    ledger.save(first)
    ledger.save(second)
    overlays.setDuplicateDecision(first.id, second.id, first.id, LinkState.ACCEPTED)
    val outgoing = activity("out", "from", "-5")
    val incoming = activity("in", "to", "5")
    val shared = activity("shared", "from", "-7")
    val firstClaim = activity("claim-a", "to-a", "7")
    val secondClaim = activity("claim-b", "to-b", "7")
    listOf(outgoing, incoming, shared, firstClaim, secondClaim).forEach(ledger::save)
    overlays.setDecision(outgoing.id, incoming.id, LinkState.ACCEPTED)
    overlays.setDecision(shared.id, firstClaim.id, LinkState.ACCEPTED)
    overlays.setDecision(shared.id, secondClaim.id, LinkState.ACCEPTED)
    val expected = overlays.replayForLedger(ledger.current())
    ClassifiedActivityProjection(db).withProjection(ledger.current().chunked(2).asSequence()) { stage ->
        check(stage.resolvedPage() == expected.activities)
        check(stage.suppressedPage().map { it.second } == expected.suppressed)
        check(stage.duplicateConflictPage().isEmpty())
        check(stage.transferLinksPage().map { it.second } == expected.transferLinks)
        check(stage.transferConflictsPage() == expected.transferLinkConflicts)
    }
    check(db.query("SELECT name FROM sqlite_temp_master WHERE name LIKE 'classified_%'").isEmpty())
}
