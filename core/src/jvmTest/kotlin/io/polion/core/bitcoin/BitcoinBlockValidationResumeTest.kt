package io.polion.core.bitcoin

import io.polion.core.ledger.ActivityRepository
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BitcoinBlockValidationResumeTest {
    private val first = "a".repeat(64)
    private val second = "b".repeat(64)
    private val tip = BitcoinBlockAnchor(100, "c".repeat(64))

    @Test
    fun rateLimitResumesPersistedChecksAfterReopen() {
        val path = Files.createTempFile("bitcoin-validation-resume", ".sqlite")
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                val stage = stage(db)
                seed(db, stage)
                val requested = mutableListOf<String>()
                val failure =
                    assertFailsWith<BitcoinProviderException> {
                        stage.validateBlocks(
                            EsploraTransport { endpoint ->
                                requested += endpoint
                                if (endpoint == "/block-height/43") throw BitcoinProviderException(BitcoinProviderFailure.RATE_LIMITED)
                                hash(endpoint)
                            },
                            tip,
                        )
                    }
                assertEquals(BitcoinProviderFailure.RATE_LIMITED, failure.reason)
                assertEquals(1, requested.count { it == "/block-height/42" })
                assertEquals("1", db.query("SELECT COUNT(*) FROM bitcoin_scan_validated_blocks").single().single())
            }
            JvmSqliteDatabase(path.toString()).use { db ->
                val requests = mutableListOf<String>()
                val progress = mutableListOf<Pair<Int, Int>>()
                stage(db).validateBlocks(
                    EsploraTransport { endpoint ->
                        requests += endpoint
                        hash(endpoint)
                    },
                    tip,
                ) { done, total -> progress += done to total }
                assertEquals(0, requests.count { it == "/block-height/42" })
                assertEquals(1, requests.count { it == "/block-height/43" })
                assertEquals(listOf(1 to 2, 2 to 2), progress)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun advancingCanonicalTipKeepsEarlierChecksAndValidatesNewBlocks() =
        withStage { db, stage ->
            seed(db, stage)
            stage.validateBlocks(EsploraTransport(::hash), tip)
            val newer = BitcoinBlockAnchor(101, "d".repeat(64))
            db.execute("INSERT INTO bitcoin_scan_blocks VALUES (?, 101, 0)", listOf(newer.hash))
            val requests = mutableListOf<String>()
            stage.validateBlocks(
                EsploraTransport { endpoint ->
                    requests += endpoint
                    if (endpoint == "/block-height/101") newer.hash else hash(endpoint)
                },
                newer,
            )
            assertEquals(0, requests.count { it == "/block-height/42" || it == "/block-height/43" })
            assertEquals(1, requests.count { it == "/block-height/100" })
            assertEquals("3", db.query("SELECT COUNT(*) FROM bitcoin_scan_validated_blocks").single().single())
            assertEquals("101", db.query("SELECT anchor_height FROM bitcoin_scan_validation").single().single())
        }

    @Test
    fun changedCachedAnchorForcesFreshChecksAndRejectsReorganizedBlock() =
        withStage { db, stage ->
            seed(db, stage)
            stage.validateBlocks(EsploraTransport(::hash), tip)
            val newer = BitcoinBlockAnchor(101, "d".repeat(64))
            val requested = mutableListOf<String>()
            val failure =
                assertFailsWith<BitcoinProviderException> {
                    stage.validateBlocks(
                        EsploraTransport { endpoint ->
                            requested += endpoint
                            when (endpoint) {
                                "/block-height/101" -> newer.hash
                                "/block-height/100", "/block-height/42" -> "e".repeat(64)
                                else -> hash(endpoint)
                            }
                        },
                        newer,
                    )
                }
            assertEquals(BitcoinProviderFailure.REORGANIZED, failure.reason)
            assertEquals(1, requested.count { it == "/block-height/42" })
            assertEquals("0", db.query("SELECT COUNT(*) FROM bitcoin_scan_validated_blocks").single().single())
        }

    @Test
    fun changedProviderOrSessionCannotReuseVerification() =
        withStage { db, stage ->
            seed(db, stage)
            stage.validateBlocks(EsploraTransport(::hash), tip)
            // Exercise identity checking independently of createSession's scratch-table cleanup.
            db.execute("UPDATE bitcoin_scan_session SET fingerprint='other-provider', generation=2")
            val requested = mutableListOf<String>()
            stage.validateBlocks(
                EsploraTransport { endpoint ->
                    requested += endpoint
                    hash(endpoint)
                },
                tip,
            )
            assertEquals(1, requested.count { it == "/block-height/42" })
            assertEquals(1, requested.count { it == "/block-height/43" })
            stage.createSession(BitcoinScanSession(3, "third-provider", tip.hash, tip.height), emptyList())
            assertEquals("0", db.query("SELECT COUNT(*) FROM bitcoin_scan_validated_blocks").single().single())
            assertEquals("0", db.query("SELECT COUNT(*) FROM bitcoin_scan_validation").single().single())
        }

    @Test
    fun finalAnchorChangeDiscardsChecksAndDoesNotCompleteValidation() =
        withStage { db, stage ->
            seed(db, stage)
            var anchorRequests = 0
            val failure =
                assertFailsWith<BitcoinProviderException> {
                    stage.validateBlocks(
                        EsploraTransport { endpoint ->
                            if (endpoint == "/block-height/100" && ++anchorRequests > 1) "f".repeat(64) else hash(endpoint)
                        },
                        tip,
                    )
                }
            assertEquals(BitcoinProviderFailure.REORGANIZED, failure.reason)
            assertEquals("0", db.query("SELECT COUNT(*) FROM bitcoin_scan_validated_blocks").single().single())
        }

    @Test
    fun blocksAboveAnchorAreRejectedBeforeAnyVerificationIsCached() =
        withStage { db, stage ->
            seed(db, stage)
            db.execute("INSERT INTO bitcoin_scan_blocks VALUES (?, 101, 0)", listOf("d".repeat(64)))
            val failure =
                assertFailsWith<BitcoinProviderException> {
                    stage.validateBlocks(EsploraTransport { error("No request expected") }, tip)
                }
            assertEquals(BitcoinProviderFailure.HISTORY_CHANGED, failure.reason)
            assertEquals("0", db.query("SELECT COUNT(*) FROM bitcoin_scan_validated_blocks").single().single())
        }

    private fun seed(
        db: JvmSqliteDatabase,
        stage: BitcoinScanStage,
    ) {
        stage.createSession(BitcoinScanSession(1, "provider-and-scope", tip.hash, tip.height), emptyList())
        db.execute("INSERT INTO bitcoin_scan_blocks VALUES (?, 42, 0)", listOf(first))
        db.execute("INSERT INTO bitcoin_scan_blocks VALUES (?, 43, 0)", listOf(second))
    }

    private fun hash(endpoint: String): String =
        when (endpoint) {
            "/block-height/42" -> first
            "/block-height/43" -> second
            "/block-height/100" -> tip.hash
            else -> error("Unexpected endpoint: $endpoint")
        }

    private fun stage(db: JvmSqliteDatabase): BitcoinScanStage = BitcoinScanStage(db, Store(db), ActivityRepository(db))

    private fun withStage(block: (JvmSqliteDatabase, BitcoinScanStage) -> Unit) {
        val path = Files.createTempFile("bitcoin-validation-resume", ".sqlite")
        try {
            JvmSqliteDatabase(path.toString()).use { db -> block(db, stage(db)) }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
