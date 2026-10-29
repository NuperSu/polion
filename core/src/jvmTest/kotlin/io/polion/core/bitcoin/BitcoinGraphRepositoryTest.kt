package io.polion.core.bitcoin

import io.polion.core.backup.PortableBackup
import io.polion.core.model.AccountId
import io.polion.core.model.ExactInteger
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.Store
import io.polion.core.storage.sha256
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class BitcoinGraphRepositoryTest {
    private val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val scope = mapOf(address to AccountId("account-a"))
    private val parent = "a".repeat(64)
    private val anchor = BitcoinBlockAnchor(200, "e".repeat(64))

    private fun payload(
        id: String,
        confirmed: Boolean = false,
    ): String {
        val status =
            if (confirmed) {
                """"confirmed":true,"block_height":200,"block_hash":"${anchor.hash}","block_time":1700000000"""
            } else {
                """"confirmed":false"""
            }
        return """
            {"txid":"$id","vin":[{"txid":"$parent","vout":2,"sequence":4294967293,
            "prevout":{"value":10000,"scriptpubkey":"abcd","scriptpubkey_type":"p2pkh","scriptpubkey_address":"$address"}}],
            "vout":[{"value":9000,"scriptpubkey":"ef01","scriptpubkey_type":"p2pkh","scriptpubkey_address":"$address"}],
            "fee":1000,"status":{$status}}
            """.trimIndent()
    }

    private fun save(
        repository: BitcoinGraphRepository,
        payloads: List<String>,
        time: String = "2026-10-29T00:00:00Z",
        scope: Map<String, AccountId> = this.scope,
        anchor: BitcoinBlockAnchor = this.anchor,
        recoveryBaseline: Boolean = false,
    ): Long {
        val graph = BitcoinUtxoGraph.build(payloads, scope)
        return repository.saveInTransaction(
            graph,
            "provider-a",
            anchor,
            time,
            payloads.associate {
                parseBitcoinGraphTransaction(it).id to
                    sha256(it)
            },
            compareUtxoSets(graph, EsploraUtxoSnapshot(emptyList(), emptyMap())),
            recoveryBaseline = recoveryBaseline,
        )
    }

    @Test
    fun reviewPagesExactEvidenceWithoutGraphReconstruction() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val queries = mutableListOf<String>()
            val tracked =
                object : SqliteDatabase by db {
                    override fun query(
                        sql: String,
                        arguments: List<String?>,
                    ): List<List<String?>> {
                        queries.add(sql)
                        return db.query(sql, arguments)
                    }
                }
            val repository = BitcoinGraphRepository(tracked)
            val raw = payload("b".repeat(64), true)
            val graph = BitcoinUtxoGraph.build(listOf(raw), scope)
            val coin = graph.unspent.single()
            val reported = EsploraUtxo(address, coin.outpoint, ExactInteger.parse("9001"), null)
            val comparison = compareUtxoSets(graph, EsploraUtxoSnapshot(listOf(reported), emptyMap()))
            val id =
                db.transaction {
                    repository.saveInTransaction(
                        graph,
                        "provider-a",
                        anchor,
                        "2026-10-29T00:00:00Z",
                        mapOf(coin.outpoint.txid to sha256(raw)),
                        comparison,
                    )
                }
            queries.clear()
            val first = repository.utxoReview(scope, limit = 1)
            assertEquals(id, first.snapshotId)
            assertEquals("provider-a", first.providerSourceId)
            assertEquals("2026-10-29T00:00:00Z", first.observedAt)
            assertEquals(anchor, first.anchor)
            assertEquals(3, first.total)
            assertEquals(1, first.rows.size)
            assertEquals(true, first.hasNext)
            val row = first.rows.single()
            assertEquals("VALUE_DIFFERS", row.kind)
            assertEquals(AccountId("account-a"), row.account)
            assertEquals(address, row.address)
            assertEquals(coin.outpoint, row.outpoint)
            assertEquals(ExactInteger.parse("9000"), row.reconstructedValue)
            assertEquals(anchor, row.reconstructedBlock)
            assertEquals(ExactInteger.parse("9001"), row.providerValue)
            assertNull(row.providerBlock)
            assertEquals(3, queries.size) // snapshot header, count, bounded joined page
            assertEquals(true, queries.last().contains("LIMIT 1 OFFSET 0"))
            val second = repository.utxoReview(scope, offset = 1, limit = 1, expectedSnapshotId = id)
            assertEquals("CONFIRMATION_DIFFERS", second.rows.single().kind)
            val third = repository.utxoReview(scope, offset = 2, limit = 1, expectedSnapshotId = id)
            assertEquals("MISSING_PARENT", third.rows.single().kind)
            assertEquals(AccountId("account-a"), third.rows.single().account)
            assertEquals(ExactInteger.parse("10000"), third.rows.single().reconstructedValue)
            assertEquals("UNKNOWN_PARENT", third.rows.single().reconstructedStatus)
            assertEquals(false, third.hasNext)
            assertFails { repository.utxoReview(scope, limit = 101) }
            assertFails { repository.utxoReview(scope, offset = -1) }
        }
    }

    @Test
    fun reviewMissingParentWithMultipleSpendersIsOneRow() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repository = BitcoinGraphRepository(db)
            db.transaction { save(repository, listOf(payload("b".repeat(64)), payload("c".repeat(64)))) }
            val page = repository.utxoReview(scope, limit = 100)
            val missing = page.rows.filter { it.kind == "MISSING_PARENT" }
            assertEquals(1, missing.size)
            assertEquals(BitcoinOutpoint(parent, 2), missing.single().outpoint)
            assertEquals(page.total, page.rows.size)
        }
    }

    @Test
    fun reviewDoesNotMixDisabledScopesAndResetsPagesAfterRefresh() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repository = BitcoinGraphRepository(db)
            assertNull(repository.utxoReview(scope).snapshotId)
            val id = db.transaction { save(repository, listOf(payload("b".repeat(64)))) }
            val otherScope = mapOf(address to AccountId("account-disabled"))
            val otherId = db.transaction { save(repository, emptyList(), scope = otherScope) }
            assertEquals(otherId, repository.utxoReview(otherScope).snapshotId)
            assertEquals(id, repository.utxoReview(scope).snapshotId)
            assertNull(repository.utxoReview(emptyMap()).snapshotId)
            val updated = db.transaction { save(repository, listOf(payload("c".repeat(64)))) }
            val page = repository.utxoReview(scope, offset = 1, limit = 1, expectedSnapshotId = id)
            assertEquals(updated, page.snapshotId)
            assertEquals(0, page.offset)
            assertEquals("MISSING_FROM_PROVIDER", page.rows.single().kind)
        }
    }

    @Test
    fun exactGraphEvidenceSurvivesReopen() {
        val path = Files.createTempFile("polion-graph", ".sqlite")
        val pending = payload("b".repeat(64))
        val competing = payload("c".repeat(64))
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                Store(db)
                db.transaction { save(BitcoinGraphRepository(db), listOf(pending, competing)) }
            }
            JvmSqliteDatabase(path.toString()).use { db ->
                Store(db)
                val loaded = BitcoinGraphRepository(db).latest()!!
                val graph = BitcoinUtxoGraph.build(listOf(pending, competing), scope)
                assertEquals(scope, loaded.graph.ownedAddresses)
                assertEquals(graph.transactions, loaded.graph.transactions)
                assertEquals(graph.states, loaded.graph.states)
                assertEquals(graph.ownedOutputs, loaded.graph.ownedOutputs)
                assertEquals(graph.conflicts, loaded.graph.conflicts)
                assertEquals(graph.missingParents, loaded.graph.missingParents)
                assertEquals(compareUtxoSets(graph, EsploraUtxoSnapshot(emptyList(), emptyMap())), loaded.comparison)
                assertEquals(mapOf("b".repeat(64) to sha256(pending), "c".repeat(64) to sha256(competing)), loaded.rawChecksums)
                assertEquals("provider-a", loaded.providerSourceId)
                assertEquals(anchor, loaded.anchor)
                assertEquals("2026-10-29T00:00:00Z", loaded.observedAt)
                assertEquals(setOf(BitcoinReplacementEvidenceKind.POSSIBLE), loaded.replacementEvidence.map { it.kind }.toSet())
                assertEquals(listOf("abcd", "abcd"), db.query("SELECT script FROM bitcoin_graph_inputs ORDER BY txid").map { it.single() })
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun absentPendingSpenderRemainsHistoricalAndConfirmedCompetitionIsExplicit() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repository = BitcoinGraphRepository(db)
            val oldId = db.transaction { save(repository, listOf(payload("b".repeat(64)))) }
            db.transaction { save(repository, listOf(payload("c".repeat(64))), "2026-10-29T01:00:00Z") }
            assertEquals(
                BitcoinReplacementEvidenceKind.POSSIBLE,
                repository
                    .latest()!!
                    .replacementEvidence
                    .single()
                    .kind,
            )
            db.transaction { save(repository, listOf(payload("d".repeat(64), true)), "2026-10-29T02:00:00Z") }
            val evidence = repository.latest()!!.replacementEvidence.single()
            assertEquals(BitcoinReplacementEvidenceKind.CONFIRMED, evidence.kind)
            assertEquals("c".repeat(64), evidence.previousTransaction)
            assertEquals(
                BitcoinTransactionState.PENDING,
                repository
                    .snapshot(oldId)!!
                    .graph.states
                    .getValue("b".repeat(64)),
            )
            assertEquals(3, repository.snapshots().size)
            assertEquals(listOf(anchor), repository.anchors())
        }
    }

    @Test
    fun exactScopeMembershipAndCallerRollbackArePreserved() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repository = BitcoinGraphRepository(db)
            db.transaction { save(repository, listOf(payload("b".repeat(64)))) }
            assertNull(repository.latest(mapOf(address to AccountId("another-account"))))
            assertFails {
                db.transaction {
                    save(repository, emptyList(), "2026-10-29T03:00:00Z")
                    error("interrupted apply")
                }
            }
            assertEquals(1, repository.snapshots().size)
            db.transaction { save(repository, emptyList(), "2026-10-29T04:00:00Z") }
            assertEquals(emptyMap(), repository.latest(scope)!!.graph.transactions)
            assertEquals(2, repository.snapshots().size)
        }
    }

    @Test
    fun recoveryBaselineIsScopedAndRollsBackWithItsSnapshot() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repository = BitcoinGraphRepository(db)
            val otherScope = mapOf(address to AccountId("another-account"))
            db.transaction { save(repository, emptyList()) }
            db.transaction { save(repository, emptyList(), scope = otherScope, anchor = BitcoinBlockAnchor(210, "f".repeat(64))) }
            assertFails {
                db.transaction {
                    save(repository, emptyList(), anchor = BitcoinBlockAnchor(190, "d".repeat(64)), recoveryBaseline = true)
                    error("interrupted recovery apply")
                }
            }
            assertEquals(listOf(anchor), repository.anchors(scope))
            assertEquals(2, repository.snapshots().size)
            val lower = BitcoinBlockAnchor(190, "d".repeat(64))
            db.transaction { save(repository, emptyList(), anchor = lower, recoveryBaseline = true) }
            assertEquals(listOf(lower), repository.anchors(scope))
            assertEquals(listOf(BitcoinBlockAnchor(210, "f".repeat(64))), repository.anchors(otherScope))
            assertEquals(listOf(210L, 190L), repository.anchors().map { it.height })
            assertEquals(3, repository.snapshots().size)
        }
    }

    @Test
    fun versionFourUpgradeCreatesEmptyEvidenceAndVersionFiveReopens() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            bitcoinGraphTables.reversed().forEach { db.execute("DROP TABLE $it") }
            db.execute("PRAGMA user_version = 4")
            Store(db)
            assertEquals("5", db.query("PRAGMA user_version").single().single())
            assertEquals(emptyList(), BitcoinGraphRepository(db).snapshots())
            Store(db)
            assertEquals(emptyList(), db.query("PRAGMA foreign_key_check"))
        }
    }

    @Test
    fun versionFourPortableArchiveRestoresWithEmptyGraphEvidence() {
        val archive =
            JvmSqliteDatabase(":memory:").use { source ->
                Store(source)
                bitcoinGraphTables.reversed().forEach { source.execute("DROP TABLE $it") }
                source.execute("PRAGMA user_version = 4")
                source.execute("INSERT INTO owners VALUES ('owner-a','Owner A')")
                PortableBackup.discover(source).export(source)
            }
        JvmSqliteDatabase(":memory:").use { staging ->
            Store(staging)
            PortableBackup.discover(staging).restoreToStaging(staging, archive)
            Store(staging)
            assertEquals(listOf(listOf("owner-a", "Owner A")), staging.query("SELECT * FROM owners"))
            assertNull(BitcoinGraphRepository(staging).latest())
            val review = BitcoinGraphRepository(staging).utxoReview(scope)
            assertNull(review.snapshotId)
            assertEquals(false, review.compared)
            assertEquals("5", staging.query("PRAGMA user_version").single().single())
        }
    }
}
