package io.polion.core.application

import io.polion.core.bitcoin.BITCOIN_MAINNET
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
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import kotlin.io.path.fileSize
import kotlin.time.TimeSource

/** Explicit stress entry point; run with :core:bitcoinHeapStress, outside the ordinary test suite. */
object BitcoinHeapStress {
    private const val ADDRESS = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"

    // Esplora's address-history contract accepts fewer than 50 pending transactions for complete coverage.
    private const val DESCENDANTS = 48

    @JvmStatic
    fun main(args: Array<String>) {
        val transactions = args.singleOrNull()?.toInt() ?: 100_000
        require(transactions >= 100)
        check(Runtime.getRuntime().maxMemory() <= 64L * 1024 * 1024) { "Run this fixture with -Xmx64m" }
        val directory = Files.createTempDirectory("polion-bitcoin-heap-")
        val path = directory.resolve("portfolio.sqlite")
        println("Bitcoin heap stress: confirmed=$transactions descendants=$DESCENDANTS heap=${Runtime.getRuntime().maxMemory()}")
        try {
            JvmSqliteDatabase(path.toString()).use { db ->
                val application = application(db)
                val provider = Provider(transactions)
                var firstCounts: Map<String, Long>? = null
                repeat(2) { pass ->
                    val start = TimeSource.Monotonic.markNow()
                    var phase = ""
                    val states =
                        application.refreshBitcoin(provider.transport) { progress ->
                            if (progress.phase != phase || progress.pages % 1000 == 0) {
                                phase = progress.phase
                                println("pass=${pass + 1} phase=$phase pages=${progress.pages} elapsed=${start.elapsedNow()}")
                            }
                        }
                    check(states == listOf(JobState.COMPLETED)) { "Refresh failed: $states" }
                    validate(db, transactions)
                    val counts =
                        listOf(
                            "bitcoin_graph_revisions",
                            "bitcoin_graph_revision_inputs",
                            "bitcoin_graph_revision_outputs",
                            "bitcoin_graph_membership",
                            "bitcoin_graph_facts",
                            "ledger_activities",
                            "ledger_movements",
                            "raw_revisions",
                        ).associateWith { count(db, it) }
                    if (pass ==
                        0
                    ) {
                        firstCounts = counts
                    } else {
                        check(firstCounts == counts) { "Unchanged scan grew rows: $firstCounts -> $counts" }
                    }
                    check(count(db, "bitcoin_graph_snapshots") == (pass + 1).toLong())
                    println("pass=${pass + 1} complete elapsed=${start.elapsedNow()} bytes=${path.fileSize()} rows=$counts")
                }
                val peak = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.sumOf { it.peakUsage.used }
                println("PASS: two production refreshes, exact balances, unchanged graph/ledger row counts; heapPoolPeaks=$peak")
            }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun application(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1_700_000_000_000L }
        val owner = OwnerId("stress-owner")
        val account = AccountId("stress-wallet")
        app.saveOwner(Owner(owner, "Stress owner"))
        app.saveAccount(Account(account, owner, "Stress wallet", AccountKind.WALLET))
        app.saveTarget(
            TrackingTarget(
                TrackingTargetId("stress-address"),
                account,
                TargetIdentity(BITCOIN_MAINNET, "address", ADDRESS),
                TargetRole.LEAF,
                "Stress address",
            ),
        )
        app.setSourceConsent(true)
        return app
    }

    private fun validate(
        db: JvmSqliteDatabase,
        transactions: Int,
    ) {
        val total = transactions.toLong() + 1 + DESCENDANTS
        check(count(db, "bitcoin_graph_revisions") == total)
        check(count(db, "bitcoin_graph_membership") == total)
        check(count(db, "ledger_activities") == total)
        check(scalar(db, "SELECT COUNT(*) FROM bitcoin_graph_membership WHERE state='REPLACED'") == DESCENDANTS.toLong())
        check(scalar(db, "SELECT COUNT(*) FROM ledger_activities WHERE status='REVERSED'") == DESCENDANTS.toLong())
        check(
            scalar(
                db,
                "SELECT SUM(CAST(m.units AS INTEGER)) FROM ledger_movements m JOIN ledger_activities a " +
                    "ON a.id=m.activity_id AND a.revision=m.revision WHERE a.status='CONFIRMED'",
            ) == transactions.toLong(),
        ) { "Ledger balance disagrees with confirmed winner" }
        check(
            scalar(
                db,
                "SELECT SUM(CAST(o.value AS INTEGER)) FROM bitcoin_graph_membership m " +
                    "JOIN bitcoin_graph_revision_outputs o ON o.txid=m.txid AND o.checksum=m.checksum " +
                    "WHERE m.valid_to IS NULL AND m.state IN ('CONFIRMED','PENDING') AND o.address='$ADDRESS' " +
                    "AND NOT EXISTS (SELECT 1 FROM bitcoin_graph_revision_inputs i CROSS JOIN bitcoin_graph_membership s " +
                    "ON s.txid=i.txid AND s.checksum=i.checksum AND s.scope_key=m.scope_key AND s.valid_to IS NULL " +
                    "WHERE i.parent_txid=o.txid AND i.parent_vout=o.vout AND s.state IN ('CONFIRMED','PENDING'))",
            ) == transactions.toLong(),
        ) { "Graph projected balance disagrees with confirmed winner" }
        check(scalar(db, "SELECT COUNT(*) FROM bitcoin_graph_facts WHERE kind='D' AND valid_to IS NULL") == 0L)
        BitcoinScanStage.SCRATCH_TABLES.forEach { check(count(db, it) == 0L) { "Scratch table retained rows: $it" } }
    }

    private fun count(
        db: JvmSqliteDatabase,
        table: String,
    ) = scalar(db, "SELECT COUNT(*) FROM $table")

    private fun scalar(
        db: JvmSqliteDatabase,
        sql: String,
    ) = db
        .query(sql)
        .single()
        .single()!!
        .toLong()

    private fun id(number: Int) = number.toString(16).padStart(64, '0')

    private class Provider(
        private val transactions: Int,
    ) {
        private val lastConfirmed = transactions + 1
        private val firstPending = transactions + 2
        private val lastPending = transactions + DESCENDANTS + 1
        private val blockHash = id(1_000_000_000)
        private val status = """{"confirmed":true,"block_height":1,"block_hash":"$blockHash","block_time":1}"""
        val transport =
            EsploraTransport { path ->
                when {
                    path == "/blocks/tip/hash" || path == "/block-height/1" -> blockHash
                    path == "/block/$blockHash" -> """{"id":"$blockHash","height":1}"""
                    path == "/address/$ADDRESS" ->
                        """{"chain_stats":{"tx_count":$lastConfirmed},"mempool_stats":{"tx_count":$DESCENDANTS}}"""
                    path.endsWith("/txs/mempool") -> (firstPending..lastPending).joinToString(",", "[", "]", transform = ::transaction)
                    path.contains("/txs/chain") -> {
                        val cursor = path.substringAfter("/txs/chain", "").removePrefix("/")
                        val first = if (cursor.isEmpty()) lastConfirmed else cursor.toInt(16) - 1
                        (first downTo maxOf(1, first - 24)).joinToString(",", "[", "]", transform = ::transaction)
                    }
                    path.endsWith("/utxo") ->
                        """[{"txid":"${id(lastConfirmed)}","vout":0,"value":$transactions,"status":$status}]"""
                    else -> error("Unexpected request: $path")
                }
            }

        private fun transaction(number: Int): String {
            val pending = number >= firstPending
            val parent = if (number == firstPending) transactions else number - 1
            val ordinal = if (pending) number - 1 else number
            val incoming = 2 * transactions + 2 - ordinal
            val amount = if (number == 1) 2 * transactions else incoming - 1
            val previousAddress = if (number == 1) "external" else ADDRESS
            val fee = if (number == 1) 0 else 1
            val currentStatus = if (pending) """{"confirmed":false}""" else status
            return """{"txid":"${id(
                number,
            )}","vin":[{"txid":"${id(
                parent,
            )}","vout":0,"sequence":4294967293,"prevout":{"value":${if (number == 1) amount else incoming},"scriptpubkey_address":"$previousAddress"}}],"vout":[{"value":$amount,"scriptpubkey_address":"$ADDRESS"}],"fee":$fee,"status":$currentStatus}"""
        }
    }
}
