package io.polion.core.bitcoin

import io.polion.core.connectors.Coverage
import io.polion.core.connectors.HistoryRequest
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.LedgerDeltas
import io.polion.core.ledger.MovementRole
import io.polion.core.model.AccountId
import io.polion.core.model.ProviderId
import io.polion.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class EsploraTest {
    private val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val account = AccountId("account")
    private val request = HistoryRequest(SourceId("source"), ProviderId("esplora"), BITCOIN_MAINNET, account)
    private val id = "a".repeat(64)

    private fun tx(confirmed: Boolean) =
        """{"txid":"$id","vin":[{"is_coinbase":false,"prevout":{"scriptpubkey_address":"$address","value":10000}}],"vout":[{"scriptpubkey_address":"$address","value":3000},{"scriptpubkey_address":"external","value":6000}],"fee":1000,"status":{"confirmed":$confirmed${if (confirmed) ",\"block_time\":123" else ""}}}"""

    @Test
    fun decodesOwnedChangeAndFeeFromPersistableObservation() {
        val fetcher =
            EsploraHistoryFetcher(
                mapOf(account to address),
                EsploraTransport { path ->
                    if (path.endsWith("mempool")) "[]" else "[${tx(true)}]"
                },
                { "2026-01-01T00:00:00Z" },
            )
        val page = fetcher.fetch(request)
        assertEquals(Coverage.COMPLETE, page.coverage)
        val activity = BitcoinDecoder(mapOf(address to account)).decode(page.observations.single()).single()
        assertEquals(ActivityStatus.CONFIRMED, activity.status)
        assertEquals(
            "-7000",
            LedgerDeltas
                .calculate(listOf(activity))
                .getValue(account)
                .getValue(BITCOIN_ASSET)
                .toString(),
        )
        assertEquals(
            "-1000",
            activity.movements
                .single { it.role == MovementRole.FEE }
                .amount.atomicUnits
                .toString(),
        )
    }

    @Test
    fun pendingAndConfirmationShareIdentity() {
        fun observation(confirmed: Boolean) =
            EsploraHistoryFetcher(
                mapOf(account to address),
                EsploraTransport { path ->
                    if (path.endsWith("mempool") == !confirmed) "[${tx(confirmed)}]" else "[]"
                },
                { "now" },
            ).fetch(request).observations.single()
        val decoder = BitcoinDecoder(mapOf(address to account))
        val pending = decoder.decode(observation(false)).single()
        val confirmed = decoder.decode(observation(true)).single()
        assertEquals(pending.id, confirmed.id)
        assertEquals(ActivityStatus.PENDING, pending.status)
        assertEquals(ActivityStatus.CONFIRMED, confirmed.status)
        assertEquals(emptyMap(), LedgerDeltas.calculate(listOf(pending)))
    }

    @Test
    fun paginatesConfirmedHistoryAndFlagsMempoolLimit() {
        val chain = (0 until 25).joinToString(",") { tx(true).replace(id, it.toString(16).padStart(64, '0')) }
        val pending = (25 until 75).joinToString(",") { tx(false).replace(id, it.toString(16).padStart(64, '0')) }
        val fetcher =
            EsploraHistoryFetcher(
                mapOf(account to address),
                EsploraTransport { path ->
                    when {
                        path.endsWith("mempool") -> "[$pending]"
                        path.endsWith("/chain") -> "[$chain]"
                        else -> "[]"
                    }
                },
                { "2026-01-01T00:00:00Z" },
            )
        val first = fetcher.fetch(request)
        assertEquals(75, first.observations.size)
        val second = fetcher.fetch(request.copy(cursor = first.nextCursor))
        assertEquals(null, second.nextCursor)
        assertEquals(Coverage.PARTIAL, second.coverage)
    }

    @Test
    fun resolvesBlockPositionAndRejectsMissingTransactions() {
        val hash = "b".repeat(64)
        val payload = tx(true).replace("\"block_time\":123", "\"block_time\":123,\"block_height\":42,\"block_hash\":\"$hash\"")
        val fetcher =
            EsploraHistoryFetcher(
                mapOf(account to address),
                EsploraTransport { path ->
                    if (path.endsWith("mempool")) "[]" else "[$payload]"
                },
                { "2026-01-01T00:00:00Z" },
            )
        val observation = fetcher.fetch(request).observations.single()
        val resolved = BitcoinBlockOrdering.resolve(listOf(observation), EsploraTransport { "[\"${"0".repeat(64)}\",\"$id\"]" })
        assertEquals("bitcoin-block:$hash", resolved.blocks.single().id)
        val activity = BitcoinDecoder(mapOf(address to account)).decode(resolved.transactions.single()).single()
        assertEquals("bitcoin-chain:00000000000000000042:0000000001:$id", activity.orderingKey)
        assertEquals(setOf("bitcoin:$id", "bitcoin-block:$hash"), activity.sources.map { it.value }.toSet())
        assertFails { BitcoinBlockOrdering.resolve(listOf(observation), EsploraTransport { "[]" }) }
    }

    @Test
    fun validatesSegwitAddressesAgainstBip350Vectors() {
        val valid =
            listOf(
                "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4",
                "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0",
                "BC1SW50QGDZ25J",
            )
        valid.forEach { assertEquals(it.lowercase(), bitcoinMainnetAddress(it)) }
        listOf(
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd",
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh",
            "bc1pw5dgrnzv",
            "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P",
        ).forEach { assertFails { bitcoinMainnetAddress(it) } }
    }

    @Test
    fun rejectsInvalidTransactionAmounts() {
        val invalid =
            listOf(
                tx(true).replace("\"fee\":1000", "\"fee\":999"),
                tx(true).replace("\"value\":10000", "\"value\":2100000000000001"),
                tx(true).replace("\"vin\":[{", "\"vin\":[],\"ignored\":[{"),
            )
        invalid.forEach { content ->
            val fetcher =
                EsploraHistoryFetcher(
                    mapOf(account to address),
                    EsploraTransport { path ->
                        if (path.endsWith("mempool")) "[]" else "[$content]"
                    },
                    { "2026-01-01T00:00:00Z" },
                )
            assertFails { fetcher.fetch(request) }
        }
    }

    @Test
    fun transferBetweenTrackedAccountsKeepsEachNetDelta() {
        val otherAddress = "1BitcoinEaterAddressDontSendf59kuE"
        val other = AccountId("other")
        val payload = tx(true).replace("\"external\"", "\"$otherAddress\"")
        val fetcher =
            EsploraHistoryFetcher(
                mapOf(account to address),
                EsploraTransport { path ->
                    if (path.endsWith("mempool")) "[]" else "[$payload]"
                },
                { "2026-01-01T00:00:00Z" },
            )
        val activity =
            BitcoinDecoder(
                mapOf(address to account, otherAddress to other),
            ).decode(fetcher.fetch(request).observations.single()).single()
        val balances = LedgerDeltas.calculate(listOf(activity))
        assertEquals("-7000", balances.getValue(account).getValue(BITCOIN_ASSET).toString())
        assertEquals("6000", balances.getValue(other).getValue(BITCOIN_ASSET).toString())
    }

    @Test
    fun rejectsInvalidAddressesAndMalformedPages() {
        assertFails { bitcoinMainnetAddress(address.dropLast(1) + "b") }
        assertFails { bitcoinMainnetAddress("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn") }
        val fetcher = EsploraHistoryFetcher(mapOf(account to address), EsploraTransport { "[{}]" }, { "now" })
        assertFails { fetcher.fetch(request) }
    }
}
