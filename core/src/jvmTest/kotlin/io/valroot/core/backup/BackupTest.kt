package io.valroot.core.backup

import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.core.storage.RawObservation
import io.valroot.core.storage.Store
import io.valroot.core.storage.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class BackupTest {
    private fun encrypt(
        body: ByteArray,
        password: String = "password",
    ): ByteArray {
        val out = ByteArraySink()
        BackupEncryption.encrypt(out, password.toCharArray()).apply {
            write(body)
            close()
        }
        return out.toByteArray()
    }

    private fun decrypt(
        file: ByteArray,
        password: String = "password",
    ): ByteArray {
        val source = BackupEncryption.decrypt(ByteArraySource(file), password.toCharArray())
        val out = ByteArraySink()
        val buffer = ByteArray(1000)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    @Test fun snapshotAndEncryptionRoundTrip() {
        JvmSqliteDatabase(":memory:").use { source ->
            val store = Store(source)
            store.ingest(
                RawObservation("id", "btc", "tx", "main", "provider", "2026-10-28T00:00:00Z", "payload", "1", sha256("payload"), "{}"),
            )
            val backup = PortableBackup.discover(source)
            val body = backup.export(source)
            val encrypted = encrypt(body)
            assertTrue(!encrypted.decodeToString().contains("payload"))
            assertFails { decrypt(encrypted, "wrong") }
            JvmSqliteDatabase(":memory:").use { staging ->
                val restored = Store(staging)
                backup.restoreToStaging(staging, decrypt(encrypted))
                assertEquals(store.records(), restored.records())
                assertEquals(store.revisions(), restored.revisions())
                assertEquals(store.observations(), restored.observations())
                assertFails { backup.restoreToStaging(staging, body) }
            }
            assertEquals(1, store.records().size)
        }
    }

    @Test fun envelopeStreamsManyChunksAndRejectsTamperingTruncationAndExtension() {
        // Several 64 KiB chunks plus a partial one.
        val body = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val file = encrypt(body)
        assertTrue(decrypt(file).contentEquals(body))

        val flipped = file.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFails { decrypt(flipped) }
        // Cutting the file at a chunk boundary is caught because that chunk was not sealed as the last one.
        val firstFrame = 34 + 4 + 64 * 1024 + 16
        assertFails { decrypt(file.copyOf(firstFrame)) }
        assertFails { decrypt(file.copyOf(file.size - 1)) }
        assertFails { decrypt(file + file.copyOfRange(34, firstFrame)) }
        val otherVersion = file.copyOf().also { it[5] = 2 }
        assertFails { decrypt(otherVersion) }
        // An empty body still has one authenticated final chunk.
        assertTrue(decrypt(encrypt(ByteArray(0))).isEmpty())
    }

    @Test fun restoreRejectsDataAfterTheEndOfTheBody() {
        JvmSqliteDatabase(":memory:").use { source ->
            source.execute("CREATE TABLE item (id TEXT PRIMARY KEY)")
            source.execute("INSERT INTO item VALUES ('one')")
            val backup = PortableBackup.discover(source)
            val body = backup.export(source) + byteArrayOf(0)
            JvmSqliteDatabase(":memory:").use { staging ->
                staging.execute("CREATE TABLE item (id TEXT PRIMARY KEY)")
                assertFails { backup.restoreToStaging(staging, body) }
                assertEquals(0, staging.query("SELECT * FROM item").size)
            }
        }
    }

    @Test fun scanWorkingTablesAreExcludedFromBackups() {
        JvmSqliteDatabase(":memory:").use { source ->
            val store = Store(source)
            store.ingest(
                RawObservation("id", "btc", "tx", "main", "provider", "2026-10-28T00:00:00Z", "payload", "1", sha256("payload"), "{}"),
            )
            val oldArchive = PortableBackup.discover(source).export(source)
            io.valroot.core.bitcoin
                .BitcoinScanStage(
                    source,
                    store,
                    io.valroot.core.ledger
                        .ActivityRepository(source),
                )
            source.execute("INSERT INTO bitcoin_scan_blocks VALUES ('scratch-sensitive', 42, 0)")
            val archiveWithScratch = PortableBackup.discover(source).export(source)
            assertTrue(oldArchive.contentEquals(archiveWithScratch))
            assertTrue(!archiveWithScratch.decodeToString().contains("scratch-sensitive"))
            JvmSqliteDatabase(":memory:").use { staging ->
                val target = Store(staging)
                io.valroot.core.bitcoin
                    .BitcoinScanStage(
                        staging,
                        target,
                        io.valroot.core.ledger
                            .ActivityRepository(staging),
                    )
                val backup = PortableBackup.discover(staging)
                staging.execute("INSERT INTO bitcoin_scan_blocks VALUES ('unrelated', 42, 0)")
                assertFails { backup.restoreToStaging(staging, oldArchive) }
                staging.execute("DELETE FROM bitcoin_scan_blocks")
                backup.restoreToStaging(staging, oldArchive)
                assertEquals(store.records(), target.records())
                assertEquals("0", staging.query("SELECT COUNT(*) FROM bitcoin_scan_blocks").single().single())
            }
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
                    object : io.valroot.core.storage.SqliteDatabase by staging {
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
                assertFails { backup.restoreToStaging(staging, archive.copyOf(archive.size - 1) + "X".encodeToByteArray()) }
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
        fun setup(db: io.valroot.core.storage.SqliteDatabase) {
            Store(db)
            io.valroot.core.market
                .MarketRepository(db)
            io.valroot.core.reconcile
                .OverlayRepository(db)
            io.valroot.core.accounting
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
                assertTrue(archive.contentEquals(backup.export(staging)))
                assertEquals(listOf(listOf(null, null)), staging.query("SELECT numerator, denominator FROM accounting_treatments"))
                assertEquals("note", staging.query("SELECT note FROM overlay_revisions").single().single())
                val ledger =
                    io.valroot.core.ledger
                        .ActivityRepository(staging)
                        .current()
                val effective =
                    io.valroot.core.reconcile
                        .OverlayRepository(staging)
                        .replayForLedger(ledger)
                assertEquals(1, effective.activities.size)
                val actions =
                    effective.activities.flatMap {
                        io.valroot.core.accounting.LedgerAccounting.actions(
                            it.activity,
                            io.valroot.core.accounting
                                .TreatmentRepository(staging)
                                .load(it.activity.id),
                        )
                    }
                val report =
                    io.valroot.core.accounting.FifoAccounting.calculate(
                        actions,
                        mapOf(
                            io.valroot.core.model
                                .AccountId("account") to
                                io.valroot.core.model
                                    .OwnerId("owner"),
                        ),
                        io.valroot.core.model
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

    @Test fun olderSchemaArchiveRestoresIntoNewerStagingAndNewerArchiveIsRejected() {
        JvmSqliteDatabase(":memory:").use { source ->
            Store(source)
            source.execute("INSERT INTO owners VALUES ('owner','Alice')")
            source.execute("INSERT INTO accounts VALUES ('account','owner','Wallet','WALLET',NULL)")
            source.execute("DROP TABLE ledger_fee_candidates")
            source.execute("DROP TABLE ledger_unallocated_fees")
            io.valroot.core.bitcoin.bitcoinGraphDeltaTables
                .reversed()
                .forEach { source.execute("DROP TABLE $it") }
            io.valroot.core.bitcoin.bitcoinGraphTables
                .reversed()
                .forEach { source.execute("DROP TABLE $it") }
            source.execute("PRAGMA user_version = 3")
            val archive = PortableBackup.discover(source).export(source)
            assertTrue(!archive.decodeToString().contains("ledger_unallocated_fees"))
            JvmSqliteDatabase(":memory:").use { staging ->
                Store(staging)
                val backup = PortableBackup.discover(staging)
                backup.restoreToStaging(staging, archive)
                assertEquals("6", staging.query("PRAGMA user_version").single().single())
                assertEquals("Alice", staging.query("SELECT label FROM owners").single().single())
                assertEquals("0", staging.query("SELECT COUNT(*) FROM ledger_unallocated_fees").single().single())
                assertEquals("0", staging.query("SELECT COUNT(*) FROM ledger_fee_candidates").single().single())
            }
            source.execute("PRAGMA user_version = 7")
            val newer = PortableBackup.discover(source).export(source)
            JvmSqliteDatabase(":memory:").use { staging ->
                Store(staging)
                assertFails { PortableBackup.discover(staging).restoreToStaging(staging, newer) }
                assertEquals("0", staging.query("SELECT COUNT(*) FROM owners").single().single())
            }
        }
    }
}
