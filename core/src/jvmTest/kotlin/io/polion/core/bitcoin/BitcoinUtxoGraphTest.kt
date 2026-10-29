package io.polion.core.bitcoin

import io.polion.core.model.AccountId
import io.polion.core.model.ExactInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BitcoinUtxoGraphTest {
    private val addressA = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val addressB = "1BitcoinEaterAddressDontSendf59kuE"
    private val external = "1ExternalPayeeAddressAAAAAAAAAAA"
    private val accountA = AccountId("account-a")
    private val accountB = AccountId("account-b")
    private val owners = mapOf(addressA to accountA, addressB to accountB)
    private val hash100 = "1".repeat(64)
    private val hash200 = "2".repeat(64)

    private fun hex(char: Char): String = char.toString().repeat(64)

    private fun out(
        value: Long,
        address: String?,
        type: String? = "p2pkh",
    ): String {
        val addressField = address?.let { ",\"scriptpubkey_address\":\"$it\"" } ?: ""
        val typeField = type?.let { ",\"scriptpubkey_type\":\"$it\"" } ?: ""
        return "{\"value\":$value,\"scriptpubkey\":\"76a914\"$typeField$addressField}"
    }

    private fun input(
        txid: String,
        vout: Int,
        value: Long,
        address: String?,
        sequence: Long = 0xffffffff,
    ): String {
        val addressField = address?.let { ",\"scriptpubkey_address\":\"$it\"" } ?: ""
        return "{\"txid\":\"$txid\",\"vout\":$vout,\"sequence\":$sequence,\"is_coinbase\":false," +
            "\"prevout\":{\"value\":$value,\"scriptpubkey\":\"76a914\"$addressField}}"
    }

    private fun tx(
        txid: String,
        inputs: List<String>,
        outputs: List<String>,
        fee: Long,
        block: Pair<Long, String>?,
        blockTime: Long = 1_700_000_000L,
    ): String {
        val status =
            if (block == null) {
                "{\"confirmed\":false}"
            } else {
                "{\"confirmed\":true,\"block_height\":${block.first},\"block_hash\":\"${block.second}\",\"block_time\":$blockTime}"
            }
        return "{\"txid\":\"$txid\",\"vin\":[${inputs.joinToString(
            ",",
        )}],\"vout\":[${outputs.joinToString(",")}],\"fee\":$fee,\"status\":$status}"
    }

    private fun coinbase(
        txid: String,
        value: Long,
        address: String,
        block: Pair<Long, String>,
    ): String =
        "{\"txid\":\"$txid\",\"vin\":[{\"is_coinbase\":true,\"sequence\":4294967295}]," +
            "\"vout\":[${out(value, address)}],\"fee\":0,\"status\":{\"confirmed\":true,\"block_height\":${block.first}," +
            "\"block_hash\":\"${block.second}\",\"block_time\":1700000000}}"

    @Test
    fun multiAddressWalletTracksChangeAndSpendsAcrossInputs() {
        val fund = hex('a')
        val fundPayload =
            tx(
                fund,
                listOf(input(hex('0'), 0, 80_000, external)),
                listOf(out(50_000, addressA), out(30_000, addressB)),
                0,
                100L to hash100,
            )
        val spendTxid = hex('b')
        val spend =
            tx(
                spendTxid,
                listOf(input(fund, 0, 50_000, addressA), input(fund, 1, 30_000, addressB)),
                listOf(out(70_000, external), out(9_000, addressA)),
                1_000,
                null,
            )
        val graph = BitcoinUtxoGraph.build(listOf(fundPayload, spend), owners)
        assertEquals(emptyList(), graph.missingParents.filter { it.txid != hex('0') })
        assertEquals(
            listOf(BitcoinOutputStatus.SPEND_PENDING, BitcoinOutputStatus.SPEND_PENDING),
            graph.ownedOutputs.filter { it.outpoint.txid == fund }.map { it.status },
        )
        val change = graph.unspent(accountA).single()
        assertEquals(BitcoinOutpoint(spendTxid, 1), change.outpoint)
        assertEquals(ExactInteger.parse("9000"), change.value)
        assertTrue(!change.confirmed)
        val balances = graph.balances()
        assertEquals(ExactInteger.parse("50000"), balances.getValue(accountA).confirmed)
        assertEquals(ExactInteger.parse("9000"), balances.getValue(accountA).projected)
        assertEquals(ExactInteger.parse("9000"), balances.getValue(accountA).unconfirmedIncoming)
        assertEquals(ExactInteger.ZERO, balances.getValue(accountB).projected)
    }

    @Test
    fun coinbaseOutputIsImmatureUntilMaturityAndNonstandardOutputsAreNotOwned() {
        val mined = coinbase(hex('c'), 625_000_000, addressA, 100L to hash100)
        val payment =
            tx(
                hex('d'),
                listOf(input(hex('c'), 0, 625_000_000, addressA)),
                listOf(out(1, null, null), out(624_999_000, external)),
                999,
                200L to hash200,
            )
        val graph = BitcoinUtxoGraph.build(listOf(mined), owners)
        val coinbaseOutput = graph.unspent(accountA).single()
        assertTrue(coinbaseOutput.coinbase)
        assertEquals(ExactInteger.parse("625000000"), graph.balances(tipHeight = 150).getValue(accountA).immatureCoinbase)
        assertEquals(ExactInteger.ZERO, graph.balances(tipHeight = 199).getValue(accountA).immatureCoinbase)
        assertEquals(true, coinbaseOutput.mature(199))
        val premature = payment.replace("\"block_height\":200", "\"block_height\":199")
        val failure = assertFailsWith<IllegalArgumentException> { BitcoinUtxoGraph.build(listOf(mined, premature), owners) }
        assertEquals("Immature coinbase spend", failure.message)
        val withPayment = BitcoinUtxoGraph.build(listOf(mined, payment), owners)
        assertEquals(emptyList(), withPayment.unspent(accountA))
        val nonstandard = withPayment.ownedOutputs.none { it.outpoint == BitcoinOutpoint(hex('d'), 0) }
        assertTrue(nonstandard)
    }

    @Test
    fun rbfReplacementInvalidatesReplacedBranchAndKeepsRecords() {
        val fund = hex('e')
        val fundTx = tx(fund, listOf(input(hex('0'), 0, 20_000, external)), listOf(out(20_000, addressA)), 0, 100L to hash100)
        val original =
            tx(hex('f'), listOf(input(fund, 0, 20_000, addressA, sequence = 0xfffffffd)), listOf(out(19_000, addressA)), 1_000, null)
        val replacement =
            tx(
                hex('9'),
                listOf(input(fund, 0, 20_000, addressA, sequence = 0xfffffffd)),
                listOf(out(18_000, addressA)),
                2_000,
                120L to hash200,
            )
        val graph = BitcoinUtxoGraph.build(listOf(fundTx, original, replacement), owners)
        assertEquals(BitcoinTransactionState.REPLACED, graph.states.getValue(hex('f')))
        assertEquals(listOf(hex('9')), graph.replacements.getValue(hex('f')))
        assertEquals(BitcoinOutputStatus.INVALIDATED, graph.ownedOutputs.single { it.outpoint.txid == hex('f') }.status)
        assertEquals(listOf(BitcoinOutpoint(hex('9'), 0)), graph.unspent(accountA).map { it.outpoint })
        assertEquals(ExactInteger.parse("18000"), graph.balances().getValue(accountA).confirmed)
        assertEquals(3, graph.transactions.size)
    }

    @Test
    fun unconfirmedDoubleSpendLeavesBothBranchesConflicting() {
        val fund = hex('2')
        val fundTx = tx(fund, listOf(input(hex('0'), 0, 5_000, external)), listOf(out(5_000, addressA)), 0, 100L to hash100)
        val first = tx(hex('3'), listOf(input(fund, 0, 5_000, addressA)), listOf(out(4_000, external)), 1_000, null)
        val second = tx(hex('4'), listOf(input(fund, 0, 5_000, addressA)), listOf(out(4_500, external)), 500, null)
        val graph = BitcoinUtxoGraph.build(listOf(fundTx, first, second), owners)
        assertEquals(BitcoinTransactionState.CONFLICTING, graph.states.getValue(hex('3')))
        assertEquals(BitcoinTransactionState.CONFLICTING, graph.states.getValue(hex('4')))
        assertEquals(listOf(BitcoinOutpoint(fund, 0)), graph.unspent(accountA).map { it.outpoint })
        assertEquals(ExactInteger.parse("5000"), graph.balances().getValue(accountA).projected)
    }

    @Test
    fun conflictingObservationsForOneTxidAreRejected() {
        val payload = tx(hex('5'), listOf(input(hex('0'), 0, 1, external)), listOf(out(1, addressA)), 0, 100L to hash100)
        val other = payload.replace("\"value\":1", "\"value\":2")
        assertFailsWith<IllegalArgumentException> { BitcoinUtxoGraph.build(listOf(payload, other), owners) }
    }

    @Test
    fun spendingAnOwnedOutputWithoutItsParentReportsMissingParent() {
        val spend = tx(hex('6'), listOf(input(hex('7'), 0, 9_000, addressA)), listOf(out(8_000, external)), 1_000, null)
        val graph = BitcoinUtxoGraph.build(listOf(spend), owners)
        assertEquals(listOf(BitcoinOutpoint(hex('7'), 0)), graph.missingParents)
    }
}
