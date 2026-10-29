package io.polion.core.ledger

import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.SourceRecordId
import io.polion.core.storage.JvmSqliteDatabase
import io.polion.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LedgerTest {
    private val btc = AssetId("btc:mainnet")

    private fun movement(
        id: String,
        account: String,
        value: String,
        role: MovementRole = MovementRole.TRANSFER,
    ) = Movement(EventId(id), AccountId(account), AtomicAmount(btc, ExactInteger.parse(value)), role)

    private fun activity(
        id: String,
        vararg movements: Movement,
    ) = Activity(
        ActivityId(id),
        1000,
        id,
        ActivityKind.TRANSFER,
        ActivityStatus.CONFIRMED,
        movements.toList(),
        setOf(SourceRecordId("raw:$id")),
        "decoder:1",
    )

    @Test
    fun economicScenariosAndStatus() {
        val deposit = activity("deposit", movement("d", "wallet", "1000"))
        val spend = activity("spend", movement("s", "wallet", "-200"), movement("f", "wallet", "-10", MovementRole.FEE))
        val transfer = activity("transfer", movement("out", "wallet", "-300"), movement("in", "other", "300"))
        val reward = activity("reward", movement("r", "other", "5", MovementRole.REWARD)).copy(kind = ActivityKind.REWARD)
        val pending = activity("pending", movement("p", "wallet", "40")).copy(status = ActivityStatus.PENDING)
        val reversed = activity("reversed", movement("x", "wallet", "999")).copy(status = ActivityStatus.REVERSED)
        val totals = LedgerDeltas.calculate(listOf(deposit, spend, transfer, reward, pending, reversed))
        assertEquals("490", totals[AccountId("wallet")]!![btc].toString())
        assertEquals("305", totals[AccountId("other")]!![btc].toString())
        assertEquals("530", LedgerDeltas.calculate(listOf(deposit, spend, transfer, pending), true)[AccountId("wallet")]!![btc].toString())
        assertFailsWith<IllegalArgumentException> { LedgerDeltas.calculate(listOf(deposit, deposit)) }
    }

    @Test
    fun revisionsSurviveReopenAndManualRecordsRemainDistinct() {
        val file = Files.createTempFile("polion-ledger", ".db")
        try {
            val original =
                activity(
                    "trade",
                    movement("sold", "wallet", "-100", MovementRole.TRADE_LEG),
                    Movement(
                        EventId("bought"),
                        AccountId("wallet"),
                        AtomicAmount(AssetId("eth:mainnet"), ExactInteger.parse("1000000000000000000")),
                        MovementRole.TRADE_LEG,
                    ),
                ).copy(kind = ActivityKind.TRADE)
            JvmSqliteDatabase(file.toString()).use { db ->
                Store(db)
                val repository = ActivityRepository(db)
                repository.save(original)
                repository.save(original)
                repository.save(original.copy(status = ActivityStatus.REVERSED, decoderVersion = "decoder:2"))
                repository.save(
                    activity("manual", movement("m", "wallet", "2")).copy(manual = true, sources = emptySet(), decoderVersion = null),
                )
                assertEquals(2, repository.history(original.id).size)
                assertFailsWith<IllegalArgumentException> {
                    repository.save(
                        original.copy(manual = true, sources = emptySet(), decoderVersion = null),
                    )
                }
            }
            JvmSqliteDatabase(file.toString()).use { db ->
                Store(db)
                val repository = ActivityRepository(db)
                assertEquals(ActivityStatus.REVERSED, repository.history(original.id).last().status)
                assertEquals("2", LedgerDeltas.calculate(repository.current())[AccountId("wallet")]!![btc].toString())
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun unallocatedFeesPersistAcrossRevisions() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repository = ActivityRepository(db)
            val fee =
                UnallocatedFee(
                    AtomicAmount(btc, ExactInteger.parse("10")),
                    FeeAllocationReason.MULTIPLE_TRACKED_PAYERS,
                    setOf(AccountId("wallet"), AccountId("other")),
                )
            val shared =
                activity(
                    "shared",
                    movement("a", "wallet", "-100"),
                    movement("b", "other", "-50"),
                ).copy(unallocatedFees = listOf(fee))
            repository.save(shared)
            repository.save(shared.copy(status = ActivityStatus.REVERSED))
            val history = repository.history(shared.id)
            assertEquals(listOf(listOf(fee), listOf(fee)), history.map { it.unallocatedFees })
            assertEquals(history.last(), repository.currentPage().single())
            assertFailsWith<IllegalArgumentException> {
                UnallocatedFee(fee.amount, FeeAllocationReason.MULTIPLE_TRACKED_PAYERS, setOf(AccountId("wallet")))
            }
        }
    }

    @Test
    fun currentPagesReturnLatestNumericRevisionWithoutSkippingIds() {
        JvmSqliteDatabase(":memory:").use { db ->
            Store(db)
            val repository = ActivityRepository(db)
            val first = activity("a", movement("one", "wallet", "1"))
            repeat(12) { index ->
                repository.save(first.copy(status = if (index % 2 == 0) ActivityStatus.CONFIRMED else ActivityStatus.REVERSED))
            }
            repository.save(activity("b", movement("two", "wallet", "2")))
            repository.save(activity("c", movement("three", "wallet", "3")))
            val page = repository.currentPage(limit = 2)
            assertEquals(listOf("a", "b"), page.map { it.id.value })
            assertEquals(ActivityStatus.REVERSED, page.first().status)
            assertEquals(listOf("c"), repository.currentPage(page.last().id, 2).map { it.id.value })
            assertEquals(emptyList(), repository.currentPage(ActivityId("c"), 2))
            assertEquals(repository.current().map { it.id.value }, listOf("a", "b", "c"))
            assertFailsWith<IllegalArgumentException> { repository.currentPage(limit = 0) }
            repeat(260) { index ->
                val id = "id-${index.toString().padStart(3, '0')}"
                repository.save(activity(id, movement("event-$id", "wallet", "1")))
            }
            val ids = mutableListOf<ActivityId>()
            var cursor: ActivityId? = null
            while (true) {
                val batch = repository.currentPage(cursor, 128)
                if (batch.isEmpty()) break
                ids.addAll(batch.map { it.id })
                cursor = batch.last().id
            }
            assertEquals(263, ids.size)
            assertEquals(ids, repository.current().map { it.id })
        }
    }
}
