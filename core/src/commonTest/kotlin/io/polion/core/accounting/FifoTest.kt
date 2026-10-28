package io.polion.core.accounting

import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.ExactInteger
import io.polion.core.model.OwnerId
import io.polion.core.model.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FifoTest {
    private val usd = AssetId("usd")

    private fun v(n: String) = QuoteValue(usd, q(n))

    private val btc = AssetId("btc")
    private val eth = AssetId("eth")
    private val a = AccountId("a")
    private val b = AccountId("b")
    private val owners = mapOf(a to OwnerId("o"), b to OwnerId("o"))

    private fun n(n: String) = ExactInteger.parse(n)

    private fun q(n: String) = Rational.of(n)

    @Test fun fifoTransferTradeAndFeesAreExact() {
        val acts =
            listOf(
                AccountingAction.Acquisition(ActivityId("open"), 0, "a", a, btc, n("3"), v("10"), true),
                AccountingAction.Acquisition(ActivityId("buy"), 1, "a", a, btc, n("2"), v("12")),
                AccountingAction.Transfer(ActivityId("transfer"), 2, "a", a, b, btc, n("2")),
                AccountingAction.Disposal(ActivityId("trade"), 3, "a", b, btc, n("1"), v("8")),
                AccountingAction.Acquisition(ActivityId("trade"), 3, "b", b, eth, n("5"), v("8")),
                AccountingAction.Disposal(ActivityId("fee"), 4, "a", a, btc, n("1"), v("2"), true),
            )
        val report = FifoAccounting.calculate(acts, owners, usd, mapOf(eth to v("3")))
        assertEquals(q("10") / q("3"), report.disposals[0].basis)
        assertEquals(q("14") / q("3"), report.disposals[0].gain)
        assertEquals(q("10") / q("3"), report.disposals[1].basis)
        assertEquals(q("7"), report.valuations.single { it.lot.asset == eth }.unrealizedGain)
        assertEquals(n("3"), report.lots.filter { it.asset == btc }.fold(n("0")) { x, l -> x + l.quantity })
        assertEquals(report, FifoAccounting.calculate(acts.reversed(), owners, usd, mapOf(eth to v("3"))))
    }

    @Test fun explicitReplayOrderPreservesAcquisitionAgeAcrossTransfers() {
        val actions =
            listOf(
                AccountingAction.Acquisition(ActivityId("first"), 200, "01", a, btc, n("3"), v("9")),
                AccountingAction.Acquisition(ActivityId("second"), 100, "02", a, btc, n("3"), v("30")),
                AccountingAction.Transfer(ActivityId("move"), 50, "03", a, b, btc, n("2")),
                AccountingAction.Disposal(ActivityId("sell"), 0, "04", b, btc, n("1"), v("8")),
            )
        val report = FifoAccounting.calculate(actions.reversed(), owners, usd, replayOrder = compareBy { it.order })
        assertEquals(q("3"), report.disposals.single().basis)
        assertEquals(q("5"), report.disposals.single().gain)
        assertEquals(200, report.lots.single { it.account == b }.acquiredAt)
        assertEquals(q("3"), report.lots.single { it.account == b }.basis)
    }

    @Test fun unknownBasisAndPriceAreNotInvented() {
        val report =
            FifoAccounting.calculate(
                listOf(
                    AccountingAction.Acquisition(ActivityId("missing"), 0, "a", a, btc, n("1"), null),
                    AccountingAction.Disposal(ActivityId("sale"), 1, "a", a, btc, n("2"), v("5")),
                    AccountingAction.Acquisition(ActivityId("unpriced"), 2, "a", a, btc, n("1"), v("3")),
                ),
                owners,
                usd,
            )
        assertNull(report.disposals.single().basis)
        assertNull(report.disposals.single().gain)
        assertNull(report.valuations.single().value)
        assertEquals(
            null,
            report.disposals
                .single()
                .consumed
                .last()
                .lotId,
        )
        assertFailsWith<IllegalArgumentException> {
            FifoAccounting.calculate(
                listOf(AccountingAction.Transfer(ActivityId("bad"), 0, "a", a, AccountId("foreign"), btc, n("1"))),
                owners,
                usd,
            )
        }
    }

    @Test fun ledgerRequiresCompleteExplicitClassification() {
        val activity =
            io.polion.core.ledger.Activity(
                ActivityId("sale"),
                1,
                "a",
                io.polion.core.ledger.ActivityKind.PAYMENT,
                io.polion.core.ledger.ActivityStatus.CONFIRMED,
                listOf(
                    io.polion.core.ledger.Movement(
                        io.polion.core.model
                            .EventId("m"),
                        a,
                        io.polion.core.model
                            .AtomicAmount(btc, n("-2")),
                        io.polion.core.ledger.MovementRole.TRANSFER,
                    ),
                ),
                setOf(
                    io.polion.core.model
                        .SourceRecordId("raw"),
                ),
                "1",
            )
        assertFailsWith<IllegalArgumentException> { LedgerAccounting.actions(activity, emptyList()) }
        val actions =
            LedgerAccounting.actions(
                activity,
                listOf(
                    Treatment.Dispose(
                        io.polion.core.model
                            .EventId("m"),
                        v("5"),
                    ),
                ),
            )
        assertEquals(n("2"), (actions.single() as AccountingAction.Disposal).quantity)
        assertEquals(
            emptyList(),
            LedgerAccounting.actions(activity.copy(status = io.polion.core.ledger.ActivityStatus.REVERSED), emptyList()),
        )
    }

    @Test fun transferWithMissingInventoryAndUnpricedFeePreservesUncertainty() {
        val report =
            FifoAccounting.calculate(
                listOf(
                    AccountingAction.Acquisition(ActivityId("opening"), 0, "a", a, btc, n("1"), v("7"), true),
                    AccountingAction.Transfer(ActivityId("move"), 1, "a", a, b, btc, n("2")),
                    AccountingAction.Disposal(ActivityId("fee"), 2, "a", b, btc, n("2"), null, true),
                ),
                owners,
                usd,
            )
        assertNull(report.disposals.single().basis)
        assertNull(report.disposals.single().gain)
        assertEquals(
            q("7"),
            report.disposals
                .single()
                .consumed
                .first()
                .basis,
        )
        assertNull(
            report.disposals
                .single()
                .consumed
                .last()
                .basis,
        )
        assertEquals(true, report.disposals.single().fee)
    }

    @Test fun rationalFractionsNormalizeAndRetainSign() {
        val third = q("1") / q("3")
        assertEquals(q("1"), third + third + third)
        assertEquals(q("-2") / q("3"), q("2") / q("-3"))
        assertEquals(0, (q("0") / q("20")).numerator.sign)
        assertEquals(q("1").hashCode(), (q("2") / q("2")).hashCode())
    }

    @Test fun mixedQuoteCurrenciesAreRejected() {
        val action = AccountingAction.Acquisition(ActivityId("x"), 0, "a", a, btc, n("1"), QuoteValue(AssetId("eur"), q("1")))
        assertFailsWith<IllegalArgumentException> { FifoAccounting.calculate(listOf(action), owners, usd) }
        assertFailsWith<IllegalArgumentException> {
            FifoAccounting.calculate(
                emptyList(),
                owners,
                usd,
                mapOf(
                    btc to QuoteValue(AssetId("eur"), q("1")),
                ),
            )
        }
    }
}
