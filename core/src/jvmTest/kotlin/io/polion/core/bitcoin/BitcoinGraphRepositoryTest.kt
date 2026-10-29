package io.polion.core.bitcoin

import io.polion.core.backup.PortableBackup
import io.polion.core.model.AccountId
import io.polion.core.storage.JvmSqliteDatabase
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
        )
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
            assertEquals("5", staging.query("PRAGMA user_version").single().single())
        }
    }
}
