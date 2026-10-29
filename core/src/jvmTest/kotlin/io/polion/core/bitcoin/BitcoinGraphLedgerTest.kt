package io.polion.core.bitcoin

import io.polion.core.ledger.ActivityRepository
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.LedgerDeltas
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.RawObservation
import io.polion.core.storage.Store
import io.polion.core.storage.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class BitcoinGraphLedgerTest {
    private val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    private val otherAddress = "1BitcoinEaterAddressDontSendf59kuE"
    private val external = "external"
    private val account = AccountId("account-a")
    private val otherAccount = AccountId("account-b")
    private val owners = mapOf(address to account, otherAddress to otherAccount)
    private val anchor = BitcoinBlockAnchor(200, hex('e'))
    private val fund = hex('a')
    private val original = hex('b')
    private val competitor = hex('c')
    private val descendant = hex('d')

    private fun hex(char: Char) = char.toString().repeat(64)

    private fun payload(
        id: String,
        parent: String = hex('0'),
        inputValue: Long = 10_000,
        inputAddress: String = external,
        outputValue: Long = 10_000,
        outputAddress: String = address,
        confirmed: Boolean = false,
    ): String {
        val status =
            if (confirmed) {
                """{"confirmed":true,"block_height":200,"block_hash":"${anchor.hash}","block_time":1700000000}"""
            } else {
                """{"confirmed":false}"""
            }
        return """
            {"txid":"$id","vin":[{"txid":"$parent","vout":0,"sequence":4294967293,
            "prevout":{"value":$inputValue,"scriptpubkey_address":"$inputAddress"}}],
            "vout":[{"value":$outputValue,"scriptpubkey_address":"$outputAddress"}],
            "fee":${inputValue - outputValue},"status":$status}
            """.trimIndent()
    }

    private fun observation(payload: String): RawObservation {
        val id = parseBitcoinGraphTransaction(payload).id
        return RawObservation(
            "bitcoin:$id",
            "bitcoin",
            id,
            BITCOIN_MAINNET.value,
            "esplora",
            "2026-10-29T00:00:00Z",
            payload,
            "esplora-1",
            sha256(payload),
            "esplora-history",
        )
    }

    private class Fixture(
        val db: JvmSqliteDatabase,
    ) {
        val store = Store(db)
        val ledger = ActivityRepository(db)
        val stage = BitcoinScanStage(db, store, ledger)
        val repository = BitcoinGraphRepository(db)
    }

    private fun Fixture.apply(
        payloads: List<String>,
        scope: Map<String, AccountId> = owners,
        reconciled: Set<AccountId> = scope.values.toSet(),
    ): BitcoinUtxoGraph {
        stage.clear()
        stage.addPage(payloads.map(::observation))
        val graph = BitcoinUtxoGraph.build(payloads, scope)
        stage.applyComplete(scope, reconciledAccounts = reconciled, requireBlockPositions = false, graph = graph)
        return graph
    }

    private fun Fixture.status(id: String) = ledger.current().single { it.id == ActivityId("bitcoin:$id") }.status

    private fun Fixture.projected() = LedgerDeltas.calculate(ledger.current(), true)[account]?.get(BITCOIN_ASSET).toString()

    @Test
    fun pendingCompetitionSupersedesBothBranchesAndTheirDescendants() {
        JvmSqliteDatabase(":memory:").use { db ->
            val fixture = Fixture(db)
            with(fixture) {
                val funding = payload(fund, confirmed = true)
                val first = payload(original, fund, inputAddress = address, outputValue = 9_000)
                val child = payload(descendant, original, 9_000, address, 8_000)
                apply(listOf(funding, first, child))
                assertEquals("8000", projected())

                val second = payload(competitor, fund, inputAddress = address, outputValue = 8_500)
                val graph = apply(listOf(funding, first, child, second))
                listOf(original, competitor, descendant).forEach { assertEquals(ActivityStatus.SUPERSEDED, status(it)) }
                assertEquals(ActivityStatus.CONFIRMED, status(fund))
                assertEquals("10000", projected())
                assertEquals(
                    "10000",
                    graph
                        .balances()
                        .getValue(account)
                        .projected
                        .toString(),
                )
                assertEquals(
                    listOf(ActivityStatus.PENDING, ActivityStatus.SUPERSEDED),
                    ledger.history(ActivityId("bitcoin:$descendant")).map { it.status },
                )
            }
        }
    }

    @Test
    fun stagedGraphStateControlsLedgerWithoutMaterializingGraph() {
        JvmSqliteDatabase(":memory:").use { db ->
            with(Fixture(db)) {
                val payloads =
                    listOf(
                        payload(fund, confirmed = true),
                        payload(original, fund, inputAddress = address, outputValue = 9_000),
                        payload(competitor, fund, inputAddress = address, outputValue = 8_500),
                    )
                stage.addPage(payloads.map(::observation))
                val staged = BitcoinStagedGraph(db)
                staged.rebuild(stage.payloads(), owners)
                stage.applyComplete(owners, requireBlockPositions = false, graphState = staged::state)
                assertEquals(ActivityStatus.SUPERSEDED, status(original))
                assertEquals(ActivityStatus.SUPERSEDED, status(competitor))
                assertEquals("10000", projected())
            }
        }
    }

    @Test
    fun confirmedWinnerReversesLosingBranchAndItsDescendant() {
        JvmSqliteDatabase(":memory:").use { db ->
            with(Fixture(db)) {
                val funding = payload(fund, confirmed = true)
                val first = payload(original, fund, inputAddress = address, outputValue = 9_000)
                val child = payload(descendant, original, 9_000, address, 8_000)
                val second = payload(competitor, fund, inputAddress = address, outputValue = 8_500)
                apply(listOf(funding, first, child, second))
                val winner = payload(competitor, fund, inputAddress = address, outputValue = 8_500, confirmed = true)
                val graph = apply(listOf(funding, first, child, winner))

                assertEquals(ActivityStatus.CONFIRMED, status(competitor))
                listOf(original, descendant).forEach {
                    assertEquals(ActivityStatus.REVERSED, status(it))
                    assertEquals(
                        listOf(ActivityStatus.SUPERSEDED, ActivityStatus.REVERSED),
                        ledger.history(ActivityId("bitcoin:$it")).map { revision ->
                            revision.status
                        },
                    )
                }
                assertEquals("8500", projected())
                assertEquals(
                    "8500",
                    graph
                        .balances()
                        .getValue(account)
                        .projected
                        .toString(),
                )
            }
        }
    }

    @Test
    fun confirmationReorganizationAndDisappearanceAppendRevisions() {
        JvmSqliteDatabase(":memory:").use { db ->
            with(Fixture(db)) {
                val pending = payload(original)
                apply(listOf(pending))
                apply(listOf(payload(original, confirmed = true)))
                apply(listOf(pending))
                apply(emptyList())
                val history = ledger.history(ActivityId("bitcoin:$original"))
                assertEquals(
                    listOf(
                        ActivityStatus.PENDING,
                        ActivityStatus.CONFIRMED,
                        ActivityStatus.PENDING,
                        ActivityStatus.REVERSED,
                    ),
                    history.map {
                        it.status
                    },
                )
                assertEquals(history.first().movements, history.last().movements)
                assertEquals(history.first().sources, history.last().sources)
                assertEquals(ActivityStatus.REVERSED, status(original))
                apply(emptyList())
                assertEquals(4, ledger.history(ActivityId("bitcoin:$original")).size)
            }
        }
    }

    @Test
    fun disappearanceOnlyReconcilesEnabledAccounts() {
        JvmSqliteDatabase(":memory:").use { db ->
            with(Fixture(db)) {
                apply(listOf(payload(original), payload(competitor, parent = hex('9'), outputAddress = otherAddress)))
                apply(emptyList(), scope = mapOf(address to account))
                assertEquals(ActivityStatus.REVERSED, status(original))
                assertEquals(ActivityStatus.PENDING, status(competitor))
                assertEquals(1, ledger.history(ActivityId("bitcoin:$competitor")).size)
            }
        }
    }

    @Test
    fun graphLedgerAndStageCompletionRollbackTogetherWhenLedgerWriteFails() {
        JvmSqliteDatabase(":memory:").use { db ->
            with(Fixture(db)) {
                val pending = payload(original)
                val graph = apply(listOf(pending))
                db.transaction {
                    repository.saveInTransaction(graph, "esplora", anchor, "2026-10-29T00:00:00Z", stage.rawChecksums())
                }
                stage.clear()
                val confirmed = payload(original, confirmed = true)
                stage.addPage(listOf(observation(confirmed)))
                val nextGraph = BitcoinUtxoGraph.build(listOf(confirmed), owners)
                db.execute(
                    "CREATE TRIGGER reject_graph_ledger BEFORE INSERT ON ledger_activities WHEN NEW.status='CONFIRMED' BEGIN SELECT RAISE(ABORT, 'fixture'); END",
                )

                fun complete() =
                    db.transaction {
                        repository.saveInTransaction(nextGraph, "esplora", anchor, "2026-10-29T01:00:00Z", stage.rawChecksums())
                        stage.applyCompleteInTransaction(owners, requireBlockPositions = false, graph = nextGraph)
                        stage.clearInTransaction()
                    }
                assertFails { complete() }
                assertEquals(1, repository.snapshots().size)
                assertEquals(
                    BitcoinTransactionState.PENDING,
                    repository
                        .latest()!!
                        .graph.states
                        .getValue(original),
                )
                assertEquals(ActivityStatus.PENDING, status(original))
                assertEquals(1, ledger.history(ActivityId("bitcoin:$original")).size)
                assertEquals(listOf(confirmed), stage.payloads().toList())

                db.execute("DROP TRIGGER reject_graph_ledger")
                complete()
                assertEquals(2, repository.snapshots().size)
                assertEquals(
                    BitcoinTransactionState.CONFIRMED,
                    repository
                        .latest()!!
                        .graph.states
                        .getValue(original),
                )
                assertEquals(ActivityStatus.CONFIRMED, status(original))
                assertEquals(emptyList(), stage.payloads().toList())
            }
        }
    }

    @Test
    fun moreThanTenRevisionsRetainNumericOrderAndDisappearFromLatestRevision() {
        JvmSqliteDatabase(":memory:").use { db ->
            with(Fixture(db)) {
                repeat(12) { index -> apply(listOf(payload(original, confirmed = index % 2 == 1))) }
                val id = ActivityId("bitcoin:$original")
                val expected = List(12) { if (it % 2 == 1) ActivityStatus.CONFIRMED else ActivityStatus.PENDING }
                assertEquals(expected, ledger.history(id).map { it.status })
                assertEquals(ActivityStatus.CONFIRMED, status(original))
                apply(emptyList())
                assertEquals(expected + ActivityStatus.REVERSED, ledger.history(id).map { it.status })
                assertEquals(ActivityStatus.REVERSED, status(original))
                apply(emptyList())
                assertEquals(13, ledger.history(id).size)
            }
        }
    }
}
