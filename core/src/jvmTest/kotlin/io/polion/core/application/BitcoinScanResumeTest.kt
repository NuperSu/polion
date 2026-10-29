package io.polion.core.application

import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.bitcoin.BitcoinProviderFailure
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
import io.polion.core.sync.JobState
import io.polion.testing.utxoResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BitcoinScanResumeTest {
    private val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val hash = "b".repeat(64)

    private fun setUp(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 15552000000L }
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
        app.setSourceEndpoint("https://example.org/api")
        return app
    }

    private fun countRows(
        db: JvmSqliteDatabase,
        table: String,
    ): Int =
        db
            .query("SELECT COUNT(*) FROM $table")
            .single()
            .single()!!
            .toInt()

    private fun tx(index: Int): String {
        val txid = index.toString(16).padStart(64, '0')
        val vout = """[{"scriptpubkey_address":"$address","value":100}]"""
        val status = """{"confirmed":true,"block_time":1,"block_height":1,"block_hash":"$hash"}"""
        return """{"txid":"$txid","vin":[{"is_coinbase":true}],"vout":$vout,"fee":0,"status":$status}"""
    }

    /** Serves a fixed chain of transactions in pages of 25, newest-first by index; each page cursor is the last txid served. */
    private class ChainProvider(
        private val address: String,
        private val hash: String,
        private val totalTransactions: Int,
        private val tx: (Int) -> String,
    ) {
        var chainRequests = 0
        var failChainRequest: Int? = null

        val transport =
            EsploraTransport { path ->
                when {
                    path == "/blocks/tip/hash" || path == "/block-height/1" -> hash
                    path == "/block/$hash" -> """{"id":"$hash","height":1}"""
                    path.endsWith("/txs/mempool") -> "[]"
                    path.endsWith("/utxo") -> utxoResponse(address, (0 until totalTransactions).map(tx))
                    path.contains("/txs/chain") -> {
                        chainRequests++
                        if (failChainRequest == chainRequests) throw BitcoinProviderException(BitcoinProviderFailure.TIMEOUT)
                        val cursor = path.substringAfter("/txs/chain/", "").takeIf { it.isNotEmpty() }
                        val startIndex = cursor?.let { it.trimStart('0').toInt(16) + 1 } ?: 0
                        val count = minOf(25, totalTransactions - startIndex)
                        if (count <= 0) "[]" else (startIndex until startIndex + count).joinToString(",", "[", "]", transform = tx)
                    }
                    else -> """{"chain_stats":{"tx_count":$totalTransactions},"mempool_stats":{"tx_count":0}}"""
                }
            }
    }

    @Test fun transientFailureIsRetriedWithoutTheBackoffBlockingTheUserRetry() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider(address, hash, totalTransactions = 75) { tx(it) }
            provider.failChainRequest = 3
            assertEquals(listOf(JobState.READY), app.refreshBitcoin(provider.transport))
            provider.failChainRequest = null
            provider.chainRequests = 0
            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))
            assertEquals(75, countRows(db, "ledger_activities WHERE status='CONFIRMED'"))
        }
    }

    @Test fun changedTransactionFailsTheScanAndDiscardsItsStage() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val first = ChainProvider(address, hash, totalTransactions = 75) { tx(it) }
            first.failChainRequest = 3
            app.refreshBitcoin(first.transport)
            val changed =
                ChainProvider(address, hash, totalTransactions = 75) { tx(it).replace("\"value\":100", "\"value\":101") }
            assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(changed.transport) }
            assertEquals(
                0,
                db
                    .query("SELECT COUNT(*) FROM bitcoin_scan_stage")
                    .single()
                    .single()!!
                    .toInt(),
            )
            assertEquals(
                0,
                db
                    .query("SELECT COUNT(*) FROM ledger_activities")
                    .single()
                    .single()!!
                    .toInt(),
            )
        }
    }

    @Test fun scanCompletesPastThousandChainPages() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider(address, hash, totalTransactions = 25 * 1001) { tx(it) }
            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))
            assertEquals(false, app.snapshot().sourceStatus.interrupted)
        }
    }
}
