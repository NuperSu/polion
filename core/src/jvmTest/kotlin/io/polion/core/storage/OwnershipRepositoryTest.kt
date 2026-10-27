package io.polion.core.storage

import io.polion.core.model.AccountId
import io.polion.core.model.NetworkId
import io.polion.core.model.OwnerId
import io.polion.core.model.PortfolioId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.Portfolio
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class OwnershipRepositoryTest {
    @Test
    fun snapshotSurvivesReopenAndInvalidReplacement() {
        val file = Files.createTempFile("polion-ownership", ".sqlite")
        val owner = Owner(OwnerId("owner"), "Owner", setOf("personal"))
        val account = Account(AccountId("account"), owner.id, "Wallet", AccountKind.WALLET, tags = setOf("tag"))
        val child = Account(AccountId("child"), owner.id, "Child", AccountKind.ACCOUNT, account.id)
        val wallet =
            TrackingTarget(
                TrackingTargetId("wallet"),
                account.id,
                TargetIdentity(NetworkId("btc:mainnet"), "wallet", "x"),
                TargetRole.WALLET,
                "Wallet",
            )
        val address =
            TrackingTarget(
                TrackingTargetId("address"),
                child.id,
                TargetIdentity(NetworkId("btc:mainnet"), "address", "y"),
                TargetRole.LEAF,
                "Address",
                setOf("receive"),
                false,
                wallet.id,
            )
        val portfolio = Portfolio(PortfolioId("p"), "P", "USD", setOf(owner.id), setOf(account.id), setOf("tax"))
        val snapshot = OwnershipSnapshot(listOf(owner), listOf(account, child), listOf(address, wallet), listOf(portfolio))
        try {
            JvmSqliteDatabase(file.toString()).use { db ->
                Store(db)
                val repo = OwnershipRepository(db)
                repo.replace(snapshot)
                assertEquals(snapshot, repo.load())
                assertFails { repo.replace(snapshot.copy(owners = emptyList())) }
                assertEquals(snapshot, repo.load())
                repo.replace(snapshot)
            }
            JvmSqliteDatabase(file.toString()).use { db ->
                Store(db)
                assertEquals(snapshot, OwnershipRepository(db).load())
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
