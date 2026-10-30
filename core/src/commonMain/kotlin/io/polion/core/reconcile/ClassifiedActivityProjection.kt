package io.polion.core.reconcile

import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityRepository
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.storage.SqliteDatabase

/** Transaction-scoped classification scratch space. The callback must not mutate the source ledger. */
internal class ClassifiedActivityProjection(
    private val db: SqliteDatabase,
) {
    private var active = false

    fun <T> withProjection(
        batches: Sequence<List<Activity>>,
        block: (ClassifiedActivityProjection) -> T,
    ): T {
        check(!active) { "Projection already active" }
        active = true
        var headerCreated = false
        var amountsCreated = false
        var unresolvedCreated = false
        try {
            db.execute(
                "CREATE TEMP TABLE classified_projection (id TEXT PRIMARY KEY, kind TEXT NOT NULL, label TEXT, note TEXT, origin TEXT NOT NULL, rule_id TEXT, rule_revision INTEGER, overlay_revision INTEGER)",
            )
            headerCreated = true
            db.execute(
                "CREATE TEMP TABLE classified_amounts (id TEXT NOT NULL, event_id TEXT NOT NULL, asset TEXT NOT NULL, units TEXT NOT NULL, PRIMARY KEY(id, event_id))",
            )
            amountsCreated = true
            db.execute("CREATE TEMP TABLE classified_unresolved (id TEXT NOT NULL, event_id TEXT NOT NULL, PRIMARY KEY(id, event_id))")
            unresolvedCreated = true
            val overlays = OverlayRepository(db)
            return overlays.withClassificationBatches(batches, { batch -> batch.forEach(::insert) }) { block(this) }
        } finally {
            try {
                if (unresolvedCreated) db.execute("DROP TABLE temp.classified_unresolved")
                if (amountsCreated) db.execute("DROP TABLE temp.classified_amounts")
                if (headerCreated) db.execute("DROP TABLE temp.classified_projection")
            } finally {
                active = false
            }
        }
    }

    private fun insert(item: EffectiveActivity) {
        val original = item.original
        val effective = item.activity
        check(ActivityRepository(db).currentById(original.id) == original) { "Projection input does not match the current ledger revision" }
        check(original.id == effective.id && original.movements.size == effective.movements.size)
        check(original.copy(kind = effective.kind, movements = effective.movements) == effective) { "Unexpected classification fields" }
        db.execute(
            "INSERT INTO classified_projection VALUES (?,?,?,?,?,?,?,?)",
            listOf(
                original.id.value,
                effective.kind.name,
                item.label,
                item.note,
                item.classificationOrigin.name,
                item.ruleId,
                item.ruleRevision?.toString(),
                item.overlayRevision?.toString(),
            ),
        )
        original.movements.zip(effective.movements).forEach { (before, after) ->
            check(before.copy(amount = after.amount) == after) { "Classification changed movement identity" }
            if (before.amount != after.amount) {
                db.execute(
                    "INSERT INTO classified_amounts VALUES (?,?,?,?)",
                    listOf(original.id.value, before.id.value, after.amount.assetId.value, after.amount.atomicUnits.toString()),
                )
            }
        }
        item.unresolvedMovementIds.forEach { id ->
            db.execute("INSERT INTO classified_unresolved VALUES (?,?)", listOf(original.id.value, id.value))
        }
    }

    fun page(
        after: ActivityId? = null,
        limit: Int = 128,
    ): List<EffectiveActivity> {
        check(active)
        require(limit in 1..500)
        val rows =
            db.query(
                "SELECT id FROM classified_projection WHERE id>? ORDER BY id LIMIT ?",
                listOf(after?.value ?: "", limit.toString()),
            )
        return rows.map { requireNotNull(get(ActivityId(requireNotNull(it.single())))) }
    }

    fun get(id: ActivityId): EffectiveActivity? {
        check(active)
        val row =
            db
                .query(
                    "SELECT kind,label,note,origin,rule_id,rule_revision,overlay_revision FROM classified_projection WHERE id=?",
                    listOf(id.value),
                ).singleOrNull()
                ?: return null
        val original = checkNotNull(ActivityRepository(db).currentById(id)) { "Projected activity is missing" }
        val amounts =
            db.query("SELECT event_id,asset,units FROM classified_amounts WHERE id=?", listOf(id.value)).associate {
                EventId(requireNotNull(it[0])) to AtomicAmount(AssetId(requireNotNull(it[1])), ExactInteger.parse(requireNotNull(it[2])))
            }
        val movements = original.movements.map { movement -> amounts[movement.id]?.let { movement.copy(amount = it) } ?: movement }
        val unresolved =
            db
                .query("SELECT event_id FROM classified_unresolved WHERE id=? ORDER BY event_id", listOf(id.value))
                .map {
                    EventId(requireNotNull(it.single()))
                }.toSet()
        return EffectiveActivity(
            original,
            original.copy(kind = ActivityKind.valueOf(requireNotNull(row[0])), movements = movements),
            row[1],
            row[2],
            ClassificationOrigin.valueOf(requireNotNull(row[3])),
            row[4],
            row[5]?.toInt(),
            row[6]?.toInt(),
            unresolved,
        )
    }
}
