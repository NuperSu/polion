package io.polion.core.application

import io.polion.core.backup.PortableBackup
import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.bitcoin.BitcoinBlockAnchor
import io.polion.core.bitcoin.BitcoinGraphRepository
import io.polion.core.bitcoin.BitcoinStagedGraph
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BitcoinDeltaRefreshIntegrationTest {
    private val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val fundingId = "a".repeat(64)
    private val spendId = "b".repeat(64)
    private val scope = mapOf(address to AccountId("wallet"))

    private fun hash(height: Long): String = height.toString(16).padStart(64, '0')

    private fun application(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1_700_000_000_000L }
        val owner = OwnerId("owner")
        app.saveOwner(Owner(owner, "Owner"))
        app.saveAccount(Account(scope.getValue(address), owner, "Wallet", AccountKind.WALLET))
        app.saveTarget(
            TrackingTarget(
                TrackingTargetId("address"),
                scope.getValue(address),
                TargetIdentity(BITCOIN_MAINNET, "address", address),
                TargetRole.LEAF,
                "Address",
            ),
        )
        app.setSourceConsent(true)
        return app
    }

    private inner class Provider {
        var confirmed = false
        var wrongValue = false
        var spendAddress = address
        var reorganized = false
        var failUtxo = false
        val tip: Long get() = if (reorganized) 111 else 110
        private val funding: String get() =
            """{"txid":"$fundingId","vin":[{"txid":"${"0".repeat(
                64,
            )}","vout":0,"prevout":{"value":10000,"scriptpubkey_address":"external"}}],"vout":[{"value":10000,"scriptpubkey_address":"$address"}],"fee":0,"status":{"confirmed":true,"block_height":100,"block_hash":"${hash(
                100,
            )}","block_time":1}}"""
        private val status: String get() =
            if (confirmed) {
                """{"confirmed":true,"block_height":101,"block_hash":"${hash(101)}","block_time":101}"""
            } else {
                """{"confirmed":false}"""
            }
        private val spend: String get() =
            """{"txid":"$spendId","vin":[{"txid":"$fundingId","vout":0,"prevout":{"value":10000,"scriptpubkey_address":"$address"}}],"vout":[{"value":9000,"scriptpubkey_address":"$spendAddress"}],"fee":1000,"status":$status}"""
        val transport =
            EsploraTransport { path ->
                when {
                    path == "/blocks/tip/hash" -> hash(tip)
                    path == "/block/${hash(tip)}" -> """{"id":"${hash(tip)}","height":$tip}"""
                    path.startsWith("/block-height/") -> {
                        val height = path.substringAfterLast('/').toLong()
                        if (reorganized && height in 101L..110L) "f".repeat(64) else hash(height)
                    }
                    path == "/address/$address" ->
                        """{"chain_stats":{"tx_count":${if (confirmed) 2 else 1}},"mempool_stats":{"tx_count":${if (confirmed) 0 else 1}}}"""
                    path.endsWith("/txs/mempool") -> if (confirmed) "[]" else "[$spend]"
                    path.endsWith("/txs/chain") -> if (confirmed) "[$spend,$funding]" else "[$funding]"
                    path.contains("/txs/chain/") -> "[]"
                    path.endsWith("/utxo") -> {
                        check(!failUtxo) { "Injected UTXO failure" }
                        """[{"txid":"$spendId","vout":0,"value":${if (wrongValue) 9001 else 9000},"status":$status}]"""
                    }
                    else -> error("Unexpected request $path")
                }
            }
    }

    private fun count(
        db: JvmSqliteDatabase,
        table: String,
    ): String = db.query("SELECT COUNT(*) FROM $table").single().single()!!

    @Test
    fun productionRefreshSharesUnchangedBodiesAndPersistsChangedDiagnosticsAndBackup() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = application(db)
            val provider = Provider()
            repeat(2) { assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport)) }
            assertEquals("2", count(db, "bitcoin_graph_snapshots"))
            assertEquals("2", count(db, "bitcoin_graph_revisions"))
            assertEquals("2", count(db, "bitcoin_graph_membership"))
            assertEquals("0", count(db, "bitcoin_graph_transactions"))
            assertEquals(0, app.bitcoinUtxoReview().total)
            val repository = BitcoinGraphRepository(db)
            val first = assertNotNull(repository.snapshot(1))
            provider.confirmed = true
            provider.wrongValue = true
            app.refreshBitcoin(provider.transport)
            assertEquals("3", count(db, "bitcoin_graph_revisions"))
            assertEquals("3", count(db, "bitcoin_graph_membership"))
            assertEquals(
                null,
                first.graph.transactions
                    .getValue(spendId)
                    .block,
            )
            val latest = assertNotNull(repository.latest(scope))
            assertEquals(
                BitcoinBlockAnchor(101, hash(101)),
                latest.graph.transactions
                    .getValue(spendId)
                    .block,
            )
            val review = app.bitcoinUtxoReview(limit = 1)
            assertEquals(1, review.total)
            assertEquals("VALUE_DIFFERS", review.rows.single().kind)
            assertEquals(
                "9000",
                review.rows
                    .single()
                    .reconstructedValue
                    .toString(),
            )
            assertEquals(
                "9001",
                review.rows
                    .single()
                    .providerValue
                    .toString(),
            )
            assertEquals("UNSPENT", review.rows.single().reconstructedStatus)
            assertEquals(1, app.snapshot().sourceStatus.utxoMismatchCount)
            BitcoinStagedGraph.SCRATCH_TABLES.forEach { assertEquals("0", count(db, it)) }
            val archive = PortableBackup.discover(db).export(db)
            JvmSqliteDatabase(":memory:").use { restored ->
                val reopened = PortfolioApplication(restored) { 1_700_000_000_000L }
                PortableBackup.discover(restored).restoreToStaging(restored, archive)
                assertEquals(review, reopened.bitcoinUtxoReview(limit = 1))
                assertEquals(first.graph.ownedOutputs, BitcoinGraphRepository(restored).snapshot(1)!!.graph.ownedOutputs)
            }
        }
    }

    @Test
    fun providerReportsWatchedAddressForUnownedOutputWithoutBorrowingItsGraphContext() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = application(db)
            val provider = Provider().also { it.spendAddress = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT" }
            app.refreshBitcoin(provider.transport)
            val row = app.bitcoinUtxoReview().rows.single()
            assertEquals("MISSING_FROM_RECONSTRUCTION", row.kind)
            assertEquals(scope.getValue(address), row.account)
            assertEquals(address, row.address)
            assertEquals(address, row.providerAddress)
            assertEquals("9000", row.providerValue.toString())
            assertEquals(null, row.reconstructedValue)
            assertEquals(null, row.reconstructedStatus)
            assertEquals(null, row.reconstructedBlock)
        }
    }

    @Test
    fun reorganizationUsesDeltaAnchorsAndFailedReplacementPreservesAcceptedState() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = application(db)
            val provider = Provider().also { it.confirmed = true }
            app.refreshBitcoin(provider.transport)
            val repository = BitcoinGraphRepository(db)
            assertTrue(BitcoinBlockAnchor(100, hash(100)) in repository.anchors(scope))
            provider.reorganized = true
            provider.confirmed = false
            provider.failUtxo = true
            assertFails { app.refreshBitcoin(provider.transport) }
            assertEquals("1", count(db, "bitcoin_graph_snapshots"))
            assertEquals(
                BitcoinBlockAnchor(101, hash(101)),
                repository
                    .latest()!!
                    .graph.transactions
                    .getValue(spendId)
                    .block,
            )
            provider.failUtxo = false
            app.refreshBitcoin(provider.transport)
            assertEquals("2", count(db, "bitcoin_graph_snapshots"))
            assertEquals(
                null,
                repository
                    .latest()!!
                    .graph.transactions
                    .getValue(spendId)
                    .block,
            )
            assertEquals(0, app.bitcoinUtxoReview().total)
        }
    }
}
