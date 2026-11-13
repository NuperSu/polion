package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.market.PriceObservation
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.model.Price
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyWeightedReturnTest {
    private val day = 86_400_000L
    private val now = 400 * day
    private val usd = AssetId("USD")
    private val btc = BITCOIN_ASSET
    private val a = AccountId("a")
    private val b = AccountId("b")

    @Test fun rateMatchesAnIndependentSpreadsheetFigure() {
        // The widely published XIRR example (Actual/365): 37.34% a year.
        val dates = listOf(0, 60, 303, 411, 456)
        val amounts = listOf(-10_000.0, 2_750.0, 4_250.0, 3_250.0, 2_750.0)
        val (rate, status) = InternalRate.solve(dates.zip(amounts) { d, amount -> DatedFlow(d / 365.0, amount) })
        assertEquals(ReturnStatus.OK, status)
        assertEquals(0.373362535, rate!!, 1e-6)
        assertEquals(0.1, InternalRate.solve(listOf(DatedFlow(0.0, -1000.0), DatedFlow(1.0, 1100.0))).first!!, 1e-9)
    }

    @Test fun ambiguousAndOneSidedFlowsHaveNoRate() {
        // -1, +6, -11, +6 has rates of 0%, 100% and 200%.
        val ambiguous = listOf(-1.0, 6.0, -11.0, 6.0).mapIndexed { year, amount -> DatedFlow(year.toDouble(), amount) }
        assertEquals(ReturnStatus.NO_UNIQUE_RATE, InternalRate.solve(ambiguous).second)
        assertEquals(ReturnStatus.NO_INVESTMENT, InternalRate.solve(listOf(DatedFlow(0.0, 5.0), DatedFlow(1.0, 6.0))).second)
    }

    private fun setUp(db: JvmSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { now }
        app.snapshot()
        app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.accounts.saveAccount(Account(a, OwnerId("owner"), "Exchange", AccountKind.ACCOUNT))
        app.accounts.saveAccount(Account(b, OwnerId("owner"), "Cold wallet", AccountKind.WALLET))
        mapOf(10 to "10000", 100 to "20000", 200 to "15000", 250 to "20000", 260 to "20000").forEach { (at, price) ->
            app.prices.recordManual(PriceObservation("p$at", Price(btc, usd, ExactDecimal.parse(price)), null, at * day, at * day, true))
        }
        app.prices.recordManual(PriceObservation("now", Price(btc, usd, ExactDecimal.parse("40000")), null, now - 3_600_000L, now, true))
        return app
    }

    private fun draft(
        id: String,
        type: TransactionType,
        asset: AssetId,
        quantity: String,
        atDay: Long,
        price: String? = null,
        account: AccountId = a,
        destination: AccountId? = null,
    ) = TransactionDraft(
        ActivityId(id),
        type,
        asset,
        account,
        ExactDecimal.parse(quantity),
        atDay * day,
        usd,
        price?.let(ExactDecimal::parse),
        destination,
    )

    private fun record(app: PortfolioApplication) {
        app.transactions.saveTransaction(draft("deposit", TransactionType.DEPOSIT, usd, "1000", 10))
        app.transactions.saveTransaction(draft("buy", TransactionType.BUY, btc, "0.1", 10, "10000"))
        app.transactions.saveTransaction(draft("move", TransactionType.TRANSFER, btc, "0.05", 100, destination = b))
        app.transactions.saveTransaction(draft("withdraw", TransactionType.WITHDRAWAL, btc, "0.02", 200))
        app.transactions.saveTransaction(draft("reward", TransactionType.REWARD, btc, "0.01", 250, "20000", account = b))
        app.transactions.saveTransaction(draft("fee", TransactionType.FEE, btc, "0.001", 260))
    }

    @Test fun contributionsWithdrawalsAndGainFollowTheDefinitions() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            record(app)
            val result = app.analytics.moneyWeightedReturn(null, 5 * day, now)
            // Only the cash deposit and the external withdrawal are flows: the buy, the move between own accounts,
            // the reward and the fee are part of the return.
            assertEquals(ReturnStatus.OK, result.status)
            assertEquals(ExactDecimal.parse("0"), result.startValue)
            assertEquals(0, result.contributions!!.compareTo(ExactDecimal.parse("1000")))
            assertEquals(0, result.withdrawals!!.compareTo(ExactDecimal.parse("300")))
            // 0.089 BTC at 40,000 = 3,560; gain = 3,560 - 0 - 1,000 + 300.
            assertEquals(0, result.endValue!!.compareTo(ExactDecimal.parse("3560")))
            assertEquals(0, result.gain!!.compareTo(ExactDecimal.parse("2860")))
            // Independent check: the investor flows discount to zero at the reported annual rate.
            val year = 365.25 * day
            val span = (now - 5 * day) / year
            val rate = (1 + result.periodReturn!!).pow(1 / span) - 1
            val npv =
                -1000 * (1 + rate).pow(-(5 * day) / year) +
                    300 * (1 + rate).pow(-(195 * day) / year) +
                    3560 * (1 + rate).pow(-span)
            assertTrue(abs(npv) < 1e-6, "npv $npv")
            assertEquals(rate, result.annualReturn!!, 1e-9)

            val activity = app.analytics.insights(null, 5 * day, now)
            assertEquals(1, activity.trades)
            assertEquals(2, activity.tradedAssets)
            val fee = activity.fees.single()
            assertEquals(ExactInteger.parse("100000"), fee.atomicUnits)
            assertEquals(0, fee.value!!.compareTo(ExactDecimal.parse("20")))
            assertEquals(listOf("Exchange", "Cold wallet"), activity.accounts.map { it.label })
            assertEquals(listOf(5, 2), activity.accounts.map { it.activities })
        }
    }

    @Test fun aMissingFlowPriceMakesTheRateUnavailableAndShortPeriodsAreNotAnnualised() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            record(app)
            app.transactions.saveTransaction(draft("late", TransactionType.WITHDRAWAL, btc, "0.001", 320))
            val missing = app.analytics.moneyWeightedReturn(null, 5 * day, now)
            assertEquals(ReturnStatus.MISSING_PRICES, missing.status)
            assertEquals(1, missing.unpricedFlows)
            assertNull(missing.periodReturn)
            assertNull(missing.gain)
            val month = app.analytics.moneyWeightedReturn(null, now - 30 * day, now)
            assertNull(month.annualReturn)
        }
    }
}
