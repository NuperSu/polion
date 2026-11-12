package io.valroot.testing

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.bitcoin.EsploraTransport
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
import io.valroot.core.storage.SqliteDatabase
import io.valroot.core.sync.JobState

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
        app.accounts.saveOwner(Owner(owner, "Fixture owner"))
        app.accounts.saveAccount(Account(AccountId("other"), owner, "Other", AccountKind.ACCOUNT))
        app.accounts.savePortfolio(Portfolio(PortfolioId("scope"), "Fixture portfolio", "USD", accountIds = setOf(AccountId("other"))))
        app.bitcoinWallets.save(
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
        check(
            app.analytics
                .accountOverview()
                .single { it.account.id == account }
                .accepted == null,
        )
        check(app.bitcoinWallets.discover(wallet, transport).addresses == 1)
        check(app.bitcoin.refresh(transport).all { it == JobState.COMPLETED })
        val accepted = app.analytics.accountOverview().single { it.account.id == account }
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
        val csv = app.csv.export()
        now += 1000
        failing = true
        reportedTransactions = 2
        check(app.bitcoin.refresh(transport).any { it != JobState.COMPLETED })
        check(app.snapshot().activities.map { it.activity.id } == ids)
        check(
            app.analytics
                .accountOverview()
                .single { it.account.id == account }
                .accepted == accepted.accepted,
        )
        failing = false
        reportedTransactions = 1
        val reopened = PortfolioApplication(db) { now }
        check(reopened.bitcoin.refresh(transport).all { it == JobState.COMPLETED })
        check(reopened.csv.export() == csv)
        reopened.accounts.archiveAccount(account)
        check(reopened.snapshot().valuations.none { it.amount.atomicUnits.sign != 0 })
        check(
            reopened.analytics
                .accountOverview()
                .single { it.account.id == account }
                .accepted != null,
        )
        reopened.accounts.archiveAccount(account, false)
        check(reopened.snapshot().activities.map { it.activity.id } == ids)
        check(reopened.bitcoin.refresh(transport, recoverHistory = true).all { it == JobState.COMPLETED })
        check(reopened.csv.export() == csv)
        val acceptedBeforeChange =
            reopened.analytics
                .accountOverview()
                .single { it.account.id == account }
                .accepted
        reopened.bitcoin.configureSource("https://example.org/api", true)
        val changed = reopened.analytics.accountOverview().single { it.account.id == account }
        check(changed.accepted == acceptedBeforeChange && !changed.currentSourcesAccepted)
        reopened.bitcoin.configureSource("https://blockstream.info/api", true)
        reopened.bitcoinWallets.save(
            TrackingTarget(
                TrackingTargetId("undiscovered"),
                account,
                TargetIdentity(BITCOIN_MAINNET, "wallet", "addr(1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa)"),
                TargetRole.WALLET,
                "Undiscovered source",
            ),
            consentAccepted = true,
        )
        check(
            !reopened.analytics
                .accountOverview()
                .single { it.account.id == account }
                .currentSourcesAccepted,
        )
        reopened.settings.setRefreshPreferences(automatic = true, wifiOnly = true)
        reopened.prices.setAutomaticConsent(true)
        val backup = reopened.backup.export("account-fixture-password".toCharArray())
        openDatabase().use { restored ->
            reopened.backup.restoreToStaging(restored, backup, "account-fixture-password".toCharArray())
            val copy = PortfolioApplication(restored) { now }
            check(copy.analytics.accountOverview() == reopened.analytics.accountOverview())
            check(copy.csv.export() == csv)
            check(copy.settings.automaticRefresh() == true && copy.settings.wifiOnlyRefresh())
            check(copy.sources.consent() && copy.prices.automaticConsent())
        }
    }
}
