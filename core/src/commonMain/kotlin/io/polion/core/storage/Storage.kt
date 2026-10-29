package io.polion.core.storage

import io.polion.core.model.SourceRecordId
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Platform SQLite connections serialize transactions and roll them back when the block fails. */
interface SqliteDatabase : AutoCloseable {
    fun execute(
        sql: String,
        arguments: List<String?> = emptyList(),
    )

    fun query(
        sql: String,
        arguments: List<String?> = emptyList(),
    ): List<List<String?>>

    fun <T> transaction(block: () -> T): T
}

expect fun sha256(value: String): String

class Store(
    private val db: SqliteDatabase,
) : AutoCloseable {
    init {
        db.execute("PRAGMA foreign_keys = ON")
        db.transaction {
            val version =
                db
                    .query("PRAGMA user_version")
                    .single()
                    .single()!!
                    .toInt()
            require(version in 0..6) { "Unsupported database schema version $version" }
            if (version == 0) {
                db.execute(
                    "CREATE TABLE raw_records (id TEXT PRIMARY KEY, namespace TEXT NOT NULL, external_id TEXT NOT NULL, network TEXT NOT NULL, UNIQUE(namespace, external_id, network))",
                )
                db.execute(
                    "CREATE TABLE raw_revisions (record_id TEXT NOT NULL REFERENCES raw_records(id), checksum TEXT NOT NULL, payload TEXT NOT NULL, payload_version TEXT NOT NULL, PRIMARY KEY(record_id, checksum))",
                )
                db.execute(
                    "CREATE TABLE raw_observations (record_id TEXT NOT NULL, checksum TEXT NOT NULL, source_id TEXT NOT NULL, retrieved_at TEXT NOT NULL, context TEXT NOT NULL, PRIMARY KEY(record_id, checksum, source_id, retrieved_at), FOREIGN KEY(record_id, checksum) REFERENCES raw_revisions(record_id, checksum))",
                )
                db.execute("PRAGMA user_version = 1")
            }
            if (version < 2) {
                db.execute(
                    "CREATE TABLE ledger_activities (id TEXT NOT NULL, revision INTEGER NOT NULL, occurred TEXT NOT NULL, ordering_key TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, decoder TEXT, manual INTEGER NOT NULL, PRIMARY KEY(id, revision))",
                )
                db.execute(
                    "CREATE TABLE ledger_movements (activity_id TEXT NOT NULL, revision INTEGER NOT NULL, position INTEGER NOT NULL, event_id TEXT NOT NULL, account_id TEXT NOT NULL, asset_id TEXT NOT NULL, units TEXT NOT NULL, role TEXT NOT NULL, counterparty TEXT, PRIMARY KEY(activity_id, revision, position), FOREIGN KEY(activity_id, revision) REFERENCES ledger_activities(id, revision))",
                )
                db.execute(
                    "CREATE TABLE ledger_sources (activity_id TEXT NOT NULL, revision INTEGER NOT NULL, source_id TEXT NOT NULL, PRIMARY KEY(activity_id, revision, source_id), FOREIGN KEY(activity_id, revision) REFERENCES ledger_activities(id, revision))",
                )
                db.execute("PRAGMA user_version = 2")
            }
            if (version < 3) {
                db.execute("CREATE TABLE owners (id TEXT PRIMARY KEY, label TEXT NOT NULL)")
                db.execute(
                    "CREATE TABLE accounts (id TEXT PRIMARY KEY, owner_id TEXT NOT NULL REFERENCES owners(id), label TEXT NOT NULL, kind TEXT NOT NULL, parent_id TEXT REFERENCES accounts(id) DEFERRABLE INITIALLY DEFERRED)",
                )
                db.execute(
                    "CREATE TABLE targets (id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id), network TEXT NOT NULL, kind TEXT NOT NULL, canonical_key TEXT NOT NULL, role TEXT NOT NULL, label TEXT NOT NULL, enabled INTEGER NOT NULL, wallet_id TEXT REFERENCES targets(id) DEFERRABLE INITIALLY DEFERRED)",
                )
                db.execute("CREATE TABLE portfolios (id TEXT PRIMARY KEY, label TEXT NOT NULL, currency TEXT NOT NULL)")
                db.execute(
                    "CREATE TABLE portfolio_owners (portfolio_id TEXT NOT NULL REFERENCES portfolios(id), owner_id TEXT NOT NULL REFERENCES owners(id), PRIMARY KEY(portfolio_id, owner_id))",
                )
                db.execute(
                    "CREATE TABLE portfolio_accounts (portfolio_id TEXT NOT NULL REFERENCES portfolios(id), account_id TEXT NOT NULL REFERENCES accounts(id), PRIMARY KEY(portfolio_id, account_id))",
                )
                db.execute(
                    "CREATE TABLE ownership_tags (entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, tag TEXT NOT NULL, PRIMARY KEY(entity_type, entity_id, tag))",
                )
                db.execute("PRAGMA user_version = 3")
            }
            if (version < 4) {
                db.execute(
                    "CREATE TABLE ledger_unallocated_fees (activity_id TEXT NOT NULL, revision INTEGER NOT NULL, position INTEGER NOT NULL, asset_id TEXT NOT NULL, units TEXT NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(activity_id, revision, position), FOREIGN KEY(activity_id, revision) REFERENCES ledger_activities(id, revision))",
                )
                db.execute(
                    "CREATE TABLE ledger_fee_candidates (activity_id TEXT NOT NULL, revision INTEGER NOT NULL, fee_position INTEGER NOT NULL, account_id TEXT NOT NULL, PRIMARY KEY(activity_id, revision, fee_position, account_id), FOREIGN KEY(activity_id, revision, fee_position) REFERENCES ledger_unallocated_fees(activity_id, revision, position))",
                )
                db.execute("PRAGMA user_version = 4")
            }
            if (version < 5) {
                io.polion.core.bitcoin
                    .createBitcoinGraphTables(db)
                db.execute("PRAGMA user_version = 5")
            }
            if (version < 6) {
                io.polion.core.bitcoin
                    .createBitcoinGraphDeltaTables(db)
                db.execute("PRAGMA user_version = 6")
            }
            val requiredTables =
                listOf(
                    "raw_records",
                    "raw_revisions",
                    "raw_observations",
                    "ledger_activities",
                    "ledger_movements",
                    "ledger_sources",
                    "ledger_unallocated_fees",
                    "ledger_fee_candidates",
                    "owners",
                    "accounts",
                    "targets",
                    "portfolios",
                    "portfolio_owners",
                    "portfolio_accounts",
                    "ownership_tags",
                )
            val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").mapNotNull { it.single() }.toSet()
            check(
                tables.containsAll(
                    requiredTables + io.polion.core.bitcoin.bitcoinGraphTables + io.polion.core.bitcoin.bitcoinGraphDeltaTables,
                ),
            ) { "Database schema is incomplete" }
            check(db.query("PRAGMA integrity_check").single().single() == "ok") { "Database integrity check failed" }
            check(db.query("PRAGMA foreign_key_check").isEmpty()) { "Database foreign key check failed" }
        }
    }

    /** A shared namespace identifies records from different providers only when their native IDs are comparable. */
    fun ingest(record: RawObservation) = db.transaction { ingestInTransaction(record) }

    @OptIn(ExperimentalTime::class)
    fun ingestInTransaction(record: RawObservation): SourceRecordId {
        require(
            listOf(record.id, record.namespace, record.externalId, record.sourceId, record.checksum, record.retrievedAt).all {
                it.isNotBlank()
            },
        )
        require(record.retrievedAt.endsWith("Z")) { "Retrieval timestamp must be UTC" }
        Instant.parse(record.retrievedAt)
        require(record.checksum == sha256(record.payload)) { "Payload checksum mismatch" }
        db.execute(
            "INSERT OR IGNORE INTO raw_records VALUES (?, ?, ?, ?)",
            listOf(record.id, record.namespace, record.externalId, record.network),
        )
        val found =
            db
                .query(
                    "SELECT id FROM raw_records WHERE namespace=? AND external_id=? AND network=?",
                    listOf(record.namespace, record.externalId, record.network),
                ).singleOrNull()
                ?.single()
        require(found != null) { "Record ID already identifies a different source record" }
        val existing =
            db
                .query(
                    "SELECT payload=? AND payload_version=? FROM raw_revisions WHERE record_id=? AND checksum=?",
                    listOf(record.payload, record.payloadVersion, found, record.checksum),
                ).singleOrNull()
        require(existing == null || existing.single() == "1") {
            "Checksum collision or inconsistent payload version"
        }
        db.execute(
            "INSERT OR IGNORE INTO raw_revisions VALUES (?, ?, ?, ?)",
            listOf(found, record.checksum, record.payload, record.payloadVersion),
        )
        val previous =
            db
                .query(
                    "SELECT context FROM raw_observations WHERE record_id=? AND checksum=? AND source_id=? AND retrieved_at=?",
                    listOf(found, record.checksum, record.sourceId, record.retrievedAt),
                ).singleOrNull()
        require(previous == null || previous.single() == record.context) { "Conflicting observation metadata" }
        db.execute(
            "INSERT OR IGNORE INTO raw_observations VALUES (?, ?, ?, ?, ?)",
            listOf(found, record.checksum, record.sourceId, record.retrievedAt, record.context),
        )
        return SourceRecordId(found)
    }

    fun records(): List<List<String?>> = db.query("SELECT id, namespace, external_id, network FROM raw_records ORDER BY id")

    fun revisions(): List<List<String?>> =
        db.query("SELECT record_id, checksum, payload, payload_version FROM raw_revisions ORDER BY record_id, checksum")

    fun observations(): List<List<String?>> =
        db.query(
            "SELECT record_id, checksum, source_id, retrieved_at, context FROM raw_observations ORDER BY record_id, source_id, retrieved_at",
        )

    override fun close() = db.close()
}

data class RawObservation(
    val id: String,
    val namespace: String,
    val externalId: String,
    val network: String,
    val sourceId: String,
    val retrievedAt: String,
    val payload: String,
    val payloadVersion: String,
    val checksum: String,
    val context: String,
)
