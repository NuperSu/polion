package io.polion.core.bitcoin

import io.polion.core.application.PortfolioApplication
import io.polion.core.model.AccountId
import io.polion.core.model.OwnerId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.Account
import io.polion.core.ownership.AccountKind
import io.polion.core.ownership.Owner
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TargetRole
import io.polion.core.ownership.TrackingTarget
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class BitcoinWalletTest {
    // BIP-84 account key converted from its zpub version bytes to xpub.
    private val xpub = "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"

    @Test fun bip84PublicDerivationAndDescriptorValidation() {
        val wallet = bitcoinWallet(xpub)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", wallet.address(0, 0))
        assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", wallet.address(0, 1))
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", wallet.address(1, 0))
        assertEquals("1JaUQDVNRdhfNsVncGkXedaPSM5Gc54Hso", bitcoinWallet("pkh($xpub/0/*)").address(0, 0))
        assertEquals("3GtVZYzsKF6Feikdjd4bDyPdAiyeHANY9b", bitcoinWallet("sh(wpkh($xpub/0/*))").address(0, 0))
        val descriptor = "wpkh([deadbeef/84h/0h/0h]$xpub/0/*)"
        assertEquals(wallet.address(0, 1), bitcoinWallet(descriptor + "#" + descriptorChecksum(descriptor)).address(0, 1))
        assertEquals("89f8spxm", descriptorChecksum("raw(deadbeef)"))
        assertFails { bitcoinWallet("$descriptor#aaaaaaaa") }
        assertFails { bitcoinWallet("wpkh($xpub/0h/*)") }
        assertFails { bitcoinWallet("tr($xpub/0/*)") }
        assertFails { bitcoinWallet(xpub.dropLast(1) + "1") }
    }

    @Test fun discoveryRequiresConsentAndIsAtomicAtLimit() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 0L }
            val owner = OwnerId("o")
            val account = AccountId("a")
            val walletId = TrackingTargetId("w")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.WALLET))
            app.saveTarget(TrackingTarget(walletId, account, TargetIdentity(BITCOIN_MAINNET, "wallet", xpub), TargetRole.WALLET, "Wallet"))
            val unused = EsploraTransport { """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}""" }
            assertFails { app.discoverBitcoinWallet(walletId, unused, 2, 3) }
            app.setSourceConsent(true)
            val alwaysUsed = EsploraTransport { """{"chain_stats":{"tx_count":1},"mempool_stats":{"tx_count":0}}""" }
            assertFails { app.discoverBitcoinWallet(walletId, alwaysUsed, 2, 3) }
            assertEquals(
                1,
                app
                    .snapshot()
                    .ownership.targets.size,
            )
            val result = app.discoverBitcoinWallet(walletId, unused, 2, 3)
            assertEquals(4, result.addresses)
            assertEquals(0, result.usedAddresses)
            assertEquals(
                4,
                PortfolioApplication(db) { 0L }
                    .snapshot()
                    .ownership.targets
                    .count { it.walletId == walletId },
            )
            assertTrue(
                app
                    .snapshot()
                    .ownership.targets
                    .filter { it.walletId == walletId }
                    .all { it.enabled },
            )
            val nextAddress = bitcoinWallet(xpub).address(0, 1)
            val usedNext =
                EsploraTransport { path ->
                    val count = if (path.endsWith(nextAddress)) 1 else 0
                    """{"chain_stats":{"tx_count":$count},"mempool_stats":{"tx_count":0}}"""
                }
            val expanded = app.discoverBitcoinWallet(walletId, usedNext, 2, 5)
            assertEquals(6, expanded.addresses)
            assertEquals(1, expanded.usedAddresses)
            assertEquals(6, app.discoverBitcoinWallet(walletId, unused, 2, 5).addresses)
        }
    }
}
