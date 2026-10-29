package io.polion.core.sources

import io.polion.core.bitcoin.BITCOIN_CONNECTOR_ID
import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.model.AccountId
import io.polion.core.model.NetworkId
import io.polion.core.model.OwnerId
import io.polion.core.model.PortfolioId
import io.polion.core.model.ProviderId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.OwnershipCatalog
import io.polion.core.ownership.Portfolio
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceCatalogTest {
    private val target =
        TrackingTarget(
            TrackingTargetId("saved-address"),
            AccountId("owned-account"),
            TargetIdentity(BITCOIN_MAINNET, "address", "public-address"),
            TargetRole.LEAF,
            "Savings",
        )

    @Test
    fun sourceInventoryContainsOnlyImplementedWorkflows() {
        val choices = SupportedSources.choices
        assertEquals(SourceWorkflow.entries.toSet(), choices.map { it.workflow }.toSet())
        assertEquals(choices.size, choices.map { it.id }.distinct().size)
        val publicSources = SupportedSources.supporting(SourceCapability.PUBLIC_INPUT)
        assertEquals(setOf(SourceWorkflow.BITCOIN_ADDRESS, SourceWorkflow.BITCOIN_PUBLIC_WALLET), publicSources.map { it.workflow }.toSet())
        publicSources.forEach {
            assertEquals(BITCOIN_MAINNET, it.networkId)
            assertEquals(ConnectorId(BITCOIN_CONNECTOR_ID), it.connectorId)
            assertTrue(it.requiresQueryConsent)
            assertTrue(SourceCapability.HISTORY_REFRESH in it.capabilities)
        }
        assertEquals(
            listOf(SourceWorkflow.BITCOIN_PUBLIC_WALLET),
            SupportedSources.supporting(SourceCapability.BOUNDED_DISCOVERY).map { it.workflow },
        )
    }

    @Test
    fun manualRecordsDoNotImplyNetworkConnection() {
        val manual = SupportedSources.supporting(SourceCapability.LOCAL_RECORD).single()
        assertEquals(SourceWorkflow.MANUAL_ACTIVITY, manual.workflow)
        assertNull(manual.networkId)
        assertNull(manual.connectorId)
        assertFalse(manual.requiresQueryConsent)
        assertEquals(setOf(SourceCapability.LOCAL_RECORD), manual.capabilities)
    }

    @Test
    fun accountTargetNetworkConnectorAndProviderRemainDistinct() {
        val identity = SourceTargetIdentity.from(target)
        val route = SourceRoute(ConnectorId(BITCOIN_CONNECTOR_ID), ProviderId("selected-service"))
        assertEquals(target.accountId, identity.accountId)
        assertEquals(target.id, identity.targetId)
        assertEquals(BITCOIN_MAINNET, identity.networkId)
        assertNotEquals(identity, SourceTargetIdentity.from(target.copy(accountId = AccountId("other-account"))))
        assertNotEquals(identity, SourceTargetIdentity.from(target.copy(id = TrackingTargetId("alias"))))
        assertNotEquals(identity.identity, target.identity.copy(networkId = NetworkId("another-network")))
        assertNotEquals(route, route.copy(providerId = ProviderId("another-service")))
        assertNotEquals(route, route.copy(connectorId = ConnectorId("another-implementation")))
        assertEquals(identity, SourceTargetIdentity.from(target.copy(label = "Renamed", tags = setOf("brand-label"))))
    }

    @Test
    fun walletBrandLabelsAndProviderRoutesCannotCreateBalanceBearingIdentities() {
        val owner = Owner(OwnerId("owner"), "Owner")
        val account = Account(target.accountId, owner.id, "Account", AccountKind.ACCOUNT)
        val portfolio = Portfolio(PortfolioId("portfolio"), "Portfolio", "USD")

        fun scope(targets: List<TrackingTarget>) =
            OwnershipCatalog(listOf(owner), listOf(account), targets, listOf(portfolio)).scope(portfolio.id)

        val before = scope(listOf(target))
        val brand = WalletBrand(WalletBrandId("brand"), "Wallet software", "Bitcoin")
        val renamedBrand = brand.copy(label = "Custom brand label")
        val alias = target.copy(id = TrackingTargetId("alias"), label = renamedBrand.label)
        val after = scope(listOf(target.copy(label = brand.label), alias))
        assertEquals(before.leaves.map { it.identity }, after.leaves.map { it.identity })
        assertEquals(1, after.leaves.size)
        assertEquals(setOf(target.id, alias.id), after.leaves.single().targetIds)
        assertTrue(after.wallets.isEmpty())

        val firstRoute = SourceRoute(ConnectorId(BITCOIN_CONNECTOR_ID), ProviderId("first"))
        val secondRoute = firstRoute.copy(connectorId = ConnectorId("new-adapter"), providerId = ProviderId("second"))
        assertNotEquals(firstRoute, secondRoute)
        assertEquals(SourceTargetIdentity.from(target).identity, SourceTargetIdentity.from(alias).identity)
        assertEquals(3, SupportedSources.choices.size)
    }
}
