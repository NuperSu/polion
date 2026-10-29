package io.polion.core.bitcoin

import io.polion.core.model.AccountId
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.SqliteDatabase
import io.polion.core.storage.sha256
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BitcoinStagedUtxoComparisonTest {
    private val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val other = "1BitcoinEaterAddressDontSendf59kuE"
    private val unwatched = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
    private val owners = mapOf(address to AccountId("a"), other to AccountId("b"))

    @Test
    fun comparisonMatchesInMemoryOrderingMetadataAndSpentMembership() =
        withDatabase { db ->
            val payloads = listOf(tx(1, 0, height = 1), tx(2, 1), tx(3, 99), tx(4, 98, height = 2), tx(5, 97))
            val provider =
                linkedMapOf(
                    address to "[${utxo(1)},${utxo(3, value = "999999999999999999999999999999999999")} ]",
                    other to "[${utxo(2, value = "999", height = 5)},${utxo(6)}]",
                    unwatched to "[${utxo(5)}]",
                )
            assertEquivalent(db, payloads, provider)
            assertEquivalent(db, payloads, emptyMap())
            assertEquivalent(db, emptyList(), provider)
            assertEquivalent(db, emptyList(), emptyMap())
        }

    @Test
    fun duplicateOrMalformedResponsesCannotPartiallyReplaceStagedProviderRows() =
        withDatabase { db ->
            BitcoinStagedGraph(db).rebuild(listOf(tx(1, 0)), owners)
            val compared = BitcoinStagedUtxoComparison(db)
            compared.addResponse(address, "[${utxo(1)}]")
            assertEquals(1L, compared.compare()) // The owned previous output's parent is absent.
            assertFailsWith<IllegalArgumentException> { compared.addResponse(other, "[${utxo(2)},${utxo(1)}]") }
            assertFailsWith<IllegalArgumentException> { compared.addResponse(other, "[${utxo(2)},${utxo(2)}]") }
            assertFailsWith<IllegalArgumentException> { compared.addResponse(address, "[]") }
            listOf("-1", "1.0", "1e3", "\"1000\"").forEach { amount ->
                assertFailsWith<IllegalArgumentException> { compared.addResponse(other, "[${utxo(2, value = amount)}]") }
            }
            assertEquals(listOf(listOf(id(1))), db.query("SELECT tx_id FROM bitcoin_graph_work_provider"))
            assertEquals(listOf(listOf(address)), db.query("SELECT address FROM bitcoin_graph_work_utxo_responses"))
            assertEquals(1L, compared.compare())
        }

    @Test
    fun fetchStreamsAddressesAndAllComparisonQueriesAreBounded() =
        withDatabase { underlying ->
            var largestRead = 0
            val db =
                object : SqliteDatabase by underlying {
                    override fun query(
                        sql: String,
                        arguments: List<String?>,
                    ): List<List<String?>> = underlying.query(sql, arguments).also { largestRead = maxOf(largestRead, it.size) }
                }
            BitcoinStagedGraph(db).rebuild(emptyList(), owners)
            val compared = BitcoinStagedUtxoComparison(db)
            var fetched = 0
            var saved = 0
            val count =
                compared.fetchAndCompare(
                    EsploraTransport { endpoint ->
                        assertEquals(fetched, saved)
                        fetched++
                        val base = if (endpoint.contains(address)) 0 else 500
                        (1..500).joinToString(",", "[", "]") { utxo(base + it) }
                    },
                ) { responseAddress, payload ->
                    // Callback can open a transaction to persist provenance.
                    db.transaction {
                        assertEquals(
                            sha256(payload),
                            db
                                .query("SELECT checksum FROM bitcoin_graph_work_utxo_responses WHERE address=?", listOf(responseAddress))
                                .single()
                                .single(),
                        )
                    }
                    saved++
                }
            assertEquals(1000L, count)
            assertEquals(2, saved)
            assertTrue(largestRead <= 1)
            assertEquals("999", db.query("SELECT MAX(position) FROM bitcoin_graph_work_diffs").single().single())
            // Fetch starts a fresh comparison rather than retaining provider rows from its prior result.
            assertEquals(0L, compared.fetchAndCompare(EsploraTransport { "[]" }))
        }

    private fun assertEquivalent(
        db: SqliteDatabase,
        payloads: List<String>,
        responses: Map<String, String>,
    ) {
        val graph = BitcoinUtxoGraph.build(payloads, owners)
        val provider = EsploraUtxoSnapshot(responses.flatMap { parseEsploraUtxos(it.key, it.value) }, responses)
        val expected = compareUtxoSets(graph, provider)
        BitcoinStagedGraph(db).rebuild(payloads, owners)
        val staged = BitcoinStagedUtxoComparison(db)
        responses.forEach { (address, payload) -> staged.addResponse(address, payload) }
        assertEquals((expected.mismatches.size + expected.missingParents.size).toLong(), staged.compare())
        val actual =
            db.query(
                "SELECT d.position, d.kind, d.tx_id, d.vout, d.reconstructed, p.address, p.value, p.height, p.hash " +
                    "FROM bitcoin_graph_work_diffs d LEFT JOIN bitcoin_graph_work_provider p ON p.tx_id=d.tx_id AND p.vout=d.vout " +
                    "ORDER BY d.position",
            )
        assertEquals(
            expected.mismatches.mapIndexed { position, diff ->
                listOf(
                    position.toString(),
                    diff.kind.name,
                    diff.outpoint.txid,
                    diff.outpoint.vout.toString(),
                    if (diff.reconstructed == null) "0" else "1",
                    diff.provider?.address,
                    diff.provider?.value?.toString(),
                    diff.provider
                        ?.block
                        ?.height
                        ?.toString(),
                    diff.provider?.block?.hash,
                )
            },
            actual,
        )
        assertEquals(
            expected.missingParents.map { listOf(it.txid, it.vout.toString()) },
            db.query("SELECT tx_id, vout FROM bitcoin_graph_work_missing ORDER BY tx_id,vout"),
        )
    }

    private fun id(number: Int) = number.toString(16).padStart(64, '0')

    private fun status(height: Int?) =
        if (height == null) {
            """{"confirmed":false}"""
        } else {
            """{"confirmed":true,"block_height":$height,"block_hash":"${id(9000 + height)}"}"""
        }

    private fun utxo(
        number: Int,
        value: String = "1000",
        height: Int? = null,
    ) = """{"txid":"${id(number)}","vout":0,"value":$value,"status":${status(height)}}"""

    private fun tx(
        number: Int,
        parent: Int,
        height: Int? = null,
    ) = """{"txid":"${id(
        number,
    )}","vin":[{"txid":"${id(
        parent,
    )}","vout":0,"prevout":{"value":1000,"scriptpubkey_address":"$address"}}],"vout":[{"value":1000,"scriptpubkey_address":"$address"}],"fee":0,"status":${status(
        height,
    )}}"""

    private fun withDatabase(block: (SqliteDatabase) -> Unit) {
        val path = Files.createTempFile("bitcoin-staged-utxo", ".sqlite")
        try {
            JvmSqliteDatabase(path.toString()).use(block)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
