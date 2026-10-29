package io.polion.core.application

import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.bitcoin.EsploraTransport
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
import io.polion.testing.utxoResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderProvenanceTest {
    @Test fun endpointSwitchRetainsSeparateObservationsAtIdenticalRetrievalTime() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
            val owner = OwnerId("owner")
            val account = AccountId("account")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.WALLET))
            app.saveTarget(
                TrackingTarget(
                    TrackingTargetId("target"),
                    account,
                    TargetIdentity(BITCOIN_MAINNET, "address", address),
                    TargetRole.LEAF,
                    "Address",
                ),
            )
            app.setSourceConsent(true)
            val txid = "a".repeat(64)
            val hash = "b".repeat(64)
            val payload =
                """
                {"txid":"$txid","vin":[{"is_coinbase":true}],
                "vout":[{"scriptpubkey_address":"$address","value":100}],
                "fee":0,"status":{"confirmed":true,"block_time":1,
                "block_height":1,"block_hash":"$hash"}}
                """.trimIndent()
            val transport =
                EsploraTransport { path ->
                    when {
                        path == "/blocks/tip/hash" || path == "/block-height/1" -> hash
                        path == "/block/$hash" -> """{"id":"$hash","height":1}"""
                        path.endsWith("/txs/mempool") -> "[]"
                        path.endsWith("/txs/chain") -> "[$payload]"
                        path.endsWith("/utxo") -> utxoResponse(address, listOf(payload))
                        else -> """{"chain_stats":{"tx_count":1},"mempool_stats":{"tx_count":0}}"""
                    }
                }
            for (endpoint in listOf("https://first.example/api", "https://second.example/api")) {
                app.setSourceEndpoint(endpoint)
                app.refreshBitcoin(transport)
            }
            assertEquals(1, app.snapshot().activityTotal)
            val sources = db.query("SELECT DISTINCT source_id FROM raw_observations").map { it.single()!! }
            assertEquals(2, sources.size)
            assertTrue(sources.all { it in app.bitcoinProviderEndpoints() })
            assertEquals(setOf("https://first.example/api", "https://second.example/api"), app.bitcoinProviderEndpoints().values.toSet())
            val archive = app.exportBackup("password".toCharArray())
            JvmSqliteDatabase(":memory:").use { staging ->
                app.restoreToStaging(staging, archive, "password".toCharArray())
                assertEquals(app.bitcoinProviderEndpoints(), PortfolioApplication(staging) { 15552000000L }.bitcoinProviderEndpoints())
                assertEquals(2, staging.query("SELECT DISTINCT source_id FROM raw_observations").size)
            }
        }
    }
}
