package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.model.AccountId
import io.valroot.core.model.OwnerId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletOnboardingTest {
    private val descriptor = "addr(1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa)"
    private val owner = Owner(OwnerId("personal"), "Personal")
    private val account = Account(AccountId("savings"), owner.id, "Savings", AccountKind.WALLET)

    @Test
    fun firstWalletCreatesOwnershipAndConsentTogetherAndPersists() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            app.bitcoinWallets.save(wallet(), account, owner, consentAccepted = true)
            val reopened = PortfolioApplication(db) { 1000L }
            val catalog = reopened.snapshot().ownership
            assertEquals(listOf(owner), catalog.owners)
            assertEquals(listOf(account), catalog.accounts)
            assertEquals(listOf(wallet()), catalog.targets)
            assertTrue(reopened.sources.consent())
        }
    }

    @Test
    fun invalidWalletOrMissingConsentLeavesFreshPortfolioUntouched() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            val initial = app.snapshot().ownership
            assertFails { app.bitcoinWallets.save(wallet(), account, owner) }
            assertFails { app.bitcoinWallets.save(wallet(expression = "not-a-wallet"), account, owner, consentAccepted = true) }
            assertEquals(initial, app.snapshot().ownership)
            assertFalse(app.sources.consent())
            assertTrue(db.query("SELECT * FROM application_settings").isEmpty())
        }
    }

    @Test
    fun existingAccountUsesStoredConsentAndDuplicateCannotCreateOrphans() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            app.accounts.saveOwner(owner)
            app.accounts.saveAccount(account)
            app.sources.setConsent(true)
            app.bitcoinWallets.save(wallet())
            val initial = app.snapshot().ownership
            val anotherOwner = Owner(OwnerId("another-owner"), "Another owner")
            val anotherAccount = Account(AccountId("another-account"), anotherOwner.id, "Another account", AccountKind.WALLET)
            // The undiscovered wallet is resumed in its original account; the requested account and owner are not created.
            assertEquals(
                TrackingTargetId("wallet"),
                app.bitcoinWallets.save(wallet("duplicate", anotherAccount.id), anotherAccount, anotherOwner, consentAccepted = true),
            )
            assertEquals(initial, app.snapshot().ownership)
        }
    }

    @Test
    fun inactiveOrGroupAccountCannotReceiveWallet() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            app.accounts.saveOwner(owner)
            app.accounts.saveAccount(account.copy(kind = AccountKind.GROUP))
            assertFails { app.bitcoinWallets.save(wallet(), consentAccepted = true) }
            app.accounts.saveAccount(account.copy(tags = setOf("valroot:archived")))
            assertFails { app.bitcoinWallets.save(wallet(), consentAccepted = true) }
            assertTrue(
                app
                    .snapshot()
                    .ownership.targets
                    .isEmpty(),
            )
            assertFalse(app.sources.consent())
        }
    }

    @Test
    fun writeFailureRollsBackOwnerAccountWalletAndConsent() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            val initial = app.snapshot().ownership
            db.execute("CREATE TRIGGER fail_wallet BEFORE INSERT ON targets BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
            assertFails { app.bitcoinWallets.save(wallet(), account, owner, consentAccepted = true) }
            assertEquals(initial, app.snapshot().ownership)
            assertFalse(app.sources.consent())
            db.execute("DROP TRIGGER fail_wallet")
            app.bitcoinWallets.save(wallet(), account, owner, consentAccepted = true)
            assertEquals(listOf(wallet()), app.snapshot().ownership.targets)
        }
    }

    private fun wallet(
        id: String = "wallet",
        accountId: AccountId = account.id,
        expression: String = descriptor,
    ) = TrackingTarget(
        TrackingTargetId(id),
        accountId,
        TargetIdentity(BITCOIN_MAINNET, "wallet", expression),
        TargetRole.WALLET,
        "Savings",
    )
}
