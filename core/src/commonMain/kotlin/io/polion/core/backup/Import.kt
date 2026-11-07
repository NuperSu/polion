package io.polion.core.backup

import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.sha256

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
