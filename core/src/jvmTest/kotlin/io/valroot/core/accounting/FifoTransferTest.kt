package io.valroot.core.accounting

import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.model.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FifoTransferTest {
    private val usd = AssetId("USD")
    private val exchange = AccountId("exchange")
    private val wallet = AccountId("wallet")
    private val owners = mapOf(exchange to OwnerId("o"), wallet to OwnerId("o"))
    private val coin = AssetId("coin:tether")
    private val token = AssetId("eip155:1/erc20:0xdac17f958d2ee523a2206206994597c13d831ec7")

    private fun units(text: String) = ExactInteger.parse(text)

    private fun buy(
        id: String,
        at: Long,
        quantity: String,
        cost: String,
    ) = AccountingAction.Acquisition(ActivityId(id), at, "0", exchange, coin, units(quantity), QuoteValue(usd, Rational.of(cost)))

    @Test
    fun lotsMoveBetweenAssetsOfOneCoinWithTheirCostAndDates() {
        // Two lots of 1.5 and 2 (18 decimals); 3 arrive as a 6-decimal token.
        val actions =
            listOf(
                buy("a", 1, "1500000000000000000", "3"),
                buy("b", 2, "2000000000000000000", "8"),
                AccountingAction.Transfer(
                    ActivityId("t"),
                    3,
                    "0",
                    exchange,
                    wallet,
                    token,
                    units("3000000"),
                    coin,
                    units("3000000000000000000"),
                ),
            )
        val report = FifoAccounting.calculate(actions, owners, usd)
        val moved = report.lots.filter { it.account == wallet }
        assertEquals(listOf(units("1500000"), units("1500000")), moved.map { it.quantity })
        assertEquals(listOf(Rational.of("3"), Rational.of("6")), moved.map { it.basis })
        assertEquals(listOf(1L, 2L), moved.map { it.acquiredAt })
        val left = report.lots.single { it.account == exchange }
        assertEquals(units("500000000000000000"), left.quantity)
        assertEquals(Rational.of("2"), left.basis)
    }

    @Test
    fun partsThatRoundAwayLeaveTheTotalExact() {
        // Lots whose 18-decimal units do not divide into 6 decimals: the parts still add up to what arrived.
        val actions =
            listOf(
                buy("a", 1, "1000000000000", "1"),
                buy("b", 2, "999999000000000000", "1"),
                AccountingAction.Transfer(
                    ActivityId("t"),
                    3,
                    "0",
                    exchange,
                    wallet,
                    token,
                    units("1000000"),
                    coin,
                    units("1000000000000000000"),
                ),
            )
        val moved = FifoAccounting.calculate(actions, owners, usd).lots.filter { it.account == wallet }
        assertEquals(units("1000000"), moved.fold(ExactInteger.ZERO) { sum, lot -> sum + lot.quantity })
    }

    @Test
    fun differentAmountsAreRefused() {
        assertNull(decimalShift(units("1000001"), units("1000000000000000000")))
        assertEquals(12, decimalShift(units("1000000"), units("1000000000000000000")))
        assertEquals(-12, decimalShift(units("1000000000000000000"), units("1000000")))
        assertFailsWith<IllegalArgumentException> {
            FifoAccounting.calculate(
                listOf(
                    buy("a", 1, "2000000000000000000", "2"),
                    AccountingAction.Transfer(
                        ActivityId("t"),
                        3,
                        "0",
                        exchange,
                        wallet,
                        token,
                        units("1000001"),
                        coin,
                        units("1000000000000000000"),
                    ),
                ),
                owners,
                usd,
            )
        }
    }
}
