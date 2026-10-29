package io.polion.core.bitcoin

import io.polion.core.backup.PortableBackup
import io.polion.core.model.AccountId
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.Store
import io.polion.core.storage.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class BitcoinGraphDeltaRepositoryTest {
    private val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val scope = mapOf(address to AccountId("a"))
    private val anchor = BitcoinBlockAnchor(200, "e".repeat(64))

    private fun payload(
        id: String,
        value: Int = 9000,
    ): String =
        """{"txid":"$id","vin":[{"txid":"${sha256(
            "parent:$id",
        )}","vout":0,"prevout":{"value":10000,"scriptpubkey_address":"$address"}}],"vout":[{"value":$value,"scriptpubkey_address":"$address"}],"fee":${10000 - value},"status":{"confirmed":false}}"""

    private fun save(
        db: SqliteDatabase,
        payloads: List<String>,
        owners: Map<String, AccountId> = scope,
    ): Long {
        BitcoinStagedGraph(db).rebuild(payloads, owners)
        return db.transaction {
            BitcoinGraphDeltaRepository(db).saveInTransaction("provider", anchor, "2026-10-29T00:00:00Z", compared = false)
        }
    }

    private fun counts(db: SqliteDatabase): List<String?> =
        listOf(
            "bitcoin_graph_revisions",
            "bitcoin_graph_revision_inputs",
            "bitcoin_graph_revision_outputs",
            "bitcoin_graph_membership",
            "bitcoin_graph_facts",
        ).map { db.query("SELECT COUNT(*) FROM $it").single().single() }

    @Test
    fun unchangedScansShareBodiesAndRangesAndSingleChangeIsProportional() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val payloads = (1..40).map { payload(sha256("tx:$it")) }
            val first = save(db, payloads)
            val initial = counts(db)
            val second = save(db, payloads)
            assertEquals(initial, counts(db))
            assertEquals(2, db.query("SELECT id FROM bitcoin_graph_snapshots").size)
            val updated = payloads.toMutableList().also { it[0] = payload(sha256("tx:1"), 8999) }
            val third = save(db, updated)
            assertEquals(listOf("41", "41", "41", "41", "0"), counts(db))
            val repository = BitcoinGraphDeltaRepository(db)
            assertEquals(repository.snapshot(first)!!.graph.ownedOutputs, repository.snapshot(second)!!.graph.ownedOutputs)
            assertEquals(
                "9000",
                repository
                    .snapshot(first)!!
                    .graph.transactions
                    .getValue(sha256("tx:1"))
                    .outputs
                    .single()
                    .output.value
                    .toString(),
            )
            assertEquals(
                "8999",
                repository
                    .snapshot(third)!!
                    .graph.transactions
                    .getValue(sha256("tx:1"))
                    .outputs
                    .single()
                    .output.value
                    .toString(),
            )
        }
    }

    @Test
    fun callerRollbackPreservesHistoricalAndCurrentMembershipAndRecoveryScope() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val raw = payload("a".repeat(64))
            val first = save(db, listOf(raw))
            val repository = BitcoinGraphDeltaRepository(db)
            val before = counts(db)
            BitcoinStagedGraph(db).rebuild(emptyList(), scope)
            assertFails {
                db.transaction {
                    repository.saveInTransaction("provider", anchor, "2026-10-29T01:00:00Z", compared = false, recoveryBaseline = true)
                    error("Injected ledger failure")
                }
            }
            assertEquals(before, counts(db))
            assertEquals(1, db.query("SELECT id FROM bitcoin_graph_snapshots").size)
            assertEquals(
                1,
                repository
                    .snapshot(first)!!
                    .graph.transactions.size,
            )
            assertEquals(emptyList(), db.query("SELECT value FROM application_settings WHERE key LIKE 'bitcoin_graph_baseline:%'"))
            val other = mapOf(address to AccountId("b"))
            BitcoinStagedGraph(db).rebuild(emptyList(), other)
            val second =
                db.transaction {
                    repository.saveInTransaction("provider", anchor, "2026-10-29T02:00:00Z", compared = false, recoveryBaseline = true)
                }
            assertEquals(
                1,
                repository
                    .snapshot(first)!!
                    .graph.transactions.size,
            )
            assertEquals(emptyMap(), repository.snapshot(second)!!.graph.transactions)
            assertEquals(
                listOf(listOf(second.toString())),
                db.query(
                    "SELECT value FROM application_settings WHERE key=?",
                    listOf("bitcoin_graph_baseline:${BitcoinGraphDeltaRepository.scopeKey(other)}"),
                ),
            )
            assertEquals(
                listOf(listOf(null)),
                db.query(
                    "SELECT valid_to FROM bitcoin_graph_membership WHERE scope_key=?",
                    listOf(BitcoinGraphDeltaRepository.scopeKey(scope)),
                ),
            )
        }
    }

    @Test
    fun replacementEvidenceSurvivesDisappearanceAndHistoricalReads() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val old = payload("a".repeat(64))
            val replacement = payload("b".repeat(64)).replace(sha256("parent:${"b".repeat(64)}"), sha256("parent:${"a".repeat(64)}"))
            val first = save(db, listOf(old, replacement))
            val second = save(db, listOf(replacement))
            val third = save(db, listOf(replacement))
            val repo = BitcoinGraphDeltaRepository(db)
            assertEquals(2, repo.snapshot(first)!!.replacementEvidence.size)
            assertEquals(1, repo.snapshot(second)!!.replacementEvidence.size)
            assertEquals(repo.snapshot(second)!!.replacementEvidence, repo.snapshot(third)!!.replacementEvidence)
            assertEquals(BitcoinTransactionState.CONFLICTING, repo.snapshot(first)!!.graph.states["a".repeat(64)])
            assertEquals(BitcoinTransactionState.PENDING, repo.snapshot(second)!!.graph.states["b".repeat(64)])
        }
    }

    @Test
    fun nativeArchivePreservesRangesAndVersionFiveArchiveRetainsLegacyEvidence() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val first = save(db, listOf(payload("a".repeat(64))))
            val second = save(db, listOf(payload("a".repeat(64), 8999)))
            // Portable backups intentionally require empty scratch state before restoration, not export.
            val archive = PortableBackup.discover(db).export(db)
            JvmSqliteDatabase(":memory:").use { restored ->
                Store(restored)
                BitcoinGraphDeltaRepository(restored)
                PortableBackup.discover(restored).restoreToStaging(restored, archive)
                val repo = BitcoinGraphDeltaRepository(restored)
                assertEquals(
                    "9000",
                    repo
                        .snapshot(first)!!
                        .graph.ownedOutputs
                        .single()
                        .value
                        .toString(),
                )
                assertEquals(
                    "8999",
                    repo
                        .snapshot(second)!!
                        .graph.ownedOutputs
                        .single()
                        .value
                        .toString(),
                )
            }
        }
        JvmSqliteDatabase(":memory:").use { old ->
            Store(old)
            bitcoinGraphDeltaTables.asReversed().forEach { old.execute("DROP TABLE $it") }
            old.execute("PRAGMA user_version = 5")
            val raw = payload("a".repeat(64))
            val graph = BitcoinUtxoGraph.build(listOf(raw), scope)
            val id =
                old.transaction {
                    BitcoinGraphRepository(old).saveInTransaction(
                        graph,
                        "provider",
                        anchor,
                        "2026-10-29T00:00:00Z",
                        mapOf(
                            "a".repeat(64) to sha256(raw),
                        ),
                    )
                }
            val archive = PortableBackup.discover(old).export(old)
            JvmSqliteDatabase(":memory:").use { restored ->
                Store(restored)
                BitcoinGraphDeltaRepository(restored)
                PortableBackup.discover(restored).restoreToStaging(restored, archive)
                assertNull(BitcoinGraphDeltaRepository(restored).snapshot(id))
                assertEquals(graph.ownedOutputs, BitcoinGraphRepository(restored).snapshot(id)!!.graph.ownedOutputs)
                assertEquals("6", restored.query("PRAGMA user_version").single().single())
            }
        }
    }
}
