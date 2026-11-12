package io.valroot.core.exchange

import io.valroot.core.market.AssetDefinition
import io.valroot.core.market.AssetIdentity
import io.valroot.core.market.AssetKind
import io.valroot.core.market.CoingeckoCatalog
import io.valroot.core.model.AssetId
import io.valroot.core.storage.SqliteDatabase

/** A token contract an exchange lists for a coin: the exchange's chain code and name, and the contract address. */
data class ExchangeContract(
    val chain: String,
    val chainType: String,
    val address: String,
)

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

    /** Exchange chain codes of EVM networks, by chain ID; other chains are recognized by their name in the catalog. */
    val EVM_CHAIN_CODES =
        mapOf(
            "ETH" to 1L,
            "BSC" to 56L,
            "BEP20" to 56L,
            "ARBI" to 42161L,
            "ARBITRUM" to 42161L,
            "OP" to 10L,
            "OPTIMISM" to 10L,
            "BASE" to 8453L,
            "MATIC" to 137L,
            "POL" to 137L,
            "POLYGON" to 137L,
            "AVAXC" to 43114L,
            "MANTLE" to 5000L,
            "ZKSYNC" to 324L,
            "LINEA" to 59144L,
            "CELO" to 42220L,
            "SCROLL" to 534352L,
            "BLAST" to 81457L,
            "OPBNB" to 204L,
            "GNO" to 100L,
            "XDAI" to 100L,
        )

    /**
     * The coin an exchange [symbol] stands for, in this order: a well-known symbol; the coin CoinGecko maps the
     * exchange's token [contracts] to, when they all agree; the only catalog coin with that symbol; the one among
     * several that ranks highest by market capitalization in [ranked] (coin IDs, largest first). Null when none of
     * these decides; the user then chooses.
     */
    fun resolve(
        symbol: String,
        catalog: CoingeckoCatalog?,
        ranked: List<String>,
        contracts: List<ExchangeContract> = emptyList(),
    ): String? {
        WELL_KNOWN[symbol.uppercase()]?.let { return it }
        if (catalog != null) {
            val byContract =
                contracts
                    .mapNotNull { contract ->
                        val chain =
                            EVM_CHAIN_CODES[contract.chain.uppercase()]
                                ?: catalog.platforms.firstOrNull { it.name.equals(contract.chainType.trim(), ignoreCase = true) }?.chainId
                        chain?.let { catalog.tokenCoin(it, contract.address) }
                    }.distinct()
            if (byContract.size == 1) return byContract.single()
        }
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
