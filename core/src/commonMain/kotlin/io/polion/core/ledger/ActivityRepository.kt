package io.polion.core.ledger

import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.SourceRecordId
import io.polion.core.storage.SqliteDatabase

/** Append-only activity revisions retain decoder outcomes and manual edits separately. */
class ActivityRepository(
    private val db: SqliteDatabase,
) {
    fun save(activity: Activity) = db.transaction { saveInTransaction(activity) }

    fun saveInTransaction(activity: Activity) {
        val previous = history(activity.id)
        if (previous.lastOrNull() != activity) {
            require(previous.isEmpty() || previous.last().manual == activity.manual) { "Manual/source origin cannot change" }
            val revision = previous.size.toString()
            db.execute(
                "INSERT INTO ledger_activities VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(
                    activity.id.value,
                    revision,
                    activity.occurredAtEpochMillis.toString(),
                    activity.orderingKey,
                    activity.kind.name,
                    activity.status.name,
                    activity.decoderVersion,
                    if (activity.manual) "1" else "0",
                ),
            )
            activity.movements.forEachIndexed { index, movement ->
                db.execute(
                    "INSERT INTO ledger_movements VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    listOf(
                        activity.id.value,
                        revision,
                        index.toString(),
                        movement.id.value,
                        movement.accountId.value,
                        movement.amount.assetId.value,
                        movement.amount.atomicUnits.toString(),
                        movement.role.name,
                        movement.counterparty,
                    ),
                )
            }
            activity.unallocatedFees.forEachIndexed { position, fee ->
                db.execute(
                    "INSERT INTO ledger_unallocated_fees VALUES (?, ?, ?, ?, ?, ?)",
                    listOf(
                        activity.id.value,
                        revision,
                        position.toString(),
                        fee.amount.assetId.value,
                        fee.amount.atomicUnits.toString(),
                        fee.reason.name,
                    ),
                )
                fee.candidateAccounts.sortedBy { it.value }.forEach { account ->
                    db.execute(
                        "INSERT INTO ledger_fee_candidates VALUES (?, ?, ?, ?)",
                        listOf(activity.id.value, revision, position.toString(), account.value),
                    )
                }
            }
            activity.sources.forEach {
                db.execute("INSERT INTO ledger_sources VALUES (?, ?, ?)", listOf(activity.id.value, revision, it.value))
            }
        }
    }

    fun history(id: ActivityId): List<Activity> =
        db.query("SELECT * FROM ledger_activities WHERE id=? ORDER BY CAST(revision AS INTEGER)", listOf(id.value)).map { row ->
            decodeRow(row)
        }

    private fun decodeRow(row: List<String?>): Activity {
        val id = ActivityId(row[0]!!)
        val revision = row[1]!!
        return Activity(
            id,
            row[2]!!.toLong(),
            row[3]!!,
            ActivityKind.valueOf(row[4]!!),
            ActivityStatus.valueOf(row[5]!!),
            db
                .query(
                    "SELECT event_id, account_id, asset_id, units, role, counterparty FROM ledger_movements WHERE activity_id=? AND revision=? ORDER BY position",
                    listOf(id.value, revision),
                ).map {
                    Movement(
                        EventId(it[0]!!),
                        AccountId(it[1]!!),
                        AtomicAmount(AssetId(it[2]!!), ExactInteger.parse(it[3]!!)),
                        MovementRole.valueOf(it[4]!!),
                        it[5],
                    )
                },
            db
                .query("SELECT source_id FROM ledger_sources WHERE activity_id=? AND revision=?", listOf(id.value, revision))
                .map {
                    SourceRecordId(it[0]!!)
                }.toSet(),
            row[6],
            row[7] == "1",
            db
                .query(
                    "SELECT position, asset_id, units, reason FROM ledger_unallocated_fees WHERE activity_id=? AND revision=? ORDER BY position",
                    listOf(id.value, revision),
                ).map { fee ->
                    UnallocatedFee(
                        AtomicAmount(AssetId(fee[1]!!), ExactInteger.parse(fee[2]!!)),
                        FeeAllocationReason.valueOf(fee[3]!!),
                        db
                            .query(
                                "SELECT account_id FROM ledger_fee_candidates WHERE activity_id=? AND revision=? AND fee_position=? ORDER BY account_id",
                                listOf(id.value, revision, fee[0]),
                            ).map { AccountId(it.single()!!) }
                            .toSet(),
                    )
                },
        )
    }

    /** Keyset pagination over latest revisions; callers must retain global reconciliation semantics. */
    fun currentPage(
        afterId: ActivityId? = null,
        limit: Int = 128,
    ): List<Activity> {
        require(limit in 1..500)
        val ids =
            db.query(
                "SELECT DISTINCT id FROM ledger_activities WHERE id > ? ORDER BY id LIMIT ?",
                listOf(afterId?.value ?: "", limit.toString()),
            )
        return ids.map { row ->
            val latest =
                db
                    .query(
                        "SELECT * FROM ledger_activities WHERE id=? ORDER BY revision DESC LIMIT 1",
                        listOf(row.single()),
                    ).single()
            decodeRow(latest)
        }
    }

    fun current(): List<Activity> {
        val all = mutableListOf<Activity>()
        var after: ActivityId? = null
        while (true) {
            val page = currentPage(after)
            if (page.isEmpty()) break
            all.addAll(page)
            after = page.last().id
        }
        return all
    }
}
