package io.valroot.core.application

import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.PortfolioId
import io.valroot.core.ownership.AccountKind
import io.valroot.core.reconcile.ActivityOverlay
import io.valroot.core.reconcile.ClassificationRule
import io.valroot.core.reconcile.EffectiveActivity
import io.valroot.core.reconcile.LinkState

/** Manual transactions, overlays on recorded activities, rules and duplicate decisions. */
class TransactionService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    /** Commit all linked legs, treatments and notes together, retaining append-only manual revisions. */
    fun saveTransaction(
        draft: TransactionDraft,
        editing: Boolean = false,
    ) {
        app.assets.ensureDefaults()
        val prepared = prepareTransaction(draft, editing)
        db.transaction { writeTransaction(draft, prepared) }
        invalidate()
    }

    /**
     * Validates [draft] against the current accounts and asset precision. A new entry needs an unused ID; [restoring]
     * also accepts an ID whose latest revision is a reversed manual entry, which an import brings back.
     */
    internal fun prepareTransaction(
        draft: TransactionDraft,
        editing: Boolean,
        restoring: Boolean = false,
    ): PreparedTransaction {
        val previous = activities.history(draft.id).lastOrNull()
        val restorable = restoring && previous?.manual == true && previous.status == io.valroot.core.ledger.ActivityStatus.REVERSED
        require(restorable || if (editing) previous?.manual == true else previous == null) {
            "Entry identity already exists or cannot be edited"
        }
        require(restorable || previous == null || previous.status == io.valroot.core.ledger.ActivityStatus.CONFIRMED) {
            "Only active manual entries can be edited"
        }
        require(
            overlays.overlay(draft.id)?.second?.let {
                it.amounts.isEmpty() && it.kind == null
            } != false ||
                !editing,
        ) { "Edit this corrected entry through its correction tools" }
        val accounts =
            app.assets.scopedAccounts(ownership.load(), null).intersect(
                ownership
                    .load()
                    .accounts
                    .filter {
                        it.kind != AccountKind.GROUP
                    }.map { it.id }
                    .toSet(),
            )
        require(draft.account in accounts && (draft.destination == null || draft.destination in accounts)) { "Select active accounts" }
        if (draft.destination != null) {
            val catalog = ownership.load().accounts.associateBy { it.id }
            require(catalog.getValue(draft.account).ownerId == catalog.getValue(draft.destination).ownerId) {
                "Internal transfers require accounts with the same owner"
            }
        }
        val assets = listOfNotNull(draft.asset, draft.quote, draft.feeAsset).distinct()
        val precision = assets.associateWith { requireNotNull(market.metadataAt(it, draft.atMillis)) { "Unknown asset" }.decimals }
        return TransactionDrafts.prepare(draft, precision)
    }

    /** Writes a prepared entry; callers hold the database transaction. */
    internal fun writeTransaction(
        draft: TransactionDraft,
        prepared: PreparedTransaction,
    ) {
        activities.saveInTransaction(prepared.activity)
        treatments.saveInTransaction(draft.id, prepared.treatments)
        overlays.saveOverlayInTransaction(ActivityOverlay(draft.id, label = overlays.overlay(draft.id)?.second?.label, note = draft.notes))
        db.execute(
            "INSERT OR REPLACE INTO application_settings VALUES (?, ?)",
            listOf("transaction_draft:${draft.id.value}", draft.encode()),
        )
    }

    /** Effective evidence intersecting the requested scope; callers paginate after computing summary metrics. */
    fun entries(filter: ActivityFilter): List<EffectiveActivity> {
        val accounts =
            app.assets
                .scopedAccounts(ownership.load(), filter.portfolio)
                .filter {
                    filter.account == null || it == filter.account
                }.toSet()
        return ledger()
            .activities
            .filter { entry ->
                val a = entry.activity
                a.movements.any { it.accountId in accounts && (filter.asset == null || it.amount.assetId == filter.asset) } &&
                    (filter.kind == null || a.kind == filter.kind) &&
                    (filter.status == null || a.status == filter.status) &&
                    (
                        filter.search.isBlank() ||
                            listOfNotNull(entry.label, entry.note, a.id.value, a.kind.name).any { it.contains(filter.search, true) }
                    )
            }.sortedWith(compareByDescending<EffectiveActivity> { it.activity.occurredAtEpochMillis }.thenBy { it.activity.id.value })
    }

    fun accounts(portfolio: PortfolioId?): Set<AccountId> = app.assets.scopedAccounts(ownership.load(), portfolio)

    fun draft(id: ActivityId): TransactionDraft? =
        setting("transaction_draft:${id.value}")?.let(TransactionDraft::decode)?.let { draft ->
            overlays.overlay(id)?.let { draft.copy(notes = it.second.note.orEmpty()) } ?: draft
        }

    /** Reversal is a retained manual revision; imported evidence can only be corrected by overlays. */
    fun reverseManual(id: ActivityId) {
        val previous = requireNotNull(activities.history(id).lastOrNull()) { "Unknown entry" }
        require(previous.manual && previous.status == io.valroot.core.ledger.ActivityStatus.CONFIRMED) {
            "Only active manual entries can be reversed"
        }
        activities.save(previous.copy(status = io.valroot.core.ledger.ActivityStatus.REVERSED))
        invalidate()
    }

    fun saveManualActivity(value: Activity) {
        require(value.manual)
        val accountIds =
            ownership
                .load()
                .accounts
                .map { it.id }
                .toSet()
        require(value.movements.all { it.accountId in accountIds }) { "Unknown account" }
        activities.save(value)
        invalidate()
    }

    fun saveOverlay(value: ActivityOverlay): Int {
        require(activities.current().any { it.id == value.activityId }) { "Unknown activity" }
        return overlays.saveOverlay(value).also { invalidate() }
    }

    fun overlay(id: ActivityId): ActivityOverlay? = overlays.overlay(id)?.second

    fun rules(): List<ClassificationRule> = overlays.rules().map { it.second }

    fun saveRule(value: ClassificationRule): Int = overlays.saveRule(value).also { invalidate() }

    fun setDecision(
        first: ActivityId,
        second: ActivityId,
        state: LinkState,
    ): Int = overlays.setDecision(first, second, state).also { invalidate() }

    fun setDuplicateDecision(
        first: ActivityId,
        second: ActivityId,
        canonical: ActivityId,
        state: LinkState,
    ): Int = overlays.setDuplicateDecision(first, second, canonical, state).also { invalidate() }
}
