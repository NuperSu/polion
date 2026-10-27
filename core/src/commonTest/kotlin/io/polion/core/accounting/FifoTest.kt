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
                AccountingAction.Acquisition(ActivityId("open"), 0, "a", a, btc, n("3"), q("10"), true),
                AccountingAction.Acquisition(ActivityId("buy"), 1, "a", a, btc, n("2"), q("12")),
                AccountingAction.Transfer(ActivityId("transfer"), 2, "a", a, b, btc, n("2")),
                AccountingAction.Disposal(ActivityId("trade"), 3, "a", b, btc, n("1"), q("8")),
                AccountingAction.Acquisition(ActivityId("trade"), 3, "b", b, eth, n("5"), q("8")),
                AccountingAction.Disposal(ActivityId("fee"), 4, "a", a, btc, n("1"), q("2"), true),
            )
        val report = FifoAccounting.calculate(acts, owners, mapOf(eth to q("3")))
        assertEquals(q("10") / q("3"), report.disposals[0].basis)
        assertEquals(q("14") / q("3"), report.disposals[0].gain)
        assertEquals(q("10") / q("3"), report.disposals[1].basis)
        assertEquals(q("7"), report.valuations.single { it.lot.asset == eth }.unrealizedGain)
        assertEquals(n("3"), report.lots.filter { it.asset == btc }.fold(n("0")) { x, l -> x + l.quantity })
        assertEquals(report, FifoAccounting.calculate(acts.reversed(), owners, mapOf(eth to q("3"))))
    }

    @Test fun unknownBasisAndPriceAreNotInvented() {
        val report =
            FifoAccounting.calculate(
                listOf(
                    AccountingAction.Acquisition(ActivityId("missing"), 0, "a", a, btc, n("1"), null),
                    AccountingAction.Disposal(ActivityId("sale"), 1, "a", a, btc, n("2"), q("5")),
                    AccountingAction.Acquisition(ActivityId("unpriced"), 2, "a", a, btc, n("1"), q("3")),
                ),
                owners,
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
                        q("5"),
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
                    AccountingAction.Acquisition(ActivityId("opening"), 0, "a", a, btc, n("1"), q("7"), true),
                    AccountingAction.Transfer(ActivityId("move"), 1, "a", a, b, btc, n("2")),
                    AccountingAction.Disposal(ActivityId("fee"), 2, "a", b, btc, n("2"), null, true),
                ),
                owners,
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
}
