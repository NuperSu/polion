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

    private var slug = "base-mainnet"

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
        return """{"network":"$slug","category":"$category","blockNum":"${hex(100L + n)}",""" +
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
            mutableMapOf(
                h(4) to Triple(me, 500L, 100L),
                h(7) to Triple(me, 50L, 0L),
                // Address poisoning: the scammer sent the transaction that moved the look-alike token "from" this address.
                h(8) to Triple(other, 999L, 0L),
                // A failed call Alchemy does not list, found by its nonce.
                h(20) to Triple(me, 300L, 0L),
            )

        // The address's sent transactions by nonce: hash and block. Nonce 3, if sent, has no transaction in its block.
        val sentByNonce = mutableListOf(h(4) to 104L, h(7) to 107L, h(20) to 110L)
        val failed = setOf(h(20))
        var sentCount = 3L
        var nativeBalance = "1999999999999999300"
        val requests = mutableListOf<String>()

        private fun transfersResponse(body: String): String {
            val request = Json.parseToJsonElement(body).jsonObject
            val networks = request["networks"]!!.jsonArray.map { it.jsonPrimitive.content }
            val categories = request["category"]!!.jsonArray.map { it.jsonPrimitive.content }
            val max = request["maxCount"]!!.jsonPrimitive.content.toInt()
            val start = request["pageKey"]?.jsonPrimitive?.content?.toInt() ?: 0
            val matching =
                if (slug in networks) {
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
            if (slug !in networks) return """{"data":{"tokens":[]}}"""
            val native = "0x" + BigInteger(nativeBalance).toString(16)
            return """{"data":{"tokens":[{"address":"$me","network":"$slug","tokenAddress":null,"tokenBalance":"$native"},""" +
                """{"address":"$me","network":"$slug","tokenAddress":"$usdc","tokenBalance":"${hex(2_500_000)}"},""" +
                """{"address":"$me","network":"$slug","tokenAddress":"$fakeUsdc","tokenBalance":"0x0"}]}}"""
        }

        private fun rpcResponse(body: String): String {
            val single = Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
            if (single != null) {
                val method = single["method"]!!.jsonPrimitive.content
                val params = single["params"]!!.jsonArray
                val result =
                    when (method) {
                        "eth_blockNumber" -> "\"${hex(200)}\""
                        "eth_getTransactionCount" -> {
                            val at = params[1].jsonPrimitive.content
                            val count =
                                if (at == "latest") {
                                    sentCount
                                } else {
                                    val block = at.removePrefix("0x").toLong(16)
                                    (0 until sentCount).count { blockOf(it) <= block }.toLong()
                                }
                            "\"${hex(count)}\""
                        }
                        "eth_getBlockByNumber" -> {
                            val block =
                                params[0]
                                    .jsonPrimitive.content
                                    .removePrefix("0x")
                                    .toLong(16)
                            val mine =
                                sentByNonce.withIndex().filter { it.value.second == block }.joinToString("") { (nonce, tx) ->
                                    """,{"hash":"${tx.first}","from":"$me","to":"$router","nonce":"${hex(nonce.toLong())}",""" +
                                        """"transactionIndex":"0x2","value":"0x0"}"""
                                }
                            """{"number":"${hex(block)}","timestamp":"${hex(1_768_003_200L + block)}","transactions":[""" +
                                """{"hash":"${h(
                                    999,
                                )}","from":"$other","to":"$me","nonce":"0x5","transactionIndex":"0x0","value":"0x0"}$mine]}"""
                        }
                        else -> error("Unexpected method $method")
                    }
                return """{"jsonrpc":"2.0","id":1,"result":$result}"""
            }
            val calls = Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
            return calls
                .mapIndexed { index, call ->
                    val hash = call["params"]!!.jsonArray[0].jsonPrimitive.content
                    if (call["method"]!!.jsonPrimitive.content == "eth_getTransactionByHash") {
                        val nonce = sentByNonce.indexOfFirst { it.first == hash }
                        """{"jsonrpc":"2.0","id":$index,"result":{"hash":"$hash","nonce":"${hex(nonce.toLong())}"}}"""
                    } else {
                        val (from, gas, l1) = receipts.getValue(hash)
                        """{"jsonrpc":"2.0","id":$index,"result":{"transactionHash":"$hash","from":"$from","blockNumber":"0x10",""" +
                            """"transactionIndex":"0x1","status":"${if (hash in failed) "0x0" else "0x1"}","gasUsed":"${hex(
                                gas,
                            )}","effectiveGasPrice":"0x1","l1Fee":"${hex(l1)}"}}"""
                    }
                }.joinToString(",", "[", "]")
        }

        /** The block holding the transaction with [nonce]; a nonce without a transaction (nonce 3) is in block 112. */
        private fun blockOf(nonce: Long): Long = sentByNonce.getOrNull(nonce.toInt())?.second ?: 112L

        val post = { url: String, body: String ->
            requests += url.substringAfterLast('/')
            when {
                url.endsWith("/transfers/by-address") -> transfersResponse(body)
                url.endsWith("/tokens/by-address") -> tokensResponse(body)
                url.startsWith("https://$slug.g.alchemy.com/v2/") -> rpcResponse(body)
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
            assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions()).jobs, app.evmChainStates(me).toString())
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

            // A failed call Alchemy did not list was found by its nonce: its fee counts, with the search as its source.
            val found = activities.getValue(h(20)).activity
            assertEquals(listOf("-300" to MovementRole.FEE), found.movements.map { it.amount.atomicUnits.toString() to it.role })
            // 3 ETH − 1 ETH − 600 − 50 + 250 − 300 matches Alchemy, with nothing missing.
            val state = app.evmChainStates(me).single()
            assertNull(state.balanceDifference)
            assertNull(state.coverage)
            assertEquals(mapOf(2L to h(20)), app.evmRecovered().nonces(me, chain).mapValues { it.value?.hash })
            assertEquals(setOf(EvmAssets.token(chain, fakeUsdc), EvmAssets.nft(chain, punk, evmQuantity(huge))), app.hiddenAssets())

            // Unchanged: newest transfer, newest contract transfer and the sent count only.
            provider.requests.clear()
            connector.refresh(app, SourceRefreshOptions())
            assertEquals(3, provider.requests.size)

            // A nonce no transaction in its block uses (a delegation authorization, say) is recorded as not found once, and
            // the unexplained difference becomes an adjustment after the last activity, so the balance matches.
            provider.sentCount = 4
            provider.nativeBalance = "1999999999999999000"
            connector.refresh(app, SourceRefreshOptions())
            val after = app.evmChainStates(me).single()
            assertNull(after.balanceDifference)
            assertEquals("1 sent transaction could not be found on the chain; their fees are not counted", after.coverage)
            assertEquals(null, app.evmRecovered().nonces(me, chain)[3L])
            provider.requests.clear()
            connector.refresh(app, SourceRefreshOptions())
            assertTrue(provider.requests.none { it.contains("eth_getBlockByNumber") })
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

    /** 1inch's history: events with their transaction hash, block, sender and token actions. */
    private fun oneInchHistory(vararg events: String) = """{"items":[${events.joinToString(",")}]}"""

    private fun oneInchEvent(
        n: Int,
        sender: String,
        vararg actions: String,
    ) = """{"timeMs":${1_767_225_600_000L + n * 86_400_000L},"details":{"txHash":"${h(n)}","blockNumber":${100 + n},""" +
        """"fromAddress":"$sender","tokenActions":[${actions.joinToString(",")}]}}"""

    private fun action(
        standard: String,
        from: String,
        to: String,
        amount: String,
        contract: String = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
    ) = """{"standard":"$standard","address":"$contract","fromAddress":"$from","toAddress":"$to","amount":"$amount"}"""

    @Test fun contractPaymentsAreRecoveredFromOneInchTracesWhereAlchemyDoesNotListThem() {
        JvmSqliteDatabase(":memory:").use { db ->
            slug = "bnb-mainnet"
            val app = setUp(db)
            val provider = FakeAlchemy()
            // Only the deposit, the swap's token side and the address-poisoning transfer are listed; Alchemy lists no
            // contract payments on BNB Smart Chain.
            provider.receipts.remove(h(20))
            provider.sentByNonce.removeAt(2)
            provider.sentCount = 2
            // 3 − 1 − 600 − 50 + 250 (swap proceeds) + 1000 (paid in someone else's transaction).
            provider.nativeBalance = "2000000000000000600"
            val payer = "0x4444444444444444444444444444444444444444"
            val traces =
                mapOf(
                    h(7) to
                        """{"transactionTrace":{"txHash":"${h(7)}","type":"CALL","from":"$me","to":"$router","value":"0x0",""" +
                        """"status":"RETURNED","calls":[""" +
                        """{"type":"CALL","from":"$router","to":"$me","value":"0xfa","status":"NO_CONTRACT","success":1},""" +
                        // A delegate call carries the caller's value without moving it.
                        """{"type":"DELEGATECALL","from":"$router","to":"$me","value":"0x3e7","status":"RETURNED","success":1},""" +
                        // A payment inside a reverted call never happened.
                        """{"type":"CALL","from":"$router","to":"$router","value":"0x0","status":"REVERTED","success":0,"calls":[""" +
                        """{"type":"CALL","from":"$router","to":"$me","value":"0x7","status":"NO_CONTRACT","success":1}]}]}}""",
                    h(30) to
                        """{"transactionTrace":{"txHash":"${h(30)}","type":"CALL","from":"$other","to":"$payer","value":"0x0",""" +
                        """"status":"RETURNED","calls":[{"type":"CALL","from":"$payer","to":"$me","value":"0x3e8","status":"NO_CONTRACT","success":1}]}}""",
                )
            val oneInchRequests = mutableListOf<String>()
            val oneInch = { path: String ->
                oneInchRequests += path
                when {
                    path.startsWith("/history/") ->
                        oneInchHistory(
                            oneInchEvent(30, other, action("Native", payer, me, "1000")),
                            oneInchEvent(7, me, action("ERC20", me, router, "2500000", usdc), action("Native", router, me, "250")),
                            // A plain transfer from the sender is listed by Alchemy and needs no trace.
                            oneInchEvent(1, other, action("Native", other, me, "3000000000000000000")),
                        )
                    else -> traces.getValue(path.substringAfter("/tx-hash/"))
                }
            }
            val connector = EvmConnector(provider.post, oneInch)
            app.addEvmChains(me, setOf(56L))
            app.recordEvmDiscovery(me)
            assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions()).jobs)
            val activities =
                app.transactionEntries(ActivityFilter()).associate {
                    it.activity.id.value
                        .substringAfter("56:")
                        .substringBefore(':') to it.activity
                }
            // The swap's proceeds join the swap itself.
            val swap = activities.getValue(h(7))
            assertEquals(ActivityKind.TRADE, swap.kind)
            assertTrue(swap.movements.any { it.amount.assetId == EvmAssets.native(56) && it.amount.atomicUnits.toString() == "250" })
            // A payment in a transaction Alchemy never listed is its own deposit.
            assertEquals(ActivityKind.DEPOSIT, activities.getValue(h(30)).kind)
            // Balances match with no adjustment, and the chain reports no gap.
            val state = app.evmChainStates(me).single()
            assertNull(state.balanceDifference)
            assertNull(state.coverage)
            assertTrue(
                app.snapshot().activities.none {
                    it.activity.id.value
                        .startsWith("evm-adjust:")
                },
            )
            assertEquals(listOf("/history/", "/traces/", "/traces/"), oneInchRequests.map { it.substring(0, it.indexOf('/', 1) + 1) })

            // Unchanged and checked recently: 1inch is not asked again, and nothing is traced twice.
            oneInchRequests.clear()
            connector.refresh(app, SourceRefreshOptions())
            assertTrue(oneInchRequests.isEmpty())

            // Without a key the chain says what is missing.
            EvmConnector(provider.post).refresh(app, SourceRefreshOptions(recoverHistory = true))
            assertEquals(
                "Payments made by contracts are not listed on this chain; a 1inch key recovers them",
                app.evmChainStates(me).single { it.chainId == 56L }.coverage,
            )
            slug = "base-mainnet"
        }
    }

    @Test fun tokensOneInchLeavesOutStartHidden() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            var listed = emptyList<String>()
            val oneInch = { path: String ->
                require(path.startsWith("/history/"))
                oneInchHistory(*listed.map { oneInchEvent(5, other, action("ERC20", other, me, "1", it)) }.toTypedArray())
            }
            app.addEvmChains(me, setOf(chain))
            app.recordEvmDiscovery(me)
            EvmConnector(provider.post, oneInch).refresh(app, SourceRefreshOptions())
            val usdcAsset = EvmAssets.token(chain, usdc)
            // USD Coin has a CoinGecko coin, but 1inch's history of the address leaves it out.
            assertTrue(usdcAsset in app.hiddenAssets())
            // The user's choice wins.
            app.setAssetShown(usdcAsset, true)
            assertTrue(usdcAsset !in app.hiddenAssets())
            app.evmRecovered().saveListing(me, chain, OneInchListing(1_800_000_000_000L, setOf(usdc)))
            JvmSqliteDatabase(":memory:").use { other ->
                val fresh = setUp(other)
                fresh.addEvmChains(me, setOf(chain))
                listed = listOf(usdc)
                EvmConnector(FakeAlchemy().post, oneInch).refresh(fresh, SourceRefreshOptions())
                assertTrue(usdcAsset !in fresh.hiddenAssets())
            }
        }
    }

    @Test fun theRegistryRoutesEvmAddressesToTheEvmConnector() {
        val evm = EvmConnector({ _, _ -> error("not called") })
        val registry = SourceRegistry(listOf(BitcoinConnector(EsploraTransport { error("not called") }, DEFAULT_ENDPOINT), evm))
        assertSame(evm, registry.forNetwork(EVM_FAMILY))
    }
}
