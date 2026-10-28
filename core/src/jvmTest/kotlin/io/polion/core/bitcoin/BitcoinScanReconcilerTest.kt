package io.polion.core.bitcoin

import io.polion.core.connectors.HistoryRequest
import io.polion.core.ledger.ActivityRepository
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.LedgerDeltas
import io.polion.core.model.AccountId
import io.polion.core.model.ProviderId
import io.polion.core.model.SourceId
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class BitcoinScanReconcilerTest {
    @Test
    fun completeScansReverseMissingAndRestoreReappearingTransactions() {
        val path = Files.createTempFile("bitcoin-reconcile", ".sqlite")
        val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
        val account = AccountId("account")
        val owners = mapOf(address to account)
        val id = "a".repeat(64)

        fun observation(confirmed: Boolean) =
            EsploraHistoryFetcher(
                mapOf(account to address),
                EsploraTransport { endpoint ->
                    if (endpoint.endsWith("mempool") == !confirmed) {
                        """[{"txid":"$id","vin":[{"is_coinbase":true}],"vout":[{"scriptpubkey_address":"$address","value":250}],"fee":0,"status":{"confirmed":$confirmed${if (confirmed) ",\"block_time\":123" else ""}}}]"""
                    } else {
                        "[]"
                    }
                },
                { "2026-01-01T00:00:00Z" },
            ).fetch(HistoryRequest(SourceId("esplora"), ProviderId("esplora"), BITCOIN_MAINNET, account)).observations.single()
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                Store(db)
                val ledger = ActivityRepository(db)
                val reconciler = BitcoinScanReconciler(db, ledger)
                val confirmed = observation(true)
                val pending = observation(false)
                reconciler.applyComplete(listOf(confirmed), owners)
                assertEquals(
                    "250",
                    LedgerDeltas
                        .calculate(ledger.current())
                        .getValue(account)
                        .getValue(BITCOIN_ASSET)
                        .toString(),
                )
                assertFails { reconciler.applyComplete(listOf(confirmed, pending), owners) }
                assertEquals(ActivityStatus.CONFIRMED, ledger.current().single().status)
                reconciler.applyComplete(listOf(pending), owners)
                assertEquals(ActivityStatus.PENDING, ledger.current().single().status)
                db.execute(
                    "CREATE TRIGGER reject_reversal BEFORE INSERT ON ledger_activities WHEN NEW.status='REVERSED' BEGIN SELECT RAISE(ABORT, 'fixture'); END",
                )
                assertFails { reconciler.applyComplete(emptyList(), owners) }
                assertEquals(ActivityStatus.PENDING, ledger.current().single().status)
                db.execute("DROP TRIGGER reject_reversal")
                reconciler.applyComplete(emptyList(), owners)
                assertEquals(ActivityStatus.REVERSED, ledger.current().single().status)
                assertEquals(emptyMap(), LedgerDeltas.calculate(ledger.current()))
                reconciler.applyComplete(listOf(confirmed), owners)
                assertEquals(ActivityStatus.CONFIRMED, ledger.current().single().status)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
