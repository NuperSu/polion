package io.polion.core.evm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Responses captured from eth.blockscout.com on 2026-11-08 (same REST v2 format as api.blockscout.com), trimmed. */
class BlockscoutTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/blockscout/$name")).readText()

    private val sender = "0xb314719033b4a950714be3f04119a1f45d6bdcf9"

    @Test fun transactionsCarryTheTotalFeeAndStatus() {
        val page = Blockscout.parseTransactions(fixture("transactions.json"))
        assertEquals(3, page.items.size)
        val first = page.items.first()
        assertEquals("0x8a54d733c0e22443b05f2bcaaa880fb547dded728a74bc35a1ec782b37075013", first.hash)
        assertEquals(sender, first.from)
        assertEquals("0x5ff12dadde5e8cab1b3734f8def0ffff838294fa", first.to)
        assertEquals("0", first.value.toString())
        // fee.value: gas_used 51632 × gas_price 45534050 = 2351014069600.
        assertEquals("2351014069600", first.fee.toString())
        assertEquals(26369789L, first.blockNumber)
        assertTrue(first.success)
        assertEquals(mapOf("block_number" to "26369000", "index" to "5", "items_count" to "50"), page.next)
    }

    @Test fun tokenTransfersKeepDecimalsContractsAndNftIds() {
        val transfers = Blockscout.parseTokenTransfers(fixture("token-transfers.json")).items
        val erc20 = transfers.first { it.standard == EvmTokenStandard.ERC20 }
        assertEquals("0x5ff12dadde5e8cab1b3734f8def0ffff838294fa", erc20.contract)
        assertEquals("777000000000000000000", erc20.amount.toString())
        assertEquals(18, erc20.decimals)
        assertEquals("QNTFY", erc20.symbol)
        val nft = transfers.first { it.standard == EvmTokenStandard.ERC721 }
        assertEquals("1", nft.amount.toString())
        assertEquals("84453794918345416145331514647027903846664455083247396107154093349515123913389", nft.tokenId.toString())
        assertEquals("0x0000000000000000000000000000000000000000", nft.to)
    }

    @Test fun internalTransfersBalancesAndPaging() {
        val internal = Blockscout.parseInternalTransfers(fixture("internal-transactions.json"))
        assertTrue(internal.items.all { it.success })
        assertEquals("2", internal.next!!["index"])
        assertEquals("2353004366901", Blockscout.parseBalance(fixture("address.json")).toString())
        val tokens = Blockscout.parseTokenBalances(fixture("tokens.json"))
        assertNull(tokens.next)
        // A uint256-size balance stays exact.
        assertEquals(
            "9989999999999123000004872852628676",
            tokens.items
                .first()
                .value
                .toString(),
        )
    }

    @Test fun urlsAreLimitedToTheAddressEndpoints() {
        assertEquals(
            "https://api.blockscout.com/8453/api/v2/addresses/$sender/token-transfers?block_number=1&index=2",
            Blockscout.url(8453, "addresses/$sender/token-transfers", linkedMapOf("block_number" to "1", "index" to "2")),
        )
        assertFails { Blockscout.url(1, "stats") }
        assertFails { Blockscout.url(1, "addresses/$sender/../stats") }
        assertEquals("a%20b%26c", Blockscout.url(1, "addresses/$sender", mapOf("q" to "a b&c")).substringAfter("q="))
    }
}
