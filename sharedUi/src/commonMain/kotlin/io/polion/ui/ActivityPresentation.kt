package io.polion.ui

import io.polion.core.application.ActivityFilter
import io.polion.core.ledger.Activity
import io.polion.core.model.AccountId
import io.polion.core.model.AssetId
import io.polion.core.model.ExactInteger
import io.polion.core.storage.OwnershipSnapshot

/** Active account intersection used to present an activity within its loaded filter. */
internal fun activityAccountScope(
    ownership: OwnershipSnapshot,
    filter: ActivityFilter,
): Set<AccountId> {
    val portfolio = filter.portfolio?.let { id -> ownership.portfolios.single { it.id == id } }
    val byId = ownership.accounts.associateBy { it.id }
    return ownership.accounts
        .filter { account ->
            var cursor: AccountId? = account.id
            var within = portfolio == null || portfolio.accountIds.isEmpty()
            while (cursor != null) {
                if (cursor in portfolio?.accountIds.orEmpty()) within = true
                cursor = byId[cursor]?.parentId
            }
            "polion:archived" !in account.tags &&
                within &&
                (portfolio == null || portfolio.ownerIds.isEmpty() || account.ownerId in portfolio.ownerIds) &&
                (filter.account == null || account.id == filter.account)
        }.map { it.id }
        .toSet()
}

internal data class ActivityRowAmounts(
    val quantities: Map<AssetId, ExactInteger>,
) {
    val directionSymbol: String
        get() {
            val signs =
                quantities.values
                    .map { it.sign }
                    .filter { it != 0 }
                    .toSet()
            return when {
                signs == setOf(1) -> "↙"
                signs == setOf(-1) -> "↗"
                else -> "↔"
            }
        }
}

/** Keeps the complete activity available to details while totaling only visible account movements. */
internal fun activityRowAmounts(
    activity: Activity,
    accounts: Set<AccountId>,
): ActivityRowAmounts =
    ActivityRowAmounts(
        activity.movements
            .filter { it.accountId in accounts }
            .groupBy { it.amount.assetId }
            .mapValues { (_, movements) -> movements.fold(ExactInteger.ZERO) { sum, movement -> sum + movement.amount.atomicUnits } },
    )
