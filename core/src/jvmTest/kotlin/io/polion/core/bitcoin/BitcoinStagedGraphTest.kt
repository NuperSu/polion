package io.polion.core.bitcoin

import io.polion.core.ledger.ActivityRepository
import io.polion.core.model.AccountId
import io.polion.core.model.ExactInteger
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitcoinStagedGraphTest {
    private val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val other = "1BitcoinEaterAddressDontSendf59kuE"
    private val owners = mapOf(address to AccountId("a"), other to AccountId("b"))

    @Test
    fun differentialConflictDescendantsOwnershipAndMissingParents() =
        withDatabase { db ->
            val fund = tx(1, 0, height = 1)
            val pending = tx(2, 1)
            val descendant = tx(3, 2)
            val competition = tx(4, 1)
            val missing = tx(5, 99, destination = other)
            val nestedCompetition = tx(6, 2)
            val unresolved = listOf(fund, pending, descendant, competition, missing, nestedCompetition)
            assertEquivalent(db, unresolved)
            // Resolving the outer competition replaces both descendants, so their shared spend no longer conflicts.
            assertEquivalent(db, unresolved + tx(7, 1, height = 2))
            assertEquivalent(db, unresolved.reversed() + listOf(fund, pending))
            assertEquivalent(db, listOf(missing))
            assertEquivalent(db, emptyList())
        }

    @Test
    fun differentialRejectsMalformedGraphsAndRollsBackScratch() =
        withDatabase { db ->
            val staged = BitcoinStagedGraph(db)
            val valid = listOf(tx(1, 0, height = 1), tx(2, 1))
            staged.rebuild(valid, owners)
            val coinbase =
                """{"txid":"${id(1)}","vin":[{"is_coinbase":true}],"vout":[${output(address)}],"fee":0,"status":${status(1)}}"""
            val invalid =
                listOf(
                    listOf(valid[0], valid[0].replace("\"block_height\":1", "\"block_height\":2")),
                    listOf(valid[0], tx(2, 1, previousAddress = other)),
                    listOf(valid[0], tx(2, 1, vout = 1)),
                    listOf(tx(1, 0), tx(2, 1, height = 2)),
                    listOf(tx(1, 0, height = 3), tx(2, 1, height = 2)),
                    listOf(valid[0], tx(2, 1, height = 2), tx(3, 1, height = 2)),
                    listOf(coinbase, tx(2, 1, height = 100)),
                )
            invalid.forEach { payloads ->
                assertFailsWith<IllegalArgumentException> { BitcoinUtxoGraph.build(payloads, owners) }
                assertFailsWith<IllegalArgumentException> { staged.rebuild(payloads, owners) }
                assertEquals(BitcoinTransactionState.PENDING, staged.state(id(2)))
                assertEquals(parseBitcoinGraphTransaction(valid[0]), staged.transaction(id(1)))
            }
            assertEquivalent(db, listOf(coinbase, tx(2, 1, height = 101)))
            // Missing script metadata is compatible; differing non-null scripts are rejected.
            assertEquivalent(db, valid.map { it.replace(",\"scriptpubkey\":\"00\"", "") })
            val scriptMismatch = listOf(valid[0], valid[1].replace("\"scriptpubkey\":\"00\"", "\"scriptpubkey\":\"01\""))
            assertFailsWith<IllegalArgumentException> { staged.rebuild(scriptMismatch, owners) }
        }

    @Test
    fun streamingChainKeepsResultReadsBoundedAndScanCleanupClearsEveryTable() =
        withDatabase { underlying ->
            var largestRead = 0
            val db =
                object : SqliteDatabase by underlying {
                    override fun query(
                        sql: String,
                        arguments: List<String?>,
                    ): List<List<String?>> = underlying.query(sql, arguments).also { largestRead = maxOf(largestRead, it.size) }
                }
            val staged = BitcoinStagedGraph(db)
            var iterations = 0
            val payloads =
                Iterable {
                    check(iterations++ == 0) { "Payload source must only be traversed once" }
                    ((1..600).asSequence().map { tx(it, it - 1) } + sequenceOf(tx(601, 0, height = 1))).iterator()
                }
            staged.rebuild(payloads, owners)
            assertEquals(1, iterations)
            assertTrue(largestRead <= 1)
            assertEquals(BitcoinTransactionState.REPLACED, staged.state(id(600)))
            val stage = BitcoinScanStage(db, Store(db), ActivityRepository(db))
            assertTrue(BitcoinScanStage.SCRATCH_TABLES.containsAll(BitcoinStagedGraph.SCRATCH_TABLES))
            stage.clear()
            BitcoinStagedGraph.SCRATCH_TABLES.forEach { table ->
                assertEquals("0", db.query("SELECT COUNT(*) FROM $table").single().single())
            }
            assertNull(staged.transaction(id(1)))
        }

    private fun assertEquivalent(
        db: SqliteDatabase,
        payloads: List<String>,
    ) {
        val expected = BitcoinUtxoGraph.build(payloads, owners)
        val staged = BitcoinStagedGraph(db)
        staged.rebuild(payloads, owners)
        assertEquals(
            expected.transactions.keys.toList(),
            db.query("SELECT id FROM bitcoin_graph_work_transactions ORDER BY id").map { it.single() },
        )
        expected.transactions.forEach { (id, transaction) ->
            assertEquals(transaction, staged.transaction(id))
            assertEquals(expected.states[id], staged.state(id))
            assertEquals(
                transaction.inputs.size.toString(),
                db.query("SELECT COUNT(*) FROM bitcoin_graph_work_inputs WHERE tx_id=?", listOf(id)).single().single(),
            )
        }
        val actualOwned =
            db
                .query(
                    "SELECT w.tx_id, w.vout, w.account_id, w.address, o.value, o.script_type, t.height, t.hash, t.coinbase, w.status " +
                        "FROM bitcoin_graph_work_owned w JOIN bitcoin_graph_work_outputs o ON o.tx_id=w.tx_id AND o.vout=w.vout " +
                        "JOIN bitcoin_graph_work_transactions t ON t.id=w.tx_id ORDER BY w.tx_id, w.vout",
                ).map { row ->
                    val spenders =
                        db
                            .query(
                                "SELECT tx_id FROM bitcoin_graph_work_inputs WHERE parent_id=? AND parent_vout=? ORDER BY tx_id",
                                listOf(row[0], row[1]),
                            ).map { it.single()!! }
                    BitcoinOwnedOutput(
                        BitcoinOutpoint(row[0]!!, row[1]!!.toLong()),
                        AccountId(row[2]!!),
                        row[3]!!,
                        ExactInteger.parse(row[4]!!),
                        row[5],
                        row[6]?.let { BitcoinBlockAnchor(it.toLong(), row[7]!!) },
                        row[8] == "1",
                        spenders,
                        BitcoinOutputStatus.valueOf(row[9]!!),
                    )
                }
        assertEquals(expected.ownedOutputs, actualOwned)
        val missing =
            db.query("SELECT tx_id, vout FROM bitcoin_graph_work_missing ORDER BY tx_id, vout").map {
                BitcoinOutpoint(it[0]!!, it[1]!!.toLong())
            }
        assertEquals(expected.missingParents, missing)
        val conflicts =
            db
                .query(
                    "SELECT parent_id, parent_vout FROM bitcoin_graph_work_inputs WHERE parent_id IS NOT NULL " +
                        "GROUP BY parent_id, parent_vout HAVING COUNT(*)>1 ORDER BY parent_id, parent_vout",
                ).map { row ->
                    val spenders =
                        db
                            .query(
                                "SELECT tx_id FROM bitcoin_graph_work_inputs WHERE parent_id=? AND parent_vout=? ORDER BY tx_id",
                                row,
                            ).map { it.single()!! }
                    BitcoinSpendConflict(
                        BitcoinOutpoint(row[0]!!, row[1]!!.toLong()),
                        spenders,
                        spenders.singleOrNull { staged.state(it) == BitcoinTransactionState.CONFIRMED },
                    )
                }
        assertEquals(expected.conflicts, conflicts)
    }

    private fun id(number: Int) = number.toString(16).padStart(64, '0')

    private fun output(destination: String) = """{"value":1000,"scriptpubkey":"00","scriptpubkey_address":"$destination"}"""

    private fun status(height: Int?) =
        if (height == null) {
            """{"confirmed":false}"""
        } else {
            """{"confirmed":true,"block_height":$height,"block_hash":"${id(9000 + height)}","block_time":123}"""
        }

    private fun tx(
        number: Int,
        parent: Int,
        height: Int? = null,
        destination: String = address,
        previousAddress: String = address,
        vout: Int = 0,
    ) = """{"txid":"${id(
        number,
    )}","vin":[{"txid":"${id(
        parent,
    )}","vout":$vout,"sequence":4294967293,"prevout":${output(
        previousAddress,
    )}}],"vout":[${output(destination)}],"fee":0,"status":${status(height)}}"""

    private fun withDatabase(block: (SqliteDatabase) -> Unit) {
        val path = Files.createTempFile("bitcoin-staged-graph", ".sqlite")
        try {
            JvmSqliteDatabase(path.toString()).use(block)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
