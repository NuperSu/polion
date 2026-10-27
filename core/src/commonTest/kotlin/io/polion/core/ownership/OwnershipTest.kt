package io.polion.core.ownership

import io.polion.core.model.AccountId
import io.polion.core.model.NetworkId
import io.polion.core.model.OwnerId
import io.polion.core.model.PortfolioId
import io.polion.core.model.TrackingTargetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnershipTest {
    private val owner = Owner(OwnerId("alice"), "Alice")
    private val walletAccount = Account(AccountId("wallet"), owner.id, "Wallet", AccountKind.WALLET)
    private val addressAccount = Account(AccountId("addresses"), owner.id, "Addresses", AccountKind.GROUP, walletAccount.id)
    private val portfolio = Portfolio(PortfolioId("all"), "All", "USD")
    private val network = NetworkId("bip122:mainnet")
    private val wallet =
        TrackingTarget(
            TrackingTargetId("wallet"),
            walletAccount.id,
            TargetIdentity(network, "wallet", "wallet-key"),
            TargetRole.WALLET,
            "Wallet",
        )

    private fun address(
        index: Int,
        id: String = "address-$index",
        accountId: AccountId = addressAccount.id,
    ) = TrackingTarget(
        TrackingTargetId(id),
        accountId,
        TargetIdentity(network, "address", "address-$index"),
        TargetRole.LEAF,
        id,
        walletId = wallet.id,
    )

    @Test
    fun walletAddressesAndAliasesHaveDistinctNonDuplicatedScopes() {
        val targets = listOf(wallet) + (0 until 100).map(::address) + address(0, "alias")
        val scoped = OwnershipCatalog(listOf(owner), listOf(walletAccount, addressAccount), targets, listOf(portfolio)).scope(portfolio.id)
        assertEquals(1, scoped.wallets.size)
        assertEquals(100, scoped.leaves.size)
        val first = scoped.leaves.single { it.identity.canonicalKey == "address-0" }
        assertEquals(setOf(TrackingTargetId("address-0"), TrackingTargetId("alias")), first.targetIds)
        assertEquals(setOf(wallet.identity), first.walletIdentities)
        assertFalse(first.ownershipAmbiguous)
        assertFalse(first.walletAssignmentAmbiguous)
    }

    @Test
    fun networkAndOwnersAreExplicitAndAmbiguitySurvivesNarrowScope() {
        val bob = Owner(OwnerId("bob"), "Bob")
        val bobAccount = Account(AccountId("bob"), bob.id, "Bob", AccountKind.ACCOUNT)
        val shared = address(0, "bob-address", bobAccount.id).copy(walletId = null)
        val testnet =
            address(
                0,
                "testnet",
            ).copy(identity = TargetIdentity(NetworkId("bip122:testnet"), "address", "address-0"), walletId = null)
        val filtered = portfolio.copy(ownerIds = setOf(owner.id), accountIds = setOf(walletAccount.id))
        val scope =
            OwnershipCatalog(
                listOf(owner, bob),
                listOf(walletAccount, addressAccount, bobAccount),
                listOf(wallet, address(0), shared, testnet),
                listOf(filtered),
            ).scope(filtered.id)
        assertEquals(2, scope.leaves.size)
        val mainnet = scope.leaves.single { it.identity.networkId == network }
        assertEquals(setOf(owner.id, bob.id), mainnet.ownerIds)
        assertTrue(mainnet.ownershipAmbiguous)
        assertEquals(setOf(TrackingTargetId("address-0")), mainnet.targetIds)
    }

    @Test
    fun disablingOneTrackerDoesNotRemoveItsIdentityOrOtherTrackers() {
        val targets = listOf(wallet, address(0).copy(enabled = false), address(0, "enabled"))
        val leaf =
            OwnershipCatalog(listOf(owner), listOf(walletAccount, addressAccount), targets, listOf(portfolio))
                .scope(portfolio.id)
                .leaves
                .single()
        assertEquals(2, leaf.targetIds.size)
        assertEquals(setOf(TrackingTargetId("enabled")), leaf.enabledTargetIds)
    }

    @Test
    fun validatesUnknownParentsCyclesAndWalletMembership() {
        val cycleA = walletAccount.copy(parentId = addressAccount.id)
        assertFailsWith<IllegalArgumentException> {
            OwnershipCatalog(listOf(owner), listOf(cycleA, addressAccount), emptyList(), listOf(portfolio))
        }
        assertFailsWith<IllegalArgumentException> {
            OwnershipCatalog(listOf(owner), listOf(walletAccount, addressAccount), listOf(address(0)), listOf(portfolio))
        }
        assertFailsWith<IllegalArgumentException> {
            OwnershipCatalog(
                listOf(owner),
                listOf(walletAccount, addressAccount),
                listOf(wallet, address(0), address(0)),
                listOf(portfolio),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OwnershipCatalog(
                listOf(owner),
                listOf(walletAccount, addressAccount),
                emptyList(),
                listOf(portfolio.copy(accountIds = setOf(AccountId("missing")))),
            )
        }
    }
}
