package io.polion.core.evm

import io.polion.core.application.ActivityFilter
import io.polion.core.application.BitcoinConnector
import io.polion.core.application.DEFAULT_ENDPOINT
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.SourceRefreshOptions
import io.polion.core.application.SourceRegistry
import io.polion.core.bitcoin.EsploraTransport
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.MovementRole
import io.polion.core.model.AccountId
import io.polion.core.model.OwnerId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.sync.JobState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EvmConnectorTest {
    private val me = "0x1111111111111111111111111111111111111111"
    private val other = "0x2222222222222222222222222222222222222222"
    private val router = "0x3333333333333333333333333333333333333333"
    private val usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"
    private val fakeUsdc = "0x" + "5".repeat(40)
    private val punk = "0x" + "6".repeat(40)
    private val chain = 8453L
    private val huge = "0x" + "f".repeat(64)

    private fun h(n: Int) = "0x" + n.toString(16).padStart(64, '0')

    private fun hex(value: Long) = "0x" + value.toString(16)

    private fun transfer(
        n: Int,
        category: String,
        from: String,
        to: String,
        raw: String? = null,
        contract: String? = null,
        decimals: Int = 18,
        tokenId: String? = null,
    ): String {
        val unique =
            when (category) {
                "external" -> "${h(n)}:external"
                "internal" -> "${h(n)}:internal:0"
                else -> "${h(n)}:log:1"
            }
        val fungible = raw?.let { ""","fungible":{"rawValue":"$it","value":1.0,"decimals":$decimals,"asset":"USDC"}""" } ?: ""
        val nft = tokenId?.let { ""","nfts":{"tokens":[{"tokenId":"$it","amount":"0x1"}]}""" } ?: ""
        val contractField = contract?.let { ""","contractAddress":"$it"""" } ?: ""
        return """{"network":"base-mainnet","category":"$category","blockNum":"${hex(100L + n)}",""" +
            """"blockTimestamp":"2026-01-0${n % 9 + 1}T00:00:00.000Z","hash":"${h(n)}","from":"$from","to":"$to",""" +
            """"uniqueId":"$unique"$contractField$fungible$nft}"""
    }

    private inner class FakeAlchemy {
        // Newest first, as Alchemy returns them with order "desc".
        val transfers =
            listOf(
                transfer(9, "erc721", other, me, contract = punk, tokenId = huge),
                transfer(8, "erc20", me, other, raw = hex(1_000_000), contract = fakeUsdc, decimals = 6),
                transfer(7, "erc20", me, router, raw = hex(2_500_000), contract = usdc, decimals = 6),
                transfer(6, "internal", router, me, raw = hex(250)),
                transfer(5, "erc20", other, me, raw = hex(5_000_000), contract = usdc, decimals = 6),
                transfer(4, "external", me, other, raw = "0xde0b6b3a7640000"),
                transfer(1, "external", other, me, raw = "0x29a2241af62c0000"),
            )

        // Receipts by hash: the sender, its gas, and an L1 data fee on top (Base is an OP-stack chain).
        val receipts =
            mapOf(
                h(4) to Triple(me, 500L, 100L),
                h(7) to Triple(me, 50L, 0L),
                // Address poisoning: the scammer sent the transaction that moved the look-alike token "from" this address.
                h(8) to Triple(other, 999L, 0L),
            )
        var sentCount = 3L
        var nativeBalance = "1999999999999999600"
        val requests = mutableListOf<String>()

        private fun transfersResponse(body: String): String {
            val request = Json.parseToJsonElement(body).jsonObject
            val networks = request["networks"]!!.jsonArray.map { it.jsonPrimitive.content }
            val categories = request["category"]!!.jsonArray.map { it.jsonPrimitive.content }
            val max = request["maxCount"]!!.jsonPrimitive.content.toInt()
            val start = request["pageKey"]?.jsonPrimitive?.content?.toInt() ?: 0
            val matching =
                if ("base-mainnet" in networks) {
                    transfers.filter { item -> categories.any { item.contains("\"category\":\"$it\"") } }
                } else {
                    emptyList()
                }
            // Pages of two, so paging is exercised.
            val size = minOf(max, 2)
            val next = if (start + size < matching.size) "\"${start + size}\"" else "null"
            return """{"transfers":[${matching.drop(start).take(size).joinToString(",")}],"pageKey":$next}"""
        }

        private fun tokensResponse(body: String): String {
            val networks =
                Json
                    .parseToJsonElement(body)
                    .jsonObject["addresses"]!!
                    .jsonArray[0]
                    .jsonObject["networks"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            if ("base-mainnet" !in networks) return """{"data":{"tokens":[]}}"""
            val native = "0x" + BigInteger(nativeBalance).toString(16)
            return """{"data":{"tokens":[{"address":"$me","network":"base-mainnet","tokenAddress":null,"tokenBalance":"$native"},""" +
                """{"address":"$me","network":"base-mainnet","tokenAddress":"$usdc","tokenBalance":"${hex(2_500_000)}"},""" +
                """{"address":"$me","network":"base-mainnet","tokenAddress":"$fakeUsdc","tokenBalance":"0x0"}]}}"""
        }

        private fun rpcResponse(body: String): String {
            if (body.contains("eth_getTransactionCount")) return """{"jsonrpc":"2.0","id":1,"result":"${hex(sentCount)}"}"""
            val hashes =
                Json.parseToJsonElement(body).jsonArray.map {
                    it.jsonObject["params"]!!
                        .jsonArray[0]
                        .jsonPrimitive.content
                }
            return hashes
                .mapIndexed { index, hash ->
                    val (from, gas, l1) = receipts.getValue(hash)
                    """{"jsonrpc":"2.0","id":$index,"result":{"transactionHash":"$hash","from":"$from","blockNumber":"0x10",""" +
                        """"transactionIndex":"0x1","status":"0x1","gasUsed":"${hex(
                            gas,
                        )}","effectiveGasPrice":"0x1","l1Fee":"${hex(l1)}"}}"""
                }.joinToString(",", "[", "]")
        }

        val post = { url: String, body: String ->
            requests += url.substringAfterLast('/')
            when {
                url.endsWith("/transfers/by-address") -> transfersResponse(body)
                url.endsWith("/tokens/by-address") -> tokensResponse(body)
                url.startsWith("https://base-mainnet.g.alchemy.com/v2/") -> rpcResponse(body)
                else -> error("Unexpected request $url")
            }
        }
    }

    private fun setUp(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1_800_000_000_000L }
        app.snapshot()
        app.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.saveAccountAndTarget(
            Account(AccountId("evm"), OwnerId("owner"), "EVM", AccountKind.WALLET),
            TrackingTarget(TrackingTargetId("evm"), AccountId("evm"), TargetIdentity(EVM_FAMILY, "address", me), TargetRole.LEAF, "EVM"),
        )
        app.setEvmConsent(true)
        app.setMarketDataConsent(true)

        fun fixture(name: String) = requireNotNull(javaClass.getResource("/coingecko/$name")).readText()
        app.refreshEvmCatalog(
            { url, _ -> if (url.contains("asset_platforms")) fixture("asset_platforms.json") else fixture("coins_list.json") },
        )
        return app
    }

    @Test fun historyFeesGapsAndSkipping() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            val connector = EvmConnector(provider.post)
            // Discovery finds Base among the networks.
            assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions()).jobs)
            assertEquals(listOf(chain), app.evmChainStates(me).map { it.chainId })

            val activities =
                app.transactionEntries(ActivityFilter()).associateBy {
                    it.activity.id.value
                        .substringAfter("$chain:")
                        .substringBefore(':')
                }
            assertEquals(ActivityKind.DEPOSIT, activities.getValue(h(1)).activity.kind)
            // Sent 1 ETH: gas 500 plus the 100 wei L1 data fee.
            val sent = activities.getValue(h(4)).activity.movements
            assertEquals(listOf("-1000000000000000000", "-600"), sent.map { it.amount.atomicUnits.toString() })
            assertEquals(MovementRole.FEE, sent.last().role)
            // A swap listed only through its token transfer still gets its fee from the receipt.
            val swap = activities.getValue(h(7)).activity.movements
            assertEquals(setOf("-2500000", "-50"), swap.map { it.amount.atomicUnits.toString() }.toSet())
            // The poisoning transfer was paid for by the scammer: no fee for this address.
            assertTrue(
                activities
                    .getValue(h(8))
                    .activity.movements
                    .none { it.role == MovementRole.FEE },
            )
            assertEquals(
                "250",
                activities
                    .getValue(h(6))
                    .activity.movements
                    .single()
                    .amount.atomicUnits
                    .toString(),
            )
            assertEquals(
                EvmAssets.nft(chain, punk, evmQuantity(huge)),
                activities
                    .getValue(h(9))
                    .activity.movements
                    .single()
                    .amount.assetId,
            )

            // 3 ETH − 1 ETH − 600 − 50 + 250 matches Alchemy; one sent transaction (an approval, say) was not listed.
            val state = app.evmChainStates(me).single()
            assertNull(state.balanceDifference)
            assertEquals("1 sent transaction not listed by Alchemy (for example approvals); their fees are not counted", state.coverage)
            assertEquals(setOf(EvmAssets.token(chain, fakeUsdc), EvmAssets.nft(chain, punk, evmQuantity(huge))), app.hiddenAssets())

            // Unchanged: newest transfer, newest contract transfer and the sent count only.
            provider.requests.clear()
            connector.refresh(app, SourceRefreshOptions())
            assertEquals(3, provider.requests.size)

            // Another unlisted transaction: its fee becomes an adjustment after the last activity, so the balance matches;
            // the gap count grows.
            provider.sentCount = 4
            provider.nativeBalance = "1999999999999999300"
            connector.refresh(app, SourceRefreshOptions())
            val after = app.evmChainStates(me).single()
            assertNull(after.balanceDifference)
            assertTrue(after.coverage!!.startsWith("2 sent transactions not listed"))
            val adjustment =
                app
                    .snapshot()
                    .activities
                    .map { it.activity }
                    .single { it.id.value.startsWith("evm-adjust:") }
            assertEquals(
                "-300",
                adjustment.movements
                    .single()
                    .amount.atomicUnits
                    .toString(),
            )
        }
    }

    @Test fun theRegistryRoutesEvmAddressesToTheEvmConnector() {
        val evm = EvmConnector({ _, _ -> error("not called") })
        val registry = SourceRegistry(listOf(BitcoinConnector(EsploraTransport { error("not called") }, DEFAULT_ENDPOINT), evm))
        assertSame(evm, registry.forNetwork(EVM_FAMILY))
    }
}
