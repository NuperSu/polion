package io.polion.ui

import io.polion.core.accounting.AccountingReport
import io.polion.core.accounting.DisposalReport
import io.polion.core.accounting.Lot
import io.polion.core.accounting.LotValuation
import io.polion.core.application.AccountingView
import io.polion.core.application.AssetValuation
import io.polion.core.application.HistoryPoint
import io.polion.core.market.PriceStatus
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.ExactDecimal
import io.polion.core.model.ExactInteger
import io.polion.core.model.OwnerId
import io.polion.core.model.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhaseTwoProjectionTest {
    private val asset = AssetId("BTC")
    private val account = AccountId("wallet")

    private fun valuation(
        value: String?,
        status: PriceStatus = PriceStatus.FRESH,
    ) = AssetValuation(
        asset,
        AtomicAmount(asset, ExactInteger.parse("1")),
        null,
        status,
        value?.let(ExactDecimal::parse),
        null,
    )

    @Test
    fun usdDisplayIsBoundedToCentsWithoutChangingTinyPrices() {
        val original = ExactDecimal.parse("1234.56789123456789")
        assertEquals("$1,234.57", quotePrice(original, AssetId("USD")))
        assertEquals("1234.56789123456789", original.toString())
        assertEquals("<$0.01", quotePrice(ExactDecimal.parse("0.000000001"), AssetId("USD")))
        assertEquals("$0.00", quotePrice(ExactDecimal.parse("0"), AssetId("USD")))
    }

    @Test
    fun contributionsUseExactNativeDifferencesAndExcludeTransfers() {
        val before =
            io.polion.core.application.AnalyticsSnapshot(
                0,
                AssetId("USD"),
                emptyList(),
                emptyList(),
                listOf(
                    io.polion.core.application.CategoryAnalytics(
                        io.polion.core.ledger.ActivityKind.DEPOSIT,
                        asset,
                        ExactInteger.parse("100"),
                        ExactInteger.ZERO,
                        1,
                    ),
                ),
                io.polion.core.holdings.BalanceCoverage.UNKNOWN,
            )
        val after =
            before.copy(
                atMillis = 1000,
                categories =
                    listOf(
                        before.categories.single().copy(incoming = ExactInteger.parse("150")),
                        io.polion.core.application.CategoryAnalytics(
                            io.polion.core.ledger.ActivityKind.TRANSFER,
                            asset,
                            ExactInteger.parse("999"),
                            ExactInteger.ZERO,
                            1,
                        ),
                    ),
            )
        val summary = portfolioMovementSummary(before, after)
        assertTrue(summary.contains("50 incoming"))
        assertTrue(!summary.contains("999"))
    }

    @Test
    fun historyRangesUseDistinctIntradayAndUtcYearBoundaries() {
        val end =
            kotlin.time.Instant
                .parse("2026-06-15T12:00:00Z")
                .toEpochMilliseconds()
        assertEquals(end - 3_600_000L, historyRangeStart(-1, end, null))
        assertEquals(
            kotlin.time.Instant
                .parse("2026-01-01T00:00:00Z")
                .toEpochMilliseconds(),
            historyRangeStart(-2, end, null),
        )
        assertEquals(end - 86_400_000L, historyRangeStart(1, end, null))
        assertEquals(123L, historyRangeStart(0, end, 123L))
    }

    @Test
    fun valueChangesUseEndpointsAndRejectUnpricedOrStaleBoundaries() {
        val points = listOf(HistoryPoint(0, listOf(valuation("100"))), HistoryPoint(1000, listOf(valuation("125"))))
        assertEquals("25.00%", portfolioValuePercent(points))
        assertTrue(portfolioValueChange(points, AssetId("USD"), "1D").contains("25.00"))
        assertEquals("—", portfolioValuePercent(listOf(points.first(), HistoryPoint(1000, listOf(valuation(null))))))
        assertEquals("—", portfolioValuePercent(listOf(HistoryPoint(0, listOf(valuation("100", PriceStatus.STALE))), points.last())))
        assertEquals("—", portfolioValuePercent(listOf(HistoryPoint(0, listOf(valuation("0"))), points.last())))
    }

    @Test
    fun unitPricePercentPreservesTinyExactPricesAndMissingEvidence() {
        assertEquals(
            "Price change · 1H · 100.00%",
            unitPriceChange(ExactDecimal.parse("0.000000000000000001"), ExactDecimal.parse("0.000000000000000002"), "1H"),
        )
        assertEquals("Price change · 1H · —", unitPriceChange(null, ExactDecimal.parse("1"), "1H"))
    }

    @Test
    fun accountingMetricsReconcileAndDoNotInventOpeningBasis() {
        val lot = Lot("lot", OwnerId("owner"), account, asset, 0, "0", ExactInteger.parse("1"), Rational.of("100"), false)
        val sale =
            DisposalReport(
                ActivityId("sale"),
                account,
                asset,
                ExactInteger.parse("1"),
                Rational.of("20"),
                Rational.of("13"),
                Rational.of("7"),
                false,
                emptyList(),
            )
        val report =
            AccountingReport(AssetId("USD"), listOf(lot), listOf(sale), listOf(LotValuation(lot, Rational.of("130"), Rational.of("30"))))
        val view = AccountingView(report, emptyList(), emptyList(), emptyList())
        val metrics = coinAccountingMetrics(view, valuation("130")).associate { it.label to it.value }
        assertEquals("37.00 USD", metrics["Total Gains"])
        assertEquals("100.00 USD", metrics["Total Cost Basis"])
        val opening = lot.copy(basis = null, opening = true)
        val unknown =
            view.copy(
                report = report.copy(lots = listOf(opening), valuations = listOf(LotValuation(opening, Rational.of("130"), null))),
            )
        val missing = coinAccountingMetrics(unknown, valuation("130")).associate { it.label to it.value }
        assertEquals("—", missing["Total Cost Basis"])
        assertEquals("—", missing["Total Gains"])
        assertTrue(
            coinAccountingMetrics(
                view.copy(orderingUncertain = listOf(ActivityId("sale"))),
                valuation("130"),
            ).first().value.contains("provisional"),
        )
    }
}
