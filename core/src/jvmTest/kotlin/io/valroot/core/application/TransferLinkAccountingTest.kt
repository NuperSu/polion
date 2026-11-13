package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.OwnerId
import io.valroot.core.model.Rational
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.reconcile.LinkState
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Coins withdrawn from one account and received in another (for example an exchange CSV and a tracked wallet). */
class TransferLinkAccountingTest {
    private val day = 86_400_000L
    private val usd = AssetId("USD")
    private val exchange = AccountId("exchange")
    private val wallet = AccountId("wallet")

    private fun draft(
        id: String,
        type: TransactionType,
        asset: AssetId,
        quantity: String,
        atDay: Long,
        account: AccountId,
        price: String? = null,
        incoming: Boolean = false,
    ) = TransactionDraft(
        ActivityId(id),
        type,
        asset,
        account,
        ExactDecimal.parse(quantity),
        atDay * day,
        usd,
        price?.let(ExactDecimal::parse),
        incomingExternal = incoming,
    )

    @Test fun anAcceptedLinkCarriesTheOriginalCostToTheReceivingAccount() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 400 * day }
            app.snapshot()
            app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
            app.accounts.saveAccount(Account(exchange, OwnerId("owner"), "Exchange", AccountKind.ACCOUNT))
            app.accounts.saveAccount(Account(wallet, OwnerId("owner"), "Wallet", AccountKind.WALLET))
            app.transactions.saveTransaction(draft("cash", TransactionType.DEPOSIT, usd, "4000", 10, exchange, price = "1"))
            app.transactions.saveTransaction(draft("buy", TransactionType.BUY, BITCOIN_ASSET, "0.1", 11, exchange, price = "40000"))
            app.transactions.saveTransaction(draft("out", TransactionType.WITHDRAWAL, BITCOIN_ASSET, "0.1", 20, exchange))
            app.transactions.saveTransaction(draft("in", TransactionType.TRANSFER, BITCOIN_ASSET, "0.1", 20, wallet, incoming = true))

            fun walletLot() =
                app
                    .accounting
                    .report()
                    .report.lots
                    .single { it.asset == BITCOIN_ASSET && it.account == wallet }

            fun btcDisposals() =
                app
                    .accounting
                    .report()
                    .report.disposals
                    .filter { it.asset == BITCOIN_ASSET }

            // Unlinked: the exchange disposes of its coins and the wallet starts a new lot with no known cost.
            assertNull(walletLot().basis)
            assertEquals(1, btcDisposals().size)

            app.transactions.setDecision(ActivityId("out"), ActivityId("in"), LinkState.ACCEPTED)
            // Linked: the lot moves with its age and cost, and nothing is disposed of.
            val lot = walletLot()
            assertEquals(Rational.of("4000"), lot.basis)
            assertEquals(11 * day, lot.acquiredAt)
            assertTrue(btcDisposals().isEmpty())

            // Rejecting the link restores the separate disposal and acquisition.
            app.transactions.setDecision(ActivityId("out"), ActivityId("in"), LinkState.REJECTED)
            assertNull(walletLot().basis)
        }
    }

    @Test fun aWithdrawalOfAnExchangeCoinCarriesItsCostToTheSameCoinOnAChain() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 400 * day }
            app.snapshot()
            app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
            app.accounts.saveAccount(Account(exchange, OwnerId("owner"), "Exchange", AccountKind.ACCOUNT))
            app.accounts.saveAccount(Account(wallet, OwnerId("owner"), "Wallet", AccountKind.WALLET))
            app.market.setConsent(true)

            fun fixture(name: String) = requireNotNull(javaClass.getResource("/coingecko/$name")).readText()
            val token =
                io.valroot.core.evm.EvmAssets
                    .token(1, "0xdac17f958d2ee523a2206206994597c13d831ec7")
            app.assets.register(
                io.valroot.core.market.AssetDefinition(
                    token,
                    io.valroot.core.market.AssetIdentity(
                        io.valroot.core.market.AssetKind.TOKEN,
                        io.valroot.core.evm
                            .evmNetwork(1),
                        "0xdac17f958d2ee523a2206206994597c13d831ec7",
                    ),
                ),
                io.valroot.core.market
                    .AssetMetadata("USDT", "Tether", 6, 0),
            )
            app.evm.refreshCatalog(
                { url, _ -> if (url.contains("asset_platforms")) fixture("asset_platforms.json") else fixture("coins_list.json") },
            )
            // The exchange's USDT has 30 decimals, the token 6.
            val coin = app.exchanges.assets("bybit", setOf("USDT")).getValue("USDT")
            app.transactions.saveTransaction(draft("buy", TransactionType.BUY, coin, "100.5", 11, exchange, price = "1"))
            app.transactions.saveTransaction(draft("out", TransactionType.WITHDRAWAL, coin, "60.25", 20, exchange))
            app.transactions.saveTransaction(draft("in", TransactionType.TRANSFER, token, "60.25", 20, wallet, incoming = true))
            app.transactions.saveTransaction(draft("odd", TransactionType.TRANSFER, token, "60.250001", 20, wallet, incoming = true))

            val suggested = app.accounting.reconciliationCandidates(day).map { it.first.value to it.second.value }
            assertTrue(("in" to "out") in suggested)
            assertTrue(("odd" to "out") !in suggested)

            app.transactions.setDecision(ActivityId("out"), ActivityId("in"), LinkState.ACCEPTED)
            val report = app.accounting.report().report
            val lot = report.lots.single { it.asset == token && it.account == wallet && it.acquiredAt == 11 * day }
            assertEquals(
                io.valroot.core.model.ExactInteger
                    .parse("60250000"),
                lot.quantity,
            )
            assertEquals(Rational.fromDecimal(ExactDecimal.parse("60.25")), lot.basis)
            assertEquals(11 * day, lot.acquiredAt)
            assertTrue(report.disposals.none { it.asset == coin })
            assertEquals(Rational.fromDecimal(ExactDecimal.parse("40.25")), report.lots.single { it.asset == coin }.basis)

            // A link between different amounts is refused.
            app.transactions.setDecision(ActivityId("out"), ActivityId("odd"), LinkState.ACCEPTED)
            assertTrue(app.accounting.transferLinks().none { it.incomingActivity == ActivityId("odd") })
        }
    }
}
