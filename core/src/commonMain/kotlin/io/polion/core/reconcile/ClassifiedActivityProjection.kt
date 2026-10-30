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

/** Transaction-scoped scratch space. The callback must not mutate the source ledger. */
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
        var removedCreated = false
        var conflictsCreated = false
        var candidatesCreated = false
        var claimsCreated = false
        var linksCreated = false
        var transferConflictsCreated = false
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
            db.execute(
                "CREATE TEMP TABLE classified_removed (seq INTEGER PRIMARY KEY, id TEXT NOT NULL, event_id TEXT NOT NULL, canonical_id TEXT NOT NULL, canonical_event TEXT NOT NULL, UNIQUE(id,event_id))",
            )
            removedCreated = true
            db.execute(
                "CREATE TEMP TABLE classified_duplicate_conflicts (seq INTEGER PRIMARY KEY, first_id TEXT NOT NULL, second_id TEXT NOT NULL, reason TEXT NOT NULL)",
            )
            conflictsCreated = true
            db.execute(
                "CREATE TEMP TABLE classified_transfer_candidates (seq INTEGER PRIMARY KEY, first_id TEXT NOT NULL, second_id TEXT NOT NULL, out_id TEXT NOT NULL, out_event TEXT NOT NULL, in_id TEXT NOT NULL, in_event TEXT NOT NULL)",
            )
            candidatesCreated = true
            db.execute(
                "CREATE TEMP TABLE classified_transfer_claims (id TEXT NOT NULL, event_id TEXT NOT NULL, claims INTEGER NOT NULL, PRIMARY KEY(id,event_id))",
            )
            claimsCreated = true
            db.execute(
                "CREATE TEMP TABLE classified_transfer_links (seq INTEGER PRIMARY KEY, out_id TEXT NOT NULL, out_event TEXT NOT NULL, in_id TEXT NOT NULL, in_event TEXT NOT NULL)",
            )
            linksCreated = true
            db.execute(
                "CREATE TEMP TABLE classified_transfer_conflicts (sort_first TEXT NOT NULL, sort_second TEXT NOT NULL, first_id TEXT NOT NULL, second_id TEXT NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(sort_first,sort_second))",
            )
            transferConflictsCreated = true
            val overlays = OverlayRepository(db)
            return overlays.withClassificationBatches(batches, { batch -> batch.forEach(::insert) }) {
                resolveDuplicates()
                resolveTransfers()
                block(this)
            }
        } finally {
            try {
                if (transferConflictsCreated) db.execute("DROP TABLE temp.classified_transfer_conflicts")
                if (linksCreated) db.execute("DROP TABLE temp.classified_transfer_links")
                if (claimsCreated) db.execute("DROP TABLE temp.classified_transfer_claims")
                if (candidatesCreated) db.execute("DROP TABLE temp.classified_transfer_candidates")
                if (conflictsCreated) db.execute("DROP TABLE temp.classified_duplicate_conflicts")
                if (removedCreated) db.execute("DROP TABLE temp.classified_removed")
                if (unresolvedCreated) db.execute("DROP TABLE temp.classified_unresolved")
                if (amountsCreated) db.execute("DROP TABLE temp.classified_amounts")
                if (headerCreated) db.execute("DROP TABLE temp.classified_projection")
            } finally {
                active = false
            }
        }
    }

    private fun resolveDuplicates() {
        var first = ""
        var second = ""
        var removedSequence = 0L
        var conflictSequence = 0L
        while (true) {
            val rows =
                db.query(
                    "SELECT first_id,second_id,canonical_id FROM duplicate_decisions d WHERE state='ACCEPTED' " +
                        "AND revision=(SELECT MAX(revision) FROM duplicate_decisions " +
                        "WHERE first_id=d.first_id AND second_id=d.second_id) " +
                        "AND (first_id>? OR (first_id=? AND second_id>?)) ORDER BY first_id,second_id LIMIT 128",
                    listOf(first, first, second),
                )
            if (rows.isEmpty()) break
            rows.forEach { row ->
                val firstId = ActivityId(requireNotNull(row[0]))
                val secondId = ActivityId(requireNotNull(row[1]))
                val canonical = ActivityId(requireNotNull(row[2]))
                val duplicate = if (canonical == firstId) secondId else firstId
                when (val result = resolveDuplicate(get(canonical), get(duplicate), removedIds(canonical), removedIds(duplicate))) {
                    is DuplicateResolution.Conflict -> {
                        conflictSequence++
                        db.execute(
                            "INSERT INTO classified_duplicate_conflicts VALUES (?,?,?,?)",
                            listOf(conflictSequence.toString(), firstId.value, secondId.value, result.reason),
                        )
                    }
                    is DuplicateResolution.Matches ->
                        result.movements.forEach { movement ->
                            removedSequence++
                            db.execute(
                                "INSERT INTO classified_removed VALUES (?,?,?,?,?)",
                                listOf(
                                    removedSequence.toString(),
                                    movement.duplicateActivity.value,
                                    movement.duplicateEvent.value,
                                    movement.canonicalActivity.value,
                                    movement.canonicalEvent.value,
                                ),
                            )
                        }
                }
            }
            first = requireNotNull(rows.last()[0])
            second = requireNotNull(rows.last()[1])
        }
    }

    private fun resolveTransfers() {
        var first = ""
        var second = ""
        var sequence = 0L
        while (true) {
            val rows =
                db.query(
                    "SELECT first_id,second_id FROM link_decisions d WHERE state='ACCEPTED' " +
                        "AND revision=(SELECT MAX(revision) FROM link_decisions WHERE first_id=d.first_id AND second_id=d.second_id) " +
                        "AND (first_id>? OR (first_id=? AND second_id>?)) ORDER BY first_id,second_id LIMIT 128",
                    listOf(first, first, second),
                )
            if (rows.isEmpty()) break
            rows.forEach { row ->
                val firstId = ActivityId(requireNotNull(row[0]))
                val secondId = ActivityId(requireNotNull(row[1]))
                val firstOriginal = get(firstId)
                val secondOriginal = get(secondId)
                val result =
                    resolveTransfer(
                        firstOriginal?.let(::withoutRemoved),
                        secondOriginal?.let(::withoutRemoved),
                        firstOriginal != null,
                        secondOriginal != null,
                    )
                when (result) {
                    is TransferResolution.Conflict -> addTransferConflict(firstId, secondId, result.reason)
                    is TransferResolution.Match -> {
                        sequence++
                        val link = result.link
                        db.execute(
                            "INSERT INTO classified_transfer_candidates VALUES (?,?,?,?,?,?,?)",
                            listOf(
                                sequence.toString(),
                                firstId.value,
                                secondId.value,
                                link.outgoingActivity.value,
                                link.outgoingEvent.value,
                                link.incomingActivity.value,
                                link.incomingEvent.value,
                            ),
                        )
                        incrementClaim(link.outgoingActivity, link.outgoingEvent)
                        incrementClaim(link.incomingActivity, link.incomingEvent)
                    }
                }
            }
            first = requireNotNull(rows.last()[0])
            second = requireNotNull(rows.last()[1])
        }
        var after = 0L
        while (true) {
            val candidates =
                db.query(
                    "SELECT seq,first_id,second_id,out_id,out_event,in_id,in_event FROM classified_transfer_candidates WHERE seq>? ORDER BY seq LIMIT 128",
                    listOf(after.toString()),
                )
            if (candidates.isEmpty()) break
            candidates.forEach { row ->
                val outgoing = ActivityId(requireNotNull(row[3]))
                val outgoingEvent = EventId(requireNotNull(row[4]))
                val incoming = ActivityId(requireNotNull(row[5]))
                val incomingEvent = EventId(requireNotNull(row[6]))
                if (claimCount(outgoing, outgoingEvent) > 1 || claimCount(incoming, incomingEvent) > 1) {
                    addTransferConflict(
                        ActivityId(requireNotNull(row[1])),
                        ActivityId(requireNotNull(row[2])),
                        "movement claimed by multiple links",
                    )
                } else {
                    db.execute("INSERT INTO classified_transfer_links VALUES (?,?,?,?,?)", listOf(row[0], row[3], row[4], row[5], row[6]))
                }
            }
            after = requireNotNull(candidates.last()[0]).toLong()
        }
    }

    private fun incrementClaim(
        id: ActivityId,
        event: EventId,
    ) {
        db.execute("INSERT OR IGNORE INTO classified_transfer_claims VALUES (?,?,0)", listOf(id.value, event.value))
        db.execute("UPDATE classified_transfer_claims SET claims=claims+1 WHERE id=? AND event_id=?", listOf(id.value, event.value))
    }

    private fun claimCount(
        id: ActivityId,
        event: EventId,
    ): Long =
        requireNotNull(
            db
                .query(
                    "SELECT claims FROM classified_transfer_claims WHERE id=? AND event_id=?",
                    listOf(id.value, event.value),
                ).single()
                .single(),
        ).toLong()

    private fun addTransferConflict(
        first: ActivityId,
        second: ActivityId,
        reason: String,
    ) {
        db.execute(
            "INSERT INTO classified_transfer_conflicts VALUES (?,?,?,?,?)",
            listOf(sortKey(first.value), sortKey(second.value), first.value, second.value, reason),
        )
    }

    /** Hexadecimal UTF-16 code units preserve Kotlin String.compareTo ordering in SQLite. */
    private fun sortKey(value: String): String = value.map { it.code.toString(16).padStart(4, '0') }.joinToString("")

    fun transferLinksPage(
        after: Long = 0,
        limit: Int = 128,
    ): List<Pair<Long, TransferLink>> {
        check(active)
        require(after >= 0 && limit in 1..500)
        return db
            .query(
                "SELECT seq,out_id,out_event,in_id,in_event FROM classified_transfer_links WHERE seq>? ORDER BY seq LIMIT ?",
                listOf(after.toString(), limit.toString()),
            ).map {
                requireNotNull(it[0]).toLong() to
                    TransferLink(
                        ActivityId(requireNotNull(it[1])),
                        EventId(requireNotNull(it[2])),
                        ActivityId(requireNotNull(it[3])),
                        EventId(requireNotNull(it[4])),
                    )
            }
    }

    fun transferConflictsPage(
        after: TransferLinkConflict? = null,
        limit: Int = 128,
    ): List<TransferLinkConflict> {
        check(active)
        require(limit in 1..500)
        val first = after?.first?.value?.let(::sortKey) ?: ""
        val second = after?.second?.value?.let(::sortKey) ?: ""
        return db
            .query(
                "SELECT first_id,second_id,reason FROM classified_transfer_conflicts WHERE sort_first>? OR (sort_first=? AND sort_second>?) ORDER BY sort_first,sort_second LIMIT ?",
                listOf(first, first, second, limit.toString()),
            ).map {
                TransferLinkConflict(ActivityId(requireNotNull(it[0])), ActivityId(requireNotNull(it[1])), requireNotNull(it[2]))
            }
    }

    private fun removedIds(id: ActivityId): Set<EventId> =
        db
            .query(
                "SELECT event_id FROM classified_removed WHERE id=?",
                listOf(id.value),
            ).map { EventId(requireNotNull(it.single())) }
            .toSet()

    /** The post-duplicate activity view; transfer links are available separately. */
    fun resolvedGet(id: ActivityId): EffectiveActivity? {
        val item = get(id) ?: return null
        return withoutRemoved(item)
    }

    private fun withoutRemoved(item: EffectiveActivity): EffectiveActivity? {
        val removed = removedIds(item.activity.id)
        val movements = item.activity.movements.filter { it.id !in removed }
        return if (movements.isEmpty()) null else item.copy(activity = item.activity.copy(movements = movements))
    }

    fun resolvedPage(
        after: ActivityId? = null,
        limit: Int = 128,
    ): List<EffectiveActivity> {
        require(limit in 1..500)
        val result = mutableListOf<EffectiveActivity>()
        var cursor = after
        while (result.size < limit) {
            val batch = page(cursor, limit - result.size)
            if (batch.isEmpty()) break
            batch.forEach { item -> withoutRemoved(item)?.let(result::add) }
            cursor = batch.last().activity.id
        }
        return result
    }

    fun suppressedPage(
        after: Long = 0,
        limit: Int = 128,
    ): List<Pair<Long, SuppressedMovement>> {
        check(active)
        require(after >= 0 && limit in 1..500)
        return db
            .query(
                "SELECT seq,canonical_id,canonical_event,id,event_id FROM classified_removed WHERE seq>? ORDER BY seq LIMIT ?",
                listOf(after.toString(), limit.toString()),
            ).map {
                requireNotNull(it[0]).toLong() to
                    SuppressedMovement(
                        ActivityId(requireNotNull(it[1])),
                        EventId(requireNotNull(it[2])),
                        ActivityId(requireNotNull(it[3])),
                        EventId(requireNotNull(it[4])),
                    )
            }
    }

    fun duplicateConflictPage(
        after: Long = 0,
        limit: Int = 128,
    ): List<Pair<Long, DuplicateConflict>> {
        check(active)
        require(after >= 0 && limit in 1..500)
        return db
            .query(
                "SELECT seq,first_id,second_id,reason FROM classified_duplicate_conflicts WHERE seq>? ORDER BY seq LIMIT ?",
                listOf(after.toString(), limit.toString()),
            ).map {
                requireNotNull(it[0]).toLong() to
                    DuplicateConflict(ActivityId(requireNotNull(it[1])), ActivityId(requireNotNull(it[2])), requireNotNull(it[3]))
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
