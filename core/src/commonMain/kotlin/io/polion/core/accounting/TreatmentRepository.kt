package io.polion.core.accounting

import io.polion.core.model.ActivityId
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.Rational
import io.polion.core.storage.SqliteDatabase

/** Durable user accounting decisions are independent of decoded activity revisions. */
class TreatmentRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.transaction {
            db.execute(
                "CREATE TABLE IF NOT EXISTS accounting_treatments (activity_id TEXT NOT NULL, position INTEGER NOT NULL, kind TEXT NOT NULL, movement TEXT NOT NULL, other_movement TEXT, numerator TEXT, denominator TEXT, flag INTEGER NOT NULL, PRIMARY KEY(activity_id, position))",
            )
        }
    }

    fun save(
        activity: ActivityId,
        treatments: List<Treatment>,
    ) = db.transaction {
        db.execute("DELETE FROM accounting_treatments WHERE activity_id=?", listOf(activity.value))
        treatments.forEachIndexed { index, item ->
            val kind: String
            val movement: EventId
            val other: EventId?
            val value: Rational?
            val flag: Boolean
            when (item) {
                is Treatment.Acquire -> {
                    kind = "ACQUIRE"
                    movement = item.movement
                    other = null
                    value = item.cost
                    flag = item.opening
                }
                is Treatment.Dispose -> {
                    kind = "DISPOSE"
                    movement = item.movement
                    other = null
                    value = item.proceeds
                    flag = item.fee
                }
                is Treatment.InternalTransfer -> {
                    kind = "TRANSFER"
                    movement = item.outgoing
                    other = item.incoming
                    value = null
                    flag =
                        false
                }
            }
            db.execute(
                "INSERT INTO accounting_treatments VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(
                    activity.value,
                    index.toString(),
                    kind,
                    movement.value,
                    other?.value,
                    value?.numerator?.toString(),
                    value?.denominator?.toString(),
                    if (flag) "1" else "0",
                ),
            )
        }
    }

    fun load(activity: ActivityId): List<Treatment> =
        db
            .query(
                "SELECT kind, movement, other_movement, numerator, denominator, flag FROM accounting_treatments WHERE activity_id=? ORDER BY position",
                listOf(activity.value),
            ).map { row ->
                val value = row[3]?.let { Rational(ExactInteger.parse(it), ExactInteger.parse(requireNotNull(row[4]))) }
                when (row[0]) {
                    "ACQUIRE" -> Treatment.Acquire(EventId(row[1]!!), value, row[5] == "1")
                    "DISPOSE" -> Treatment.Dispose(EventId(row[1]!!), value, row[5] == "1")
                    "TRANSFER" -> Treatment.InternalTransfer(EventId(row[1]!!), EventId(row[2]!!))
                    else -> error("Unknown accounting treatment")
                }
            }
}
