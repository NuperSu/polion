package io.valroot.core.ownership

import io.valroot.core.application.AppContext
import io.valroot.core.application.AppService
import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.bitcoin.bitcoinMainnetAddress
import io.valroot.core.bitcoin.bitcoinWallet
import io.valroot.core.model.AccountId
import io.valroot.core.storage.OwnershipSnapshot

/** Owners, accounts, tracking targets and portfolios. */
class AccountService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    fun saveOwner(value: Owner) = updateOwnership { it.copy(owners = it.owners.filterNot { old -> old.id == value.id } + value) }

    fun saveAccount(value: Account) = updateOwnership { it.copy(accounts = it.accounts.filterNot { old -> old.id == value.id } + value) }

    /** Removes an account and descendants from active scopes while preserving their financial evidence. */
    fun archiveAccount(
        id: AccountId,
        archived: Boolean = true,
    ) {
        db.transaction {
            val catalog = ownership.loadInTransaction()
            require(catalog.accounts.any { it.id == id }) { "Unknown account" }

            fun descendants(roots: Set<AccountId>): Set<AccountId> {
                val result = roots.toMutableSet()
                do {
                    val before = result.size
                    catalog.accounts.filter { it.parentId in result }.forEach { result.add(it.id) }
                } while (result.size != before)
                return result
            }

            val prefix = "archive_root:"
            val roots =
                db
                    .query("SELECT key FROM application_settings")
                    .mapNotNull { row ->
                        row
                            .single()
                            ?.takeIf { it.startsWith(prefix) }
                            ?.removePrefix(prefix)
                            ?.let(::AccountId)
                    }.toMutableSet()
            val inherited = descendants(roots)
            roots.addAll(catalog.accounts.filter { "valroot:archived" in it.tags && it.id !in inherited }.map { it.id })
            if (archived) roots.add(id) else roots.remove(id)
            roots.forEach { putSetting("$prefix${it.value}", "true") }
            if (!archived) db.execute("DELETE FROM application_settings WHERE key=?", listOf("$prefix${id.value}"))

            val effective = descendants(roots)
            catalog.accounts.forEach { account ->
                if (account.id in effective) {
                    db.execute("INSERT OR IGNORE INTO ownership_tags VALUES ('account', ?, 'valroot:archived')", listOf(account.id.value))
                } else {
                    db.execute(
                        "DELETE FROM ownership_tags WHERE entity_type='account' AND entity_id=? AND tag='valroot:archived'",
                        listOf(account.id.value),
                    )
                }
            }
            catalog.targets.forEach { target ->
                val key = "archived_target:${target.id.value}"
                if (target.accountId in effective) {
                    if (setting(key) == null) putSetting(key, target.enabled.toString())
                    db.execute("UPDATE targets SET enabled=0 WHERE id=?", listOf(target.id.value))
                } else if (setting(key) != null) {
                    val enabled = setting(key)?.toBooleanStrictOrNull() ?: target.enabled
                    db.execute("UPDATE targets SET enabled=? WHERE id=?", listOf(if (enabled) "1" else "0", target.id.value))
                    db.execute("DELETE FROM application_settings WHERE key=?", listOf(key))
                }
            }
            app.sources.forgetSuccess()
        }
        invalidate()
    }

    fun saveAccountAndTarget(
        account: Account,
        target: TrackingTarget,
    ) {
        require(target.accountId == account.id)
        val normalized = normalizeTarget(target)
        val catalog = ownership.load()
        require(catalog.accounts.none { it.id == account.id }) { "Account already exists" }
        require(catalog.targets.none { it.id == normalized.id }) { "Target already exists" }
        require(
            catalog.targets.filter { it.identity == normalized.identity && it.role == normalized.role }.all {
                it.accountId ==
                    normalized.accountId
            },
        ) {
            "Address assigned to multiple accounts"
        }
        ownership.replace(catalog.copy(accounts = catalog.accounts + account, targets = catalog.targets + normalized))
        app.sources.forgetSuccess()
    }

    internal fun normalizeTarget(value: TrackingTarget): TrackingTarget =
        if (value.identity.networkId == BITCOIN_MAINNET && value.identity.kind == "address") {
            value.copy(identity = value.identity.copy(canonicalKey = bitcoinMainnetAddress(value.identity.canonicalKey)))
        } else if (value.identity.networkId == io.valroot.core.evm.EVM_FAMILY) {
            require(value.identity.kind == "address" && value.role == TargetRole.LEAF) { "Track an EVM address as one address" }
            value.copy(
                identity =
                    value.identity.copy(
                        canonicalKey =
                            io.valroot.core.evm
                                .evmAddress(value.identity.canonicalKey),
                    ),
            )
        } else if (io.valroot.core.exchange.ExchangeKind
                .ofNetwork(value.identity.networkId) != null
        ) {
            val exchange =
                io.valroot.core.exchange.ExchangeKind
                    .ofNetwork(value.identity.networkId)!!
            require(value.identity.kind == "uid" && value.role == TargetRole.LEAF && exchange.validIdentity(value.identity.canonicalKey)) {
                "Track an exchange account by its user ID"
            }
            value
        } else if (value.identity.networkId == BITCOIN_MAINNET && value.identity.kind == "wallet") {
            require(value.role == TargetRole.WALLET && value.walletId == null) { "Invalid wallet target" }
            value.copy(identity = value.identity.copy(canonicalKey = bitcoinWallet(value.identity.canonicalKey).canonical))
        } else {
            value
        }

    fun saveTarget(value: TrackingTarget) {
        val normalized = normalizeTarget(value)
        val catalog = ownership.load()
        val previous = catalog.targets.firstOrNull { it.id == normalized.id }
        if (previous != null &&
            (previous.identity != normalized.identity || previous.accountId != normalized.accountId || previous.role != normalized.role)
        ) {
            require(activities.current().none { !it.manual && it.decoderVersion?.startsWith("bitcoin-") == true }) {
                "Cannot reassign a tracked target with Bitcoin activity"
            }
        }
        if (previous?.role == TargetRole.WALLET && catalog.targets.any { it.walletId == previous.id }) {
            require(
                previous.identity == normalized.identity && previous.accountId == normalized.accountId && previous.role == normalized.role,
            ) {
                "Cannot change a wallet after discovery"
            }
        }
        val existing = catalog.targets.filter { it.id != normalized.id && it.identity == normalized.identity && it.role == normalized.role }
        require(existing.all { it.accountId == normalized.accountId }) { "Address assigned to multiple accounts" }
        require(normalized.role != TargetRole.WALLET || existing.isEmpty()) { "Wallet already tracked" }
        updateOwnership { snapshot ->
            snapshot.copy(
                targets =
                    snapshot.targets.filterNot { old -> old.id == normalized.id }.map { child ->
                        if (child.walletId == normalized.id) child.copy(enabled = normalized.enabled) else child
                    } + normalized,
            )
        }
        app.sources.forgetSuccess()
    }

    fun savePortfolio(value: Portfolio) =
        updateOwnership {
            it.copy(
                portfolios =
                    it.portfolios.filterNot { old -> old.id == value.id } + value,
            )
        }

    /** Read-modify-write in one transaction, so concurrent edits cannot be lost between load and replace. */
    internal fun updateOwnership(change: (OwnershipSnapshot) -> OwnershipSnapshot) =
        db.transaction { ownership.replaceInTransaction(change(ownership.loadInTransaction())) }
}
