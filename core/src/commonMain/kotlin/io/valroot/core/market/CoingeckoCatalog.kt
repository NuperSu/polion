package io.valroot.core.market

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** A CoinGecko asset platform with an EVM chain ID: the chain's CoinGecko name and its native coin, when known. */
data class EvmPlatform(
    val platformId: String,
    val chainId: Long,
    val name: String,
    val nativeCoinId: String?,
)

/**
 * CoinGecko's mapping from EVM chains and token contracts to coin IDs. A contract that CoinGecko lists under more than one
 * coin on the same platform is left out, so a token is never matched by guess.
 */
class CoingeckoCatalog(
    val platforms: List<EvmPlatform>,
    /** Platform ID → lower-case contract → coin ID. */
    private val contracts: Map<String, Map<String, String>>,
    /** Native coin ID → symbol and name. */
    private val natives: Map<String, Pair<String, String>> = emptyMap(),
    /** Upper-case symbol → every coin with that symbol, as ID and name. */
    private val symbols: Map<String, List<Pair<String, String>>> = emptyMap(),
) {
    /** True when the catalog carries coin symbols; catalogs cached before symbols were kept do not. */
    val hasSymbols: Boolean get() = symbols.isNotEmpty()

    /** Every coin whose symbol is [symbol] (any case), as ID and name. */
    fun coinsWithSymbol(symbol: String): List<Pair<String, String>> = symbols[symbol.uppercase()].orEmpty()

    /** The upper-case symbol and the name of a chain's native coin, when CoinGecko knows the chain. */
    fun nativeLabel(chainId: Long): Pair<String, String>? = nativeCoin(chainId)?.let { natives[it] }

    private val byChain = platforms.associateBy { it.chainId }

    fun platform(chainId: Long): EvmPlatform? = byChain[chainId]

    fun nativeCoin(chainId: Long): String? = byChain[chainId]?.nativeCoinId

    fun tokenCoin(
        chainId: Long,
        contract: String,
    ): String? = byChain[chainId]?.let { contracts[it.platformId]?.get(contract.lowercase()) }

    /** The coin of a token on a CoinGecko platform by its ID, including the non-EVM ones in [OTHER_PLATFORMS]. */
    fun platformCoin(
        platformId: String,
        contract: String,
    ): String? = contracts[platformId]?.get(contract.lowercase())

    /** True when the catalog carries non-EVM token contracts; catalogs cached before they were kept do not. */
    val hasOtherPlatforms: Boolean get() = OTHER_PLATFORMS.any { it in contracts }

    /** Compact text for the device cache: one `P platform chain native name` or `C platform contract coin` line each. */
    fun encode(): String =
        buildString {
            platforms.forEach { append("P\t${it.platformId}\t${it.chainId}\t${it.nativeCoinId.orEmpty()}\t${it.name}\n") }
            contracts.forEach { (platform, map) -> map.forEach { (contract, coin) -> append("C\t$platform\t$contract\t$coin\n") } }
            natives.forEach { (coin, label) -> append("N\t$coin\t${label.first}\t${label.second}\n") }
            symbols.forEach { (symbol, coins) -> coins.forEach { (id, name) -> append("S\t$symbol\t$id\t$name\n") } }
        }

    companion object {
        const val PLATFORMS_PATH = "/asset_platforms"
        const val COINS_PATH = "/coins/list?include_platform=true"
        private val ID = Regex("[a-z0-9][a-z0-9-]{0,99}")
        private val CONTRACT = Regex("0x[0-9a-fA-F]{40}")
        private val SYMBOL = Regex("[A-Z0-9]{1,20}")

        /** Non-EVM platforms whose token contracts are kept, so exchange coins on them can be matched. */
        val OTHER_PLATFORMS = setOf("the-open-network", "solana", "tron", "near-protocol", "sui", "aptos")
        private val OTHER_CONTRACT = Regex("[A-Za-z0-9_:.\\-]{3,120}")

        fun parsePlatforms(payload: String): List<EvmPlatform> =
            Json
                .parseToJsonElement(payload)
                .jsonArray
                .mapNotNull { element ->
                    runCatching {
                        val item = element.jsonObject
                        val chain = item.number("chain_identifier")?.toLongOrNull()?.takeIf { it > 0 } ?: return@runCatching null
                        EvmPlatform(
                            item.text("id")!!.also { require(it.matches(ID)) },
                            chain,
                            item.text("name")!!.take(100),
                            item.text("native_coin_id")?.takeIf { it.matches(ID) },
                        )
                    }.getOrNull()
                }.distinctBy { it.chainId }

        /** Contract → coin per EVM platform from `/coins/list?include_platform=true`; ambiguous contracts are dropped. */
        fun parseContracts(
            payload: String,
            platforms: List<EvmPlatform>,
        ): Map<String, Map<String, String>> {
            val evm = platforms.map { it.platformId }.toSet()
            val seen = mutableMapOf<String, MutableMap<String, String?>>()
            Json.parseToJsonElement(payload).jsonArray.forEach { element ->
                runCatching {
                    val coin = element.jsonObject
                    val id = coin.text("id")!!.also { require(it.matches(ID)) }
                    (coin["platforms"] as? JsonObject)?.forEach { (platform, value) ->
                        val contract = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                        val known =
                            contract != null &&
                                (
                                    platform in evm &&
                                        contract.matches(CONTRACT) ||
                                        platform in OTHER_PLATFORMS &&
                                        contract.matches(OTHER_CONTRACT)
                                )
                        if (known) {
                            val map = seen.getOrPut(platform) { mutableMapOf() }
                            val key = contract!!.lowercase()
                            map[key] = if (key in map && map[key] != id) null else id
                        }
                    }
                }
            }
            return seen.mapValues { (_, map) -> map.filterValues { it != null }.mapValues { it.value!! } }
        }

        /** Symbols and names of the chains' native coins, from the same `/coins/list` payload. */
        fun parseNatives(
            payload: String,
            platforms: List<EvmPlatform>,
        ): Map<String, Pair<String, String>> {
            val ids = platforms.mapNotNull { it.nativeCoinId }.toSet()
            return Json
                .parseToJsonElement(payload)
                .jsonArray
                .mapNotNull { element ->
                    runCatching {
                        val coin = element.jsonObject
                        val id = coin.text("id")!!
                        if (id !in ids) return@runCatching null
                        val symbol =
                            coin
                                .text("symbol")!!
                                .uppercase()
                                .take(20)
                                .filter { it.isLetterOrDigit() }
                        val name = coin.text("name")!!.take(100).filter { it != '\t' && it != '\n' }
                        if (symbol.isEmpty()) null else id to (symbol to name)
                    }.getOrNull()
                }.toMap()
        }

        /** Every coin by upper-case symbol, from the same `/coins/list` payload. Symbols are letters and digits only. */
        fun parseSymbols(payload: String): Map<String, List<Pair<String, String>>> =
            Json
                .parseToJsonElement(payload)
                .jsonArray
                .mapNotNull { element ->
                    runCatching {
                        val coin = element.jsonObject
                        val id = coin.text("id")!!.also { require(it.matches(ID)) }
                        val symbol = coin.text("symbol")!!.uppercase()
                        require(symbol.matches(SYMBOL))
                        Triple(symbol, id, coin.text("name")!!.take(100).filter { it != '\t' && it != '\n' })
                    }.getOrNull()
                }.groupBy({ it.first }, { it.second to it.third })

        fun decode(text: String): CoingeckoCatalog {
            val platforms = mutableListOf<EvmPlatform>()
            val contracts = mutableMapOf<String, MutableMap<String, String>>()
            val natives = mutableMapOf<String, Pair<String, String>>()
            val symbols = mutableMapOf<String, MutableList<Pair<String, String>>>()
            text.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
                val parts = line.split('\t')
                when (parts[0]) {
                    "P" -> platforms += EvmPlatform(parts[1], parts[2].toLong(), parts[4], parts[3].ifEmpty { null })
                    "C" -> contracts.getOrPut(parts[1]) { mutableMapOf() }[parts[2]] = parts[3]
                    "N" -> natives[parts[1]] = parts[2] to parts[3]
                    "S" -> symbols.getOrPut(parts[1]) { mutableListOf() } += parts[2] to parts[3]
                }
            }
            return CoingeckoCatalog(platforms, contracts, natives, symbols)
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

        private fun JsonObject.number(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content
    }
}
