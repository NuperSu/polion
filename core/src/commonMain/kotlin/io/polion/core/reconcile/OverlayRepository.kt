package io.polion.core.reconcile

import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.SourceRecordId
import io.polion.core.storage.SqliteDatabase

/** User overlays, rule revisions and reconciliation decisions outlive decoder replays. */
class OverlayRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute("PRAGMA foreign_keys = ON")
        db.transaction {
            db.execute("CREATE TABLE IF NOT EXISTS overlay_schema (version INTEGER NOT NULL)")
            val schema = db.query("SELECT version FROM overlay_schema")
            if (schema.isEmpty()) {
                db.execute("INSERT INTO overlay_schema VALUES (2)")
                db.execute(
                    "CREATE TABLE overlay_revisions (activity_id TEXT NOT NULL, revision INTEGER NOT NULL, label TEXT, note TEXT, kind TEXT, PRIMARY KEY(activity_id, revision))",
                )
                db.execute(
                    "CREATE TABLE overlay_amounts (activity_id TEXT NOT NULL, revision INTEGER NOT NULL, event_id TEXT NOT NULL, asset_id TEXT NOT NULL, units TEXT NOT NULL, PRIMARY KEY(activity_id, revision, event_id), FOREIGN KEY(activity_id, revision) REFERENCES overlay_revisions(activity_id, revision))",
                )
                db.execute(
                    "CREATE TABLE classification_rules (id TEXT NOT NULL, revision INTEGER NOT NULL, priority INTEGER NOT NULL, result TEXT NOT NULL, match_kind TEXT, match_asset TEXT, match_source TEXT, enabled INTEGER NOT NULL, PRIMARY KEY(id, revision))",
                )
                db.execute(
                    "CREATE TABLE link_decisions (first_id TEXT NOT NULL, second_id TEXT NOT NULL, revision INTEGER NOT NULL, state TEXT NOT NULL, PRIMARY KEY(first_id, second_id, revision))",
                )
                db.execute(
                    "CREATE TABLE duplicate_decisions (first_id TEXT NOT NULL, second_id TEXT NOT NULL, revision INTEGER NOT NULL, canonical_id TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY(first_id, second_id, revision))",
                )
            } else {
                require(schema.size == 1 && schema.single().single() in setOf("1", "2")) { "Unsupported overlay schema" }
                if (schema.single().single() == "1") {
                    db.execute(
                        "CREATE TABLE duplicate_decisions (first_id TEXT NOT NULL, second_id TEXT NOT NULL, revision INTEGER NOT NULL, canonical_id TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY(first_id, second_id, revision))",
                    )
                    db.execute("UPDATE overlay_schema SET version=2")
                }
                val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").mapNotNull { it.single() }.toSet()
                check(
                    tables.containsAll(
                        setOf("overlay_revisions", "overlay_amounts", "classification_rules", "link_decisions", "duplicate_decisions"),
                    ),
                ) {
                    "Overlay schema is incomplete"
                }
            }
        }
    }

    fun saveOverlay(overlay: ActivityOverlay): Int =
        db.transaction {
            val previous = readOverlay(overlay.activityId)
            if (previous?.second == overlay) return@transaction previous.first
            val revision = (previous?.first ?: -1) + 1
            db.execute(
                "INSERT INTO overlay_revisions VALUES (?, ?, ?, ?, ?)",
                listOf(overlay.activityId.value, revision.toString(), overlay.label, overlay.note, overlay.kind?.name),
            )
            overlay.amounts.entries.sortedBy { it.key.value }.forEach { (event, amount) ->
                db.execute(
                    "INSERT INTO overlay_amounts VALUES (?, ?, ?, ?, ?)",
                    listOf(overlay.activityId.value, revision.toString(), event.value, amount.assetId.value, amount.atomicUnits.toString()),
                )
            }
            revision
        }

    fun overlay(id: ActivityId): Pair<Int, ActivityOverlay>? = readOverlay(id)

    private fun readOverlay(id: ActivityId): Pair<Int, ActivityOverlay>? {
        val row =
            db
                .query(
                    "SELECT revision, label, note, kind FROM overlay_revisions WHERE activity_id=? ORDER BY revision DESC LIMIT 1",
                    listOf(id.value),
                ).singleOrNull()
                ?: return null
        val revision = row[0]!!.toInt()
        val values =
            db.query(
                "SELECT event_id, asset_id, units FROM overlay_amounts WHERE activity_id=? AND revision=? ORDER BY event_id",
                listOf(id.value, revision.toString()),
            )
        val amounts = values.associate { EventId(it[0]!!) to AtomicAmount(AssetId(it[1]!!), ExactInteger.parse(it[2]!!)) }
        return revision to ActivityOverlay(id, row[1], row[2], row[3]?.let(ActivityKind::valueOf), amounts)
    }

    fun saveRule(rule: ClassificationRule): Int =
        db.transaction {
            val previous = readRules().firstOrNull { it.second.id == rule.id }
            if (previous?.second == rule) return@transaction previous.first
            val revision = (previous?.first ?: -1) + 1
            db.execute(
                "INSERT INTO classification_rules VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(
                    rule.id,
                    revision.toString(),
                    rule.priority.toString(),
                    rule.result.name,
                    rule.matchKind?.name,
                    rule.matchAsset?.value,
                    rule.matchSource?.value,
                    if (rule.enabled) "1" else "0",
                ),
            )
            revision
        }

    fun rules(): List<Pair<Int, ClassificationRule>> = readRules()

    private fun readRules(): List<Pair<Int, ClassificationRule>> =
        db
            .query(
                "SELECT id, revision, priority, result, match_kind, match_asset, match_source, enabled FROM classification_rules r WHERE revision=(SELECT MAX(revision) FROM classification_rules WHERE id=r.id) ORDER BY priority DESC, id",
            ).map {
                it[1]!!.toInt() to
                    ClassificationRule(
                        it[0]!!,
                        it[2]!!.toInt(),
                        ActivityKind.valueOf(it[3]!!),
                        it[4]?.let(ActivityKind::valueOf),
                        it[5]?.let(::AssetId),
                        it[6]?.let(::SourceRecordId),
                        it[7] == "1",
                    )
            }

    /** Resolve against one consistent snapshot; caller supplies the current decoded versions. */
    fun replay(activities: Collection<Activity>): List<EffectiveActivity> = db.transaction { replayInTransaction(activities) }

    private fun replayInTransaction(activities: Collection<Activity>): List<EffectiveActivity> {
        require(activities.map { it.id }.distinct().size == activities.size) { "Duplicate activity identity" }
        val rules = readRules()
        return activities.map { base ->
            val saved = readOverlay(base.id)
            val user = saved?.second
            val chosenRule = rules.firstOrNull { it.second.matches(base) }
            val kind = user?.kind ?: chosenRule?.second?.result ?: base.kind
            val origin =
                when {
                    user?.kind != null -> ClassificationOrigin.USER
                    chosenRule != null -> ClassificationOrigin.RULE
                    else -> ClassificationOrigin.ORIGINAL
                }
            val amounts = user?.amounts.orEmpty()
            val corrected = base.movements.map { movement -> amounts[movement.id]?.let { movement.copy(amount = it) } ?: movement }
            EffectiveActivity(
                base,
                base.copy(kind = kind, movements = corrected),
                user?.label,
                user?.note,
                origin,
                chosenRule?.second?.id?.takeIf { origin == ClassificationOrigin.RULE },
                chosenRule?.first?.takeIf { origin == ClassificationOrigin.RULE },
                saved?.first,
                amounts.keys - base.movements.map { it.id }.toSet(),
            )
        }
    }

    fun setDecision(
        first: ActivityId,
        second: ActivityId,
        state: LinkState,
    ): Int =
        db.transaction {
            require(first != second) { "Cannot reconcile an activity with itself" }
            val ids = listOf(first.value, second.value).sorted()
            val previous =
                db
                    .query(
                        "SELECT revision, state FROM link_decisions WHERE first_id=? AND second_id=? ORDER BY revision DESC LIMIT 1",
                        ids,
                    ).singleOrNull()
            if (previous?.get(1) == state.name) return@transaction previous[0]!!.toInt()
            val revision = (previous?.get(0)?.toInt() ?: -1) + 1
            db.execute("INSERT INTO link_decisions VALUES (?, ?, ?, ?)", listOf(ids[0], ids[1], revision.toString(), state.name))
            revision
        }

    fun decisions(): List<LinkDecision> =
        db
            .query(
                "SELECT first_id, second_id, state FROM link_decisions l WHERE revision=(SELECT MAX(revision) FROM link_decisions WHERE first_id=l.first_id AND second_id=l.second_id) ORDER BY first_id, second_id",
            ).map { LinkDecision(ActivityId(it[0]!!), ActivityId(it[1]!!), LinkState.valueOf(it[2]!!)) }

    fun setDuplicateDecision(
        first: ActivityId,
        second: ActivityId,
        canonical: ActivityId,
        state: LinkState,
    ): Int =
        db.transaction {
            require(first != second && canonical in setOf(first, second)) { "Canonical activity must belong to a distinct pair" }
            val ids = listOf(first.value, second.value).sorted()
            val previous =
                db
                    .query(
                        "SELECT revision, canonical_id, state FROM duplicate_decisions WHERE first_id=? AND second_id=? ORDER BY revision DESC LIMIT 1",
                        ids,
                    ).singleOrNull()
            if (previous?.get(1) == canonical.value && previous[2] == state.name) return@transaction previous[0]!!.toInt()
            val revision = (previous?.get(0)?.toInt() ?: -1) + 1
            db.execute(
                "INSERT INTO duplicate_decisions VALUES (?, ?, ?, ?, ?)",
                listOf(ids[0], ids[1], revision.toString(), canonical.value, state.name),
            )
            revision
        }

    fun duplicateDecisions(): List<DuplicateDecision> =
        db
            .query(
                "SELECT first_id, second_id, canonical_id, state FROM duplicate_decisions l WHERE revision=(SELECT MAX(revision) FROM duplicate_decisions WHERE first_id=l.first_id AND second_id=l.second_id) ORDER BY first_id, second_id",
            ).map { DuplicateDecision(ActivityId(it[0]!!), ActivityId(it[1]!!), ActivityId(it[2]!!), LinkState.valueOf(it[3]!!)) }

    /** Explicit duplicate decisions suppress only unique equal movements; all original records remain available. */
    fun replayForLedger(activities: Collection<Activity>): EffectiveLedger =
        db.transaction {
            val replayed = replayInTransaction(activities)
            val byId = replayed.associateBy { it.activity.id }
            val removed = mutableMapOf<ActivityId, MutableSet<EventId>>()
            val suppressed = mutableListOf<SuppressedMovement>()
            val conflicts = mutableListOf<DuplicateConflict>()
            for (decision in duplicateDecisions().filter { it.state == LinkState.ACCEPTED }) {
                val canonical = byId[decision.canonical]
                val duplicateId = if (decision.canonical == decision.first) decision.second else decision.first
                val duplicate = byId[duplicateId]

                fun conflict(reason: String) {
                    conflicts.add(DuplicateConflict(decision.first, decision.second, reason))
                }
                if (canonical == null || duplicate == null) {
                    conflict("activity missing")
                    continue
                }
                if (canonical.activity.status != duplicate.activity.status) {
                    conflict("activity statuses differ")
                    continue
                }
                if (canonical.unresolvedMovementIds.isNotEmpty() || duplicate.unresolvedMovementIds.isNotEmpty()) {
                    conflict("unresolved movement correction")
                    continue
                }
                val canonicalMovements = canonical.activity.movements.filter { it.id !in removed[canonical.activity.id].orEmpty() }
                val duplicateMovements = duplicate.activity.movements.filter { it.id !in removed[duplicate.activity.id].orEmpty() }
                val matches =
                    duplicateMovements.mapNotNull { candidate ->
                        val possible =
                            canonicalMovements.filter {
                                it.accountId == candidate.accountId && it.amount == candidate.amount && it.role == candidate.role
                            }
                        if (possible.size > 1) return@mapNotNull candidate.id to null
                        possible.singleOrNull()?.let { candidate.id to it.id }
                    }
                if (matches.isEmpty()) {
                    conflict("no matching movements")
                    continue
                }
                if (matches.any { it.second == null } || matches.mapNotNull { it.second }.distinct().size != matches.size) {
                    conflict("ambiguous movement matches")
                    continue
                }
                val deleted = removed.getOrPut(duplicate.activity.id) { mutableSetOf() }
                matches.forEach { (duplicateEvent, canonicalEvent) ->
                    deleted.add(duplicateEvent)
                    suppressed.add(SuppressedMovement(canonical.activity.id, canonicalEvent!!, duplicate.activity.id, duplicateEvent))
                }
            }
            val effective =
                replayed.mapNotNull { item ->
                    val movements = item.activity.movements.filter { it.id !in removed[item.activity.id].orEmpty() }
                    if (movements.isEmpty()) null else item.copy(activity = item.activity.copy(movements = movements))
                }
            EffectiveLedger(effective, suppressed, conflicts)
        }
}
