package io.polion.core.application

import io.polion.core.backup.PortableBackup
import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.bitcoin.BitcoinBlockAnchor
import io.polion.core.bitcoin.BitcoinGraphRepository
import io.polion.core.bitcoin.BitcoinProviderException
import io.polion.core.bitcoin.BitcoinProviderFailure
import io.polion.core.bitcoin.BitcoinScanStage
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BitcoinChainAcceptanceTest {
    private val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val txid = "a".repeat(64)

    private fun blockHash(height: Long): String = height.toString(16).padStart(64, '0')

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

    private inner class ChainProvider {
        var tipHeight = 110L
        val changedBlocks = mutableMapOf<Long, String>()
        val calls = mutableListOf<String>()

        private fun canonical(height: Long): String = changedBlocks[height] ?: blockHash(height)

        /** A second confirmed transaction in block 105 that the provider reports once [extraTransaction] is set. */
        var extraTransaction = false

        /** An unconfirmed transaction paying the address that the provider reports once [pendingTransaction] is set. */
        var pendingTransaction = false

        private fun transaction(
            id: String,
            height: Long,
            value: Long,
        ): String =
            """
            {"txid":"$id","vin":[{"is_coinbase":true}],"vout":[{"scriptpubkey_address":"$address","value":$value}],"fee":0,
            "status":{"confirmed":true,"block_time":1,"block_height":$height,"block_hash":"${blockHash(height)}"}}
            """.trimIndent().replace("\n", "")

        private fun funding(): String = transaction(txid, 100, 1000)

        private fun chainPayloads(): List<String> {
            val later = transaction("c".repeat(64), 105, 500).takeIf { extraTransaction }
            return listOfNotNull(later, funding())
        }

        private fun pending(): String =
            """
            {"txid":"${"d".repeat(64)}","vin":[{"txid":"${"e".repeat(64)}","vout":0,
            "prevout":{"scriptpubkey_address":"1BoatSLRHtKNngkdXEeobR76b53LETtpyT","value":1000}}],
            "vout":[{"scriptpubkey_address":"$address","value":250}],"fee":750,"status":{"confirmed":false}}
            """.trimIndent().replace("\n", "")

        val transport =
            EsploraTransport { path ->
                calls.add(path)
                when {
                    path == "/blocks/tip/hash" -> canonical(tipHeight)
                    path == "/block/${canonical(tipHeight)}" -> """{"id":"${canonical(tipHeight)}","height":$tipHeight}"""
                    path.startsWith("/block-height/") -> canonical(path.removePrefix("/block-height/").toLong())
                    path == "/address/$address" ->
                        """{"chain_stats":{"tx_count":${if (extraTransaction) 2 else 1}},"mempool_stats":{"tx_count":${if (pendingTransaction) 1 else 0}}}"""
                    path == "/address/$address/txs/mempool" -> if (pendingTransaction) "[${pending()}]" else "[]"
                    path == "/address/$address/txs/chain" -> "[${chainPayloads().joinToString(",")}]"
                    path == "/address/$address/txs/chain/$txid" -> "[]"
                    path == "/address/$address/utxo" -> utxoResponse(address, chainPayloads())
                    else -> error("Unexpected provider request: $path")
                }
            }
    }

    private fun acceptedData(db: JvmSqliteDatabase): Map<String, List<List<String?>>> =
        listOf(
            "ledger_activities",
            "ledger_movements",
            "ledger_sources",
            "raw_records",
            "raw_revisions",
            "raw_observations",
            "bitcoin_graph_snapshots",
            "bitcoin_graph_scope",
            "bitcoin_graph_transactions",
            "bitcoin_graph_outputs",
            "bitcoin_graph_owned_outputs",
        ).associateWith { table -> db.query("SELECT * FROM $table ORDER BY rowid") }

    /**
     * The accepted ledger and graph. Raw provider observations are evidence that a scan stores before it is applied, so a
     * rejected scan may add them while the accepted state stays unchanged.
     */
    private fun acceptedLedgerAndGraph(db: JvmSqliteDatabase) = acceptedData(db).filterKeys { !it.startsWith("raw_") }

    private fun initialScan(
        app: PortfolioApplication,
        db: JvmSqliteDatabase,
        provider: ChainProvider,
    ) {
        assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))
        assertEquals(1, app.snapshot().activityTotal)
        val saved = assertNotNull(BitcoinGraphRepository(db).latest())
        assertEquals(BitcoinBlockAnchor(110, blockHash(110)), saved.anchor)
        assertEquals(
            BitcoinBlockAnchor(100, blockHash(100)),
            saved.graph.transactions
                .getValue(txid)
                .block,
        )
        provider.calls.clear()
    }

    @Test
    fun unchangedProviderSkipsHistoryAndLeavesAcceptedDataIdentical() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            val before = acceptedData(db)

            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))

            assertTrue(app.lastRefreshSkipped())
            assertEquals(before, acceptedData(db))
            assertEquals(1, app.snapshot().activityTotal)
            assertNotNull(app.sourceLastSuccessMillis())
            assertEquals(listOf("/address/$address"), provider.calls.filter { it.startsWith("/address/") })
            assertTrue(provider.calls.none { it.endsWith("/txs/chain") || it.endsWith("/utxo") }, provider.calls.toString())
        }
    }

    @Test
    fun newTransactionForcesFullScanThatAppliesIt() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            provider.extraTransaction = true

            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))

            assertFalse(app.lastRefreshSkipped())
            assertTrue("/address/$address/txs/chain" in provider.calls, provider.calls.toString())
            assertEquals(2, app.snapshot().activityTotal)
        }
    }

    @Test
    fun pendingTransactionAtLastScanForcesFullScanOnNextRefresh() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            provider.pendingTransaction = true
            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))
            provider.calls.clear()

            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))

            assertFalse(app.lastRefreshSkipped())
            assertTrue("/address/$address/txs/chain" in provider.calls, provider.calls.toString())
        }
    }

    @Test
    fun changedProviderOrRecoveryForcesFullScan() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)

            app.setSourceEndpoint("https://other.example/api")
            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))
            assertFalse(app.lastRefreshSkipped())
            assertTrue("/address/$address/txs/chain" in provider.calls, provider.calls.toString())

            provider.calls.clear()
            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport, recoverHistory = true))
            assertFalse(app.lastRefreshSkipped())
            assertTrue("/address/$address/txs/chain" in provider.calls, provider.calls.toString())
        }
    }

    @Test
    fun shallowReorganizationWithProvenAncestorAcceptsReplacementSnapshot() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            provider.tipHeight = 111
            provider.changedBlocks[110] = "b".repeat(64)

            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))
            val repository = BitcoinGraphRepository(db)
            assertEquals(2, repository.snapshots().size)
            assertEquals(BitcoinBlockAnchor(111, blockHash(111)), repository.latest()?.anchor)
            assertEquals(1, app.snapshot().activityTotal)
            assertTrue("/block-height/100" in provider.calls)
        }
    }

    @Test
    fun deepReorganizationPreservesAcceptedLedgerAndGraphSnapshot() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            val before = acceptedData(db)
            provider.tipHeight = 250
            provider.changedBlocks[110] = "b".repeat(64)

            val failure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(provider.transport) }
            assertEquals(BitcoinProviderFailure.REORG_RECOVERY_REQUIRED, failure.reason)
            assertEquals(before, acceptedData(db))
            assertEquals(1, app.snapshot().activityTotal)
            assertTrue("/block-height/100" !in provider.calls)
        }
    }

    @Test
    fun explicitDeepRecoveryEstablishesBaselineAndAllowsNormalRefresh() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            provider.tipHeight = 250
            provider.changedBlocks[110] = "b".repeat(64)

            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport, recoverHistory = true))
            val repository = BitcoinGraphRepository(db)
            assertEquals(2, repository.snapshots().size)
            assertEquals(BitcoinBlockAnchor(110, blockHash(110)), repository.snapshot(1)?.anchor)
            assertEquals(listOf(250L, 100L), repository.anchors().map { it.height })
            provider.calls.clear()
            provider.tipHeight = 251
            provider.extraTransaction = true
            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))
            assertTrue("/block-height/110" !in provider.calls)
            assertTrue("/block-height/250" in provider.calls)
            assertEquals(3, repository.snapshots().size)
        }
    }

    @Test
    fun failedRecoveryRetainsAcceptedDataAndDoesNotAdvanceBaseline() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            val before = acceptedData(db)
            provider.tipHeight = 250
            provider.changedBlocks[110] = "b".repeat(64)
            val inconsistent =
                EsploraTransport { path ->
                    if (path == "/address/$address") {
                        """{"chain_stats":{"tx_count":2},"mempool_stats":{"tx_count":0}}"""
                    } else {
                        provider.transport.get(path)
                    }
                }
            val failure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(inconsistent, recoverHistory = true) }
            assertEquals(BitcoinProviderFailure.HISTORY_CHANGED, failure.reason)
            assertEquals(before, acceptedData(db))
            assertTrue(db.query("SELECT value FROM application_settings WHERE key LIKE 'bitcoin_graph_baseline:%'").isEmpty())
            val normalFailure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(provider.transport) }
            assertEquals(BitcoinProviderFailure.REORG_RECOVERY_REQUIRED, normalFailure.reason)
        }
    }

    @Test
    fun recoveryDiscardsCompletedScratchHistoryFromAnInterruptedAttempt() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            provider.tipHeight = 250
            provider.changedBlocks[110] = "b".repeat(64)
            val unavailable =
                EsploraTransport { path ->
                    if (path == "/address/$address/utxo") throw BitcoinProviderException(BitcoinProviderFailure.OFFLINE)
                    provider.transport.get(path)
                }
            assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(unavailable, recoverHistory = true) }
            assertEquals(1, BitcoinGraphRepository(db).snapshots().size)
            provider.calls.clear()
            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport, recoverHistory = true))
            assertTrue("/address/$address/txs/chain" in provider.calls)
            assertTrue("/address/$address/txs/mempool" in provider.calls)
            assertEquals(2, BitcoinGraphRepository(db).snapshots().size)
        }
    }

    @Test
    fun recoveryRetainsBlockAndFinalTipValidation() {
        for (movingTip in listOf(false, true)) {
            JvmSqliteDatabase(":memory:").use { db ->
                val app = setUp(db)
                val provider = ChainProvider()
                initialScan(app, db, provider)
                val before = acceptedData(db)
                provider.tipHeight = 250
                provider.changedBlocks[110] = "b".repeat(64)
                if (!movingTip) provider.changedBlocks[100] = "c".repeat(64)
                val inconsistent =
                    EsploraTransport { path ->
                        provider.transport.get(path).also {
                            if (movingTip && path == "/block-height/100") provider.changedBlocks[250] = "d".repeat(64)
                        }
                    }
                val failure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(inconsistent, recoverHistory = true) }
                assertEquals(BitcoinProviderFailure.REORGANIZED, failure.reason)
                assertEquals(before, acceptedData(db))
                assertTrue(db.query("SELECT value FROM application_settings WHERE key LIKE 'bitcoin_graph_baseline:%'").isEmpty())
            }
        }
    }

    @Test
    fun lowerRecoveryBaselineSurvivesPortableBackupAndNormalRefresh() {
        val archive =
            JvmSqliteDatabase(":memory:").use { db ->
                val app = setUp(db)
                val provider = ChainProvider()
                initialScan(app, db, provider)
                provider.tipHeight = 109
                assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport, recoverHistory = true))
                assertEquals(listOf(109L, 100L), BitcoinGraphRepository(db).anchors().map { it.height })
                assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))
                PortableBackup.discover(db).export(db)
            }
        JvmSqliteDatabase(":memory:").use { restored ->
            PortfolioApplication(restored) { 15552000000L }
            PortableBackup.discover(restored).restoreToStaging(restored, archive)
            val app = PortfolioApplication(restored) { 15552000000L }
            val provider =
                ChainProvider().apply {
                    tipHeight = 109
                    extraTransaction = true
                }
            assertEquals(listOf(109L, 100L), BitcoinGraphRepository(restored).anchors().map { it.height })
            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))
            // The restored snapshot count is 2: the source's no-change refresh skipped and recorded no snapshot.
            assertEquals(3, BitcoinGraphRepository(restored).snapshots().size)
            assertEquals(2, app.snapshot().activityTotal)
        }
    }

    @Test
    fun staleAnchorsWithoutProvenAncestorPreserveAcceptedData() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            val before = acceptedData(db)
            provider.tipHeight = 111
            provider.changedBlocks[110] = "b".repeat(64)
            provider.changedBlocks[100] = "c".repeat(64)

            val failure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(provider.transport) }
            assertEquals(BitcoinProviderFailure.REORG_RECOVERY_REQUIRED, failure.reason)
            assertEquals(before, acceptedData(db))
        }
    }

    @Test
    fun laggingProviderPreservesAcceptedLedgerAndGraphSnapshot() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            val before = acceptedData(db)
            provider.tipHeight = 109

            val failure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(provider.transport) }
            assertEquals(BitcoinProviderFailure.LAGGING_CHAIN, failure.reason)
            assertEquals(before, acceptedData(db))
            assertTrue(provider.calls.none { it.startsWith("/address/") })
        }
    }

    @Test
    fun longDowntimeAcceptsCanonicalSavedCheckpointBeyondRewindBound() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            provider.tipHeight = 500
            provider.extraTransaction = true

            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))
            val repository = BitcoinGraphRepository(db)
            assertEquals(2, repository.snapshots().size)
            assertEquals(BitcoinBlockAnchor(500, blockHash(500)), repository.latest()?.anchor)
            assertEquals(2, app.snapshot().activityTotal)
            assertTrue("/block-height/110" in provider.calls)
        }
    }

    @Test
    fun reorganizationDuringBlockValidationCannotCommitStaleCheckpoint() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            val before = acceptedLedgerAndGraph(db)
            provider.tipHeight = 111
            provider.extraTransaction = true
            val movingChain =
                EsploraTransport { path ->
                    provider.transport.get(path).also {
                        if (path == "/block-height/100") provider.changedBlocks[111] = "d".repeat(64)
                    }
                }
            val failure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(movingChain) }
            assertEquals(BitcoinProviderFailure.REORGANIZED, failure.reason)
            assertEquals(before, acceptedLedgerAndGraph(db))
            assertEquals(1, BitcoinGraphRepository(db).snapshots().size)
        }
    }

    @Test
    fun orphanedInterruptedSessionIsDiscardedAndNextRetryAcceptsFreshHistory() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            val before = acceptedLedgerAndGraph(db)
            provider.tipHeight = 111
            provider.extraTransaction = true
            val interrupted =
                EsploraTransport { path ->
                    if (path.endsWith("/utxo")) throw BitcoinProviderException(BitcoinProviderFailure.RATE_LIMITED)
                    provider.transport.get(path)
                }
            val interruptedFailure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(interrupted) }
            assertEquals(BitcoinProviderFailure.RATE_LIMITED, interruptedFailure.reason)
            assertEquals("1", db.query("SELECT COUNT(*) FROM bitcoin_scan_session").single().single())
            assertEquals("2", db.query("SELECT COUNT(*) FROM bitcoin_scan_validated_blocks").single().single())
            assertEquals(before, acceptedLedgerAndGraph(db))

            provider.tipHeight = 112
            provider.changedBlocks[111] = "e".repeat(64)
            val reorgFailure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(provider.transport) }
            assertEquals(BitcoinProviderFailure.REORGANIZED, reorgFailure.reason)
            assertEquals(before, acceptedLedgerAndGraph(db))
            BitcoinScanStage.SCRATCH_TABLES.forEach { table ->
                assertEquals("0", db.query("SELECT COUNT(*) FROM $table").single().single())
            }

            provider.calls.clear()
            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))
            assertTrue("/address/$address/txs/chain" in provider.calls)
            assertEquals(BitcoinBlockAnchor(112, blockHash(112)), BitcoinGraphRepository(db).latest()?.anchor)
            assertEquals(2, app.snapshot().activityTotal)
        }
    }

    @Test
    fun unverifiedPositionScanRejectsNoncanonicalTransactionBlockBeforeApply() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            val before = acceptedLedgerAndGraph(db)
            provider.tipHeight = 111
            provider.changedBlocks[100] = "c".repeat(64)
            provider.extraTransaction = true

            val failure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(provider.transport, verifyBlockPositions = false) }
            assertEquals(BitcoinProviderFailure.REORGANIZED, failure.reason)
            assertEquals(before, acceptedLedgerAndGraph(db))
            assertTrue("/block-height/100" in provider.calls)
        }
    }

    @Test
    fun endpointChangeDuringInitialTipRequestCannotAttributeOldResponseToNewProvider() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            val before = acceptedData(db)
            val endpoints = app.bitcoinProviderEndpoints()
            val changingEndpoint =
                EsploraTransport { path ->
                    provider.transport.get(path).also {
                        if (path == "/blocks/tip/hash") app.setSourceEndpoint("https://different.example/api")
                    }
                }
            val failure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(changingEndpoint) }
            assertEquals(BitcoinProviderFailure.HISTORY_CHANGED, failure.reason)
            assertEquals(listOf("/blocks/tip/hash"), provider.calls)
            assertEquals(before, acceptedData(db))
            assertEquals(endpoints, app.bitcoinProviderEndpoints())
            BitcoinScanStage.SCRATCH_TABLES.forEach { table ->
                assertEquals("0", db.query("SELECT COUNT(*) FROM $table").single().single())
            }
        }
    }

    @Test
    fun endpointChangeAfterBlockVerificationPreservesAcceptedDataAndDiscardsOldStage() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            val before = acceptedLedgerAndGraph(db)
            val endpoints = app.bitcoinProviderEndpoints()
            provider.tipHeight = 111
            provider.extraTransaction = true
            val changingEndpoint =
                EsploraTransport { path ->
                    provider.transport.get(path).also {
                        if (path.endsWith("/utxo")) app.setSourceEndpoint("https://different.example/api")
                    }
                }
            val failure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(changingEndpoint) }
            assertEquals(BitcoinProviderFailure.HISTORY_CHANGED, failure.reason)
            assertEquals(before, acceptedLedgerAndGraph(db))
            assertEquals(endpoints, app.bitcoinProviderEndpoints())
            BitcoinScanStage.SCRATCH_TABLES.forEach { table ->
                assertEquals("0", db.query("SELECT COUNT(*) FROM $table").single().single())
            }

            provider.calls.clear()
            assertEquals(listOf(JobState.COMPLETED), app.refreshBitcoin(provider.transport))
            assertTrue("/address/$address/txs/chain" in provider.calls)
            assertEquals(2, app.bitcoinProviderEndpoints().size)
        }
    }

    @Test
    fun precreatedTransportEndpointMismatchCannotResumePendingOldProviderSession() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            val before = acceptedLedgerAndGraph(db)
            val originalEndpoint = app.sourceEndpoint()
            provider.tipHeight = 111
            provider.extraTransaction = true
            val interrupted =
                EsploraTransport { path ->
                    if (path.endsWith("/utxo")) throw BitcoinProviderException(BitcoinProviderFailure.RATE_LIMITED)
                    provider.transport.get(path)
                }
            assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(interrupted) }
            assertEquals("1", db.query("SELECT COUNT(*) FROM bitcoin_scan_validation").single().single())
            app.setSourceEndpoint("https://different.example/api")
            provider.calls.clear()

            val failure =
                assertFailsWith<BitcoinProviderException> {
                    app.refreshBitcoin(provider.transport, providerEndpoint = originalEndpoint)
                }
            assertEquals(BitcoinProviderFailure.HISTORY_CHANGED, failure.reason)
            assertEquals(emptyList(), provider.calls)
            assertEquals(before, acceptedLedgerAndGraph(db))
            BitcoinScanStage.SCRATCH_TABLES.forEach { table ->
                assertEquals("0", db.query("SELECT COUNT(*) FROM $table").single().single())
            }
        }
    }

    @Test
    fun changedWatchedScopeCannotCommitAnInFlightScan() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = ChainProvider()
            initialScan(app, db, provider)
            val before = acceptedLedgerAndGraph(db)
            provider.tipHeight = 111
            provider.extraTransaction = true
            val changingScope =
                EsploraTransport { path ->
                    provider.transport.get(path).also {
                        if (path.endsWith("/utxo")) app.archiveAccount(AccountId("account"))
                    }
                }
            val failure = assertFailsWith<BitcoinProviderException> { app.refreshBitcoin(changingScope) }
            assertEquals(BitcoinProviderFailure.HISTORY_CHANGED, failure.reason)
            assertEquals(before, acceptedLedgerAndGraph(db))
            assertEquals(1, BitcoinGraphRepository(db).snapshots().size)
            BitcoinScanStage.SCRATCH_TABLES.forEach { table ->
                assertEquals("0", db.query("SELECT COUNT(*) FROM $table").single().single())
            }
        }
    }
}
