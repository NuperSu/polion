package io.polion.core.application

import io.polion.core.accounting.QuoteValue
import io.polion.core.accounting.Treatment
import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.OwnerId
import io.polion.core.model.Rational
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountingCompletenessTest {
    @Test fun missingDisposalBlocksDependentInventoryUntilClassified() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            val owner = OwnerId("owner")
            val account = AccountId("account")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.ACCOUNT))

            fun entry(
                id: String,
                at: Long,
                units: String,
                value: String?,
            ) {
                val movement = EventId(id)
                val quantity = ExactInteger.parse(units)
                app.saveManualActivity(
                    Activity(
                        ActivityId(id),
                        at,
                        id,
                        ActivityKind.OTHER,
                        ActivityStatus.CONFIRMED,
                        listOf(Movement(movement, account, AtomicAmount(AssetId("bitcoin:btc"), quantity), MovementRole.TRANSFER)),
                        emptySet(),
                        null,
                        true,
                    ),
                )
                if (value != null) {
                    val quote = QuoteValue(AssetId("USD"), Rational.of(value))
                    app.saveTreatments(
                        ActivityId(id),
                        listOf(
                            if (quantity.sign >
                                0
                            ) {
                                Treatment.Acquire(movement, quote)
                            } else {
                                Treatment.Dispose(movement, quote)
                            },
                        ),
                    )
                }
            }
            entry("buy1", 1, "1", "10")
            entry("sell1", 2, "-1", null)
            entry("buy2", 3, "1", "100")
            entry("sell2", 4, "-1", "200")
            val incomplete = app.accountingReport()
            assertEquals(listOf(ActivityId("sell1")), incomplete.missingTreatments)
            assertTrue(incomplete.report.lots.isEmpty())
            assertTrue(incomplete.report.disposals.isEmpty())
            app.saveTreatments(ActivityId("sell1"), listOf(Treatment.Dispose(EventId("sell1"), null)))
            val complete = app.accountingReport()
            assertTrue(complete.report.lots.isEmpty())
            assertEquals(
                Rational.of("100"),
                complete.report.disposals
                    .last()
                    .basis,
            )
            assertEquals(
                Rational.of("100"),
                complete.report.disposals
                    .last()
                    .gain,
            )
            db.execute("UPDATE accounting_treatments SET quote_asset=NULL WHERE activity_id='buy1'")
            assertEquals(listOf(ActivityId("buy1")), app.accountingReport().invalidTreatments)
            assertTrue(app.treatments(ActivityId("buy1")).isEmpty())
            app.saveTreatments(
                ActivityId("buy1"),
                listOf(Treatment.Acquire(EventId("buy1"), QuoteValue(AssetId("USD"), Rational.of("10")))),
            )
            assertTrue(app.accountingReport().invalidTreatments.isEmpty())
        }
    }
}
