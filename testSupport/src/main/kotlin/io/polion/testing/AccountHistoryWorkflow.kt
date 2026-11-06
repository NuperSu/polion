package io.polion.testing

import io.polion.core.application.PortfolioApplication
import io.polion.core.bitcoin.BITCOIN_ASSET
import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.bitcoin.EsploraTransport
import io.polion.core.model.AccountId
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
import io.polion.core.storage.SqliteDatabase
import io.polion.core.sync.JobState

/** Fixed public transaction amounts; the bounded scan transport is not the address's full real history. */
fun verifyAccountHistoryWorkflow(openDatabase: () -> SqliteDatabase) {
    val address = "bc1p94scc8mn65fnlhyh64zml064kn9692e2n4q7gkttrhmt365ajdyq0m2mzh"
    val hash = PublicBitcoin800000.BLOCK_HASH
    val account = AccountId("public-fixture")
    val wallet = TrackingTargetId("public-source")
    var now = 1_700_000_000_000L
    var failing = false

    // Refresh skips unchanged addresses, so the failing step reports a new transaction to make the scan fetch history.
    var reportedTransactions = 1

    fun summary() =
        """
        {"chain_stats":{"tx_count":$reportedTransactions,"funded_txo_count":1,"funded_txo_sum":143332,"spent_txo_count":0,"spent_txo_sum":0},
        "mempool_stats":{"tx_count":0,"funded_txo_count":0,"funded_txo_sum":0,"spent_txo_count":0,"spent_txo_sum":0}}
        """.trimIndent()
    val transport =
        EsploraTransport { path ->
            when {
                path == "/blocks/tip/height" -> "800000"
                path == "/block/$hash" -> """{"id":"$hash","height":800000}"""
                path == "/blocks/tip/hash" || path == "/block-height/800000" -> hash
                path == "/address/$address" -> summary()
                path == "/address/$address/txs/mempool" -> "[]"
                path == "/address/$address/txs/chain" -> {
                    check(!failing) { "fixture interruption" }
                    "[${PublicBitcoin800000.transaction}]"
                }
                path.startsWith("/address/$address/txs/chain/") -> "[]"
                path == "/address/$address/utxo" -> utxoResponse(address, listOf(PublicBitcoin800000.transaction))
                else -> error("Unexpected fixture request $path")
            }
        }
    openDatabase().use { db ->
        val app = PortfolioApplication(db) { now }
        val owner = OwnerId("owner")
        app.saveOwner(Owner(owner, "Fixture owner"))
        app.saveAccount(Account(AccountId("other"), owner, "Other", AccountKind.ACCOUNT))
        app.savePortfolio(Portfolio(PortfolioId("scope"), "Fixture portfolio", "USD", accountIds = setOf(AccountId("other"))))
        app.saveBitcoinWallet(
            TrackingTarget(
                wallet,
                account,
                TargetIdentity(BITCOIN_MAINNET, "wallet", "addr($address)"),
                TargetRole.WALLET,
                "Public source",
            ),
            newAccount = Account(account, owner, "Public account", AccountKind.WALLET),
            consentAccepted = true,
            providerEndpoint = "https://blockstream.info/api",
            portfolio = PortfolioId("scope"),
        )
        check(app.accountOverview().single { it.account.id == account }.accepted == null)
        check(app.discoverBitcoinWallet(wallet, transport).addresses == 1)
        check(app.refreshBitcoin(transport).all { it == JobState.COMPLETED })
        val accepted = app.accountOverview().single { it.account.id == account }
        check(accepted.currentSourcesAccepted)
        check(accepted.portfolios == listOf("Fixture portfolio"))
        check(accepted.accepted?.observedAt == "2023-11-14T22:13:20Z")
        check(
            accepted.holdings
                .single { it.asset == BITCOIN_ASSET }
                .amount.atomicUnits
                .toString() == "143332",
        )
        val ids = app.snapshot().activities.map { it.activity.id }
        val csv = app.exportCsv()
        now += 1000
        failing = true
        reportedTransactions = 2
        check(app.refreshBitcoin(transport).any { it != JobState.COMPLETED })
        check(app.snapshot().activities.map { it.activity.id } == ids)
        check(app.accountOverview().single { it.account.id == account }.accepted == accepted.accepted)
        failing = false
        reportedTransactions = 1
        val reopened = PortfolioApplication(db) { now }
        check(reopened.refreshBitcoin(transport).all { it == JobState.COMPLETED })
        check(reopened.exportCsv() == csv)
        reopened.archiveAccount(account)
        check(reopened.snapshot().valuations.none { it.amount.atomicUnits.sign != 0 })
        check(reopened.accountOverview().single { it.account.id == account }.accepted != null)
        reopened.archiveAccount(account, false)
        check(reopened.snapshot().activities.map { it.activity.id } == ids)
        check(reopened.refreshBitcoin(transport, recoverHistory = true).all { it == JobState.COMPLETED })
        check(reopened.exportCsv() == csv)
        val acceptedBeforeChange = reopened.accountOverview().single { it.account.id == account }.accepted
        reopened.configureBitcoinSource("https://example.org/api", true)
        val changed = reopened.accountOverview().single { it.account.id == account }
        check(changed.accepted == acceptedBeforeChange && !changed.currentSourcesAccepted)
        reopened.configureBitcoinSource("https://blockstream.info/api", true)
        reopened.saveBitcoinWallet(
            TrackingTarget(
                TrackingTargetId("undiscovered"),
                account,
                TargetIdentity(BITCOIN_MAINNET, "wallet", "addr(1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa)"),
                TargetRole.WALLET,
                "Undiscovered source",
            ),
            consentAccepted = true,
        )
        check(!reopened.accountOverview().single { it.account.id == account }.currentSourcesAccepted)
        reopened.setRefreshPreferences(automatic = true, wifiOnly = true)
        reopened.setAutomaticPriceConsent(true)
        val backup = reopened.exportBackup("account-fixture-password".toCharArray())
        openDatabase().use { restored ->
            reopened.restoreToStaging(restored, backup, "account-fixture-password".toCharArray())
            val copy = PortfolioApplication(restored) { now }
            check(copy.accountOverview() == reopened.accountOverview())
            check(copy.exportCsv() == csv)
            check(copy.automaticRefreshPreference() == true && copy.wifiOnlyRefreshPreference())
            check(copy.sourceConsent() && copy.automaticPriceConsent())
        }
    }
}
