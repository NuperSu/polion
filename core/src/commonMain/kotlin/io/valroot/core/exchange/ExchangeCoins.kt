package io.valroot.core.exchange

import io.valroot.core.market.AssetDefinition
import io.valroot.core.market.AssetIdentity
import io.valroot.core.market.AssetKind
import io.valroot.core.market.CoingeckoCatalog
import io.valroot.core.model.AssetId
import io.valroot.core.storage.SqliteDatabase

/**
 * Coins held on exchanges. An exchange balance is a claim on a coin, not on a token of one chain, so every exchange
 * shares one asset per CoinGecko coin: `coin:<coingecko-id>`, with 18 decimals, which fits the amounts exchanges
 * report (Bybit reports BTC to 14 decimals). Prices and logos come through the asset's coin link.
 */
object ExchangeAssets {
    const val PREFIX = "coin:"
    const val DECIMALS = 18

    fun asset(coinId: String) = AssetId(PREFIX + coinId)

    fun coinOf(asset: AssetId): String? = asset.value.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    fun definition(coinId: String) = AssetDefinition(asset(coinId), AssetIdentity(AssetKind.CUSTOM, null, "coingecko:$coinId"))

    /**
     * Symbols that many CoinGecko coins share (bridged copies, imitations), fixed to the coin exchanges mean by them.
     * Other symbols resolve through the catalog.
     */
    val WELL_KNOWN =
        mapOf(
            "BTC" to "bitcoin",
            "ETH" to "ethereum",
            "USDT" to "tether",
            "USDC" to "usd-coin",
            "BNB" to "binancecoin",
            "SOL" to "solana",
            "XRP" to "ripple",
            "DOGE" to "dogecoin",
            "ADA" to "cardano",
            "TRX" to "tron",
            "TON" to "the-open-network",
            "MNT" to "mantle",
            "DOT" to "polkadot",
            "LTC" to "litecoin",
            "BCH" to "bitcoin-cash",
            "AVAX" to "avalanche-2",
            "LINK" to "chainlink",
            "POL" to "polygon-ecosystem-token",
            "MATIC" to "matic-network",
            "DAI" to "dai",
            "SHIB" to "shiba-inu",
            "XLM" to "stellar",
            "ATOM" to "cosmos",
            "NEAR" to "near",
            "APT" to "aptos",
            "ARB" to "arbitrum",
            "OP" to "optimism",
            "SUI" to "sui",
            "PEPE" to "pepe",
            "UNI" to "uniswap",
            "ETC" to "ethereum-classic",
            "FIL" to "filecoin",
            "FDUSD" to "first-digital-usd",
            "USDE" to "ethena-usde",
            "WBTC" to "wrapped-bitcoin",
        )

    /**
     * The coin an exchange [symbol] stands for: a well-known symbol, the only catalog coin with that symbol, or the one
     * among several that ranks highest by market capitalization in [ranked] (coin IDs, largest first). Null when none
     * of these decides; the user then chooses.
     */
    fun resolve(
        symbol: String,
        catalog: CoingeckoCatalog?,
        ranked: List<String>,
    ): String? {
        WELL_KNOWN[symbol.uppercase()]?.let { return it }
        val candidates = catalog?.coinsWithSymbol(symbol).orEmpty().map { it.first }
        return when {
            candidates.size == 1 -> candidates.single()
            candidates.isEmpty() -> null
            else -> ranked.firstOrNull { it in candidates }
        }
    }
}

/** How an exchange symbol was matched to a coin. */
enum class CoinChoice { AUTOMATIC, USER }

/** Which CoinGecko coin each exchange symbol is. Part of backups; a user's choice is never replaced automatically. */
class ExchangeCoinRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute(
            "CREATE TABLE IF NOT EXISTS exchange_coin_map (exchange TEXT NOT NULL, symbol TEXT NOT NULL, coin_id TEXT NOT NULL, " +
                "chosen_by TEXT NOT NULL, PRIMARY KEY(exchange, symbol))",
        )
    }

    fun coins(exchange: String): Map<String, Pair<String, CoinChoice>> =
        db
            .query("SELECT symbol, coin_id, chosen_by FROM exchange_coin_map WHERE exchange=?", listOf(exchange))
            .associate { it[0]!! to (it[1]!! to CoinChoice.valueOf(it[2]!!)) }

    fun save(
        exchange: String,
        symbol: String,
        coinId: String,
        choice: CoinChoice,
    ) = db.execute("INSERT OR REPLACE INTO exchange_coin_map VALUES (?, ?, ?, ?)", listOf(exchange, symbol, coinId, choice.name))
}
