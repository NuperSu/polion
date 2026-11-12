package io.valroot.core.application

import io.valroot.core.model.AccountId
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExampleDataRemovalTest {
    @Test fun removingExampleDataRetiresItsRecordsPricesAndLabel() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_710_000_000_000L }
            app.demo.seed()
            assertTrue(app.snapshot().sampleData)

            app.demo.removeExampleData()

            val snapshot = app.snapshot()
            assertFalse(snapshot.sampleData)
            assertTrue(snapshot.valuations.none { it.amount.atomicUnits.sign != 0 }, snapshot.valuations.toString())
            assertTrue(snapshot.ownership.portfolios.none { it.id.value == "demo-portfolio" })
            assertTrue(
                "valroot:archived" in
                    snapshot.ownership.accounts
                        .single { it.id == AccountId("demo-wallet") }
                        .tags,
            )
            assertEquals(listOf("Personal"), snapshot.ownership.owners.map { it.label })
            // Example prices no longer value bitcoin.
            assertTrue(snapshot.marketCoins.none { it.price.manual })
            assertFails { app.demo.removeExampleData() }
        }
    }
}
