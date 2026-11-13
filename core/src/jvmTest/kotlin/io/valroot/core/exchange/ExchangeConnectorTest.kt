package io.valroot.core.exchange

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.model.AccountId
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ExchangeConnectorTest {
    private val account = AccountId("a")

    private class Reader : ExchangeConnector(ExchangeKind.MEXC) {
        override val manifest get() = error("unused")

        override fun sync(
            app: PortfolioApplication,
            account: AccountId,
            uid: String,
            options: SourceRefreshOptions,
        ) = false

        fun read(
            app: PortfolioApplication,
            account: AccountId,
            stream: StreamWindows,
            now: Long,
            hasBudget: () -> Boolean,
        ) = readStream(app, account, stream, now, hasBudget) { _, _ -> 0 }
    }

    @Test
    fun aSpentBudgetNeverMarksHistoryCompleteWithoutReading() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 0L }
            val day = ExchangeConnector.DAY_MILLIS
            // Read back to day 10 earlier; retention now ends at day 20, after the saved start.
            val saved = ExchangeStreamState("deposits", 10 * day, 100 * day, false, 0)
            app.exchanges.saveStream(account, saved)
            val complete = Reader().read(app, account, StreamWindows("deposits", 7 * day, 20 * day), 100 * day) { false }
            assertFalse(complete)
            assertEquals(saved, app.exchanges.streams(account).getValue("deposits"))
        }
    }
}
