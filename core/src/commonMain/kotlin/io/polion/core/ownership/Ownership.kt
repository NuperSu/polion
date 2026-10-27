package io.polion.core.ownership

import io.polion.core.model.AccountId
import io.polion.core.model.NetworkId
import io.polion.core.model.OwnerId
import io.polion.core.model.PortfolioId
import io.polion.core.model.TrackingTargetId

data class Owner(
    val id: OwnerId,
    val label: String,
    val tags: Set<String> = emptySet(),
)

enum class AccountKind { WALLET, GROUP, ACCOUNT }

data class Account(
    val id: AccountId,
    val ownerId: OwnerId,
    val label: String,
    val kind: AccountKind,
    val parentId: AccountId? = null,
    val tags: Set<String> = emptySet(),
)

/** The connector supplies its canonical key; case and chain-specific normalization are not inferred here. */
data class TargetIdentity(
    val networkId: NetworkId,
    val kind: String,
    val canonicalKey: String,
) {
    init {
        require(kind.isNotBlank() && kind == kind.trim())
        require(canonicalKey.isNotBlank() && canonicalKey == canonicalKey.trim())
    }
}

enum class TargetRole { WALLET, LEAF }

/** A tracking target is a user configuration; several targets may watch the same identity. */
data class TrackingTarget(
    val id: TrackingTargetId,
    val accountId: AccountId,
    val identity: TargetIdentity,
    val role: TargetRole,
    val label: String,
    val tags: Set<String> = emptySet(),
    val enabled: Boolean = true,
    val walletId: TrackingTargetId? = null,
)

/** Empty owner/account sets select all. Selected accounts include their descendants. */
data class Portfolio(
    val id: PortfolioId,
    val label: String,
    val valuationCurrency: String,
    val ownerIds: Set<OwnerId> = emptySet(),
    val accountIds: Set<AccountId> = emptySet(),
    val tags: Set<String> = emptySet(),
) {
    init {
        require(valuationCurrency.isNotBlank())
    }
}

data class IdentityGroup(
    val identity: TargetIdentity,
    val targetIds: Set<TrackingTargetId>,
    val enabledTargetIds: Set<TrackingTargetId>,
    val accountIds: Set<AccountId>,
    val ownerIds: Set<OwnerId>,
    val walletIdentities: Set<TargetIdentity>,
) {
    val ownershipAmbiguous: Boolean get() = ownerIds.size > 1
    val walletAssignmentAmbiguous: Boolean get() = walletIdentities.size > 1
}

/** Wallets are group identities; leaves are the non-overlapping balance-bearing identities. */
data class ScopeTargets(
    val wallets: List<IdentityGroup>,
    val leaves: List<IdentityGroup>,
)

class OwnershipCatalog(
    owners: Collection<Owner>,
    accounts: Collection<Account>,
    targets: Collection<TrackingTarget>,
    portfolios: Collection<Portfolio>,
) {
    private val owners = owners.uniqueBy { it.id }
    private val accounts = accounts.uniqueBy { it.id }
    private val targets = targets.uniqueBy { it.id }
    private val portfolios = portfolios.uniqueBy { it.id }

    init {
        this.accounts.values.forEach { account ->
            require(account.ownerId in this.owners) { "Unknown account owner" }
            account.parentId?.let { parent ->
                require(parent in this.accounts && this.accounts.getValue(parent).ownerId == account.ownerId) {
                    "Parent account must exist and have the same owner"
                }
            }
            val seen = mutableSetOf<AccountId>()
            var cursor: AccountId? = account.id
            while (cursor != null) {
                require(seen.add(cursor)) { "Account hierarchy contains a cycle" }
                cursor = this.accounts.getValue(cursor).parentId
            }
        }
        this.targets.values.forEach { target ->
            require(target.accountId in this.accounts) { "Unknown target account" }
            target.walletId?.let { walletId ->
                val wallet = requireNotNull(this.targets[walletId]) { "Unknown wallet target" }
                require(target.role == TargetRole.LEAF && wallet.role == TargetRole.WALLET) { "Invalid wallet membership" }
                require(target.identity.networkId == wallet.identity.networkId) { "Wallet and address networks differ" }
                require(this.accounts.getValue(target.accountId).ownerId == this.accounts.getValue(wallet.accountId).ownerId) {
                    "Wallet and address owners differ"
                }
            }
        }
        this.portfolios.values.forEach { portfolio ->
            require(portfolio.ownerIds.all { it in this.owners }) { "Unknown portfolio owner" }
            require(portfolio.accountIds.all { it in this.accounts }) { "Unknown portfolio account" }
        }
    }

    fun scope(portfolioId: PortfolioId): ScopeTargets {
        val portfolio = requireNotNull(portfolios[portfolioId]) { "Unknown portfolio" }
        val selected =
            targets.values.filter { target ->
                val account = accounts.getValue(target.accountId)
                (portfolio.ownerIds.isEmpty() || account.ownerId in portfolio.ownerIds) &&
                    (portfolio.accountIds.isEmpty() || accountInScope(account.id, portfolio.accountIds))
            }

        fun groups(role: TargetRole): List<IdentityGroup> =
            selected
                .filter { it.role == role }
                .groupBy { it.identity }
                .map { (identity, aliases) ->
                    // Include all claims for an identity, including claims outside the reporting scope.
                    // This prevents a narrow scope from concealing conflicting ownership.
                    val claims = targets.values.filter { it.identity == identity && it.role == role }
                    IdentityGroup(
                        identity = identity,
                        targetIds = aliases.mapTo(mutableSetOf()) { it.id },
                        enabledTargetIds = aliases.filter { it.enabled }.mapTo(mutableSetOf()) { it.id },
                        accountIds = aliases.mapTo(mutableSetOf()) { it.accountId },
                        ownerIds = claims.mapTo(mutableSetOf()) { accounts.getValue(it.accountId).ownerId },
                        walletIdentities = claims.mapNotNullTo(mutableSetOf()) { it.walletId?.let(targets::getValue)?.identity },
                    )
                }.sortedWith(compareBy({ it.identity.networkId.value }, { it.identity.kind }, { it.identity.canonicalKey }))
        return ScopeTargets(groups(TargetRole.WALLET), groups(TargetRole.LEAF))
    }

    private fun accountInScope(
        accountId: AccountId,
        selection: Set<AccountId>,
    ): Boolean {
        var cursor: AccountId? = accountId
        while (cursor != null) {
            if (cursor in selection) return true
            cursor = accounts.getValue(cursor).parentId
        }
        return false
    }
}

private fun <K, V> Collection<V>.uniqueBy(key: (V) -> K): Map<K, V> {
    val result = associateBy(key)
    require(result.size == size) { "Duplicate identity" }
    return result
}
