package io.polion.core.evm

import io.polion.core.application.ActivityFilter
import io.polion.core.application.BitcoinConnector
import io.polion.core.application.DEFAULT_ENDPOINT
import io.polion.core.application.PortfolioApplication
import io.polion.core.application.SourceRegistry
import io.polion.core.bitcoin.EsploraTransport
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EvmConnectorTest {
    private val me = "0x1111111111111111111111111111111111111111"
    private val other = "0x2222222222222222222222222222222222222222"
    private val usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"
    private val fakeUsdc = "0x" + "5".repeat(40)
    private val punk = "0x" + "6".repeat(40)
    private val chain = 8453L
    private val huge = "115792089237316195423570985008687907853269984665640564039457584007913129639935"

    private fun h(n: Int) = "0x" + n.toString(16).padStart(64, '0')

    private fun address(a: String) = """{"hash":"$a","is_contract":false,"is_scam":false,"reputation":"ok"}"""

    private fun tx(
        n: Int,
        block: Long,
        from: String,
        to: String,
        value: String,
        fee: String,
        status: String = "ok",
    ) = """{"hash":"${h(
        n,
    )}","block_number":$block,"position":1,"timestamp":"2026-01-0${n % 9 + 1}T00:00:00.000000Z","from":${address(from)},
        |"to":${address(
        to,
    )},"value":"$value","fee":{"type":"actual","value":"$fee"},"status":"$status","result":"${if (status == "ok") "success" else "Reverted"}"}
        """.trimMargin()

    private fun token(
        n: Int,
        block: Long,
        from: String,
        to: String,
        contract: String,
        value: String,
        type: String = "ERC-20",
        symbol: String = "USDC",
        decimals: String? = "6",
        tokenId: String? = null,
    ) = """{"transaction_hash":"${h(n)}","block_number":$block,"log_index":3,"timestamp":"2026-01-0${n % 9 + 1}T00:00:00.000000Z",
        |"from":${address(
        from,
    )},"to":${address(to)},"token":{"address_hash":"$contract","decimals":${decimals?.let { "\"$it\"" } ?: "null"},
        |"name":"$symbol coin","symbol":"$symbol","type":"$type","reputation":"ok"},"token_type":"$type",
        |"total":{${tokenId?.let {
        "\"token_id\":\"$it\""
    } ?: "\"decimals\":${decimals?.let { "\"$it\"" } ?: "null"},\"value\":\"$value\""}},"type":"token_transfer"}
        """.trimMargin()

    private inner class FakeBlockscout {
        var transactions =
            listOf(
                // Newest first, as Blockscout returns them.
                tx(5, 105, me, other, "0", "300", status = "error"),
                tx(4, 104, me, other, "1000000000000000000", "500"),
                tx(1, 101, other, me, "3000000000000000000", "700"),
            )
        val internal =
            listOf(
                """{"transaction_hash":"${h(
                    6,
                )}","block_number":106,"index":1,"timestamp":"2026-01-07T00:00:00.000000Z","from":${address(
                    other,
                )},"to":${address(me)},"value":"250","success":true}""",
            )
        val tokens =
            listOf(
                token(9, 109, other, me, punk, "1", type = "ERC-721", symbol = "PUNK", decimals = null, tokenId = huge),
                token(8, 108, other, me, fakeUsdc, "1000000000"),
                token(7, 107, other, me, usdc, "2500000"),
            )
        var nativeBalance = "1999999999999999450"
        val requests = mutableListOf<String>()

        fun page(
            items: List<String>,
            url: String,
        ): String {
            // Two items per page, so paging is exercised.
            val start = url.substringAfter("index=", "").substringBefore('&').toIntOrNull() ?: 0
            val next = if (start + 2 < items.size) """{"index":${start + 2},"items_count":2}""" else "null"
            return """{"items":[${items.drop(start).take(2).joinToString(",")}],"next_page_params":$next}"""
        }

        val get = { url: String ->
            requests += url
            val path = url.substringAfter("/api/v2/").substringBefore('?')
            when {
                path.endsWith("/transactions") -> page(transactions, url)
                path.endsWith("/internal-transactions") -> page(internal, url)
                path.endsWith("/token-transfers") -> page(tokens, url)
                path.endsWith("/tokens") ->
                    """{"items":[{"token":{"address_hash":"$usdc","type":"ERC-20","decimals":"6"},"token_id":null,"value":"2500000"},
                    |{"token":{"address_hash":"$fakeUsdc","type":"ERC-20","decimals":"6"},"token_id":null,"value":"1000000000"}],"next_page_params":null}
                    """.trimMargin()
                path == "addresses/$me" -> """{"hash":"$me","coin_balance":"$nativeBalance"}"""
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
            TrackingTarget(
                TrackingTargetId("evm"),
                AccountId("evm"),
                TargetIdentity(EVM_FAMILY, "address", me.uppercase().replace("0X", "0x")),
                TargetRole.LEAF,
                "EVM",
            ),
        )
        app.setEvmConsent(true)
        app.setMarketDataConsent(true)

        fun fixture(name: String) = requireNotNull(javaClass.getResource("/coingecko/$name")).readText()
        app.refreshEvmCatalog(
            { url, _ -> if (url.contains("asset_platforms")) fixture("asset_platforms.json") else fixture("coins_list.json") },
        )
        return app
    }

    @Test fun historyDecodesFeesFailuresTokensAndNftsAndSkipsWhenUnchanged() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            assertEquals(listOf(AccountId("evm") to me), app.evmTargets())
            val provider = FakeBlockscout()
            val connector = EvmConnector(provider.get) { setOf(chain) }
            assertEquals(
                listOf(JobState.COMPLETED),
                connector
                    .refresh(
                        app,
                        io.polion.core.application
                            .SourceRefreshOptions(),
                    ).jobs,
            )

            val activities =
                app.transactionEntries(ActivityFilter()).associateBy {
                    it.activity.id.value
                        .substringAfter("$chain:")
                        .substringBefore(':')
                }
            assertEquals(7, activities.size)
            // Received 3 ETH: no fee for the receiver.
            assertEquals(ActivityKind.DEPOSIT, activities.getValue(h(1)).activity.kind)
            // Sent 1 ETH and paid 500 wei of gas, as separate movements.
            val sent = activities.getValue(h(4)).activity.movements
            assertEquals(listOf("-1000000000000000000", "-500"), sent.map { it.amount.atomicUnits.toString() })
            assertEquals(MovementRole.FEE, sent.last().role)
            // A failed transaction moved nothing but still cost its gas.
            val failed = activities.getValue(h(5)).activity
            assertEquals(listOf("-300"), failed.movements.map { it.amount.atomicUnits.toString() })
            assertEquals(ActivityKind.PAYMENT, failed.kind)
            // Internal transfer from a contract, a uint256 NFT ID, and USDC with 6 decimals.
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
            // Native ETH and CoinGecko-known USDC are shown; the look-alike USDC and the NFT are hidden.
            assertEquals(
                setOf(EvmAssets.native(chain), EvmAssets.token(chain, usdc)),
                app
                    .snapshot()
                    .valuations
                    .map { it.asset }
                    .toSet(),
            )
            assertEquals(
                "ETH",
                app
                    .snapshot()
                    .valuations
                    .single { it.asset == EvmAssets.native(chain) }
                    .metadata
                    ?.symbol,
            )
            // Native balance: 3 ETH − 1 ETH − 500 − 300 (gas) + 250 (internal) equals the provider's balance, so the check passes.
            assertNull(app.evmChainStates(me).single().balanceDifference)
            assertEquals(setOf(EvmAssets.token(chain, fakeUsdc), EvmAssets.nft(chain, punk, evmQuantity(huge))), app.hiddenAssets())

            // Unchanged heads: three requests and nothing else.
            provider.requests.clear()
            connector.refresh(
                app,
                io.polion.core.application
                    .SourceRefreshOptions(),
            )
            assertEquals(3, provider.requests.size)

            // A reorganisation drops the newest transaction: it is superseded, and a wrong balance is reported.
            provider.transactions = provider.transactions.filterNot { it.contains(h(5)) }
            provider.nativeBalance = "999"
            connector.refresh(
                app,
                io.polion.core.application
                    .SourceRefreshOptions(),
            )
            val removed =
                app.transactionEntries(ActivityFilter()).single {
                    it.activity.id.value
                        .contains(h(5))
                }
            assertEquals(ActivityStatus.SUPERSEDED, removed.activity.status)
            assertTrue(
                app
                    .evmChainStates(me)
                    .single()
                    .balanceDifference!!
                    .contains("ledger 1999999999999999750, provider 999"),
            )
        }
    }

    @Test fun theRegistryRoutesEvmAddressesToTheEvmConnector() {
        val evm = EvmConnector({ error("not called") })
        val registry = SourceRegistry(listOf(BitcoinConnector(EsploraTransport { error("not called") }, DEFAULT_ENDPOINT), evm))
        assertSame(evm, registry.forNetwork(EVM_FAMILY))
    }
}
