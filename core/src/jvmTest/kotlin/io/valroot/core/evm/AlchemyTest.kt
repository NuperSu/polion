package io.valroot.core.evm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Responses captured from Alchemy's Portfolio APIs on 2026-11-08 for 0xDBAb…1DF9 (BNB Smart Chain and OP). */
class AlchemyTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/alchemy/$name")).readText()

    private val wallet = "0xdbab28379295d5d2e8da40edea27ef4e621f1df9"

    @Test fun transfersMapToTheLedgerModelPerNetwork() {
        val page = Alchemy.parseTransfers(fixture("transfers-bnb-op.json"))
        assertNull(page.pageKey)
        assertTrue(page.errors.isEmpty())
        // One native BNB payment in; the receipt later adds fee and status.
        val (network, bnb) = page.transactions.single()
        assertEquals("bnb-mainnet", network)
        assertEquals(wallet, bnb.to)
        assertEquals("998800000000000", bnb.value.toString())
        assertEquals("0x93afeaf089e65d1a745c54dc489b51276406dc9529e414bdef0f64306695db55", bnb.hash)
        // Eight token transfers on two networks, amounts exact and decimals from the response.
        assertEquals(8, page.tokens.size)
        assertEquals(setOf("bnb-mainnet", "opt-mainnet"), page.tokens.map { it.first }.toSet())
        val fake = page.tokens.single { it.second.contract == "0xfb9d9bcd07417fdea6d9a5f3de853e0c7df7ba32" }.second
        // The look-alike "U S DT" keeps its visible letters only; invisible Khmer vowels are dropped.
        assertEquals("USDΤ", fake.symbol)
        assertEquals(wallet, fake.from)
        assertEquals("2990000000000000000", fake.amount.toString())
        assertEquals(412, fake.logIndex)
        assertEquals(18, fake.decimals)
    }

    @Test fun balancesIncludeTheNativeCoin() {
        val (balances, errors) = Alchemy.parseTokenBalances(fixture("tokens.json"))
        assertTrue(errors.isEmpty())
        val native = balances.single { it.first == "bnb-mainnet" && it.second.standard == EvmTokenStandard.NATIVE }.second
        assertEquals("995991820000000", native.value.toString())
        assertEquals(12, balances.size)
    }

    @Test fun receiptsGiveFeeIncludingTheL1DataFee() {
        val payload =
            """[{"jsonrpc":"2.0","id":0,"result":{"transactionHash":"0x${"a".repeat(
                64,
            )}","from":"$wallet","blockNumber":"0x10","transactionIndex":"0x2",
            |"status":"0x1","gasUsed":"0x5208","effectiveGasPrice":"0x3b9aca00","l1Fee":"0x64"}},
            |{"jsonrpc":"2.0","id":1,"result":{"transactionHash":"0x${"b".repeat(
                64,
            )}","from":"$wallet","blockNumber":"0x11","transactionIndex":"0x0",
            |"status":"0x0","gasUsed":"0x5208","effectiveGasPrice":"0x1"}}]
            """.trimMargin()
        val (ok, failed) = Alchemy.parseReceipts(payload)
        // 21000 × 1 gwei + 100 wei of L1 fee.
        assertEquals("21000000000100", ok.fee.toString())
        assertTrue(ok.success)
        assertEquals("21000", failed.fee.toString())
        assertTrue(!failed.success)
        assertFails { Alchemy.parseReceipts("""[{"jsonrpc":"2.0","id":0,"result":null}]""") }
        assertEquals(7L, Alchemy.parseNonce("""{"jsonrpc":"2.0","id":1,"result":"0x7"}"""))
    }

    @Test fun requestsStayWithinAlchemysLimits() {
        val body = Alchemy.transfersBody(wallet, listOf(1, 56, 8453, 10, 42161), pageKey = "abc")
        assertTrue(body.contains("\"networks\":[\"eth-mainnet\",\"bnb-mainnet\",\"base-mainnet\",\"opt-mainnet\",\"arb-mainnet\"]"))
        assertTrue(body.contains("\"pageKey\":\"abc\""))
        assertFails { Alchemy.transfersBody(wallet, listOf(1, 56, 8453, 10, 42161, 137)) }
        // Contract-made transfers are listed only on some networks.
        assertFails { Alchemy.transfersBody(wallet, listOf(56), internal = true) }
        assertEquals("https://bnb-mainnet.g.alchemy.com/v2/{key}", Alchemy.rpcUrl(56))
        assertFails { Alchemy.rpcUrl(999) }
        assertFails { Alchemy.receiptsBody(List(51) { "0x" + "c".repeat(64) }) }
    }
}
