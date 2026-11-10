package io.valroot.desktop

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.bitcoin.BitcoinProviderException
import io.valroot.core.bitcoin.BitcoinProviderFailure
import io.valroot.core.bitcoin.EsploraTransport
import io.valroot.core.model.AccountId
import io.valroot.core.model.OwnerId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.ui.SyncPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWalletDiscoveryTest {
    private val walletId = TrackingTargetId("wallet")

    private fun walletApplication(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1000L }
        val owner = Owner(OwnerId("owner"), "Personal")
        val account = Account(AccountId("account"), owner.id, "Savings", AccountKind.WALLET)
        app.saveBitcoinWallet(
            TrackingTarget(
                walletId,
                account.id,
                TargetIdentity(BITCOIN_MAINNET, "wallet", "addr(1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa)"),
                TargetRole.WALLET,
                "Savings",
            ),
            account,
            owner,
            consentAccepted = true,
        )
        return app
    }

    @Test
    fun discoveryPreservesRateLimitAndOfflineGuidanceWithoutReportingSuccess() {
        for (reason in listOf(BitcoinProviderFailure.RATE_LIMITED, BitcoinProviderFailure.OFFLINE)) {
            JvmSqliteDatabase(":memory:").use { db ->
                val app = walletApplication(db)
                val initial = app.snapshot().ownership
                val progress = mutableListOf<String>()
                val status =
                    discoverWalletStatus(
                        app,
                        walletId,
                        EsploraTransport { throw BitcoinProviderException(reason) },
                        onProgress = progress::add,
                    )
                assertEquals(SyncPhase.ERROR, status.phase)
                assertEquals(reason.userMessage, status.message)
                assertEquals(initial, app.snapshot().ownership)
                assertTrue(progress.isEmpty())
                assertEquals(null, app.snapshot().sourceStatus.lastSuccessfulRefresh)
            }
        }
    }

    @Test
    fun answeredDiscoveryQueriesAreVisibleBeforeHistoryFailure() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = walletApplication(db)
            val progress = mutableListOf<String>()
            var queries = 0
            val status =
                discoverWalletStatus(
                    app,
                    walletId,
                    EsploraTransport { path ->
                        queries++
                        if (queries == 1) {
                            assertTrue(path.startsWith("/address/"))
                            """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}"""
                        } else {
                            throw BitcoinProviderException(BitcoinProviderFailure.TIMEOUT)
                        }
                    },
                    onProgress = progress::add,
                )
            assertEquals("Discovering wallet · address queries answered: 1", progress.first())
            assertEquals(SyncPhase.ERROR, status.phase)
            assertEquals(BitcoinProviderFailure.TIMEOUT.userMessage, status.message)
            assertTrue(
                app
                    .snapshot()
                    .ownership.targets
                    .any { it.walletId == walletId },
            )
            assertFalse(progress.any { it.contains("complete", ignoreCase = true) })
            assertEquals(null, app.snapshot().sourceStatus.lastSuccessfulRefresh)
        }
    }

    @Test
    fun successfulDiscoveryAndHistoryExposeBothStages() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = walletApplication(db)
            val progress = mutableListOf<String>()
            val status =
                discoverWalletStatus(
                    app,
                    walletId,
                    EsploraTransport { path ->
                        when {
                            path == "/blocks/tip/hash" || path.startsWith("/block-height/") -> "a".repeat(64)
                            path.startsWith("/block/") -> """{"id":"${"a".repeat(64)}","height":900000}"""
                            path.endsWith("/txs/mempool") || path.endsWith("/txs/chain") || path.endsWith("/utxo") -> "[]"
                            else -> """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}"""
                        }
                    },
                    onProgress = progress::add,
                )
            assertEquals(SyncPhase.SUCCESS, status.phase)
            assertTrue(status.message.contains("coverage remains unverified"))
            assertEquals("Discovering wallet · address queries answered: 1", progress.first())
            assertTrue(progress.any { it.startsWith("Address 1/1") })
            assertEquals(1000L, app.snapshot().sourceStatus.lastSuccessfulRefresh)
        }
    }

    @Test
    fun discoveryKeepsCapturedEndpointWhenSettingsChangeBeforeHistoryStarts() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = walletApplication(db)
            val endpoint = app.sourceEndpoint()
            var calls = 0
            val status =
                discoverWalletStatus(
                    app,
                    walletId,
                    EsploraTransport {
                        calls++
                        """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}"""
                    },
                    providerEndpoint = endpoint,
                ) {
                    app.setSourceEndpoint("https://replacement.example/api")
                }
            assertEquals(SyncPhase.ERROR, status.phase)
            assertEquals(BitcoinProviderFailure.HISTORY_CHANGED.userMessage, status.message)
            assertEquals(1, calls)
            assertTrue(app.sourceConsent())
            assertEquals("https://replacement.example/api", app.sourceEndpoint())
            assertEquals(null, app.snapshot().sourceStatus.lastSuccessfulRefresh)
        }
    }

    @Test
    fun unexpectedFailureUsesFixedGuidance() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = walletApplication(db)
            val status = discoverWalletStatus(app, walletId, EsploraTransport { error("private provider response") }) {}
            assertEquals(SyncPhase.ERROR, status.phase)
            assertEquals("Wallet discovery incomplete. Check consent, provider and discovery limits; retry.", status.message)
        }
    }
}
