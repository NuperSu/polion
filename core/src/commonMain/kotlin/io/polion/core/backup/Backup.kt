package io.polion.core.backup

import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.sha256

/** Callers choose durable tables explicitly in foreign-key dependency order; never include credentials. */
class PortableBackup(
    private val tables: List<String>,
) {
    companion object {
        /** Discover durable application tables; transient Bitcoin scan working tables are excluded. */
        fun discover(db: SqliteDatabase): PortableBackup {
            val tables =
                db
                    .query(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('bitcoin_scan_stage','bitcoin_scan_blocks','bitcoin_scan_present','android_metadata') ORDER BY name",
                    ).map { it.single()!! }
            return PortableBackup(tables)
        }
    }

    init {
        require(tables.isNotEmpty() && tables.distinct().size == tables.size)
        require(tables.all { it.matches(Regex("[a-z][a-z0-9_]*")) && !it.contains("secret") && !it.contains("credential") })
    }

    fun export(db: SqliteDatabase): String =
        db.transaction {
            val fields = mutableListOf<String?>("POLION", "2", tables.size.toString(), db.query("PRAGMA user_version").single().single())
            tables.forEach { table ->
                val metadata = db.query("PRAGMA table_info($table)")
                require(metadata.none { (it[2] ?: "").uppercase().contains("BLOB") }) { "Binary columns require an explicit backup codec" }
                val columns = metadata.map { it[1]!! }
                require(columns.isNotEmpty() && columns.all { it.matches(Regex("[a-z][a-z0-9_]*")) })
                val rows = db.query("SELECT ${columns.joinToString(",")} FROM $table").sortedBy { row -> encode(row) }
                fields.add(table)
                fields.add(columns.size.toString())
                fields.addAll(columns)
                fields.add(rows.size.toString())
                rows.forEach { fields.addAll(it) }
            }
            val payload = encode(fields)
            encode(listOf(sha256(payload), payload))
        }

    /** Restore to a new, empty database with application-created schemas. The original DB remains untouched. */
    fun restoreToStaging(
        db: SqliteDatabase,
        archive: String,
    ) {
        require(archive.length <= 100_000_000)
        val outer = decode(archive)
        require(outer.size == 2 && outer[0] == sha256(requireNotNull(outer[1]))) { "Corrupt archive" }
        val fields = decode(outer[1]!!)
        var i = 0

        fun next(): String = requireNotNull(fields.getOrNull(i++)) { "Malformed archive" }
        require(next() == "POLION" && next() == "2")
        val sourceTableCount = next().toInt()
        require(sourceTableCount in 1..tables.size + 1)
        val archiveVersion = next().toInt()
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
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('bitcoin_scan_stage','bitcoin_scan_blocks','bitcoin_scan_present','android_metadata') ORDER BY name",
                    ).map {
                        it.single()!!
                    }
            require(targetTables.toSet() == tables.toSet()) { "Staging table set mismatch" }
            listOf("bitcoin_scan_stage", "bitcoin_scan_blocks", "bitcoin_scan_present").forEach { table ->
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
            repeat(sourceTableCount) {
                val table = next()
                if (table == "android_metadata") {
                    require(next().toInt() == 1 && next() == "locale")
                    val platformRows = next().toInt()
                    require(platformRows in 0..1)
                    repeat(platformRows) { next() }
                    return@repeat
                }
                val position = tables.indexOf(table)
                require(position >= expectedIndex) { "Archive table order mismatch" }
                expectedIndex = position + 1
                val count = next().toInt()
                require(count in 1..1000)
                val columns = List(count) { next() }
                require(columns == db.query("PRAGMA table_info($table)").map { it[1]!! }) { "Schema mismatch" }
                require(db.query("SELECT COUNT(*) FROM $table").single().single() == "0") { "Staging database is not empty" }
                val rowCount = next().toInt()
                require(rowCount in 0..1_000_000)
                if (table in expectedVersions) require(rowCount == expectedVersions.getValue(table).size) { "Subsystem schema mismatch" }
                repeat(rowCount) {
                    val row = List(count) { fields.getOrNull(i++).also { require(i <= fields.size) } }
                    if (table in
                        expectedVersions
                    ) {
                        require(row == expectedVersions.getValue(table).single()) { "Subsystem schema version mismatch" }
                    }
                    db.execute("INSERT INTO $table (${columns.joinToString(",")}) VALUES (${List(count) { "?" }.joinToString(",")})", row)
                }
            }
            require(i == fields.size)
            require(db.query("PRAGMA foreign_key_check").isEmpty())
        }
    }
}

private fun encode(fields: List<String?>): String =
    buildString {
        fields.forEach {
            if (it == null) {
                append("-1:")
            } else {
                append(it.length)
                append(':')
                append(it)
            }
        }
    }

private fun decode(data: String): List<String?> {
    val out = mutableListOf<String?>()
    var i = 0
    while (i < data.length) {
        val colon = data.indexOf(':', i)
        require(colon in i..(i + 10).coerceAtMost(data.lastIndex))
        val length = data.substring(i, colon).toInt()
        i = colon + 1
        require(length >= -1 && (length == -1 || length <= data.length - i))
        if (length == -1) {
            out.add(null)
        } else {
            out.add(data.substring(i, i + length))
            i += length
        }
        require(out.size <= 10_000_000)
    }
    return out
}

expect object BackupEncryption {
    fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
    ): ByteArray

    fun decrypt(
        ciphertext: ByteArray,
        password: CharArray,
    ): ByteArray
}

data class ImportDiagnostic(
    val row: Int,
    val message: String,
)

data class ImportPreview<T>(
    val valid: List<T>,
    val diagnostics: List<ImportDiagnostic>,
)

interface RowImporter<T> {
    fun parse(row: String): T

    fun key(value: T): String

    fun preview(rows: List<String>): ImportPreview<T> {
        val valid = mutableListOf<T>()
        val diagnostics = mutableListOf<ImportDiagnostic>()
        val keys = mutableSetOf<String>()
        rows.forEachIndexed { index, row ->
            try {
                val value = parse(row)
                if (keys.add(key(value))) valid.add(value) else diagnostics.add(ImportDiagnostic(index + 1, "Duplicate row"))
            } catch (_: IllegalArgumentException) {
                diagnostics.add(ImportDiagnostic(index + 1, "Invalid row"))
            }
        }
        return ImportPreview(valid, diagnostics)
    }
}

/** Durable importer commits accepted rows and deduplication keys in the same transaction. */
class ImportJournal(
    private val db: SqliteDatabase,
) {
    init {
        db.transaction {
            db.execute(
                "CREATE TABLE IF NOT EXISTS import_journal (importer TEXT NOT NULL, row_key TEXT NOT NULL, fingerprint TEXT NOT NULL, PRIMARY KEY(importer, row_key))",
            )
        }
    }

    fun <T> commit(
        importerId: String,
        importer: RowImporter<T>,
        preview: ImportPreview<T>,
        canonical: (T) -> String,
        apply: (T) -> Unit,
    ): Int =
        db.transaction {
            require(importerId.isNotBlank())
            var inserted = 0
            preview.valid.forEach { value ->
                val key = importer.key(value)
                require(key.isNotBlank())
                val fingerprint = sha256(canonical(value))
                val existing =
                    db
                        .query(
                            "SELECT fingerprint FROM import_journal WHERE importer=? AND row_key=?",
                            listOf(importerId, key),
                        ).singleOrNull()
                        ?.single()
                require(existing == null || existing == fingerprint) { "Import key conflict" }
                if (existing == null) {
                    apply(value)
                    db.execute("INSERT INTO import_journal VALUES (?, ?, ?)", listOf(importerId, key, fingerprint))
                    inserted++
                }
            }
            inserted
        }
}
