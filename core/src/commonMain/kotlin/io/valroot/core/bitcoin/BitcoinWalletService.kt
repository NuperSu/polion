package io.valroot.core.bitcoin

import io.valroot.core.application.AppContext
import io.valroot.core.application.AppService
import io.valroot.core.application.BitcoinDiscoveryConflict
import io.valroot.core.application.BitcoinWalletDiscovery
import io.valroot.core.model.AccountId
import io.valroot.core.model.PortfolioId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.OwnershipSnapshot
import io.valroot.core.storage.sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Bitcoin wallets from public descriptors or xpubs: saving them and discovering their used addresses. */
class BitcoinWalletService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    /** Wallet expressions that derive the same first address watch the same coins, whatever text they were saved with. */
    private fun sameWalletSource(
        first: String,
        second: String,
    ): Boolean =
        first == second || runCatching { bitcoinWallet(first).address(0, 0) == bitcoinWallet(second).address(0, 0) }.getOrDefault(false)

    /**
     * Saves a consented public wallet and optional ownership records as one local change, and returns the wallet ID that
     * discovery must use. Importing a wallet that is already tracked resumes it: the tracked wallet and its account are
     * kept, and discovery and history run again. Empty duplicates left by failed imports of the same wallet are removed,
     * together with an account created for them that holds nothing else.
     */
    fun save(
        target: TrackingTarget,
        newAccount: Account? = null,
        newOwner: Owner? = null,
        consentAccepted: Boolean = false,
        providerEndpoint: String? = null,
        portfolio: PortfolioId? = null,
    ): TrackingTargetId {
        require(target.identity.networkId == BITCOIN_MAINNET && target.identity.kind == "wallet") { "Expected a Bitcoin public wallet" }
        require(target.label.isNotBlank()) { "Enter a wallet name" }
        val normalized = app.accounts.normalizeTarget(target)
        val walletId =
            db.transaction {
                require(app.sources.consent() || consentAccepted) { "Source consent required" }
                if (providerEndpoint != null) {
                    require(
                        providerEndpoint.trimEnd('/') == app.sources.endpoint() || consentAccepted,
                    ) { "Consent is required for the selected provider" }
                    app.sources.setEndpoint(providerEndpoint)
                }
                val catalog = ownership.loadInTransaction()
                val equivalent =
                    catalog.targets.filter {
                        it.role == TargetRole.WALLET &&
                            it.identity.networkId == BITCOIN_MAINNET &&
                            it.identity.kind == "wallet" &&
                            sameWalletSource(it.identity.canonicalKey, normalized.identity.canonicalKey)
                    }

                fun hasAddresses(wallet: TrackingTarget) = catalog.targets.any { it.walletId == wallet.id }
                val reused = equivalent.firstOrNull(::hasAddresses) ?: equivalent.firstOrNull()
                val duplicates = equivalent.filter { it != reused && !hasAddresses(it) }.map { it.id }.toSet()
                val remainingTargets = catalog.targets.filterNot { it.id in duplicates }
                // An account created only for a removed duplicate is removed when nothing else uses it.
                val emptyAccounts =
                    catalog.targets
                        .filter { it.id in duplicates }
                        .map { it.accountId }
                        .filter { accountId ->
                            accountId != reused?.accountId &&
                                remainingTargets.none { it.accountId == accountId } &&
                                catalog.accounts.none { it.parentId == accountId } &&
                                catalog.portfolios.none { accountId in it.accountIds } &&
                                db.query("SELECT 1 FROM ledger_movements WHERE account_id=? LIMIT 1", listOf(accountId.value)).isEmpty()
                        }.toSet()
                require(reused != null || catalog.targets.none { it.id == normalized.id }) { "Target already exists" }
                require(reused == null || newAccount != null || target.accountId == reused.accountId) {
                    val label = catalog.accounts.firstOrNull { it.id == reused?.accountId }?.label ?: "another account"
                    "This wallet is already tracked in account \"$label\""
                }
                val wallet = reused ?: normalized
                val createdAccount = if (reused == null) newAccount else null
                val createdOwner = if (reused == null) newOwner else null
                require(createdOwner == null || createdAccount != null && createdAccount.ownerId == createdOwner.id) {
                    "Owner requires a matching new account"
                }
                if (createdOwner != null) {
                    require(createdOwner.label.isNotBlank()) { "Enter an owner name" }
                    require(catalog.owners.none { it.id == createdOwner.id }) { "Owner already exists" }
                }
                if (createdAccount != null) {
                    require(createdAccount.id == wallet.accountId) { "Wallet account does not match" }
                    require(createdAccount.label.isNotBlank()) { "Enter an account name" }
                    require(catalog.accounts.none { it.id == createdAccount.id }) { "Account already exists" }
                }
                val account =
                    requireNotNull(
                        createdAccount ?: catalog.accounts.firstOrNull { it.id == wallet.accountId },
                    ) { "Unknown account" }
                require(account.kind != AccountKind.GROUP && "valroot:archived" !in account.tags) { "Select an active account" }
                val byId = catalog.accounts.associateBy { it.id }
                var parent = account.parentId
                while (parent != null) {
                    val ancestor = requireNotNull(byId[parent]) { "Unknown parent account" }
                    require("valroot:archived" !in ancestor.tags) { "Select an active account group" }
                    parent = ancestor.parentId
                }
                val portfolios =
                    catalog.portfolios.map { selected ->
                        if (selected.id != portfolio) {
                            selected
                        } else {
                            require(
                                selected.ownerIds.isEmpty() || account.ownerId in selected.ownerIds,
                            ) { "Select an owner included in this portfolio" }
                            if (selected.accountIds.isEmpty()) selected else selected.copy(accountIds = selected.accountIds + account.id)
                        }
                    }
                require(portfolio == null || catalog.portfolios.any { it.id == portfolio }) { "Unknown portfolio" }
                ownership.replaceInTransaction(
                    catalog.copy(
                        portfolios = portfolios,
                        owners = catalog.owners + listOfNotNull(createdOwner),
                        accounts = catalog.accounts.filterNot { it.id in emptyAccounts } + listOfNotNull(createdAccount),
                        targets = if (reused == null) remainingTargets + normalized else remainingTargets,
                    ),
                )
                if (consentAccepted) putSetting("source_consent", "true")
                app.sources.forgetSuccess()
                wallet.id
            }
        invalidate()
        return walletId
    }

    /**
     * Standalone copies of a wallet address that the wallet's owner tracks in another account. Discovery moves them into
     * the wallet, because the wallet now covers that address; another owner's copy stays a conflict.
     */
    private fun adoptableAddresses(
        snapshot: OwnershipSnapshot,
        address: String,
        accountId: AccountId,
    ): List<TrackingTarget> {
        val owners = snapshot.accounts.associate { it.id to it.ownerId }
        val owner = owners[accountId] ?: return emptyList()
        return snapshot.targets.filter {
            it.role == TargetRole.LEAF &&
                it.identity.networkId == BITCOIN_MAINNET &&
                it.identity.kind == "address" &&
                it.identity.canonicalKey == address &&
                it.walletId == null &&
                it.accountId != accountId &&
                owners[it.accountId] == owner
        }
    }

    /**
     * An address that another account (or another wallet) already tracks cannot join this wallet, unless it is a
     * standalone copy the same owner keeps (see [adoptableAddresses]). The message names that account.
     */
    private fun discoveryConflict(
        snapshot: OwnershipSnapshot,
        address: String,
        accountId: AccountId,
        walletId: TrackingTargetId,
        childId: TrackingTargetId,
    ): BitcoinDiscoveryConflict? {
        val conflict =
            snapshot.targets.firstOrNull {
                it.id != childId &&
                    it.role == TargetRole.LEAF &&
                    it.identity.networkId == BITCOIN_MAINNET &&
                    it.identity.kind == "address" &&
                    it.identity.canonicalKey == address &&
                    !(it.accountId == accountId && (it.walletId == null || it.walletId == walletId))
            } ?: return null
        val label = snapshot.accounts.firstOrNull { it.id == conflict.accountId }?.label ?: "another account"
        return BitcoinDiscoveryConflict(
            "This address is already tracked in account \"$label\". Remove it there, then retry wallet discovery.",
        )
    }

    /** Discovers public addresses; requests disclose each derived address to the selected provider. */
    fun discover(
        walletId: TrackingTargetId,
        transport: EsploraTransport,
        gapLimit: Int = 20,
        maxAddressesPerBranch: Int = 1000,
        providerEndpoint: String? = null,
    ): BitcoinWalletDiscovery {
        require(app.sources.consent()) { "Source consent required" }
        require(gapLimit in 1..100 && maxAddressesPerBranch in gapLimit..10000)
        val catalog = ownership.load()
        val target = requireNotNull(catalog.targets.firstOrNull { it.id == walletId }) { "Unknown wallet" }
        require(
            target.enabled &&
                target.role == TargetRole.WALLET &&
                target.identity.networkId == BITCOIN_MAINNET &&
                target.identity.kind == "wallet",
        ) { "Enabled Bitcoin wallet required" }
        val wallet = bitcoinWallet(target.identity.canonicalKey)
        val oldChildren = catalog.targets.filter { it.walletId == walletId }
        val children = mutableListOf<TrackingTarget>()
        var used = 0
        for (branch in 0 until wallet.branchCount) {
            val prefix = "bitcoin-wallet-child-" + sha256(walletId.value) + ":$branch:"
            val previousMax =
                oldChildren
                    .mapNotNull {
                        it.id.value
                            .takeIf { id -> id.startsWith(prefix) }
                            ?.removePrefix(prefix)
                            ?.toIntOrNull()
                    }.maxOrNull() ?: -1
            var gap = 0
            var index = 0
            while (index < maxAddressesPerBranch) {
                val address = wallet.address(branch, index)
                val json = Json.parseToJsonElement(transport.get("/address/$address")).jsonObject
                val chain =
                    json
                        .getValue("chain_stats")
                        .jsonObject
                        .getValue("tx_count")
                        .jsonPrimitive.long
                val mempool =
                    json
                        .getValue("mempool_stats")
                        .jsonObject
                        .getValue("tx_count")
                        .jsonPrimitive.long
                require(chain >= 0 && mempool >= 0) { "Invalid address summary" }
                val hasActivity = chain > 0 || mempool > 0
                if (hasActivity) used++
                gap = if (hasActivity) 0 else gap + 1
                val id = TrackingTargetId(prefix + index)
                val existing = catalog.targets.firstOrNull { it.id == id }
                require(existing == null || existing.walletId == walletId && existing.identity.canonicalKey == address) {
                    "Wallet child identity conflict"
                }
                val adoptable = adoptableAddresses(catalog, address, target.accountId).toSet()
                discoveryConflict(catalog.copy(targets = catalog.targets - adoptable), address, target.accountId, walletId, id)?.let {
                    throw it
                }
                children.add(
                    TrackingTarget(
                        id,
                        target.accountId,
                        TargetIdentity(BITCOIN_MAINNET, "address", address),
                        TargetRole.LEAF,
                        "${target.label} / $branch / $index",
                        enabled = true,
                        walletId = walletId,
                    ),
                )
                index++
                if (!wallet.ranged || gap >= gapLimit && index > previousMax) break
            }
            require(!wallet.ranged || gap >= gapLimit && index > previousMax) {
                "Discovery limit reached before the gap was verified; increase the limit"
            }
        }
        val childIds = children.map { it.id }.toSet()
        var moved = emptyList<TrackingTarget>()
        val scopeNotice =
            when {
                used == 0 ->
                    "No used addresses in ${children.size} checked. Check the expression: a bare xpub is read as native SegWit (BIP84) only."
                wallet.ranged && wallet.branchCount == 1 -> "The descriptor covers one branch; change addresses are not scanned."
                else -> null
            }
        // Merge into the catalog as it is at commit time, so edits saved during the network scan survive.
        db.transaction {
            require(app.sources.consent()) { "Source consent required" }
            providerEndpoint?.let { endpoint ->
                if (app.sources.endpoint() != endpoint) throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED)
            }
            val fresh = ownership.loadInTransaction()
            require(fresh.targets.firstOrNull { it.id == walletId } == target) { "Wallet changed during discovery; retry" }
            moved = children.flatMap { adoptableAddresses(fresh, it.identity.canonicalKey, target.accountId) }
            val remaining = fresh.copy(targets = fresh.targets - moved.toSet())
            children.forEach { child ->
                discoveryConflict(remaining, child.identity.canonicalKey, target.accountId, walletId, child.id)?.let { throw it }
            }
            ownership.replaceInTransaction(remaining.copy(targets = remaining.targets.filterNot { it.id in childIds } + children))
            app.sources.forgetSuccess()
        }
        invalidate()
        val movedNotice =
            moved.takeIf { it.isNotEmpty() }?.let { addresses ->
                val labels =
                    addresses
                        .mapNotNull { leaf -> catalog.accounts.firstOrNull { it.id == leaf.accountId }?.label }
                        .distinct()
                        .joinToString { "\"$it\"" }
                "Moved ${addresses.size} address${if (addresses.size == 1) "" else "es"} from $labels into this wallet."
            }
        return BitcoinWalletDiscovery(children.size, used, listOfNotNull(scopeNotice, movedNotice).joinToString(" ").ifEmpty { null })
    }

    /**
     * Wallets that need address discovery before a history refresh: enabled wallets with no addresses yet (for example
     * after an interrupted import) and wallets whose last applied scan saw a used address inside the trailing gap, so
     * later addresses may now be in use. History refresh only scans addresses that discovery has added.
     */
    fun needingDiscovery(gapLimit: Int = 20): List<TrackingTargetId> {
        val catalog = ownership.load()
        val archived =
            catalog.accounts
                .filter { "valroot:archived" in it.tags }
                .map { it.id }
                .toSet()
        val counts =
            app.bitcoin
                .acceptedScan()
                ?.counts
                .orEmpty()
        return catalog.targets
            .filter {
                it.enabled &&
                    it.role == TargetRole.WALLET &&
                    it.identity.networkId == BITCOIN_MAINNET &&
                    it.identity.kind == "wallet" &&
                    it.accountId !in archived
            }.filter { wallet ->
                val children = catalog.targets.filter { it.walletId == wallet.id }
                if (children.isEmpty()) return@filter true
                val prefix = "bitcoin-wallet-child-" + sha256(wallet.id.value) + ":"
                children
                    .mapNotNull { child ->
                        val position =
                            child.id.value
                                .removePrefix(prefix)
                                .split(':')
                        val branch = position.getOrNull(0)?.toIntOrNull()
                        val index = position.getOrNull(1)?.toIntOrNull()
                        if (branch == null || index == null) null else Triple(branch, index, child.identity.canonicalKey)
                    }.groupBy { it.first }
                    .values
                    .any { branch ->
                        val last = branch.maxOf { it.second }
                        branch.any { (_, index, address) ->
                            val seen = counts[address]
                            index > last - gapLimit && seen != null && (seen.first > 0 || seen.second > 0)
                        }
                    }
            }.map { it.id }
    }

    /**
     * Discovers [needingDiscovery] before a history refresh. A wallet that cannot be discovered because of an
     * address conflict or the discovery limit is skipped with its message, so other wallets still refresh; provider
     * failures propagate and stop the refresh.
     */
    fun discoverPending(
        transport: EsploraTransport,
        providerEndpoint: String = app.sources.endpoint(),
        gapLimit: Int = 20,
    ): List<String> =
        needingDiscovery(gapLimit).mapNotNull { walletId ->
            try {
                discover(walletId, transport, gapLimit, providerEndpoint = providerEndpoint).notice
            } catch (failure: IllegalArgumentException) {
                failure.message ?: "A wallet could not be discovered; retry from its connection."
            }
        }
}
