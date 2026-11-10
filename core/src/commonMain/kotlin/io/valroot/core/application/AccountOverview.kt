package io.valroot.core.application

import io.valroot.core.model.AccountId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.SqliteDatabase

/** Accepted graph headers and source membership, without loading transaction graph bodies. */
data class AccountHistoryAcceptance(
    val observedAt: String,
    val providerSourceId: String,
    val addresses: Set<String>,
)

data class AccountOverview(
    val account: Account,
    val sources: List<TrackingTarget>,
    val derivedAddresses: Int,
    val portfolios: List<String>,
    val holdings: List<AssetValuation>,
    val accepted: AccountHistoryAcceptance?,
    val currentSourcesAccepted: Boolean,
    val acceptedProviderEndpoint: String? = null,
)

internal class AccountHistoryProjection(
    private val db: SqliteDatabase,
) {
    fun latest(account: AccountId): AccountHistoryAcceptance? {
        val header =
            db
                .query(
                    "SELECT id,observed_at,provider_source_id,scope_key FROM bitcoin_graph_snapshots WHERE id IN (" +
                        "SELECT snapshot_id FROM bitcoin_graph_scope WHERE account_id=? UNION " +
                        "SELECT MAX(s.id) FROM bitcoin_graph_snapshots s JOIN bitcoin_graph_delta_scopes d ON d.scope_key=s.scope_key " +
                        "WHERE d.account_id=? GROUP BY s.scope_key) ORDER BY id DESC LIMIT 1",
                    listOf(account.value, account.value),
                ).singleOrNull() ?: return null
        val addresses =
            db
                .query(
                    "SELECT address FROM bitcoin_graph_scope WHERE snapshot_id=? AND account_id=? UNION " +
                        "SELECT address FROM bitcoin_graph_delta_scopes WHERE scope_key=? AND account_id=?",
                    listOf(header[0], account.value, header[3], account.value),
                ).map { requireNotNull(it.single()) }
                .toSet()
        return AccountHistoryAcceptance(requireNotNull(header[1]), requireNotNull(header[2]), addresses)
    }
}
