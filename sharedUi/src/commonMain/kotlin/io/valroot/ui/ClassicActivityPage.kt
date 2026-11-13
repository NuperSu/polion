package io.valroot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.model.AccountId
import io.valroot.core.reconcile.EffectiveActivity

/** Classic Activity page: filters, search, and paged activity rows. */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.classicActivityItems(
    search: String,
    setSearch: (String) -> Unit,
    kind: ActivityKind?,
    setKind: (ActivityKind?) -> Unit,
    offset: Int,
    /** Changes the page offset from its current value. */
    updateOffset: ((Int) -> Int) -> Unit,
    activityStatus: ActivityStatus?,
    setActivityStatus: (ActivityStatus?) -> Unit,
    activityNetwork: String?,
    setActivityNetwork: (String?) -> Unit,
    selectedAccount: AccountId?,
    setSelectedAccount: (AccountId?) -> Unit,
    setSelectedActivity: (EffectiveActivity?) -> Unit,
    setDialog: (String?) -> Unit,
    data: PortfolioSnapshot,
    compact: Boolean,
    presenter: ProductPresenter,
    snapshotFilter: ActivityFilter,
    activityAccounts: Set<AccountId>,
) {
    item {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                search,
                {
                    setSearch(it)
                    updateOffset { 0 }
                },
                Modifier.weight(
                    1f,
                ),
                label = { Text("Search activity") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            Button(
                onClick = { setDialog("activity") },
                enabled = data.ownership.accounts.isNotEmpty(),
            ) { Text("+ Add") }
        }
    }
    item {
        FilterKinds(kind) {
            setKind(it)
            updateOffset { 0 }
        }
    }
    item {
        AccountSelector(data, selectedAccount) {
            setSelectedAccount(it)
            updateOffset { 0 }
        }
    }
    presenter.state.activityAsset?.let { asset ->
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Asset: ${asset.value}", modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { presenter.dispatch(ProductEvent.ClearActivityAsset) },
                ) { Text("Clear asset") }
            }
        }
    }
    item {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterActivityStatus(activityStatus) { setActivityStatus(it) }
            FilterActivityNetwork(data.activityNetworks, activityNetwork) { setActivityNetwork(it) }
        }
    }
    if (snapshotFilter != presenter.state.requestedFilter()) {
        item {
            Text(
                if (presenter.state.snapshotLoading) {
                    "Updating activity results…"
                } else {
                    "Activity results are unavailable for these filters."
                },
                color = Muted,
            )
        }
    } else {
        item {
            Text(
                "${data.activityTotal} activities · matching filters",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (data.activities.isEmpty()) {
            item {
                EmptyCard(
                    "No matching activity",
                    "Try another filter or sync an account to fetch its history.",
                )
            }
        } else {
            items(data.activities, key = { it.activity.id.value }) {
                ActivityRow(it, data, compact, activityAccounts) {
                    setSelectedActivity(it)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(
                    onClick = { updateOffset { (it - 50).coerceAtLeast(0) } },
                    enabled = offset > 0,
                ) { Text("Previous") }
                Text(
                    "${if (data.activityTotal == 0) 0 else offset + 1}–${minOf(
                        offset + 50,
                        data.activityTotal,
                    )}",
                    color = Muted,
                    modifier = Modifier.padding(top = 12.dp),
                )
                OutlinedButton(
                    onClick = { updateOffset { it + 50 } },
                    enabled = offset + 50 < data.activityTotal,
                ) { Text("Next") }
            }
        }
    }
}
