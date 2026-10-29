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
import kotlin.test.assertTrue

class BitcoinMultiAddressAccountTest {
    private val addresses = listOf("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", "1BoatSLRHtKNngkdXEeobR76b53LETtpyT")
    private val account = AccountId("wallet")
    private val hash = "b".repeat(64)

    private fun application(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 15_552_000_000L }
        val owner = OwnerId("owner")
        app.saveOwner(Owner(owner, "Owner"))
        app.saveAccount(Account(account, owner, "Wallet", AccountKind.WALLET))
        addresses.forEachIndexed { index, address ->
            app.saveTarget(
                TrackingTarget(
                    TrackingTargetId("address-$index"),
                    account,
                    TargetIdentity(BITCOIN_MAINNET, "address", address),
                    TargetRole.LEAF,
                    "Address $index",
                ),
            )
        }
        app.setSourceConsent(true)
        return app
    }

    private inner class Provider(
        private val count: Int,
    ) {
        val historyCalls = mutableListOf<String>()
        var failAddress: String? = null

        private fun txid(
            address: String,
            index: Int,
        ): String = (addresses.indexOf(address) * 100 + index + 1).toString(16).padStart(64, '0')

        private fun transaction(
            address: String,
            index: Int,
        ): String {
            val value = (addresses.indexOf(address) + 1) * 100
            val output = """{"scriptpubkey_address":"$address","value":$value}"""
            val status = """{"confirmed":true,"block_time":1,"block_height":1,"block_hash":"$hash"}"""
            return """{"txid":"${txid(address, index)}","vin":[{"is_coinbase":true}],"vout":[$output],"fee":0,"status":$status}"""
        }

        val transport =
            EsploraTransport { path ->
                when {
                    path == "/blocks/tip/hash" || path.startsWith("/block-height/") -> hash
                    path == "/block/$hash" -> """{"id":"$hash","height":110}"""
                    path.startsWith("/address/") -> {
                        val address = path.removePrefix("/address/").substringBefore('/')
                        check(address in addresses)
                        when {
                            path.endsWith("/txs/mempool") -> "[]"
                            path.contains("/txs/chain") -> {
                                historyCalls.add(path)
                                if (address == failAddress) throw BitcoinProviderException(BitcoinProviderFailure.TIMEOUT)
                                val cursor = path.substringAfter("/txs/chain/", "")
                                val start = if (cursor.isEmpty()) 0 else (0 until count).single { txid(address, it) == cursor } + 1
                                (start until minOf(start + 25, count)).joinToString(",", "[", "]") { transaction(address, it) }
                            }
                            path.endsWith("/utxo") -> utxoResponse(address, (0 until count).map { transaction(address, it) })
                            else -> """{"chain_stats":{"tx_count":$count},"mempool_stats":{"tx_count":0}}"""
                        }
                    }
                    else -> error("Unexpected request $path")
                }
            }
    }

    private fun assertComplete(
        db: JvmSqliteDatabase,
        app: PortfolioApplication,
        count: Int,
    ) {
        assertEquals(
            (count * 300).toString(),
            app
                .snapshot()
                .balances.quantities.values
                .single()
                .toString(),
        )
        assertEquals((count * 2).toString(), db.query("SELECT COUNT(*) FROM ledger_activities").single().single())
        assertEquals((count * 2).toString(), db.query("SELECT COUNT(*) FROM raw_records").single().single())
        assertEquals(0, app.bitcoinUtxoReview().total)
        assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
    }

    @Test
    fun equalCountsUnderOneAccountFetchBothAddressesAndRepeatWithoutDuplicates() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = application(db)
            val provider = Provider(1)
            repeat(2) {
                assertEquals(listOf(JobState.COMPLETED, JobState.COMPLETED), app.refreshBitcoin(provider.transport))
                assertComplete(db, app, 1)
            }
            addresses.forEach { address ->
                assertEquals(2, provider.historyCalls.count { it == "/address/$address/txs/chain" })
            }
        }
    }

    @Test
    fun secondAddressFailureResumesWithItsOwnPaginationAfterApplicationRecreation() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = application(db)
            val provider = Provider(26)
            provider.failAddress = addresses[1]
            assertEquals(listOf(JobState.COMPLETED, JobState.READY), app.refreshBitcoin(provider.transport))
            assertTrue(
                app
                    .snapshot()
                    .balances.quantities
                    .isEmpty(),
            )
            provider.failAddress = null
            val resumed = PortfolioApplication(db) { 15_552_000_000L }
            assertEquals(listOf(JobState.COMPLETED, JobState.COMPLETED), resumed.refreshBitcoin(provider.transport))
            assertComplete(db, resumed, 26)
            addresses.forEach { address ->
                assertTrue(provider.historyCalls.any { it == "/address/$address/txs/chain" })
                assertTrue(provider.historyCalls.any { it.startsWith("/address/$address/txs/chain/") })
            }
            assertEquals(listOf(JobState.COMPLETED, JobState.COMPLETED), resumed.refreshBitcoin(provider.transport))
            assertComplete(db, resumed, 26)
        }
    }
}
