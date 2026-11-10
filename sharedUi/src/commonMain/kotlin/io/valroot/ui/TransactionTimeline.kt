package io.valroot.ui

import io.valroot.core.accounting.Treatment
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.MovementRole
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.Rational
import io.valroot.core.reconcile.EffectiveActivity
import io.valroot.ui.model.ModelMetric
import io.valroot.ui.model.ModelTransactionCard
import kotlin.time.Instant

internal data class TransactionTimeline(
    val entries: List<EffectiveActivity>,
    val cards: List<ModelTransactionCard>,
    val metrics: List<ModelMetric>,
    val total: Int,
)

/** Quantity-weighted gross execution prices exclude fees and unconfirmed/reversed records. */
internal fun transactionTimeline(
    app: PortfolioApplication,
    data: PortfolioSnapshot,
    filter: ActivityFilter,
    type: String,
): TransactionTimeline {
    val asset = requireNotNull(filter.asset)
    val accounts = app.transactionAccounts(filter.portfolio)
    val all = app.transactionEntries(filter).filter { entry -> type == "All" || transactionKind(entry, asset, accounts) == type }
    val page = all.drop(filter.offset).take(filter.limit)
    val catalog = (data.valuations + data.marketCoins.map { it.detailValuation() }).associateBy { it.asset }

    fun decimals(id: AssetId) = catalog[id]?.metadata?.decimals ?: if (id.value in listOf("USD", "EUR", "GBP")) 2 else null

    fun units(
        value: ExactInteger,
        id: AssetId,
    ): String =
        decimals(id)?.let { "${formatUnits(value, it)} ${catalog[id]?.metadata?.symbol ?: id.value}" } ?: "$value atomic ${id.value}"

    fun average(buy: Boolean): String {
        var sum = Rational.ZERO
        var quantity = Rational.ZERO
        var eligible = 0
        var count = 0
        for (entry in all.filter { it.activity.kind == ActivityKind.TRADE && it.activity.status == ActivityStatus.CONFIRMED }) {
            val movements =
                entry.activity.movements.filter {
                    it.amount.assetId == asset &&
                        it.accountId in accounts &&
                        it.role == MovementRole.TRADE_LEG &&
                        (it.amount.atomicUnits.sign > 0) == buy
                }
            for (m in movements) {
                count++
                val treatments = app.treatments(entry.activity.id)
                val total =
                    if (buy) {
                        treatments.filterIsInstance<Treatment.Acquire>().find { it.movement == m.id }?.cost
                    } else {
                        treatments
                            .filterIsInstance<Treatment.Dispose>()
                            .find {
                                it.movement ==
                                    m.id
                            }?.proceeds
                    }
                val precision = decimals(asset)
                if (total?.asset == data.currency && precision != null && entry.unresolvedMovementIds.isEmpty()) {
                    sum += total.amount
                    quantity +=
                        Rational.fromDecimal(
                            ExactDecimal(
                                if (m.amount.atomicUnits.sign <
                                    0
                                ) {
                                    -m.amount.atomicUnits
                                } else {
                                    m.amount.atomicUnits
                                },
                                precision,
                            ),
                        )
                    eligible++
                }
            }
        }
        return if (quantity ==
            Rational.ZERO
        ) {
            "—"
        } else {
            "${(sum / quantity).displayAmount()} ${data.currency.value}" +
                if (eligible < count) " · $eligible/$count priced" else ""
        }
    }
    val cards =
        page.map { entry ->
            val activity = entry.activity
            val relevant =
                activity.movements.filter {
                    it.amount.assetId == asset && it.accountId in accounts && it.role != MovementRole.FEE
                }
            val selected = relevant.ifEmpty { activity.movements.filter { it.amount.assetId == asset && it.accountId in accounts } }
            val net = selected.fold(ExactInteger.ZERO) { total, m -> total + m.amount.atomicUnits }
            val quantity =
                if (net.sign ==
                    0
                ) {
                    selected.filter { it.amount.atomicUnits.sign > 0 }.fold(ExactInteger.ZERO) { total, m -> total + m.amount.atomicUnits }
                } else if (net.sign <
                    0
                ) {
                    -net
                } else {
                    net
                }

            fun party(positive: Boolean): String {
                val candidates =
                    activity.movements
                        .filter {
                            it.amount.assetId == asset &&
                                (it.role != MovementRole.FEE || relevant.isEmpty()) &&
                                (it.amount.atomicUnits.sign > 0) == positive
                        }.map { m ->
                            data.ownership.accounts
                                .find { it.id == m.accountId }
                                ?.label
                                ?: m.accountId.value
                        }.distinct()
                return candidates.joinToString().ifEmpty {
                    activity.movements
                        .mapNotNull { it.counterparty }
                        .distinct()
                        .joinToString()
                        .ifEmpty { "—" }
                }
            }
            val fees = activity.movements.filter { it.accountId in accounts && it.role == MovementRole.FEE }
            val fee =
                fees
                    .joinToString(" + ") {
                        units(
                            if (it.amount.atomicUnits.sign <
                                0
                            ) {
                                -it.amount.atomicUnits
                            } else {
                                it.amount.atomicUnits
                            },
                            it.amount.assetId,
                        )
                    }.ifEmpty { if (activity.unallocatedFees.isEmpty()) "—" else "Unallocated · details" }

            fun worth(price: ExactDecimal?): String =
                if (price == null ||
                    decimals(asset) == null ||
                    activity.status != ActivityStatus.CONFIRMED ||
                    entry.unresolvedMovementIds.isNotEmpty()
                ) {
                    "—"
                } else {
                    money(ExactDecimal(quantity, requireNotNull(decimals(asset))) * price, data.currency)
                }
            val now =
                if (asset == data.currency) {
                    ExactDecimal.parse("1")
                } else {
                    catalog[asset]
                        ?.takeIf { it.priceStatus == PriceStatus.FRESH }
                        ?.price
                        ?.price
                        ?.value
                }
            val historic =
                if (asset == data.currency) {
                    ExactDecimal.parse("1")
                } else {
                    app
                        .priceSeries(asset, data.currency, listOf(activity.occurredAtEpochMillis))
                        .single()
                        .takeIf {
                            it.priceStatus ==
                                PriceStatus.FRESH
                        }?.price
                        ?.price
                        ?.value
                }
            val executionEvidence =
                app.transactionDraft(activity.id)?.takeIf {
                    it.quote == data.currency && entry.activity.movements == entry.original.movements
                }
            val execution =
                when {
                    executionEvidence?.asset == asset -> executionEvidence.unitPrice
                    relevant.isEmpty() && executionEvidence?.feeAsset == asset -> executionEvidence.feeUnitPrice
                    else -> null
                }
            ModelTransactionCard(
                activity.id.value,
                transactionKind(entry, asset, accounts),
                "${Instant.fromEpochMilliseconds(
                    activity.occurredAtEpochMillis,
                )} · ${activity.status.name.lowercase()} · ${if (activity.manual) "Manual" else "Imported"}",
                party(false),
                party(true),
                units(quantity, asset),
                fee,
                worth(now),
                worth(
                    execution ?: historic,
                ),
            )
        }
    return TransactionTimeline(
        page,
        cards,
        listOf(
            ModelMetric("Avg. Buy Price", average(true)),
            ModelMetric("Avg. Sell Price", average(false)),
            ModelMetric("Transactions", all.size.toString()),
        ),
        all.size,
    )
}

internal fun transactionKind(
    entry: EffectiveActivity,
    asset: AssetId,
    accounts: Set<io.valroot.core.model.AccountId>,
): String {
    val a = entry.activity
    val legs = a.movements.filter { it.accountId in accounts && it.amount.assetId == asset && it.role != MovementRole.FEE }
    if (legs.isEmpty()) return "Fee"
    // Balance corrections derived from the provider's balance, not transactions it listed.
    if (a.id.value.startsWith("evm-adjust:") || (a.id.value.startsWith("evm-rebase:") && a.kind != ActivityKind.REWARD)) return "Adjustment"
    return when (a.kind) {
        ActivityKind.TRADE ->
            if (legs
                    .first()
                    .amount.atomicUnits.sign > 0
            ) {
                "Buy"
            } else {
                "Sell"
            }
        ActivityKind.DEPOSIT -> "Deposit"
        ActivityKind.PAYMENT -> "Withdrawal"
        ActivityKind.REWARD -> "Reward"
        ActivityKind.TRANSFER -> "Transfer"
        ActivityKind.OTHER -> "Other"
    }
}
