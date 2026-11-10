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
            app.saveOwner(Owner(OwnerId("owner"), "Owner"))
            app.saveAccount(Account(exchange, OwnerId("owner"), "Exchange", AccountKind.ACCOUNT))
            app.saveAccount(Account(wallet, OwnerId("owner"), "Wallet", AccountKind.WALLET))
            app.saveTransaction(draft("cash", TransactionType.DEPOSIT, usd, "4000", 10, exchange, price = "1"))
            app.saveTransaction(draft("buy", TransactionType.BUY, BITCOIN_ASSET, "0.1", 11, exchange, price = "40000"))
            app.saveTransaction(draft("out", TransactionType.WITHDRAWAL, BITCOIN_ASSET, "0.1", 20, exchange))
            app.saveTransaction(draft("in", TransactionType.TRANSFER, BITCOIN_ASSET, "0.1", 20, wallet, incoming = true))

            fun walletLot() =
                app
                    .accountingReport()
                    .report.lots
                    .single { it.asset == BITCOIN_ASSET && it.account == wallet }

            fun btcDisposals() =
                app
                    .accountingReport()
                    .report.disposals
                    .filter { it.asset == BITCOIN_ASSET }

            // Unlinked: the exchange disposes of its coins and the wallet starts a new lot with no known cost.
            assertNull(walletLot().basis)
            assertEquals(1, btcDisposals().size)

            app.setDecision(ActivityId("out"), ActivityId("in"), LinkState.ACCEPTED)
            // Linked: the lot moves with its age and cost, and nothing is disposed of.
            val lot = walletLot()
            assertEquals(Rational.of("4000"), lot.basis)
            assertEquals(11 * day, lot.acquiredAt)
            assertTrue(btcDisposals().isEmpty())

            // Rejecting the link restores the separate disposal and acquisition.
            app.setDecision(ActivityId("out"), ActivityId("in"), LinkState.REJECTED)
            assertNull(walletLot().basis)
        }
    }
}
