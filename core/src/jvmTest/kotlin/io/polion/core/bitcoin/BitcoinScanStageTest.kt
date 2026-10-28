package io.polion.core.bitcoin

import io.polion.core.connectors.HistoryRequest
import io.polion.core.ledger.ActivityRepository
import io.polion.core.ledger.ActivityStatus
import io.polion.core.model.AccountId
import io.polion.core.model.ProviderId
import io.polion.core.model.SourceId
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class BitcoinScanStageTest {
    @Test
    fun interruptedAndFailedScansLeaveLedgerUntouchedAndCompleteScanReversesMissing() {
        val path = Files.createTempFile("bitcoin-scan-stage", ".sqlite")
        val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
        val account = AccountId("account")
        val owners = mapOf(address to account)
        val hash = "b".repeat(64)
        val firstId = "1".repeat(64)
        val secondId = "2".repeat(64)

        fun tx(
            id: String,
            amount: Int,
        ) =
            """{"txid":"$id","vin":[{"is_coinbase":true}],"vout":[{"scriptpubkey_address":"$address","value":$amount}],"fee":0,"status":{"confirmed":true,"block_time":123,"block_height":42,"block_hash":"$hash"}}"""

        fun page(ids: List<Pair<String, Int>>) =
            EsploraHistoryFetcher(
                mapOf(account to address),
                EsploraTransport { endpoint ->
                    if (endpoint.endsWith("mempool")) "[]" else ids.joinToString(",", "[", "]") { tx(it.first, it.second) }
                },
                { "2026-01-01T00:00:00Z" },
            ).fetch(HistoryRequest(SourceId("esplora"), ProviderId("esplora"), BITCOIN_MAINNET, account)).observations
        val transport = EsploraTransport { "[\"$firstId\",\"$secondId\"]" }
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                val store = Store(db)
                val ledger = ActivityRepository(db)
                val stage = BitcoinScanStage(db, store, ledger)
                val original = page(listOf(firstId to 250, secondId to 100))
                original.forEach(store::ingest)
                stage.reset()
                stage.addPage(original)
                stage.resolveBlocks(transport)
                stage.applyComplete(owners)
                assertEquals(2, ledger.current().count { it.status == ActivityStatus.CONFIRMED })
                stage.reset()
                stage.addPage(listOf(original.last()))
                assertFails { stage.addPage(page(listOf(secondId to 101))) }
                stage.clear()
                assertEquals(2, ledger.current().count { it.status == ActivityStatus.CONFIRMED })
                stage.addPage(listOf(original.last()))
                stage.resolveBlocks(transport)
                db.execute(
                    "CREATE TRIGGER reject_stage_reversal BEFORE INSERT ON ledger_activities WHEN NEW.status='REVERSED' BEGIN SELECT RAISE(ABORT, 'fixture'); END",
                )
                assertFails { stage.applyComplete(owners) }
                assertEquals(2, ledger.current().count { it.status == ActivityStatus.CONFIRMED })
                db.execute("DROP TRIGGER reject_stage_reversal")
                stage.applyComplete(owners)
                assertEquals(ActivityStatus.REVERSED, ledger.current().single { it.id.value == "bitcoin:$firstId" }.status)
                assertEquals(ActivityStatus.CONFIRMED, ledger.current().single { it.id.value == "bitcoin:$secondId" }.status)
                stage.clear()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
