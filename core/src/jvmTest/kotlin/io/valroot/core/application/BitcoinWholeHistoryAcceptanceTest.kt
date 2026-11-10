package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.bitcoin.BitcoinGraphRepository
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
import io.valroot.core.sync.JobState
import io.valroot.testing.utxoResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 20 acceptance: a multi-page, multi-address Bitcoin history synced from scratch gives the same ledger, graph,
 * holdings, FIFO report and export whether the sync runs once, is interrupted and resumed, or is rebuilt from scratch.
 */
class BitcoinWholeHistoryAcceptanceTest {
    private val a = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val b = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
    private val external = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
    private val tip = 140L

    private fun blockHash(height: Long) = height.toString(16).padStart(64, '0')

    private fun id(
        prefix: Char,
        n: Int,
    ) = n.toString(16).padStart(64, prefix)

    private data class Out(
        val address: String,
        val value: Long,
    )

    private data class In(
        val txid: String,
        val vout: Int,
        val address: String,
        val value: Long,
    )

    private data class Tx(
        val txid: String,
        val height: Long,
        val inputs: List<In>,
        val outputs: List<Out>,
    ) {
        val fee = inputs.sumOf { it.value } - outputs.sumOf { it.value }

        fun json(blockHash: String): String =
            """{"txid":"$txid","vin":[${inputs.joinToString(",") {
                """{"txid":"${it.txid}","vout":${it.vout},"prevout":{"scriptpubkey_address":"${it.address}","value":${it.value}}}"""
            }}],"vout":[${outputs.joinToString(",") { """{"scriptpubkey_address":"${it.address}","value":${it.value}}""" }}],""" +
                """"fee":$fee,"status":{"confirmed":true,"block_time":${1_700_000_000 + height * 600},"block_height":$height,""" +
                """"block_hash":"$blockHash"}}"""
    }

    /** 30 payments into A from outside, two spends with change, and a move from B back to A. */
    private val history: List<Tx> =
        buildList {
            repeat(30) { i ->
                add(Tx(id('f', i), 100L + i, listOf(In(id('e', i), 0, external, 10_100L + i)), listOf(Out(a, 10_000L + i))))
            }
            val s1 =
                Tx(
                    id('1', 1),
                    130,
                    listOf(In(id('f', 0), 0, a, 10_000), In(id('f', 1), 0, a, 10_001)),
                    listOf(Out(b, 15_000), Out(external, 4_000), Out(a, 801)),
                )
            add(s1)
            add(Tx(id('2', 2), 131, listOf(In(id('f', 2), 0, a, 10_002)), listOf(Out(external, 9_000), Out(a, 802))))
            add(Tx(id('3', 3), 132, listOf(In(s1.txid, 0, b, 15_000)), listOf(Out(a, 14_000))))
        }

    private fun involving(address: String) =
        history
            .filter { tx ->
                tx.inputs.any { it.address == address } || tx.outputs.any { it.address == address }
            }.sortedByDescending { it.height }

    private val requested = mutableListOf<String>()

    private val transport =
        EsploraTransport { path ->
            requested += path

            fun page(
                address: String,
                after: String?,
            ): String {
                val list = involving(address)
                val start = after?.let { last -> list.indexOfFirst { it.txid == last } + 1 } ?: 0
                return "[" + list.drop(start).take(25).joinToString(",") { it.json(blockHash(it.height)) } + "]"
            }
            when {
                path == "/blocks/tip/hash" -> blockHash(tip)
                path == "/block/${blockHash(tip)}" -> """{"id":"${blockHash(tip)}","height":$tip}"""
                path.startsWith("/block-height/") -> blockHash(path.removePrefix("/block-height/").toLong())
                path.endsWith("/txs/mempool") -> "[]"
                path.endsWith("/utxo") -> {
                    val address = path.removePrefix("/address/").removeSuffix("/utxo")
                    utxoResponse(address, involving(address).map { it.json(blockHash(it.height)) })
                }
                path.contains("/txs/chain") -> {
                    val rest = path.removePrefix("/address/")
                    val address = rest.substringBefore("/txs/chain")
                    page(address, rest.substringAfter("/txs/chain", "").removePrefix("/").ifEmpty { null })
                }
                path.startsWith("/address/") -> {
                    val address = path.removePrefix("/address/")
                    """{"chain_stats":{"tx_count":${involving(address).size}},"mempool_stats":{"tx_count":0}}"""
                }
                else -> error("Unexpected provider request: $path")
            }
        }

    private fun setUp(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1_800_000_000_000L }
        app.saveOwner(Owner(OwnerId("owner"), "Owner"))
        listOf("spending" to a, "savings" to b).forEach { (name, address) ->
            app.saveAccount(Account(AccountId(name), OwnerId("owner"), name, AccountKind.WALLET))
            app.saveTarget(
                TrackingTarget(
                    TrackingTargetId(name),
                    AccountId(name),
                    TargetIdentity(BITCOIN_MAINNET, "address", address),
                    TargetRole.LEAF,
                    name,
                ),
            )
        }
        app.setSourceConsent(true)
        app.setSourceEndpoint("https://example.org/api")
        return app
    }

    /** Everything a user sees or exports, plus the accepted transaction graph. */
    private fun results(
        app: PortfolioApplication,
        db: JvmSqliteDatabase,
    ) = listOf(
        app.exportCsv(),
        app.snapshot().valuations.map { it.asset to it.amount },
        app.accountOverview().map { it.account.id to it.holdings },
        app.accountingReport().let { it.report.lots to it.report.disposals },
        app.transactionEntries(ActivityFilter()).map { it.activity },
        BitcoinGraphRepository(db).latest()!!.graph.transactions,
    )

    @Test fun interruptedResumedAndRebuiltHistoriesAreIdentical() {
        val uninterrupted =
            JvmSqliteDatabase(":memory:").use { db ->
                val app = setUp(db)
                assertEquals(listOf(JobState.COMPLETED, JobState.COMPLETED), app.refreshBitcoin(transport))
                // A's 33 transactions span two pages; the second page is requested after the last ID of the first.
                assertTrue(requested.any { it.startsWith("/address/$a/txs/chain/") }, requested.toString())
                // 30 payments in, two spends with change, one move between the wallets.
                assertEquals(33, app.transactionEntries(ActivityFilter()).size)
                val expected = (0 until 30).sumOf { 10_000L + it } - 10_000 - 10_001 - 10_002 + 801 + 802 + 14_000
                assertEquals(
                    expected.toString(),
                    app
                        .snapshot()
                        .valuations
                        .single { it.asset == BITCOIN_ASSET }
                        .amount.atomicUnits
                        .toString(),
                )
                val first = results(app, db)
                // A rebuild from scratch reaches the same results.
                assertTrue(app.refreshBitcoin(transport, recoverHistory = true).all { it == JobState.COMPLETED })
                assertEquals(first, results(app, db))
                first
            }
        // Interrupt the first sync after a growing number of requests, then resume; every cut gives the same end state.
        for (cut in listOf(3, 8, 15, 22)) {
            JvmSqliteDatabase(":memory:").use { db ->
                val app = setUp(db)
                var calls = 0
                val failing =
                    EsploraTransport { path ->
                        if (++calls > cut) throw BitcoinProviderException(BitcoinProviderFailure.OFFLINE)
                        transport.get(path)
                    }
                val interrupted = runCatching { app.refreshBitcoin(failing) }
                assertTrue(calls > cut, "cut $cut was not reached")
                assertTrue(interrupted.isFailure || interrupted.getOrThrow().any { it != JobState.COMPLETED }, "cut $cut")
                // Nothing partial is accepted.
                assertTrue(app.transactionEntries(ActivityFilter()).isEmpty(), "cut $cut")
                val resumed = PortfolioApplication(db) { 1_800_000_000_000L }
                assertTrue(resumed.refreshBitcoin(transport).all { it == JobState.COMPLETED }, "cut $cut")
                assertEquals(uninterrupted, results(resumed, db), "cut $cut")
            }
        }
    }
}
