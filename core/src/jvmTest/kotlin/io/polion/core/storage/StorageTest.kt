package io.polion.core.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class StorageTest {
    private fun observation(
        id: String,
        source: String,
        checksum: String = sha256("data"),
        payload: String = "data",
    ) = RawObservation(id, "btc", "txid", "mainnet", source, "2026-01-01T00:00:00Z", payload, "1", checksum, "context")

    @Test
    fun reopenAndRetainDistinctProvenanceAndRevisions() {
        val path = Files.createTempFile("polion", ".sqlite")
        try {
            Store(JvmSqliteDatabase(path.toString())).use { store ->
                store.ingest(observation("1", "provider-a"))
                store.ingest(observation("2", "provider-b"))
                store.ingest(observation("1", "provider-a"))
                store.ingest(observation("1", "provider-a", sha256("updated"), "updated"))
                assertEquals(1, store.records().size)
                assertEquals(2, store.revisions().size)
                assertEquals(3, store.observations().size)
                assertFails { store.ingest(observation("1", "provider-a", sha256("data"), "collision")) }
            }
            Store(JvmSqliteDatabase(path.toString())).use { store ->
                assertEquals(2, store.revisions().size)
                assertEquals(3, store.observations().size)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rollbackAndForeignKeyEnforcement() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db).use { store ->
                assertFails {
                    db.transaction {
                        store.ingestInTransaction(observation("1", "a"))
                        error("crash")
                    }
                }
                assertEquals(emptyList(), store.records())
                assertFails { db.execute("INSERT INTO raw_revisions VALUES ('missing', 'hash', 'payload', '1')") }
            }
        }
    }

    @Test
    fun rejectsFutureSchemaAndCorruptDatabase() {
        val path = Files.createTempFile("polion-schema", ".sqlite")
        try {
            JvmSqliteDatabase(path.toString()).use { it.execute("PRAGMA user_version = 999") }
            assertFails { JvmSqliteDatabase(path.toString()).use { Store(it) } }
            Files.writeString(path, "not a database")
            assertFails { JvmSqliteDatabase(path.toString()).use { Store(it) } }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun serializesConcurrentIngestion() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db).use { store ->
                val threads =
                    (0 until 20).map { i ->
                        Thread { store.ingest(observation("1", "provider-$i")) }.also { it.start() }
                    }
                threads.forEach { it.join() }
                assertEquals(1, store.records().size)
                assertEquals(20, store.observations().size)
            }
        }
    }

    @Test
    fun migrationRollsBackOnConflictingSchemaAndCanRetry() {
        val path = Files.createTempFile("polion-migration", ".sqlite")
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                Store(db)
                listOf("portfolio_owners", "portfolio_accounts", "ownership_tags", "targets", "accounts", "portfolios", "owners").forEach {
                    db.execute("DROP TABLE $it")
                }
                db.execute("DROP TABLE ledger_sources")
                db.execute("DROP TABLE ledger_movements")
                db.execute("DROP TABLE ledger_activities")
                db.execute("PRAGMA user_version = 1")
                db.execute("CREATE TABLE ledger_movements (conflict TEXT)")
                assertFails { Store(db) }
                assertEquals("1", db.query("PRAGMA user_version").single().single())
                assertEquals(emptyList(), db.query("SELECT name FROM sqlite_master WHERE name='ledger_activities'"))
                db.execute("DROP TABLE ledger_movements")
                Store(db)
                assertEquals("3", db.query("PRAGMA user_version").single().single())
            }
            JvmSqliteDatabase(path.toString()).use { db -> Store(db) }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
