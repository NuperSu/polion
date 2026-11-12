package io.valroot.core.application

import io.valroot.core.accounting.AccountingReport
import io.valroot.core.accounting.FifoAccounting
import io.valroot.core.accounting.LedgerAccounting
import io.valroot.core.accounting.QuoteValue
import io.valroot.core.accounting.Treatment
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.PortfolioId
import io.valroot.core.model.Rational
import io.valroot.core.reconcile.EffectiveActivity
import io.valroot.core.reconcile.Reconciliation
import io.valroot.core.reconcile.ReconciliationCandidate
import io.valroot.core.reconcile.TransferLink
import io.valroot.core.reconcile.TransferLinkConflict
import kotlin.time.Instant

/** Cost basis and gains: accounting treatments, the accounting report, transfer links and reconciliation. */
class AccountingService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    /** The on-chain transaction behind an activity: its own for Bitcoin and EVM, the exchange's record for exchanges. */
    private fun transactionOf(
        activity: Activity,
        exchange: Map<ActivityId, io.valroot.core.exchange.ExchangeTransfer>,
    ): String? {
        val id = activity.id.value
        return when {
            activity.id in exchange -> exchange.getValue(activity.id).txId.lowercase()
            id.startsWith("bitcoin:") -> id.removePrefix("bitcoin:").lowercase()
            id.startsWith("eip155:") ->
                id
                    .split(':')
                    .getOrNull(2)
                    ?.takeIf { it.startsWith("0x") }
                    ?.lowercase()
            else -> null
        }
    }

    fun saveTreatments(
        activityId: ActivityId,
        values: List<Treatment>,
    ) {
        val activity =
            ledger().activities.firstOrNull { it.activity.id == activityId }?.activity
                ?: throw IllegalArgumentException("Unknown activity")
        LedgerAccounting.actions(activity, values)
        treatments.save(activityId, values)
    }

    /** Resolve an effective activity only when it intersects the current portfolio scope. */
    fun effectiveActivity(
        activityId: ActivityId,
        portfolio: PortfolioId? = null,
    ): EffectiveActivity? {
        val selected = app.assets.scopedAccounts(ownership.load(), portfolio)
        return ledger().activities.firstOrNull { entry ->
            entry.activity.id == activityId && entry.activity.movements.any { it.accountId in selected }
        }
    }

    /** Reward acquisitions become known zero-cost lots in the report currency; other treatments are unchanged. */
    private fun withZeroBasisRewards(
        activity: Activity,
        saved: List<io.valroot.core.accounting.Treatment>,
        quote: AssetId,
    ): List<io.valroot.core.accounting.Treatment> {
        val rewards =
            activity.movements
                .filter { it.role == io.valroot.core.ledger.MovementRole.REWARD }
                .map { it.id }
                .toSet()
        return saved.map { treatment ->
            if (treatment is io.valroot.core.accounting.Treatment.Acquire && treatment.movement in rewards) {
                treatment.copy(cost = QuoteValue(quote, Rational.ZERO), opening = false)
            } else {
                treatment
            }
        }
    }

    fun report(portfolio: PortfolioId? = null): AccountingView {
        app.assets.ensureDefaults()
        val catalog = ownership.load()
        val selected = app.assets.scopedAccounts(catalog, portfolio)
        val missing = mutableListOf<ActivityId>()
        val invalid = mutableListOf<ActivityId>()
        val orderingUncertain = mutableListOf<ActivityId>()
        val currencyMismatch = mutableListOf<ActivityId>()
        val quote = app.assets.portfolioCurrency(portfolio)
        val zeroBasisRewards = app.settings.general().zeroBasisRewards
        val priceAt = now()
        val confirmedLedger = ledger().activities.filter { it.activity.status == io.valroot.core.ledger.ActivityStatus.CONFIRMED }
        // Replay each owner's full inventory before selecting accounts, preserving transferred lot age and basis.
        val reports =
            catalog.accounts.filter { it.id in selected }.map { it.ownerId }.distinct().map { owner ->
                val ownerAccounts =
                    catalog.accounts
                        .filter { it.ownerId == owner }
                        .map { it.id }
                        .toSet()

                fun ownerAssets(activity: Activity) = activity.movements.filter { it.accountId in ownerAccounts }.map { it.amount.assetId }

                val actions = mutableListOf<io.valroot.core.accounting.AccountingAction>()
                val confirmed = confirmedLedger.filter { entry -> entry.activity.movements.any { it.accountId in ownerAccounts } }
                val selectedAssets =
                    confirmed
                        .flatMap { entry ->
                            entry.activity.movements
                                .filter { it.accountId in selected && it.accountId in ownerAccounts }
                                .map { it.amount.assetId }
                        }.toSet()

                // Missing or invalid inventory in the owner/asset pool can block lots transferred into selected accounts.
                fun affectsSelection(activity: Activity) = ownerAssets(activity).any { it in selectedAssets }

                val incompleteAssets = mutableSetOf<AssetId>()
                // An accepted transfer link between two of this owner's accounts moves lots instead of disposing and
                // re-acquiring them, when the outgoing side is a disposal and the incoming side an acquisition.
                val byId = confirmed.associateBy { it.activity.id }
                val linked =
                    ledger().transferLinks.filter { link ->
                        val out = byId[link.outgoingActivity]?.activity?.movements?.firstOrNull { it.id == link.outgoingEvent }
                        val into = byId[link.incomingActivity]?.activity?.movements?.firstOrNull { it.id == link.incomingEvent }
                        out != null &&
                            into != null &&
                            out.accountId in ownerAccounts &&
                            into.accountId in ownerAccounts &&
                            out.accountId != into.accountId &&
                            treatments.load(link.outgoingActivity).any { it is Treatment.Dispose && it.movement == link.outgoingEvent } &&
                            treatments.load(link.incomingActivity).any { it is Treatment.Acquire && it.movement == link.incomingEvent }
                    }
                val linkedOut = linked.associateBy { it.outgoingActivity to it.outgoingEvent }
                val linkedIn = linked.map { it.incomingActivity to it.incomingEvent }.toSet()
                confirmed.forEach { entry ->
                    val activity = entry.activity
                    try {
                        val saved =
                            treatments
                                .load(
                                    activity.id,
                                ).let { if (zeroBasisRewards) withZeroBasisRewards(activity, it, quote) else it }
                        if (saved.isEmpty()) {
                            if (affectsSelection(activity)) missing.add(activity.id)
                            incompleteAssets.addAll(ownerAssets(activity))
                        } else {
                            val computed =
                                LedgerAccounting
                                    .actions(activity, saved)
                                    .flatMapIndexed { index, action ->
                                        val treatment = saved[index]
                                        val link = (treatment as? Treatment.Dispose)?.let { linkedOut[activity.id to it.movement] }
                                        when {
                                            link != null -> {
                                                val out = activity.movements.first { it.id == link.outgoingEvent }
                                                val into =
                                                    byId.getValue(link.incomingActivity).activity.movements.first {
                                                        it.id ==
                                                            link.incomingEvent
                                                    }
                                                listOf(
                                                    io.valroot.core.accounting.AccountingAction.Transfer(
                                                        activity.id,
                                                        activity.occurredAtEpochMillis,
                                                        action.order,
                                                        out.accountId,
                                                        into.accountId,
                                                        into.amount.assetId,
                                                        into.amount.atomicUnits,
                                                        out.amount.assetId,
                                                        -out.amount.atomicUnits,
                                                    ),
                                                )
                                            }
                                            treatment is Treatment.Acquire && (activity.id to treatment.movement) in linkedIn -> emptyList()
                                            else -> listOf(action)
                                        }
                                    }.filter { action ->
                                        when (action) {
                                            is io.valroot.core.accounting.AccountingAction.Acquisition -> action.account in ownerAccounts
                                            is io.valroot.core.accounting.AccountingAction.Disposal -> action.account in ownerAccounts
                                            is io.valroot.core.accounting.AccountingAction.Transfer ->
                                                action.from in ownerAccounts || action.to in ownerAccounts
                                        }
                                    }
                            require(
                                computed.all { action ->
                                    when (action) {
                                        is io.valroot.core.accounting.AccountingAction.Transfer ->
                                            action.from in ownerAccounts && action.to in ownerAccounts && action.from != action.to
                                        else -> true
                                    }
                                },
                            ) { "Internal transfer requires distinct accounts with the same owner" }
                            // A treatment recorded in another quote currency keeps its lot and quantity, but its cost or
                            // proceeds are unknown in this report's currency. Its basis and gain therefore stay unknown.
                            val normalized =
                                computed.map { action ->
                                    when (action) {
                                        is io.valroot.core.accounting.AccountingAction.Acquisition ->
                                            if (action.totalCost != null && action.totalCost.asset != quote) {
                                                currencyMismatch.add(action.activityId)
                                                action.copy(totalCost = null)
                                            } else {
                                                action
                                            }
                                        is io.valroot.core.accounting.AccountingAction.Disposal ->
                                            if (action.proceeds != null && action.proceeds.asset != quote) {
                                                currencyMismatch.add(action.activityId)
                                                action.copy(proceeds = null)
                                            } else {
                                                action
                                            }
                                        is io.valroot.core.accounting.AccountingAction.Transfer -> action
                                    }
                                }
                            require(
                                normalized.all { action ->
                                    val value =
                                        when (action) {
                                            is io.valroot.core.accounting.AccountingAction.Acquisition -> action.totalCost
                                            is io.valroot.core.accounting.AccountingAction.Disposal -> action.proceeds
                                            is io.valroot.core.accounting.AccountingAction.Transfer -> null
                                        }
                                    value == null || value.amount >= Rational.ZERO
                                },
                            )
                            actions.addAll(normalized)
                        }
                    } catch (_: IllegalArgumentException) {
                        if (affectsSelection(activity)) invalid.add(activity.id)
                        incompleteAssets.addAll(ownerAssets(activity))
                    }
                }
                val bitcoin = confirmed.filter { !it.original.manual && it.original.decoderVersion?.startsWith("bitcoin-") == true }
                val bitcoinAssets = bitcoin.flatMap { ownerAssets(it.activity) }.toSet()
                val mixed =
                    confirmed.filter { entry ->
                        entry !in bitcoin &&
                            entry.activity.movements.any { it.accountId in ownerAccounts && it.amount.assetId in bitcoinAssets }
                    }
                val unverified = bitcoin.filter { !it.original.orderingKey.startsWith("bitcoin-chain:") }
                val uncertain = (unverified + if (mixed.isNotEmpty()) bitcoin + mixed else emptyList()).distinct()
                val ownerOrderingUncertain =
                    uncertain
                        .filter { entry ->
                            affectsSelection(entry.activity)
                        }.map { it.activity.id }
                // Chain positions establish relative order only among Bitcoin activities. Mixed sources
                // use the timestamp fallback and remain uncertain until cross-source order is explicit.
                val chainOrder = bitcoin.associate { it.activity.id to it.original.orderingKey }
                val replayOrder =
                    if (bitcoin.isNotEmpty() && unverified.isEmpty() && mixed.isEmpty()) {
                        compareBy<io.valroot.core.accounting.AccountingAction>(
                            { chainOrder[it.activityId] ?: "" },
                            { it.order },
                            { it.activityId.value },
                        )
                    } else {
                        compareBy({ it.at }, { it.order }, { it.activityId.value })
                    }
                val unitPrices =
                    confirmed
                        .flatMap { entry -> ownerAssets(entry.activity) }
                        .toSet()
                        .associateWith { app.assets.unitPrice(it, quote, priceAt) }
                val calculated =
                    FifoAccounting.calculate(
                        actions.filter { action ->
                            val asset =
                                when (action) {
                                    is io.valroot.core.accounting.AccountingAction.Acquisition -> action.asset
                                    is io.valroot.core.accounting.AccountingAction.Disposal -> action.asset
                                    is io.valroot.core.accounting.AccountingAction.Transfer -> action.asset
                                }
                            asset !in incompleteAssets
                        },
                        catalog.accounts.associate { it.id to it.ownerId },
                        quote,
                        unitPrices = unitPrices,
                        replayOrder = replayOrder,
                    )
                val uncertainAssets = uncertain.flatMap { ownerAssets(it.activity) }.toSet()
                val report =
                    if (uncertain.isEmpty()) {
                        calculated
                    } else {
                        calculated.copy(
                            lots = calculated.lots.map { if (it.asset in uncertainAssets) it.copy(basis = null) else it },
                            disposals =
                                calculated.disposals.map { disposal ->
                                    if (disposal.asset in
                                        uncertainAssets
                                    ) {
                                        disposal.copy(
                                            basis = null,
                                            gain = null,
                                            consumed =
                                                disposal.consumed.map {
                                                    it.copy(
                                                        lotId = null,
                                                        basis = null,
                                                        acquisitionActivityId = null,
                                                    )
                                                },
                                        )
                                    } else {
                                        disposal
                                    }
                                },
                            valuations =
                                calculated.valuations.map {
                                    if (it.lot.asset in
                                        uncertainAssets
                                    ) {
                                        it.copy(lot = it.lot.copy(basis = null), unrealizedGain = null)
                                    } else {
                                        it
                                    }
                                },
                        )
                    }
                orderingUncertain.addAll(ownerOrderingUncertain)
                report
            }
        return AccountingView(
            AccountingReport(
                quote,
                reports.flatMap { it.lots }.filter { it.account in selected },
                reports.flatMap { it.disposals }.filter { it.account in selected },
                reports.flatMap { it.valuations }.filter { it.lot.account in selected },
            ),
            missing.distinct(),
            invalid.distinct(),
            orderingUncertain.distinct(),
            currencyMismatch.distinct(),
        )
    }

    fun transferLinks(): List<TransferLink> = ledger().transferLinks

    fun transferLinkConflicts(): List<TransferLinkConflict> = ledger().transferLinkConflicts

    /**
     * A short description for review lists: the activity's UTC date and each movement as account, signed amount and
     * symbol, for example "2026-01-20 · Exchange −0.1 BTC". Null for an unknown activity.
     */
    fun activitySummary(id: ActivityId): String? {
        val activity = ledger().activities.firstOrNull { it.activity.id == id }?.activity ?: return null
        val labels = ownership.load().accounts.associate { it.id to it.label }
        val date =
            kotlin.time.Instant
                .fromEpochMilliseconds(activity.occurredAtEpochMillis)
                .toString()
                .take(10)
        val movements =
            activity.movements.joinToString(", ") { movement ->
                val metadata = market.metadataAt(movement.amount.assetId, activity.occurredAtEpochMillis)
                val units = movement.amount.atomicUnits
                val amount =
                    metadata?.let { ExactDecimal(units, it.decimals).toString().trimEnd('0').trimEnd('.') } ?: units.toString()
                val signed = if (units.sign < 0) "−" + amount.removePrefix("-") else "+$amount"
                "${labels[movement.accountId] ?: movement.accountId.value} $signed ${metadata?.symbol ?: movement.amount.assetId.value}"
            }
        return "$date · $movements"
    }

    fun reconciliationCandidates(windowMillis: Long): List<ReconciliationCandidate> {
        val exchange = exchangeSync.transfers()
        val reviewedPairs = overlays.decisions().map { it.first to it.second }.toSet()
        val pending =
            Reconciliation
                .suggest(ledger().activities.map { it.activity }, windowMillis, amountScale()) { activity ->
                    transactionOf(activity, exchange)
                }.filterNot { (it.first to it.second) in reviewedPairs }
        val counts = pending.flatMap { listOf(it.first, it.second) }.groupingBy { it }.eachCount()
        return pending.map {
            it.copy(ambiguous = it.matchedMovements > 1 || counts.getValue(it.first) > 1 || counts.getValue(it.second) > 1)
        }
    }

    fun treatments(activityId: ActivityId): List<Treatment> =
        try {
            treatments.load(activityId)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
}
