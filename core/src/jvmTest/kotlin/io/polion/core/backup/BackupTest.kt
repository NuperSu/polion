package io.polion.core.backup

import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.RawObservation
import io.polion.core.storage.Store
import io.polion.core.storage.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class BackupTest {
    @Test fun snapshotAndEncryptionRoundTrip() {
        JvmSqliteDatabase(":memory:").use { source ->
            val store = Store(source)
            store.ingest(
                RawObservation("id", "btc", "tx", "main", "provider", "2026-10-28T00:00:00Z", "payload", "1", sha256("payload"), "{}"),
            )
            val backup = PortableBackup.discover(source)
            val archive = backup.export(source)
            val encrypted = BackupEncryption.encrypt(archive.encodeToByteArray(), "password".toCharArray())
            assertTrue(!encrypted.decodeToString().contains("payload"))
            assertFails { BackupEncryption.decrypt(encrypted, "wrong".toCharArray()) }
            val broken = encrypted.copyOf()
            broken[broken.lastIndex] = (broken.last() + 1).toByte()
            assertFails { BackupEncryption.decrypt(broken, "password".toCharArray()) }
            JvmSqliteDatabase(":memory:").use { staging ->
                val restored = Store(staging)
                backup.restoreToStaging(staging, BackupEncryption.decrypt(encrypted, "password".toCharArray()).decodeToString())
                assertEquals(store.records(), restored.records())
                assertEquals(store.revisions(), restored.revisions())
                assertEquals(store.observations(), restored.observations())
                assertFails { backup.restoreToStaging(staging, archive) }
            }
            assertEquals(1, store.records().size)
        }
    }

    @Test fun importPreviewJournalAndRollback() {
        JvmSqliteDatabase(":memory:").use { db ->
            db.execute("CREATE TABLE imported (id TEXT PRIMARY KEY, value TEXT NOT NULL)")
            val journal = ImportJournal(db)
            val importer =
                object : RowImporter<Pair<String, String>> {
                    override fun parse(row: String): Pair<String, String> {
                        val parts = row.split('=')
                        require(parts.size == 2 && parts[0].isNotBlank())
                        return parts[0] to parts[1]
                    }

                    override fun key(value: Pair<String, String>) = value.first
                }
            val preview = importer.preview(listOf("a=1", "invalid", "a=1", "b=2"))
            assertEquals(2, preview.valid.size)
            assertEquals(2, preview.diagnostics.size)

            fun apply(row: Pair<String, String>) {
                db.execute("INSERT INTO imported VALUES (?, ?)", listOf(row.first, row.second))
            }
            assertEquals(2, journal.commit("csv", importer, preview, { it.second }, ::apply))
            assertEquals(0, journal.commit("csv", importer, preview, { it.second }, ::apply))
            val conflict = importer.preview(listOf("c=3", "a=changed"))
            assertFails { journal.commit("csv", importer, conflict, { it.second }, ::apply) }
            assertEquals(2, db.query("SELECT * FROM imported").size)
        }
    }

    @Test fun interruptedRestoreRollsBackAndCorruptionIsRejected() {
        JvmSqliteDatabase(":memory:").use { source ->
            source.execute("CREATE TABLE item (id TEXT PRIMARY KEY)")
            source.execute("INSERT INTO item VALUES ('one')")
            source.execute("INSERT INTO item VALUES ('two')")
            val backup = PortableBackup.discover(source)
            val archive = backup.export(source)
            JvmSqliteDatabase(":memory:").use { staging ->
                staging.execute("CREATE TABLE item (id TEXT PRIMARY KEY)")
                var inserts = 0
                val interrupted =
                    object : io.polion.core.storage.SqliteDatabase by staging {
                        override fun execute(
                            sql: String,
                            arguments: List<String?>,
                        ) {
                            if (sql.startsWith("INSERT INTO item") && ++inserts == 2) error("simulated interruption")
                            staging.execute(sql, arguments)
                        }
                    }
                assertFails { backup.restoreToStaging(interrupted, archive) }
                assertEquals(0, staging.query("SELECT * FROM item").size)
                assertFails { backup.restoreToStaging(staging, archive.dropLast(1) + "X") }
                assertEquals(2, source.query("SELECT * FROM item").size)
            }
        }
    }

    @Test fun secretTablesRequireExplicitPolicy() {
        JvmSqliteDatabase(":memory:").use { db ->
            db.execute("CREATE TABLE credentials (id TEXT)")
            assertFails { PortableBackup.discover(db) }
        }
    }

    @Test fun completeFinancialStateRoundTripsWithRelationshipsAndUnknownValues() {
        fun setup(db: io.polion.core.storage.SqliteDatabase) {
            Store(db)
            io.polion.core.market
                .MarketRepository(db)
            io.polion.core.reconcile
                .OverlayRepository(db)
            io.polion.core.accounting
                .TreatmentRepository(db)
            ImportJournal(db)
        }
        JvmSqliteDatabase(":memory:").use { source ->
            setup(source)
            source.execute("INSERT INTO owners VALUES ('owner','Alice')")
            source.execute("INSERT INTO accounts VALUES ('account','owner','Wallet','WALLET',NULL)")
            source.execute("INSERT INTO portfolios VALUES ('portfolio','Main','USD')")
            source.execute("INSERT INTO portfolio_accounts VALUES ('portfolio','account')")
            source.execute("INSERT INTO market_assets VALUES ('btc','NATIVE','btc','btc','')")
            source.execute("INSERT INTO market_metadata VALUES ('btc',0,'BTC','Bitcoin',8)")
            source.execute("INSERT INTO ledger_activities VALUES ('activity',0,'1','a','DEPOSIT','CONFIRMED',NULL,1)")
            source.execute("INSERT INTO ledger_movements VALUES ('activity',0,0,'event','account','btc','123456789','TRANSFER',NULL)")
            source.execute("INSERT INTO ledger_activities VALUES ('activity2',0,'1','b','DEPOSIT','CONFIRMED',NULL,1)")
            source.execute("INSERT INTO ledger_movements VALUES ('activity2',0,0,'event2','account','btc','123456789','TRANSFER',NULL)")
            source.execute("INSERT INTO duplicate_decisions VALUES ('activity','activity2',0,'activity','ACCEPTED')")
            source.execute("INSERT INTO overlay_revisions VALUES ('activity',0,'label','note',NULL)")
            source.execute("INSERT INTO accounting_treatments VALUES ('activity',0,'ACQUIRE','event',NULL,NULL,NULL,0,NULL)")
            val backup = PortableBackup.discover(source)
            val archive = backup.export(source)
            JvmSqliteDatabase(":memory:").use { staging ->
                setup(staging)
                backup.restoreToStaging(staging, archive)
                assertEquals(archive, backup.export(staging))
                assertEquals(listOf(listOf(null, null)), staging.query("SELECT numerator, denominator FROM accounting_treatments"))
                assertEquals("note", staging.query("SELECT note FROM overlay_revisions").single().single())
                val ledger =
                    io.polion.core.ledger
                        .ActivityRepository(staging)
                        .current()
                val effective =
                    io.polion.core.reconcile
                        .OverlayRepository(staging)
                        .replayForLedger(ledger)
                assertEquals(1, effective.activities.size)
                val actions =
                    effective.activities.flatMap {
                        io.polion.core.accounting.LedgerAccounting.actions(
                            it.activity,
                            io.polion.core.accounting
                                .TreatmentRepository(staging)
                                .load(it.activity.id),
                        )
                    }
                val report =
                    io.polion.core.accounting.FifoAccounting.calculate(
                        actions,
                        mapOf(
                            io.polion.core.model
                                .AccountId("account") to
                                io.polion.core.model
                                    .OwnerId("owner"),
                        ),
                        io.polion.core.model
                            .AssetId("usd"),
                    )
                assertEquals(null, report.lots.single().basis)
                assertEquals(
                    "123456789",
                    report.lots
                        .single()
                        .quantity
                        .toString(),
                )
            }
        }
    }

    @Test fun restoreRejectsSchemaVersionMismatchWithoutChangingStaging() {
        JvmSqliteDatabase(":memory:").use { source ->
            source.execute("CREATE TABLE example_schema (version INTEGER NOT NULL)")
            source.execute("INSERT INTO example_schema VALUES (1)")
            source.execute("CREATE TABLE data (id TEXT)")
            source.execute("INSERT INTO data VALUES ('saved')")
            val backup = PortableBackup.discover(source)
            val archive = backup.export(source)
            JvmSqliteDatabase(":memory:").use { staging ->
                staging.execute("CREATE TABLE example_schema (version INTEGER NOT NULL)")
                staging.execute("INSERT INTO example_schema VALUES (2)")
                staging.execute("CREATE TABLE data (id TEXT)")
                assertFails { backup.restoreToStaging(staging, archive) }
                assertEquals("2", staging.query("SELECT version FROM example_schema").single().single())
                assertTrue(staging.query("SELECT * FROM data").isEmpty())
            }
        }
    }
}
