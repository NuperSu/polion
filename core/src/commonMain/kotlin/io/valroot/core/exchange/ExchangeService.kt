package io.valroot.core.exchange

import io.valroot.core.application.AppContext
import io.valroot.core.application.AppService
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.market.AssetMetadata
import io.valroot.core.market.CoingeckoCatalog
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.storage.sha256
import kotlin.time.Instant

/** An exchange coin symbol. */
private val EXCHANGE_SYMBOL = Regex("[A-Z0-9]{1,20}")

/** Exchange API keys and secrets: letters and digits. */
private val EXCHANGE_KEY = Regex("[A-Za-z0-9]{10,128}")

/** Currencies exchanges hold; each is an exchange currency asset worth one of the portfolio currency. */
private val EXCHANGE_CURRENCIES = io.valroot.core.exchange.ExchangeAssets.CURRENCIES

/** Saved in place of a coin ID when the user says an exchange symbol is no CoinGecko coin; not a valid ID. */
private const val UNLISTED_COIN = ":unlisted"

/** A CoinGecko coin ID. */
private val COIN_ID = Regex("[a-z0-9][a-z0-9-]{0,99}")

/** Exchange accounts: API keys, stored records and sync state, coin matching, trading pairs, and decoded history. */
class ExchangeService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    /**
     * The coin each of an exchange's [symbols] is, as its shared exchange asset. Symbols matched before keep their coin;
     * new ones are matched by [io.valroot.core.exchange.ExchangeAssets.resolve] and saved. A symbol that cannot be matched
     * is saved without a coin and left out of the result until the user chooses one with [chooseCoin].
     */
    fun assets(
        exchange: String,
        symbols: Set<String>,
        contracts: Map<String, List<io.valroot.core.exchange.ExchangeContract>> = emptyMap(),
    ): Map<String, AssetId> {
        val saved = exchangeCoins.coins(exchange)
        val catalog = marketFeeds.catalog()?.second
        val ranked =
            marketFeeds
                .markets(
                    app.assets
                        .currency()
                        .value
                        .lowercase(),
                )?.items
                .orEmpty()
                .sortedBy { it.rank ?: Int.MAX_VALUE }
                .map { it.coinId }
        val result = mutableMapOf<String, AssetId>()
        db.transaction {
            symbols.forEach { symbol ->
                if (symbol.uppercase() in EXCHANGE_CURRENCIES) {
                    val definition =
                        io.valroot.core.exchange.ExchangeAssets
                            .currencyDefinition(symbol)
                    if (market.metadataAt(definition.id, Long.MAX_VALUE) == null) {
                        market.registerAssetInTransaction(
                            definition,
                            io.valroot.core.market.AssetMetadata(
                                symbol.uppercase(),
                                symbol.uppercase(),
                                io.valroot.core.exchange.ExchangeAssets.DECIMALS,
                                0,
                            ),
                        )
                    }
                    result[symbol] = definition.id
                    return@forEach
                }
                if (saved[symbol]?.first == UNLISTED_COIN) {
                    result[symbol] = registerUnlistedAsset(exchange, symbol)
                    return@forEach
                }
                val known = saved[symbol]?.first?.takeIf { it.isNotEmpty() }
                val coin =
                    known ?: io.valroot.core.exchange.ExchangeAssets
                        .resolve(symbol, catalog, ranked, contracts[symbol].orEmpty())
                if (known == null && (coin != null || symbol !in saved)) {
                    exchangeCoins.save(exchange, symbol, coin.orEmpty(), io.valroot.core.exchange.CoinChoice.AUTOMATIC)
                }
                if (coin != null) {
                    result[symbol] = registerAsset(symbol, coin, catalog)
                } else if (catalog != null && catalog.hasSymbols && catalog.coinsWithSymbol(symbol).isEmpty()) {
                    // CoinGecko lists no coin with this symbol: it is counted without a price rather than left out.
                    result[symbol] = registerUnlistedAsset(exchange, symbol)
                }
            }
        }
        return result
    }

    /** Which of [symbols] have no coin yet for [exchange] and are not well known, so their contracts may decide. */
    fun unmatchedSymbols(
        exchange: String,
        symbols: Set<String>,
    ): Set<String> {
        val saved = exchangeCoins.coins(exchange)
        return symbols
            .filter {
                saved[it]?.first.isNullOrEmpty() &&
                    it.uppercase() !in io.valroot.core.exchange.ExchangeAssets.WELL_KNOWN &&
                    it.uppercase() !in EXCHANGE_CURRENCIES
            }.toSet()
    }

    /** The coin symbols in an exchange account's saved records. */
    fun rowSymbols(
        kind: io.valroot.core.exchange.ExchangeKind,
        account: AccountId,
    ): Set<String> {
        val rows = exchangeSync.rows(account)
        return when (kind) {
            io.valroot.core.exchange.ExchangeKind.BINANCE ->
                io.valroot.core.exchange.BinanceDecoder
                    .coins(rows, pairs(account))
            io.valroot.core.exchange.ExchangeKind.MEXC ->
                io.valroot.core.exchange.MexcDecoder
                    .coins(rows, pairs(account))
            io.valroot.core.exchange.ExchangeKind.BYBIT ->
                rows
                    .flatMap { row ->
                        val o =
                            io.valroot.core.exchange.Bybit
                                .row(row.payload)
                        listOf(
                            "currency",
                            "coin",
                            "fromCoin",
                            "toCoin",
                        ).mapNotNull { (o[it] as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    }.filter { it.isNotBlank() }
                    .toSet()
        }
    }

    /** The symbols an exchange account's trades are read for (Binance, MEXC), with their base and quote coins. */
    fun pairs(account: AccountId): Map<String, Pair<String, String>> =
        exchangeSync
            .cursors(account)
            .filterKeys {
                it.startsWith(io.valroot.core.exchange.BinanceConnector.PAIR) ||
                    it.startsWith(io.valroot.core.exchange.BinanceConnector.USER_PAIR)
            }.mapNotNull { (key, value) ->
                val coins = value.split('/')
                if (coins.size != 2) {
                    null
                } else {
                    key.substringAfter(':') to (coins[0] to coins[1])
                }
            }.toMap()

    /** Adds a symbol to read an exchange account's trades for, as its base and quote coins; read on the next refresh. */
    fun addPair(
        account: AccountId,
        base: String,
        quote: String,
    ) {
        val b = base.trim().uppercase()
        val q = quote.trim().uppercase()
        require(b.matches(EXCHANGE_SYMBOL) && q.matches(EXCHANGE_SYMBOL) && b != q) { "Enter two coin symbols, such as BTC and USDT" }
        exchangeSync.saveCursor(account, io.valroot.core.exchange.BinanceConnector.USER_PAIR + b + q, "$b/$q")
    }

    /**
     * Derives a MEXC account's activities again from every saved record and its spot [balances], read at [atMillis].
     * Coins nothing explains are an opening amount where the records start.
     */
    fun applyMexcHistory(
        account: AccountId,
        uid: String,
        balances: Map<String, ExactDecimal>,
        atMillis: Long,
        pairs: Map<String, Pair<String, String>>,
        contracts: Map<String, List<io.valroot.core.exchange.ExchangeContract>> = emptyMap(),
    ): io.valroot.core.exchange.ExchangeDecoded {
        val kind = io.valroot.core.exchange.ExchangeKind.MEXC
        require(targets(kind).any { it.first == account && it.second == uid }) { "Unknown MEXC account" }
        val rows = exchangeSync.rows(account)
        val held = balances.filterValues { it.coefficient.sign != 0 }
        val assets =
            assets(
                kind.id,
                io.valroot.core.exchange.MexcDecoder
                    .coins(rows, pairs) + held.keys,
                contracts,
            )
        val snapshot = "mexc-balances-1\n" + held.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}|${it.value}" }
        val decoded =
            db.transaction {
                val source = ingestRecord(kind, account, uid, "balances", snapshot, io.valroot.core.exchange.MexcDecoder.VERSION)
                val recordsFrom =
                    exchangeSync
                        .streams(account)
                        .values
                        .filter {
                            it.stream == io.valroot.core.exchange.MexcDecoder.DEPOSITS ||
                                it.stream == io.valroot.core.exchange.MexcDecoder.WITHDRAWALS
                        }.minOfOrNull { it.fromMillis }
                val decoded =
                    io.valroot.core.exchange.MexcDecoder.decode(
                        account,
                        uid,
                        rows,
                        assets,
                        pairs,
                        io.valroot.core.exchange.MexcDecoder
                            .Balances(atMillis, held, source, recordsFrom),
                    ) { asset -> market.metadataAt(asset, Long.MAX_VALUE)?.decimals ?: io.valroot.core.exchange.ExchangeAssets.DECIMALS }
                saveDecode(kind, account, uid, decoded, recordsFrom, null)
                decoded
            }
        invalidate()
        return decoded
    }

    /**
     * Derives a Binance account's activities again from every saved record and the current [balances] (each coin's
     * total over every wallet), read at [atMillis]. [pairs] names each traded symbol's coins; [contracts] are Binance's
     * token contracts for symbols not matched yet.
     */
    fun applyBinanceHistory(
        account: AccountId,
        uid: String,
        balances: Map<String, ExactDecimal>,
        atMillis: Long,
        pairs: Map<String, Pair<String, String>>,
        contracts: Map<String, List<io.valroot.core.exchange.ExchangeContract>> = emptyMap(),
    ): io.valroot.core.exchange.ExchangeDecoded {
        val kind = io.valroot.core.exchange.ExchangeKind.BINANCE
        require(targets(kind).any { it.first == account && it.second == uid }) { "Unknown Binance account" }
        val rows = exchangeSync.rows(account)
        val held = balances.filterValues { it.coefficient.sign != 0 }
        val assets =
            assets(
                kind.id,
                io.valroot.core.exchange.BinanceDecoder
                    .coins(rows, pairs) + held.keys,
                contracts,
            )
        val snapshot = "binance-balances-1\n" + held.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}|${it.value}" }
        val decoded =
            db.transaction {
                val source = ingestRecord(kind, account, uid, "balances", snapshot, io.valroot.core.exchange.BinanceDecoder.VERSION)
                val decoded =
                    io.valroot.core.exchange.BinanceDecoder.decode(
                        account,
                        uid,
                        rows,
                        assets,
                        pairs,
                        io.valroot.core.exchange.BinanceDecoder
                            .Balances(atMillis, held, source),
                    ) { asset -> market.metadataAt(asset, Long.MAX_VALUE)?.decimals ?: io.valroot.core.exchange.ExchangeAssets.DECIMALS }
                val historyFrom =
                    exchangeSync
                        .streams(account)
                        .values
                        .filter {
                            it.stream == io.valroot.core.exchange.BinanceDecoder.DEPOSITS ||
                                it.stream == io.valroot.core.exchange.BinanceDecoder.WITHDRAWALS
                        }.minOfOrNull { it.fromMillis }
                saveDecode(kind, account, uid, decoded, historyFrom, null)
                decoded
            }
        invalidate()
        return decoded
    }

    /**
     * An exchange's symbols that have no coin yet, with the catalog coins that carry each symbol. Symbols no CoinGecko
     * coin carries are left out once the catalog has symbols, since they are counted without a price.
     */
    fun unresolvedCoins(exchange: String): Map<String, List<Pair<String, String>>> {
        val catalog = marketFeeds.catalog()?.second
        return exchangeCoins
            .coins(exchange)
            .filterValues { it.first.isEmpty() }
            .keys
            .filter { it.uppercase() !in EXCHANGE_CURRENCIES }
            .sorted()
            .associateWith { catalog?.coinsWithSymbol(it).orEmpty() }
            .filterValues { it.isNotEmpty() || catalog?.hasSymbols != true }
    }

    /** The user's coin for an exchange symbol; it replaces any automatic match and takes effect on the next refresh. */
    fun chooseCoin(
        exchange: String,
        symbol: String,
        coinId: String,
    ) {
        require(COIN_ID.matches(coinId)) { "Unknown coin" }
        exchangeCoins.save(exchange, symbol, coinId, io.valroot.core.exchange.CoinChoice.USER)
    }

    /** The user's answer that an exchange symbol is no CoinGecko coin: it is counted without a price. */
    fun markCoinUnlisted(
        exchange: String,
        symbol: String,
    ) = exchangeCoins.save(exchange, symbol, UNLISTED_COIN, io.valroot.core.exchange.CoinChoice.USER)

    private fun registerUnlistedAsset(
        exchange: String,
        symbol: String,
    ): AssetId {
        val definition =
            io.valroot.core.exchange.ExchangeAssets
                .unlistedDefinition(exchange, symbol)
        if (market.metadataAt(definition.id, Long.MAX_VALUE) == null) {
            market.registerAssetInTransaction(
                definition,
                io.valroot.core.market.AssetMetadata(
                    symbol.uppercase(),
                    symbol.uppercase(),
                    io.valroot.core.exchange.ExchangeAssets.DECIMALS,
                    0,
                ),
            )
        }
        return definition.id
    }

    private fun registerAsset(
        symbol: String,
        coinId: String,
        catalog: CoingeckoCatalog?,
    ): AssetId {
        val definition =
            io.valroot.core.exchange.ExchangeAssets
                .definition(coinId)
        if (market.metadataAt(definition.id, Long.MAX_VALUE) == null) {
            val name = catalog?.coinsWithSymbol(symbol)?.firstOrNull { it.first == coinId }?.second ?: symbol.uppercase()
            market.registerAssetInTransaction(
                definition,
                io.valroot.core.market.AssetMetadata(
                    symbol.uppercase(),
                    name,
                    io.valroot.core.exchange.ExchangeAssets.DECIMALS,
                    0,
                ),
            )
        }
        if (assetLinks.coinOf(definition.id) != coinId) assetLinks.link(definition.id, coinId)
        return definition.id
    }

    /** Enabled accounts of an exchange with their user IDs there. */
    fun targets(kind: io.valroot.core.exchange.ExchangeKind): List<Pair<AccountId, String>> =
        ownership
            .load()
            .targets
            .filter { it.enabled && it.identity.networkId == kind.network && it.identity.kind == "uid" }
            .map { it.accountId to it.identity.canonicalKey }

    fun hasKey(
        kind: io.valroot.core.exchange.ExchangeKind,
        account: AccountId,
    ): Boolean = key(kind, account) != null

    /** An exchange account's API key and secret; device settings, not part of backups. */
    fun key(
        kind: io.valroot.core.exchange.ExchangeKind,
        account: AccountId,
    ): Pair<String, String>? {
        val key = marketFeeds.deviceSetting("${kind.id}_key:${account.value}") ?: return null
        val secret = marketFeeds.deviceSetting("${kind.id}_secret:${account.value}") ?: return null
        return key to secret
    }

    /** Saves or (with null) removes an exchange account's API key and secret on this device. */
    fun setKey(
        kind: io.valroot.core.exchange.ExchangeKind,
        account: AccountId,
        key: String?,
        secret: String?,
    ) {
        val k = key?.trim()?.takeIf { it.isNotEmpty() }
        val s = secret?.trim()?.takeIf { it.isNotEmpty() }
        require((k == null) == (s == null)) { "Enter both the API key and its secret" }
        require(k == null || k.matches(EXCHANGE_KEY)) { "That does not look like a ${kind.label} API key" }
        require(s == null || s.matches(EXCHANGE_KEY)) { "That does not look like a ${kind.label} API secret" }
        db.transaction {
            marketFeeds.putDeviceSetting("${kind.id}_key:${account.value}", k)
            marketFeeds.putDeviceSetting("${kind.id}_secret:${account.value}", s)
        }
    }

    fun streams(account: AccountId): Map<String, io.valroot.core.exchange.ExchangeStreamState> = exchangeSync.streams(account)

    fun saveStream(
        account: AccountId,
        state: io.valroot.core.exchange.ExchangeStreamState,
    ) = exchangeSync.saveStream(account, state)

    fun rowKeys(
        account: AccountId,
        stream: String,
    ): Set<String> =
        exchangeSync
            .rows(account)
            .filter { it.stream == stream }
            .map { it.key }
            .toSet()

    /** Saves exchange records as received, each also as a raw provider record for provenance, in one transaction. */
    fun saveRows(
        kind: io.valroot.core.exchange.ExchangeKind,
        account: AccountId,
        uid: String,
        rows: List<io.valroot.core.exchange.ExchangeRow>,
        version: String,
    ) {
        if (rows.isEmpty()) return
        db.transaction {
            rows.forEach { row ->
                val source = ingestRecord(kind, account, uid, "${row.stream}:${row.key}", row.payload, version)
                exchangeSync.saveRow(account, row.copy(source = source.value))
            }
        }
    }

    /** One exchange record as a raw provider record, namespaced by exchange and user ID. */
    private fun ingestRecord(
        kind: io.valroot.core.exchange.ExchangeKind,
        account: AccountId,
        uid: String,
        externalId: String,
        payload: String,
        version: String,
    ): io.valroot.core.model.SourceRecordId =
        store.ingestInTransaction(
            io.valroot.core.storage.RawObservation(
                "${kind.id}:$uid:$externalId",
                "${kind.id}:$uid",
                externalId,
                kind.network.value,
                "${kind.id}-api",
                Instant.fromEpochMilliseconds(now()).toString(),
                payload,
                version,
                sha256(payload),
                "account=${account.value}",
            ),
        )

    /** Small per-account progress values of an exchange sync. */
    fun cursors(account: AccountId): Map<String, String> = exchangeSync.cursors(account)

    fun saveCursor(
        account: AccountId,
        key: String,
        value: String,
    ) = exchangeSync.saveCursor(account, key, value)

    /** Forgets an exchange account's saved records, so the next refresh reads its history again. */
    fun forgetHistory(account: AccountId) = db.transaction { exchangeSync.forget(account) }

    fun status(account: AccountId): io.valroot.core.exchange.ExchangeAccountStatus = exchangeSync.status(account)

    fun recordError(
        account: AccountId,
        message: String,
    ) = exchangeSync.saveStatus(account, exchangeSync.status(account).copy(checkedAtMillis = now(), error = message))

    /** On-chain transactions behind exchange deposits and withdrawals, by activity. */
    fun transfers(): Map<ActivityId, io.valroot.core.exchange.ExchangeTransfer> = exchangeSync.transfers()

    /**
     * Derives a Bybit account's activities again from every saved record and the current [balances], read at
     * [atMillis]; [coveredFrom] is where each wallet's log starts. Activities the records no longer produce (an
     * adjustment that is now explained) are superseded. Returns the decode, with its unmatched symbols and failures.
     */
    fun applyBybitHistory(
        account: AccountId,
        uid: String,
        balances: List<io.valroot.core.exchange.BybitBalance>,
        atMillis: Long,
        coveredFrom: Map<String, Long>,
        keyExpiresAtMillis: Long?,
        /** Bybit's token contracts for symbols not matched yet, so CoinGecko's contract map can name their coins. */
        contracts: Map<String, List<io.valroot.core.exchange.ExchangeContract>> = emptyMap(),
    ): io.valroot.core.exchange.ExchangeDecoded {
        require(targets(io.valroot.core.exchange.ExchangeKind.BYBIT).any { it.first == account && it.second == uid }) {
            "Unknown Bybit account"
        }
        val rows = exchangeSync.rows(account)
        val held = balances.filter { it.amount.coefficient.sign != 0 }
        val symbols = rowSymbols(io.valroot.core.exchange.ExchangeKind.BYBIT, account) + held.map { it.coin }
        val assets = assets(io.valroot.core.exchange.BYBIT_EXCHANGE, symbols, contracts)
        val snapshot =
            held
                .sortedWith(compareBy({ it.accountType }, { it.coin }))
                .joinToString("\n") { "${it.accountType}|${it.coin}|${it.amount}" }
                .let { "bybit-balances-1\n$it" }
        val decoded =
            db.transaction {
                val source =
                    ingestRecord(
                        io.valroot.core.exchange.ExchangeKind.BYBIT,
                        account,
                        uid,
                        "balances",
                        snapshot,
                        io.valroot.core.exchange.BybitDecoder.VERSION,
                    )
                val wallets = held.filter { it.accountType == "UNIFIED" || it.accountType == "FUND" }
                val elsewhere = held - wallets.toSet()
                val decoded =
                    io.valroot.core.exchange.BybitDecoder.decode(
                        account,
                        uid,
                        rows,
                        assets,
                        io.valroot.core.exchange.BybitBalances(
                            atMillis,
                            wallets.associate { (it.accountType to it.coin) to it.amount },
                            elsewhere.groupBy { it.coin }.mapValues { (_, list) -> list.map { it.amount }.reduce { a, b -> a + b } },
                            source,
                        ),
                        coveredFrom,
                    ) { asset -> market.metadataAt(asset, Long.MAX_VALUE)?.decimals ?: io.valroot.core.exchange.ExchangeAssets.DECIMALS }
                saveDecode(
                    io.valroot.core.exchange.ExchangeKind.BYBIT,
                    account,
                    uid,
                    decoded,
                    coveredFrom.values.minOrNull(),
                    keyExpiresAtMillis,
                )
                decoded
            }
        invalidate()
        return decoded
    }

    /**
     * Saves an exchange account's derived activities in the caller's transaction: changed ones get a revision,
     * activities no longer derived are superseded, and the account's transfers and status are replaced.
     */
    private fun saveDecode(
        kind: io.valroot.core.exchange.ExchangeKind,
        account: AccountId,
        uid: String,
        decoded: io.valroot.core.exchange.ExchangeDecoded,
        historyFrom: Long?,
        keyExpiresAtMillis: Long?,
    ) {
        val wanted = decoded.activities.map { it.id }.toSet()
        val prefix = "${kind.id}:$uid:"
        activities
            .current()
            .filter { it.id.value.startsWith(prefix) && it.id !in wanted && it.status == ActivityStatus.CONFIRMED }
            .forEach { activities.saveInTransaction(it.copy(status = ActivityStatus.SUPERSEDED)) }
        decoded.activities.forEach { activity ->
            if (activities.currentById(activity.id) != activity) activities.saveInTransaction(activity)
        }
        exchangeSync.replaceTransfers(account, decoded.transfers)
        exchangeSync.saveStatus(
            account,
            io.valroot.core.exchange
                .ExchangeAccountStatus(now(), decoded.failures.firstOrNull(), historyFrom, keyExpiresAtMillis),
        )
    }
}
