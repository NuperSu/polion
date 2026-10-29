package io.polion.core.bitcoin

import io.polion.core.ledger.ActivityRepository
import io.polion.core.ledger.LedgerDeltas
import io.polion.core.ledger.MovementRole
import io.polion.core.model.AccountId
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.RawObservation
import io.polion.core.storage.Store
import io.polion.core.storage.sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BitcoinMainnetEvidenceTest {
    private val sender = AccountId("fixture-sender")
    private val recipient = AccountId("fixture-recipient")
    private val scope =
        mapOf(
            "bc1qn2yjf5z6jus76cj3jhwyl0f0p602qjmw9xr23k" to sender,
            "335Qg979J2ZDHzBzMuisgikxnfEi9ERNU7" to recipient,
        )
    private val parentId = "7cac3cf9a112cf04901a51d605058615d56ffe6d04b45270e89d1720ea955859"
    private val spendId = "f5864806e3565c34d1b41e716f72609d00b55ea5eac5b924c9719a842ef42206"

    @Test
    fun independentlyPinnedMainnetSpendPreservesOutpointsFeesAndScopedLedger() {
        val parent = fixture("parent.json")
        val spend = fixture("spend.json")
        verifyCommittedTransaction("parent", parentId, 608209, 678)
        verifyCommittedTransaction("spend", spendId, 608636, 279)
        val parentTx = parseBitcoinGraphTransaction(parent)
        val spendTx = parseBitcoinGraphTransaction(spend)
        assertEquals(
            "511884",
            parentTx.outputs[1]
                .output.value
                .toString(),
        )
        assertEquals(BitcoinOutpoint(parentId, 1), spendTx.inputs.single().spends)
        assertEquals(parentTx.outputs[1].output, spendTx.inputs.single().prevout)
        assertEquals(
            "506078",
            spendTx.outputs
                .single()
                .output.value
                .toString(),
        )
        assertEquals("5806", spendTx.fee.toString())
        assertEquals(1576409070L, parentTx.blockTime)
        assertEquals(1576667888L, spendTx.blockTime)
        assertEquals(
            "00000000000000000010a3ec7213c5c4e73f4d56be94a8a2c803503d94ebaa3c",
            parentTx.block!!.hash,
        )
        assertEquals(
            "00000000000000000014171330a4b6f9965ee0c2b5d9fa61ceedadc2de0a02a3",
            spendTx.block!!.hash,
        )

        JvmSqliteDatabase(":memory:").use { db ->
            val ledger = ActivityRepository(db)
            val stage = BitcoinScanStage(db, Store(db), ledger)

            fun apply(payloads: List<String>): BitcoinUtxoGraph {
                stage.clear()
                stage.addPage(payloads.map(::observation))
                val graph = BitcoinUtxoGraph.build(payloads, scope)
                stage.applyComplete(scope, requireBlockPositions = false, graph = graph)
                return graph
            }
            val funded = apply(listOf(parent))
            assertEquals(
                "511884",
                funded
                    .unspent(sender)
                    .single()
                    .value
                    .toString(),
            )
            assertEquals("511884", LedgerDeltas.calculate(ledger.current())[sender]!![BITCOIN_ASSET].toString())
            val spent = apply(listOf(spend, parent))
            assertTrue(spent.unspent(sender).isEmpty())
            assertEquals(BitcoinOutpoint(spendId, 0), spent.unspent(recipient).single().outpoint)
            assertEquals(
                "506078",
                spent
                    .unspent(recipient)
                    .single()
                    .value
                    .toString(),
            )
            val deltas = LedgerDeltas.calculate(ledger.current())
            assertEquals("0", deltas[sender]!![BITCOIN_ASSET].toString())
            assertEquals("506078", deltas[recipient]!![BITCOIN_ASSET].toString())
            val fee = ledger.current().flatMap { it.movements }.single { it.role == MovementRole.FEE }
            assertEquals(sender, fee.accountId)
            assertEquals("-5806", fee.amount.atomicUnits.toString())
            val accepted = ledger.current()
            apply(listOf(parent, spend))
            assertEquals(accepted.toSet(), ledger.current().toSet())
        }
    }

    private fun observation(payload: String): RawObservation {
        val id = parseBitcoinGraphTransaction(payload).id
        return RawObservation(
            "bitcoin:$id",
            "bitcoin",
            id,
            BITCOIN_MAINNET.value,
            "esplora",
            "2026-10-29T00:00:00Z",
            payload,
            "esplora-1",
            sha256(payload),
            "esplora-history",
        )
    }

    private fun verifyCommittedTransaction(
        name: String,
        id: String,
        height: Int,
        position: Int,
    ) {
        val tx = parseBitcoinGraphTransaction(fixture("$name.json"))
        val raw = hex(fixture("$name.hex"))
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4)
        assertEquals(0, buffer.get().toInt())
        assertEquals(1, buffer.get().toInt())

        fun count(): Int = (buffer.get().toInt() and 255).also { require(it < 253) }
        assertEquals(tx.inputs.size, count())
        tx.inputs.forEach { input ->
            val hash = ByteArray(32).also { buffer.get(it) }
            assertEquals(input.spends!!.txid, hexString(hash.reversedArray()))
            assertEquals(input.spends.vout, buffer.int.toLong() and 0xffffffffL)
            val scriptLength = count()
            buffer.position(buffer.position() + scriptLength)
            buffer.int
        }
        assertEquals(tx.outputs.size, count())
        tx.outputs.forEach { output ->
            assertEquals(output.output.value.toString(), buffer.long.toString())
            val script = ByteArray(count()).also { buffer.get(it) }
            assertEquals(output.output.scriptPubKey, hexString(script))
        }
        val witnessStart = buffer.position()
        tx.inputs.forEach {
            repeat(count()) {
                val itemLength = count()
                buffer.position(buffer.position() + itemLength)
            }
        }
        assertEquals(raw.size - 4, buffer.position())
        val stripped = raw.copyOfRange(0, 4) + raw.copyOfRange(6, witnessStart) + raw.takeLast(4)
        assertEquals(id, hexString(doubleHash(stripped).reversedArray()))
        val header = hex(fixture("$name.header.hex"))
        assertEquals(80, header.size)
        assertEquals(tx.block!!.hash, hexString(doubleHash(header).reversedArray()))
        val proof = Json.parseToJsonElement(fixture("$name.proof.json")).jsonObject
        assertEquals(height, proof.getValue("block_height").jsonPrimitive.int)
        assertEquals(height.toLong(), tx.block.height)
        var index = proof.getValue("pos").jsonPrimitive.int
        assertEquals(position, index)
        var root = hex(id).reversedArray()
        proof.getValue("merkle").jsonArray.forEach {
            val sibling = hex(it.jsonPrimitive.content).reversedArray()
            root = doubleHash(if (index and 1 == 0) root + sibling else sibling + root)
            index = index ushr 1
        }
        assertEquals(hexString(header.copyOfRange(36, 68)), hexString(root))
    }

    private fun fixture(name: String): String = javaClass.getResource("/bitcoin-mainnet-608636/$name")!!.readText().trim()

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hexString(value: ByteArray): String = value.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun doubleHash(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").let { it.digest(it.digest(value)) }
}
