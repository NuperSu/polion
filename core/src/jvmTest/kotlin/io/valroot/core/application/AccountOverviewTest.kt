package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.model.AccountId
import io.valroot.core.model.OwnerId
import io.valroot.core.model.PortfolioId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.Portfolio
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.testing.PublicBitcoin800000
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class AccountOverviewTest {
    @Test fun publicTransactionHistoryFailureRetryArchiveRecoveryAndRestore() {
        io.valroot.testing.verifyAccountHistoryWorkflow { JvmSqliteDatabase(":memory:") }
    }

    @Test fun connectionValidationAndPortfolioChangesAreAtomic() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            app.snapshot()
            val owner = OwnerId("owner")
            val account = AccountId("account")
            app.saveOwner(Owner(owner, "Owner"))
            app.saveOwner(Owner(OwnerId("other"), "Other"))
            app.savePortfolio(Portfolio(PortfolioId("scope"), "Scope", "USD", ownerIds = setOf(OwnerId("other"))))
            val target =
                TrackingTarget(
                    TrackingTargetId("wallet"),
                    account,
                    TargetIdentity(BITCOIN_MAINNET, "wallet", "addr(1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa)"),
                    TargetRole.WALLET,
                    "Wallet",
                )
            assertFails {
                app.saveBitcoinWallet(
                    target,
                    Account(account, owner, "Account", AccountKind.WALLET),
                    consentAccepted = true,
                    providerEndpoint = "http://invalid",
                    portfolio = PortfolioId("scope"),
                )
            }
            assertFails { app.configureBitcoinSource("https://", true) }
            assertEquals("https://blockstream.info/api", app.sourceEndpoint())
            assertTrue(
                app
                    .snapshot()
                    .ownership.targets
                    .isEmpty(),
            )
            assertFails {
                app.saveBitcoinWallet(
                    target,
                    Account(account, owner, "Account", AccountKind.WALLET),
                    consentAccepted = true,
                    providerEndpoint = "https://example.org/api",
                    portfolio = PortfolioId("scope"),
                )
            }
            assertEquals("https://blockstream.info/api", app.sourceEndpoint())
            assertTrue(
                app
                    .snapshot()
                    .ownership.accounts
                    .isEmpty(),
            )
            assertEquals(false, app.sourceConsent())
        }
    }

    @Test fun publicBlockHeaderAndSegwitTransactionMatchRetainedBinaryFacts() {
        fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun doubleHash(value: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest
                .digest(
                    digest.digest(value),
                ).reversedArray()
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        }
        val header = bytes(PublicBitcoin800000.BLOCK_HEADER)
        assertEquals(80, header.size)
        assertEquals(PublicBitcoin800000.BLOCK_HASH, doubleHash(header))
        val raw = bytes(PublicBitcoin800000.RAW_TRANSACTION)
        var position = 6

        fun byte(): Int = raw[position++].toInt() and 255

        fun size(): Int {
            val n = byte()
            require(n < 253)
            return n
        }
        val start = position
        val inputs = size()
        repeat(inputs) {
            position += 36
            val script = size()
            position += script + 4
        }
        val outputs = size()
        val amounts = mutableListOf<Long>()
        repeat(outputs) {
            var amount = 0L
            repeat(8) { shift -> amount = amount or (byte().toLong() shl (shift * 8)) }
            amounts += amount
            val script = size()
            position += script
        }
        val end = position
        repeat(inputs) {
            repeat(size()) {
                val length = size()
                position += length
            }
        }
        assertEquals(raw.size - 4, position)
        val stripped = raw.copyOfRange(0, 4) + raw.copyOfRange(start, end) + raw.copyOfRange(position, raw.size)
        assertEquals(PublicBitcoin800000.TRANSACTION_ID, doubleHash(stripped))
        assertEquals(listOf(143332L, 291851L), amounts)
        assertEquals(169125L, 604308L - amounts.sum())
    }
}
