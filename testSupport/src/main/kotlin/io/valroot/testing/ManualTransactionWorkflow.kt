package io.valroot.testing

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.TransactionDraft
import io.valroot.core.application.TransactionType
import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.ledger.ActivityRepository
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.market.AssetDefinition
import io.valroot.core.market.AssetIdentity
import io.valroot.core.market.AssetKind
import io.valroot.core.market.AssetMetadata
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.OwnerId
import io.valroot.core.model.Rational
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.storage.SqliteDatabase

/** The same exact transaction oracle runs against desktop JDBC and native Android SQLite. */
fun verifyManualTransactionWorkflow(openDatabase: () -> SqliteDatabase) {
    val usd = AssetId("USD")
    val eth = AssetId("fixture:eth")
    val a = AccountId("a")
    val b = AccountId("b")

    fun draft(
        id: String,
        type: TransactionType,
        quantity: String,
        price: String? = null,
        asset: AssetId = BITCOIN_ASSET,
        time: Long = 1000,
    ) = TransactionDraft(ActivityId(id), type, asset, a, ExactDecimal.parse(quantity), time, usd, price?.let(ExactDecimal::parse))
    openDatabase().use { db ->
        val app = PortfolioApplication(db) { 10000L }
        app.snapshot()
        app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.accounts.saveAccount(Account(a, OwnerId("owner"), "A", AccountKind.ACCOUNT))
        app.accounts.saveAccount(Account(b, OwnerId("owner"), "B", AccountKind.ACCOUNT))
        app.assets.register(
            AssetDefinition(eth, AssetIdentity(AssetKind.CUSTOM, null, "ETH-fixture")),
            AssetMetadata("ETH", "Fixture asset", 18, 0),
        )
        app.transactions.saveTransaction(draft("fund", TransactionType.DEPOSIT, "1000", "1", usd))
        app.transactions.saveTransaction(draft("fund-fee", TransactionType.DEPOSIT, "1", "10", eth))
        val buy =
            draft(
                "buy",
                TransactionType.BUY,
                "2",
                "100",
                time = 2000,
            ).copy(
                feeAsset = eth,
                feeQuantity = ExactDecimal.parse("0.01"),
                feeUnitPrice = ExactDecimal.parse("12"),
                notes = "Execution and third-asset fee evidence",
            )
        app.transactions.saveTransaction(buy)
        app.transactions.saveTransaction(
            draft("sell", TransactionType.SELL, "0.5", "150", time = 3000).copy(feeAsset = usd, feeQuantity = ExactDecimal.parse("1")),
        )
        app.transactions.saveTransaction(draft("move", TransactionType.TRANSFER, "0.5", time = 4000).copy(destination = b))
        app.transactions.saveTransaction(draft("external", TransactionType.TRANSFER, "0.1", time = 5000).copy(incomingExternal = true))
        app.transactions.saveTransaction(draft("reward", TransactionType.REWARD, "0.2", "100", time = 6000))
        app.transactions.saveTransaction(draft("withdraw", TransactionType.WITHDRAWAL, "0.1", time = 7000))
        app.transactions.saveTransaction(draft("external-out", TransactionType.TRANSFER, "0.1", time = 8000))
        app.transactions.saveTransaction(draft("fee", TransactionType.FEE, "0.01", time = 9000))
        val report = app.accounting.report().report
        check(report.disposals.single { it.activityId == ActivityId("sell") && it.asset == BITCOIN_ASSET }.gain == Rational.of("25"))
        check(
            report.disposals.single { it.activityId == ActivityId("buy") && it.fee }.gain ==
                Rational.fromDecimal(ExactDecimal.parse("-0.1")),
        )
        check(report.lots.single { it.account == b && it.asset == BITCOIN_ASSET }.basis == Rational.of("50"))
        check(report.lots.single { it.acquisitionActivityId == ActivityId("external") }.basis == null)
        check(report.disposals.single { it.activityId == ActivityId("withdraw") }.gain == null)
        check(report.disposals.single { it.activityId == ActivityId("external-out") }.proceeds == null)
        val units = app.snapshot().valuations.associate { it.asset to it.amount.atomicUnits.toString() }
        check(units[BITCOIN_ASSET] == "159000000")
        check(units[usd] == "87400")
        check(units[eth] == "990000000000000000")
        db.execute(
            "CREATE TRIGGER reject_manual_fixture BEFORE INSERT ON accounting_treatments WHEN NEW.activity_id='rollback' BEGIN SELECT RAISE(ABORT, 'fixture'); END",
        )
        check(runCatching { app.transactions.saveTransaction(draft("rollback", TransactionType.BUY, "1", "100")) }.isFailure)
        check(ActivityRepository(db).history(ActivityId("rollback")).isEmpty())
        check(app.transactions.draft(ActivityId("rollback")) == null)
        check(app.transactions.overlay(ActivityId("rollback")) == null)
        app.transactions.saveTransaction(buy.copy(notes = "Reviewed"), editing = true)
        check(ActivityRepository(db).history(buy.id).size == 1) // Notes are retained by overlay revisions.
        val reopened = PortfolioApplication(db) { 10000L }
        check(reopened.transactions.draft(buy.id)?.notes == "Reviewed")
        check(reopened.transactions.draft(buy.id)?.feeUnitPrice == ExactDecimal.parse("12"))
        val csv = reopened.csv.export()
        val backup = reopened.backup.export("manual-fixture-password".toCharArray())
        openDatabase().use { restored ->
            reopened.backup.restoreToStaging(restored, backup, "manual-fixture-password".toCharArray())
            val copy = PortfolioApplication(restored) { 10000L }
            check(copy.csv.export() == csv)
            check(copy.transactions.draft(buy.id) == reopened.transactions.draft(buy.id))
            copy.transactions.reverseManual(buy.id)
            check(ActivityRepository(restored).history(buy.id).last().status == ActivityStatus.REVERSED)
            check(ActivityRepository(restored).history(buy.id).first().status == ActivityStatus.CONFIRMED)
        }
    }
}
