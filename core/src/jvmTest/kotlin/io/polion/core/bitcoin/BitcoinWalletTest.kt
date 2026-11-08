package io.polion.core.bitcoin

import io.polion.core.application.BitcoinDiscoveryConflict
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
import io.polion.core.sync.JobState
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
        val legacyAndNested =
            listOf(
                Triple("pkh($xpub/0/*)", 1, "1FGr5rndZHDypjwMWqudNrKtnPHhugFXVg"),
                Triple("pkh($xpub/1/*)", 0, "16fuuGhkywq9pB7BBxi3btQ3C3s4f4dz1N"),
                Triple("sh(wpkh($xpub/0/*))", 1, "3F6eH8MTJeGUNvetRLt6RHFdA7oc8PH6r4"),
                Triple("sh(wpkh($xpub/1/*))", 0, "3EjM76QBfwkwVoPKaHvt1cMz2NXYfQoKUE"),
            )
        legacyAndNested.forEach { (expression, index, expected) ->
            assertEquals(expected, bitcoinWallet(expression).address(0, index), expression)
        }
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

    @Test fun discoveryKeepsOwnershipEditsSavedDuringTheScan() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 0L }
            val owner = OwnerId("o")
            val account = AccountId("a")
            val walletId = TrackingTargetId("w")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.WALLET))
            app.saveTarget(TrackingTarget(walletId, account, TargetIdentity(BITCOIN_MAINNET, "wallet", xpub), TargetRole.WALLET, "Wallet"))
            app.setSourceConsent(true)
            var saved = false
            // An edit made by another writer while the network scan is in progress.
            val transport =
                EsploraTransport {
                    if (!saved) {
                        saved = true
                        app.saveOwner(Owner(OwnerId("late"), "Saved during scan"))
                    }
                    """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}"""
                }
            val result = app.discoverBitcoinWallet(walletId, transport, 2, 3)
            val catalog = app.snapshot().ownership
            assertTrue(catalog.owners.any { it.id == OwnerId("late") }, "owner saved during discovery must survive")
            assertEquals(result.addresses, catalog.targets.count { it.walletId == walletId })
        }
    }

    @Test fun unusedBareXpubAndSingleBranchDescriptorGiveNotices() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 0L }
            val owner = OwnerId("o")
            val account = AccountId("a")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.WALLET))
            app.setSourceConsent(true)
            val unused = EsploraTransport { """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}""" }
            app.saveTarget(
                TrackingTarget(
                    TrackingTargetId("w"),
                    account,
                    TargetIdentity(BITCOIN_MAINNET, "wallet", xpub),
                    TargetRole.WALLET,
                    "W",
                ),
            )
            val bare = app.discoverBitcoinWallet(TrackingTargetId("w"), unused, 2, 3)
            assertEquals(0, bare.usedAddresses)
            assertTrue(bare.notice!!.contains("BIP84"), bare.notice)

            val descriptor = "pkh($xpub/0/*)"
            app.saveTarget(
                TrackingTarget(
                    TrackingTargetId("d"),
                    account,
                    TargetIdentity(BITCOIN_MAINNET, "wallet", bitcoinWallet(descriptor).canonical),
                    TargetRole.WALLET,
                    "D",
                ),
            )
            val firstAddress = bitcoinWallet(descriptor).address(0, 0)
            val oneUsed =
                EsploraTransport { path ->
                    val count = if (path.endsWith(firstAddress)) 1 else 0
                    """{"chain_stats":{"tx_count":$count},"mempool_stats":{"tx_count":0}}"""
                }
            val single = app.discoverBitcoinWallet(TrackingTargetId("d"), oneUsed, 2, 3)
            assertEquals(1, single.usedAddresses)
            assertTrue(single.notice!!.contains("one branch"), single.notice)
        }
    }

    /** A provider where only the wallet's second receive address has one confirmed 1000-satoshi payment. */
    private fun fundedProvider(): EsploraTransport {
        val usedAddress = bitcoinWallet(xpub).address(0, 1)
        val txid = "b".repeat(64)

        fun hash(height: Long) = height.toString(16).padStart(64, '0')
        val funding =
            """{"txid":"$txid","vin":[{"is_coinbase":true}],"vout":[{"scriptpubkey_address":"$usedAddress","value":1000}],""" +
                """"fee":0,"status":{"confirmed":true,"block_time":1,"block_height":100,"block_hash":"${hash(100)}"}}"""

        fun summary(count: Int) = """{"chain_stats":{"tx_count":$count},"mempool_stats":{"tx_count":0}}"""
        return EsploraTransport { path ->
            val address = Regex("/address/([^/]+)").find(path)?.groupValues?.get(1)
            when {
                path == "/blocks/tip/hash" -> hash(110)
                path == "/block/${hash(110)}" -> """{"id":"${hash(110)}","height":110}"""
                path.startsWith("/block-height/") -> hash(path.removePrefix("/block-height/").toLong())
                address == null -> error("Unexpected provider request: $path")
                path == "/address/$address" -> summary(if (address == usedAddress) 1 else 0)
                path == "/address/$address/txs/mempool" -> "[]"
                path == "/address/$address/utxo" ->
                    if (address == usedAddress) {
                        """[{"txid":"$txid","vout":0,"value":1000,"status":""" +
                            """{"confirmed":true,"block_height":100,"block_hash":"${hash(100)}"}}]"""
                    } else {
                        "[]"
                    }
                path == "/address/$address/txs/chain" -> if (address == usedAddress) "[$funding]" else "[]"
                path.startsWith("/address/$address/txs/chain/") -> "[]"
                else -> error("Unexpected provider request: $path")
            }
        }
    }

    @Test fun discoveryMovesTheOwnersStandaloneAddressIntoTheWalletAndItsBalanceFollows() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            val owner = OwnerId("o")
            val walletAccount = AccountId("wallet-account")
            val oldAccount = AccountId("old")
            val walletId = TrackingTargetId("w")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(oldAccount, owner, "ledger", AccountKind.WALLET))
            app.saveAccount(Account(walletAccount, owner, "ledger", AccountKind.WALLET))
            app.setSourceConsent(true)
            val transport = fundedProvider()
            // The user first tracked one of the wallet's addresses on its own, and its history was accepted.
            app.saveTarget(
                TrackingTarget(
                    TrackingTargetId("standalone"),
                    oldAccount,
                    TargetIdentity(BITCOIN_MAINNET, "address", bitcoinWallet(xpub).address(0, 1)),
                    TargetRole.LEAF,
                    "Ledger address",
                ),
            )
            assertTrue(app.refreshBitcoin(transport).all { it == JobState.COMPLETED })
            app.saveTarget(
                TrackingTarget(walletId, walletAccount, TargetIdentity(BITCOIN_MAINNET, "wallet", xpub), TargetRole.WALLET, "Wallet"),
            )

            val discovered = app.discoverBitcoinWallet(walletId, transport)

            assertTrue(discovered.notice!!.contains("Moved 1 address from \"ledger\""), discovered.notice)
            assertTrue(
                app
                    .snapshot()
                    .ownership.targets
                    .none { it.id == TrackingTargetId("standalone") },
            )
            val states = app.refreshBitcoin(transport)
            assertTrue(states.isNotEmpty() && states.all { it == JobState.COMPLETED }, states.toString())
            val overview = app.accountOverview().associateBy { it.account.id }
            assertEquals(
                "1000",
                overview
                    .getValue(walletAccount)
                    .holdings
                    .single()
                    .amount.atomicUnits
                    .toString(),
            )
            assertTrue(overview[oldAccount]?.holdings.orEmpty().isEmpty())
            assertEquals(1, app.snapshot().activityTotal)
        }
    }

    @Test fun walletSavedWithoutDiscoveryIsDiscoveredBeforeTheNextRefresh() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            val owner = OwnerId("o")
            val account = AccountId("a")
            val walletId = TrackingTargetId("w")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.WALLET))
            app.setSourceConsent(true)
            // An import whose discovery never ran leaves a wallet without addresses, which history refresh cannot scan.
            app.saveTarget(TrackingTarget(walletId, account, TargetIdentity(BITCOIN_MAINNET, "wallet", xpub), TargetRole.WALLET, "Wallet"))
            assertEquals(listOf(walletId), app.bitcoinWalletsNeedingDiscovery())
            val transport = fundedProvider()

            assertTrue(app.discoverPendingBitcoinWallets(transport).isEmpty())
            assertTrue(app.refreshBitcoin(transport).all { it == JobState.COMPLETED })

            assertEquals(1, app.snapshot().activityTotal)
            assertTrue(app.bitcoinWalletsNeedingDiscovery().isEmpty())
        }
    }

    @Test fun usedAddressInTheTrailingGapCallsForDiscoveryAgain() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            val owner = OwnerId("o")
            val account = AccountId("a")
            val walletId = TrackingTargetId("w")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.WALLET))
            app.setSourceConsent(true)
            app.saveTarget(TrackingTarget(walletId, account, TargetIdentity(BITCOIN_MAINNET, "wallet", xpub), TargetRole.WALLET, "Wallet"))
            val unused = EsploraTransport { """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}""" }
            // With a gap of 2, discovery stops after receive addresses 0 and 1.
            app.discoverBitcoinWallet(walletId, unused, 2, 3)
            assertTrue(app.bitcoinWalletsNeedingDiscovery(gapLimit = 2).isEmpty())

            // Receive address 1 is now used, so the gap after it is no longer verified.
            assertTrue(app.refreshBitcoin(fundedProvider()).all { it == JobState.COMPLETED })

            assertEquals(listOf(walletId), app.bitcoinWalletsNeedingDiscovery(gapLimit = 2))
        }
    }

    @Test fun discoveredXpubWithHistoryCompletesRefresh() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 15552000000L }
            val owner = OwnerId("o")
            val account = AccountId("a")
            val walletId = TrackingTargetId("w")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.WALLET))
            app.saveTarget(TrackingTarget(walletId, account, TargetIdentity(BITCOIN_MAINNET, "wallet", xpub), TargetRole.WALLET, "Wallet"))
            app.setSourceConsent(true)
            val transport = fundedProvider()
            val discovered = app.discoverBitcoinWallet(walletId, transport)
            assertEquals(1, discovered.usedAddresses)
            val usedAddress = bitcoinWallet(xpub).address(0, 1)
            assertTrue(
                app
                    .snapshot()
                    .ownership.targets
                    .any { it.identity.canonicalKey == usedAddress },
            )
            val states = app.refreshBitcoin(transport)
            assertTrue(states.isNotEmpty() && states.all { it == JobState.COMPLETED }, states.toString())
            assertEquals(1, app.snapshot().activityTotal)
        }
    }

    @Test fun discoveryNamesTheAccountThatAlreadyTracksAnAddress() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 0L }
            val owner = OwnerId("o")
            val account = AccountId("a")
            val walletId = TrackingTargetId("w")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.WALLET))
            // Another owner's copy of the address is not moved; discovery names the account instead.
            app.saveOwner(Owner(OwnerId("other"), "Other"))
            app.saveAccount(Account(AccountId("old"), OwnerId("other"), "Old standalone account", AccountKind.ACCOUNT))
            app.saveTarget(TrackingTarget(walletId, account, TargetIdentity(BITCOIN_MAINNET, "wallet", xpub), TargetRole.WALLET, "Wallet"))
            app.saveTarget(
                TrackingTarget(
                    TrackingTargetId("leaf"),
                    AccountId("old"),
                    TargetIdentity(BITCOIN_MAINNET, "address", bitcoinWallet(xpub).address(0, 0)),
                    TargetRole.LEAF,
                    "Old address",
                ),
            )
            app.setSourceConsent(true)
            val unused = EsploraTransport { """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}""" }
            val failure = assertFails { app.discoverBitcoinWallet(walletId, unused, 2, 3) }
            assertTrue(failure is BitcoinDiscoveryConflict, failure::class.simpleName)
            assertTrue(failure.message!!.contains("Old standalone account"), failure.message)
        }
    }

    @Test fun failedImportIsRetriedWithTheSameWalletInsteadOfBlockingTheExpression() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 0L }
            val owner = OwnerId("o")
            val account = AccountId("a")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(account, owner, "Account", AccountKind.WALLET))
            app.setSourceConsent(true)
            val target =
                TrackingTarget(
                    TrackingTargetId("w1"),
                    account,
                    TargetIdentity(BITCOIN_MAINNET, "wallet", xpub),
                    TargetRole.WALLET,
                    "Wallet",
                )
            val firstId = app.saveBitcoinWallet(target)
            val offline = EsploraTransport { throw BitcoinProviderException(BitcoinProviderFailure.OFFLINE) }
            assertFails { app.discoverBitcoinWallet(firstId, offline, 2, 3) }
            // The failed import left a wallet without addresses, so importing the same expression again reuses it.
            val retriedId = app.saveBitcoinWallet(target.copy(id = TrackingTargetId("w2")))
            assertEquals(firstId, retriedId)
            val unused = EsploraTransport { """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}""" }
            assertEquals(4, app.discoverBitcoinWallet(retriedId, unused, 2, 3).addresses)
            // A wallet that already has addresses is resumed, not imported twice.
            assertEquals(retriedId, app.saveBitcoinWallet(target.copy(id = TrackingTargetId("w3"))))
            assertEquals(
                1,
                app
                    .snapshot()
                    .ownership.targets
                    .count { it.role == TargetRole.WALLET },
            )
        }
    }

    @Test fun resumingATrackedWalletRemovesAnEmptyDuplicateAndItsAccount() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 0L }
            val owner = OwnerId("o")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveAccount(Account(AccountId("a"), owner, "ledger", AccountKind.WALLET))
            app.saveAccount(Account(AccountId("dup"), owner, "ledger", AccountKind.WALLET))
            app.setSourceConsent(true)

            fun wallet(
                id: String,
                account: String,
                expression: String = xpub,
            ) = TrackingTarget(
                TrackingTargetId(id),
                AccountId(account),
                TargetIdentity(BITCOIN_MAINNET, "wallet", expression),
                TargetRole.WALLET,
                "ledger",
            )
            val tracked = app.saveBitcoinWallet(wallet("w1", "a"))
            val unused = EsploraTransport { """{"chain_stats":{"tx_count":0},"mempool_stats":{"tx_count":0}}""" }
            app.discoverBitcoinWallet(tracked, unused, 2, 3)
            // An earlier failed import saved the same key under another text form, as an empty wallet in a second account.
            app.saveTarget(wallet("w2", "dup", "wpkh($xpub/0/*)"))

            val resumed =
                app.saveBitcoinWallet(
                    wallet("w3", "new"),
                    newAccount = Account(AccountId("new"), owner, "ledger", AccountKind.WALLET),
                )

            assertEquals(tracked, resumed)
            val ownership = app.snapshot().ownership
            assertEquals(listOf(tracked), ownership.targets.filter { it.role == TargetRole.WALLET }.map { it.id })
            assertTrue(ownership.accounts.none { it.id == AccountId("dup") || it.id == AccountId("new") })
            assertEquals(4, ownership.targets.count { it.walletId == tracked })
        }
    }
}
