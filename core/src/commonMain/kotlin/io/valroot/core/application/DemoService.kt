package io.valroot.core.application

import io.valroot.core.accounting.QuoteValue
import io.valroot.core.accounting.Treatment
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.market.AssetDefinition
import io.valroot.core.market.AssetMetadata
import io.valroot.core.market.PriceObservation
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.PortfolioId
import io.valroot.core.model.Rational
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.Portfolio

/** The example portfolio: seeding it and removing example data. */
class DemoService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    /**
     * Retires the example records. The ledger is append-only, so example transactions are reversed rather than deleted;
     * example prices are withdrawn so they cannot override real quotes; the example account is archived and the example
     * portfolio removed; an owner still named "Example owner" becomes "Personal". Each step can be repeated, and the
     * example flag is cleared last, so an interrupted removal can be retried.
     */
    fun removeExampleData() {
        require(setting("sample_data") == "true") { "No example data to remove" }
        val at = now().toString()
        db.transaction {
            db.query("SELECT id FROM market_prices WHERE manual=1 AND id LIKE 'demo-price-%'").forEach { row ->
                db.execute("INSERT OR IGNORE INTO market_override_withdrawals VALUES (?, ?, ?)", listOf(row.single(), at, at))
            }
        }
        activities
            .current()
            .filter { it.manual && it.id.value.startsWith("demo-") && it.status != io.valroot.core.ledger.ActivityStatus.REVERSED }
            .forEach { activities.save(it.copy(status = io.valroot.core.ledger.ActivityStatus.REVERSED)) }
        val example = AccountId("demo-wallet")
        if (ownership.load().accounts.any { it.id == example && "valroot:archived" !in it.tags }) app.accounts.archiveAccount(example)
        app.accounts.updateOwnership { catalog ->
            catalog.copy(
                portfolios = catalog.portfolios.filterNot { it.id.value == "demo-portfolio" },
                owners =
                    catalog.owners.map {
                        if (it.id.value == "demo-owner" && it.label == "Example owner") it.copy(label = "Personal") else it
                    },
            )
        }
        db.execute("DELETE FROM application_settings WHERE key='sample_data'")
        invalidate()
    }

    /** Seeds example records only when the portfolio is empty and the user explicitly requests them. */
    fun seed() {
        require(ownership.load().owners.isEmpty() && activities.current().isEmpty()) { "Example data requires an empty portfolio" }
        putSetting("sample_data", "true")
        val at = now()
        val owner =
            io.valroot.core.model
                .OwnerId("demo-owner")
        val account = AccountId("demo-wallet")
        val btc = AssetId("bitcoin:btc")
        val usd = AssetId("USD")
        app.assets.register(
            AssetDefinition(
                btc,
                io.valroot.core.market.AssetIdentity(
                    io.valroot.core.market.AssetKind.NATIVE,
                    io.valroot.core.model
                        .NetworkId("bitcoin-mainnet"),
                    "BTC",
                ),
            ),
            AssetMetadata("BTC", "Bitcoin", 8, 0),
        )
        app.assets.register(
            AssetDefinition(
                usd,
                io.valroot.core.market
                    .AssetIdentity(io.valroot.core.market.AssetKind.FIAT, null, "USD"),
            ),
            AssetMetadata("USD", "US Dollar", 2, 0),
        )
        app.accounts.saveOwner(Owner(owner, "Example owner"))
        app.accounts.saveAccount(Account(account, owner, "Example wallet", io.valroot.core.ownership.AccountKind.WALLET))
        app.accounts.savePortfolio(
            Portfolio(
                io.valroot.core.model
                    .PortfolioId("demo-portfolio"),
                "Example portfolio",
                "USD",
            ),
        )
        app.transactions.saveManualActivity(
            Activity(
                ActivityId("demo-deposit"),
                at,
                "demo-deposit",
                ActivityKind.DEPOSIT,
                io.valroot.core.ledger.ActivityStatus.CONFIRMED,
                listOf(
                    io.valroot.core.ledger.Movement(
                        io.valroot.core.model
                            .EventId("demo-movement"),
                        account,
                        AtomicAmount(
                            btc,
                            io.valroot.core.model.ExactInteger
                                .parse("125000000"),
                        ),
                        io.valroot.core.ledger.MovementRole.TRANSFER,
                    ),
                ),
                emptySet(),
                null,
                true,
            ),
        )
        val day = 86_400_000L

        fun addExample(
            id: String,
            whenAt: Long,
            units: String,
            pending: Boolean = false,
        ) {
            app.transactions.saveManualActivity(
                Activity(
                    ActivityId(id),
                    whenAt,
                    id,
                    if (units.startsWith('-')) ActivityKind.PAYMENT else ActivityKind.DEPOSIT,
                    if (pending) io.valroot.core.ledger.ActivityStatus.PENDING else io.valroot.core.ledger.ActivityStatus.CONFIRMED,
                    listOf(
                        io.valroot.core.ledger.Movement(
                            io.valroot.core.model
                                .EventId("$id-movement"),
                            account,
                            AtomicAmount(
                                btc,
                                io.valroot.core.model.ExactInteger
                                    .parse(units),
                            ),
                            io.valroot.core.ledger.MovementRole.TRANSFER,
                        ),
                    ),
                    emptySet(),
                    null,
                    true,
                ),
            )
        }
        addExample("demo-earlier", at - 60 * day, "40000000")
        addExample("demo-payment", at - 15 * day, "-15000000")
        addExample("demo-pending", at - day, "8000000", true)
        listOf(90L to "42000", 60L to "48000", 30L to "60000", 0L to "65000").forEach { (days, price) ->
            val time = at - days * day
            app.prices.recordManual(
                PriceObservation(
                    "demo-price-$days",
                    io.valroot.core.model
                        .Price(btc, usd, ExactDecimal.parse(price)),
                    null,
                    time,
                    time,
                    true,
                ),
            )
        }
        app.accounting.saveTreatments(
            ActivityId("demo-earlier"),
            listOf(
                Treatment.Acquire(
                    io.valroot.core.model
                        .EventId("demo-earlier-movement"),
                    io.valroot.core.accounting
                        .QuoteValue(
                            usd,
                            io.valroot.core.model.Rational
                                .of("19200"),
                        ),
                ),
            ),
        )
        app.accounting.saveTreatments(
            ActivityId("demo-payment"),
            listOf(
                Treatment.Dispose(
                    io.valroot.core.model
                        .EventId("demo-payment-movement"),
                    io.valroot.core.accounting
                        .QuoteValue(
                            usd,
                            io.valroot.core.model.Rational
                                .of("9000"),
                        ),
                ),
            ),
        )
        app.accounting.saveTreatments(
            ActivityId("demo-deposit"),
            listOf(
                Treatment.Acquire(
                    io.valroot.core.model
                        .EventId("demo-movement"),
                    io.valroot.core.accounting
                        .QuoteValue(
                            usd,
                            io.valroot.core.model.Rational
                                .of("81250"),
                        ),
                ),
            ),
        )
    }
}
