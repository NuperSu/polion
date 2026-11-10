package io.valroot.core.backup

import io.valroot.core.bitcoin.BitcoinScanStage
import io.valroot.core.model.isLowerIdentifier
import io.valroot.core.storage.SqliteDatabase

/**
 * Portable backup body, format 3: a stream of length-prefixed UTF-8 fields (see [FieldWriter]).
 *
 * ```
 * "VALROOT-BACKUP" "3" <schema user_version> <table count>
 * per table, in dependency order:
 *   <table> <column count> <column>...
 *   per row: "R" <value>...        (null values are encoded as null fields)
 *   "E" <row count>
 * "END"
 * ```
 *
 * Rows stream in rowid order straight from the database and straight into the staging database, so memory use does not
 * grow with the backup. Integrity and confidentiality come from the [BackupEncryption] envelope around this body.
 */
class PortableBackup(
    private val tables: List<String>,
) {
    companion object {
        const val FORMAT = "3"
        private const val MAGIC = "VALROOT-BACKUP"

        /** Backups made before the app was renamed from Polion; still restored. */
        private const val LEGACY_MAGIC = "POLION-BACKUP"
        private const val PAGE = 500
        private val excludedTables =
            (BitcoinScanStage.SCRATCH_TABLES + io.valroot.core.market.MarketFeedRepository.LOCAL_TABLES + "android_metadata")
                .joinToString(",") { "'$it'" }

        /** Discover durable application tables; Bitcoin scan working tables, market feed caches and device settings are excluded. */
        fun discover(db: SqliteDatabase): PortableBackup {
            val tables =
                db
                    .query(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ($excludedTables) ORDER BY name",
                    ).map { it.single()!! }
            return PortableBackup(tables)
        }
    }

    init {
        require(tables.isNotEmpty() && tables.distinct().size == tables.size)
        require(tables.all { it.isLowerIdentifier() && !it.contains("secret") && !it.contains("credential") })
    }

    /** Streams one consistent snapshot of every durable table into [sink]. */
    fun export(
        db: SqliteDatabase,
        sink: ByteSink,
    ) = db.transaction {
        val out = FieldWriter(sink)
        out.field(MAGIC)
        out.field(FORMAT)
        out.field(
            db
                .query("PRAGMA user_version")
                .single()
                .single(),
        )
        out.field(tables.size.toString())
        tables.forEach { table ->
            val metadata = db.query("PRAGMA table_info($table)")
            require(metadata.none { (it[2] ?: "").uppercase().contains("BLOB") }) { "Binary columns require an explicit backup codec" }
            val columns = metadata.map { it[1]!! }
            require(columns.isNotEmpty() && columns.all { it.isLowerIdentifier() })
            out.field(table)
            out.field(columns.size.toString())
            columns.forEach(out::field)
            var rows = 0L
            var after = Long.MIN_VALUE
            while (true) {
                val page =
                    db.query(
                        "SELECT rowid, ${columns.joinToString(
                            ",",
                        )} FROM $table WHERE rowid > CAST(? AS INTEGER) ORDER BY rowid LIMIT $PAGE",
                        listOf(after.toString()),
                    )
                page.forEach { row ->
                    out.field("R")
                    row.drop(1).forEach(out::field)
                }
                rows += page.size
                if (page.size < PAGE) break
                after = page.last()[0]!!.toLong()
            }
            out.field("E")
            out.field(rows.toString())
        }
        out.field("END")
        out.flush()
    }

    /** In-memory body (unencrypted), for tests and small data. */
    fun export(db: SqliteDatabase): ByteArray = ByteArraySink().also { export(db, it) }.toByteArray()

    fun restoreToStaging(
        db: SqliteDatabase,
        body: ByteArray,
    ) = restoreToStaging(db, ByteArraySource(body))

    /** Restore into a new, empty database with application-created schemas. The original database is untouched. */
    fun restoreToStaging(
        db: SqliteDatabase,
        source: ByteSource,
    ) {
        val input = FieldReader(source)
        require(input.next() in setOf(MAGIC, LEGACY_MAGIC)) { "This is not a Valroot backup" }
        require(input.next() == FORMAT) { "This backup was made by an unsupported Valroot version" }
        val archiveVersion = input.required().toInt()
        val tableCount = input.required().toInt()
        require(tableCount in 1..tables.size) { "Backup table set mismatch" }
        db.transaction {
            val stagingVersion =
                db
                    .query("PRAGMA user_version")
                    .single()
                    .single()!!
                    .toInt()
            require(archiveVersion in 0..stagingVersion) { "Database schema version mismatch" }
            val targetTables =
                db
                    .query(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ($excludedTables) ORDER BY name",
                    ).map { it.single()!! }
            require(targetTables.toSet() == tables.toSet()) { "Staging table set mismatch" }
            BitcoinScanStage.SCRATCH_TABLES.forEach { table ->
                val exists = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", listOf(table)).isNotEmpty()
                require(!exists || db.query("SELECT COUNT(*) FROM $table").single().single() == "0") { "Staging database is not empty" }
            }
            val expectedVersions = tables.filter { it.endsWith("_schema") }.associateWith { db.query("SELECT * FROM $it") }
            db.execute("PRAGMA defer_foreign_keys = ON")
            tables.asReversed().forEach { table ->
                val count =
                    db
                        .query("SELECT COUNT(*) FROM $table")
                        .single()
                        .single()!!
                        .toInt()
                require(count == 0 || table.endsWith("_schema") && count == 1) { "Staging database is not empty" }
                if (count != 0) db.execute("DELETE FROM $table")
            }
            var expectedIndex = 0
            repeat(tableCount) {
                val table = input.required()
                val position = tables.indexOf(table)
                require(position >= expectedIndex) { "Backup table order mismatch" }
                expectedIndex = position + 1
                val columnCount = input.required().toInt()
                require(columnCount in 1..1000)
                val columns = List(columnCount) { input.required() }
                require(columns == db.query("PRAGMA table_info($table)").map { it[1]!! }) { "Schema mismatch" }
                val insert = "INSERT INTO $table (${columns.joinToString(",")}) VALUES (${List(columnCount) { "?" }.joinToString(",")})"
                var rows = 0L
                while (true) {
                    when (input.required()) {
                        "R" -> {
                            val row = List(columnCount) { input.next() }
                            if (table in expectedVersions) {
                                require(row == expectedVersions.getValue(table).single()) { "Subsystem schema version mismatch" }
                            }
                            db.execute(insert, row)
                            rows++
                        }
                        "E" -> {
                            require(input.required().toLong() == rows) { "Backup row count mismatch" }
                            break
                        }
                        else -> throw IllegalArgumentException("Malformed backup")
                    }
                }
                if (table in
                    expectedVersions
                ) {
                    require(rows == expectedVersions.getValue(table).size.toLong()) { "Subsystem schema mismatch" }
                }
            }
            require(input.required() == "END") { "Malformed backup" }
            input.requireEnd()
            require(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }
}

/**
 * Password-based streaming encryption around the backup body. The envelope is described in `docs/backup-format.md`:
 * a versioned header with the KDF parameters, then authenticated chunks whose nonces carry a counter and a final-chunk
 * flag, so a wrong password, tampering, reordering and truncation are all rejected.
 */
expect object BackupEncryption {
    /** Returns a sink that encrypts into [output]; [EncryptingSink.close] writes the final chunk and must be called. */
    fun encrypt(
        output: ByteSink,
        password: CharArray,
    ): EncryptingSink

    /** Returns the decrypted body of [input]; a wrong password or damaged file fails with [IllegalArgumentException]. */
    fun decrypt(
        input: ByteSource,
        password: CharArray,
    ): ByteSource
}

interface EncryptingSink : ByteSink {
    fun close()
}
