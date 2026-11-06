package io.polion.core.application

import io.polion.core.accounting.Treatment
import io.polion.core.ledger.ActivityRepository
import io.polion.core.ledger.ActivityStatus
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal
import io.polion.core.model.OwnerId
import io.polion.core.model.PortfolioId
import io.polion.core.model.Rational
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.Portfolio
import io.polion.core.storage.JvmSqliteDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionDraftTest {
    private val btc = io.polion.core.bitcoin.BITCOIN_ASSET
    private val usd = AssetId("USD")
    private val a = AccountId("a")
    private val b = AccountId("b")
    private val scales = mapOf(btc to 8, usd to 2, AssetId("eth") to 18)

    private fun draft(
        id: String,
        type: TransactionType,
        amount: String,
        price: String? = null,
        asset: AssetId = btc,
        time: Long = 1000L,
    ) = TransactionDraft(ActivityId(id), type, asset, a, ExactDecimal.parse(amount), time, usd, price?.let(ExactDecimal::parse))

    private fun setup(app: PortfolioApplication) {
        app.snapshot()
        val owner = OwnerId("owner")
        app.saveOwner(Owner(owner, "Owner"))
        app.saveAccount(Account(a, owner, "A", AccountKind.ACCOUNT))
        app.saveAccount(Account(b, owner, "B", AccountKind.ACCOUNT))
    }

    @Test fun sharedNativeAndDesktopWorkflow() {
        io.polion.testing.verifyManualTransactionWorkflow { JvmSqliteDatabase(":memory:") }
    }

    @Test fun exactLinkedLegsAndFeesNeverBecomeSingleLegTrades() {
        val buy =
            TransactionDrafts.prepare(
                draft("buy", TransactionType.BUY, "1.25", "20000").copy(feeAsset = usd, feeQuantity = ExactDecimal.parse("3.50")),
                scales,
            )
        assertEquals(listOf("125000000", "-2500000", "-350"), buy.activity.movements.map { it.amount.atomicUnits.toString() })
        assertEquals(Rational.of("25000"), (buy.treatments[0] as Treatment.Acquire).cost?.amount)
        assertEquals(Rational.ZERO, (buy.treatments[2] as Treatment.Dispose).proceeds?.amount)
        val sell = TransactionDrafts.prepare(draft("sell", TransactionType.SELL, "0.5", "30000"), scales)
        assertEquals(listOf("-50000000", "1500000"), sell.activity.movements.map { it.amount.atomicUnits.toString() })
        assertTrue(sell.treatments[0] is Treatment.Dispose)
        val third =
            draft(
                "third",
                TransactionType.BUY,
                "1",
                "100",
            ).copy(feeAsset = AssetId("eth"), feeQuantity = ExactDecimal.parse("0.001"))
        assertFailsWith<IllegalArgumentException> { TransactionDrafts.prepare(third, scales) }
        val accepted = TransactionDrafts.prepare(third.copy(feeUnitPrice = ExactDecimal.parse("2000")), scales)
        assertEquals(
            "-1000000000000000",
            accepted.activity.movements
                .last()
                .amount.atomicUnits
                .toString(),
        )
    }

    @Test fun invalidPrecisionPairsAccountsAndSignsCannotSubmit() {
        val value = draft("valid", TransactionType.BUY, "1", "100")
        listOf(
            value.copy(quantity = ExactDecimal.parse("0")),
            value.copy(unitPrice = null),
            value.copy(quote = btc),
            value.copy(quantity = ExactDecimal.parse("0.000000001")),
            value.copy(unitPrice = ExactDecimal.parse("0.001")),
            value.copy(destination = b),
            value.copy(feeQuantity = ExactDecimal.parse("-1"), feeAsset = usd),
            value.copy(atMillis = -1),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { TransactionDrafts.prepare(invalid, scales) }
        }
        assertFailsWith<IllegalArgumentException> {
            TransactionDrafts.prepare(draft("same", TransactionType.TRANSFER, "1").copy(destination = a), scales)
        }
    }

    @Test fun internalTransfersCarryBasisAndExternalIncomingKeepsItUnknown() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 10000L }
            setup(app)
            app.saveTransaction(draft("buy", TransactionType.BUY, "2", "100"))
            app.saveTransaction(draft("transfer", TransactionType.TRANSFER, "0.5", time = 2000).copy(destination = b))
            val report = app.accountingReport().report
            assertEquals(Rational.of("50"), report.lots.single { it.account == b && it.asset == btc }.basis)
            app.saveTransaction(draft("external", TransactionType.TRANSFER, "0.25", time = 3000).copy(incomingExternal = true))
            assertNull(
                app
                    .accountingReport()
                    .report.lots
                    .single { it.acquisitionActivityId == ActivityId("external") }
                    .basis,
            )
            val other = OwnerId("other")
            app.saveOwner(Owner(other, "Other"))
            app.saveAccount(Account(AccountId("c"), other, "C", AccountKind.ACCOUNT))
            assertFailsWith<IllegalArgumentException> {
                app.saveTransaction(draft("wrong-owner", TransactionType.TRANSFER, "1").copy(destination = AccountId("c")))
            }
        }
    }

    @Test fun buysSellsRewardsWithdrawalsAndSeparateFeesHaveIndependentExactOracle() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 10000L }
            setup(app)
            app.saveTransaction(draft("fund", TransactionType.DEPOSIT, "1000", "1", usd))
            app.saveTransaction(
                draft("buy", TransactionType.BUY, "2", "100", time = 2000).copy(feeAsset = usd, feeQuantity = ExactDecimal.parse("1")),
            )
            app.saveTransaction(
                draft(
                    "sell",
                    TransactionType.SELL,
                    "0.5",
                    "150",
                    time = 3000,
                ).copy(feeAsset = btc, feeQuantity = ExactDecimal.parse("0.01")),
            )
            app.saveTransaction(draft("reward", TransactionType.REWARD, "0.1", "120", time = 4000))
            app.saveTransaction(draft("withdraw", TransactionType.WITHDRAWAL, "0.05", time = 5000))
            app.saveTransaction(draft("fee", TransactionType.FEE, "0.01", time = 6000))
            val report = app.accountingReport().report
            assertEquals(Rational.of("25"), report.disposals.single { it.activityId == ActivityId("sell") && !it.fee }.gain)
            assertEquals(Rational.of("-1"), report.disposals.single { it.activityId == ActivityId("sell") && it.fee }.gain)
            assertNull(report.disposals.single { it.activityId == ActivityId("withdraw") }.gain)
            assertEquals(Rational.of("-1"), report.disposals.single { it.activityId == ActivityId("fee") }.gain)
            assertEquals(Rational.of("12"), report.lots.single { it.acquisitionActivityId == ActivityId("reward") }.basis)
            val values = app.snapshot().valuations.associate { it.asset to it.amount.atomicUnits.toString() }
            assertEquals("153000000", values[btc])
            assertEquals("87400", values[usd])
        }
    }

    @Test fun failedTreatmentWriteRollsBackAllLegsNotesAndDraft() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 10000L }
            setup(app)
            db.execute(
                "CREATE TRIGGER reject_treatment BEFORE INSERT ON accounting_treatments BEGIN SELECT RAISE(ABORT, 'test rollback'); END",
            )
            assertFailsWith<Exception> { app.saveTransaction(draft("rollback", TransactionType.BUY, "1", "100")) }
            assertTrue(ActivityRepository(db).history(ActivityId("rollback")).isEmpty())
            assertNull(app.overlay(ActivityId("rollback")))
            assertNull(app.transactionDraft(ActivityId("rollback")))
            assertTrue(app.treatments(ActivityId("rollback")).isEmpty())
        }
    }

    @Test fun editsReversalsReopenAndEncryptedRestorePreserveIdentityAndEvidence() {
        val file = Files.createTempFile("transaction-reopen", ".sqlite")
        val value = draft("retained", TransactionType.BUY, "1", "100").copy(notes = "Executed quote evidence")
        try {
            JvmSqliteDatabase(file.toString()).use { db ->
                val app = PortfolioApplication(db) { 10000L }
                setup(app)
                app.saveTransaction(value.copy(notes = "  "))
                assertEquals("  ", app.transactionDraft(value.id)?.notes)
                assertFailsWith<IllegalArgumentException> { app.saveTransaction(value) }
                app.saveTransaction(value.copy(quantity = ExactDecimal.parse("2"), notes = "Corrected"), editing = true)
                val history = ActivityRepository(db).history(value.id)
                assertEquals(2, history.size)
                assertEquals(history[0].movements.map { it.id }, history[1].movements.map { it.id })
            }
            JvmSqliteDatabase(file.toString()).use { db ->
                val app = PortfolioApplication(db) { 10000L }
                assertEquals("2", app.transactionDraft(value.id)?.quantity.toString())
                assertEquals("Corrected", app.overlay(value.id)?.note)
                val backup = app.exportBackup("phase3-password".toCharArray())
                JvmSqliteDatabase(":memory:").use { restored ->
                    app.restoreToStaging(restored, backup, "phase3-password".toCharArray())
                    val copy = PortfolioApplication(restored) { 10000L }
                    assertEquals(app.transactionDraft(value.id), copy.transactionDraft(value.id))
                    assertEquals(app.exportCsv(), copy.exportCsv())
                    copy.reverseManualTransaction(value.id)
                    assertEquals(ActivityStatus.REVERSED, ActivityRepository(restored).history(value.id).last().status)
                    assertEquals(3, ActivityRepository(restored).history(value.id).size)
                    assertTrue(copy.snapshot().valuations.all { it.amount.atomicUnits.sign == 0 })
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test fun treatmentsPricedInAnotherCurrencyKeepTheirLotWithUnknownBasis() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 10000L }
            setup(app)
            app.saveTransaction(draft("buy", TransactionType.BUY, "2", "100"))
            val eur = PortfolioId("eur")
            app.savePortfolio(Portfolio(eur, "EUR", "EUR"))
            val view = app.accountingReport(eur)
            assertEquals(listOf(ActivityId("buy")), view.currencyMismatch)
            assertTrue(view.invalidTreatments.isEmpty(), "a currency change must not invalidate the treatment")
            val lot = view.report.lots.single { it.acquisitionActivityId == ActivityId("buy") && it.asset == btc }
            assertEquals("200000000", lot.quantity.toString())
            assertNull(lot.basis)
            // The same ledger still reports the USD basis in its own currency.
            val usdView = app.accountingReport()
            assertTrue(usdView.currencyMismatch.isEmpty())
            val usdLot =
                usdView.report.lots.single {
                    it.acquisitionActivityId == ActivityId("buy") && it.asset == btc
                }
            assertEquals(Rational.of("200"), usdLot.basis)
        }
    }
}
