package io.valroot.core.evm

import io.valroot.core.model.ExactInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** An Alchemy network: its slug and the EVM chain it serves, and whether Alchemy lists contract-made transfers on it. */
data class AlchemyNetwork(
    val slug: String,
    val chainId: Long,
    val name: String,
    val internalTransfers: Boolean,
)

/** A per-network problem in an otherwise successful Alchemy response. */
data class AlchemyPartialError(
    val network: String,
    /** `unsupported` (permanent for this request), `timeout` or `error`. */
    val state: String,
    val message: String,
)

data class AlchemyTransfers(
    val transactions: List<Pair<String, EvmTransaction>>,
    val internal: List<Pair<String, EvmInternalTransfer>>,
    val tokens: List<Pair<String, EvmTokenTransfer>>,
    val pageKey: String?,
    val errors: List<AlchemyPartialError>,
)

/** A transaction receipt: who paid, whether it succeeded, and the total fee including any L1 data fee. */
data class EvmReceipt(
    val hash: String,
    val from: String,
    val blockNumber: Long,
    val position: Int,
    val success: Boolean,
    val fee: ExactInteger,
)

/**
 * Alchemy's Portfolio APIs (transfers and token balances across networks) and the per-network JSON-RPC used for
 * receipts and the sent-transaction count. Requests are POST bodies; the host inserts the key into the URL path.
 */
object Alchemy {
    const val DATA_BASE = "https://api.g.alchemy.com/data/v1"
    const val MAX_NETWORKS = 5
    const val PAGE_SIZE = 1000
    const val RECEIPT_BATCH = 50

    /** Networks whose transfer history Alchemy serves, with verified chain IDs. */
    val NETWORKS =
        listOf(
            AlchemyNetwork("eth-mainnet", 1, "Ethereum", internalTransfers = true),
            AlchemyNetwork("bnb-mainnet", 56, "BNB Smart Chain", internalTransfers = false),
            AlchemyNetwork("base-mainnet", 8453, "Base", internalTransfers = true),
            AlchemyNetwork("matic-mainnet", 137, "Polygon", internalTransfers = true),
            AlchemyNetwork("arb-mainnet", 42161, "Arbitrum One", internalTransfers = false),
            AlchemyNetwork("opt-mainnet", 10, "OP Mainnet", internalTransfers = false),
            AlchemyNetwork("gnosis-mainnet", 100, "Gnosis", internalTransfers = false),
            AlchemyNetwork("celo-mainnet", 42220, "Celo", internalTransfers = false),
            AlchemyNetwork("ronin-mainnet", 2020, "Ronin", internalTransfers = false),
        )

    private val bySlug = NETWORKS.associateBy { it.slug }
    private val byChain = NETWORKS.associateBy { it.chainId }

    fun network(chainId: Long): AlchemyNetwork? = byChain[chainId]

    fun transfersUrl() = "$DATA_BASE/{key}/transfers/by-address"

    fun tokensUrl() = "$DATA_BASE/{key}/assets/tokens/by-address"

    /** The JSON-RPC URL for one network, with the key placeholder the host fills in. */
    fun rpcUrl(chainId: Long): String = "https://${requireNotNull(network(chainId)) { "Unsupported network" }.slug}.g.alchemy.com/v2/{key}"

    fun transfersBody(
        address: String,
        chains: List<Long>,
        pageKey: String? = null,
        maxCount: Int = PAGE_SIZE,
        internal: Boolean = false,
    ): String {
        require(chains.size in 1..MAX_NETWORKS && maxCount in 1..PAGE_SIZE)
        val networks = chains.map { requireNotNull(network(it)) { "Unsupported network" } }
        require(!internal || networks.all { it.internalTransfers }) { "Contract transfers are not listed on this network" }
        return buildJsonObject {
            put("address", evmAddress(address))
            put("networks", buildJsonArray { networks.forEach { add(JsonPrimitive(it.slug)) } })
            put(
                "category",
                buildJsonArray {
                    (
                        if (internal) {
                            listOf(
                                "internal",
                            )
                        } else {
                            listOf("external", "erc20", "erc721", "erc1155")
                        }
                    ).forEach { add(JsonPrimitive(it)) }
                },
            )
            put("order", "desc")
            put("excludeZeroValue", false)
            put("maxCount", maxCount)
            pageKey?.let { put("pageKey", it) }
        }.toString()
    }

    fun tokensBody(
        address: String,
        chains: List<Long>,
    ): String {
        require(chains.size in 1..MAX_NETWORKS)
        return buildJsonObject {
            put(
                "addresses",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("address", evmAddress(address))
                            put("networks", buildJsonArray { chains.forEach { add(JsonPrimitive(requireNotNull(network(it)).slug)) } })
                        },
                    )
                },
            )
            put("withMetadata", true)
            put("withPrices", false)
        }.toString()
    }

    fun parseTransfers(payload: String): AlchemyTransfers {
        val root = Json.parseToJsonElement(payload).jsonObject
        val transactions = mutableListOf<Pair<String, EvmTransaction>>()
        val internal = mutableListOf<Pair<String, EvmInternalTransfer>>()
        val tokens = mutableListOf<Pair<String, EvmTokenTransfer>>()
        root["transfers"]!!.jsonArray.forEachIndexed { position, element ->
            val item = element.jsonObject
            val slug = item.text("network")!!
            if (slug !in bySlug) return@forEachIndexed
            val hash = hash(item.text("hash"))
            val block = evmQuantity(item.text("blockNum")!!).toString().toLong()
            val time =
                kotlin.time.Instant
                    .parse(item.text("blockTimestamp")!!)
                    .toEpochMilliseconds()
            val from = evmAddress(item.text("from")!!)
            val to = item.text("to")?.let(::evmAddress)
            val fungible = item["fungible"] as? JsonObject
            val raw = fungible?.text("rawValue")?.let(::evmQuantity) ?: ExactInteger.ZERO
            when (val category = item.text("category")) {
                // Fee and success come from the receipt; a listed external transfer moved value, so it succeeded.
                "external" ->
                    transactions +=
                        slug to EvmTransaction(hash, block, null, time, from, to, raw, ExactInteger.ZERO, success = true)
                "internal" -> internal += slug to EvmInternalTransfer(hash, block, position, time, from, to, raw, success = true)
                "erc20", "erc721", "erc1155" -> {
                    val contract = evmAddress(item.text("contractAddress")!!)
                    val logIndex = item.text("uniqueId")!!.substringAfter(":log:", "").toIntOrNull() ?: position
                    if (category == "erc20") {
                        tokens +=
                            slug to
                            EvmTokenTransfer(
                                hash,
                                block,
                                logIndex,
                                time,
                                from,
                                to ?: ZERO_ADDRESS,
                                contract,
                                EvmTokenStandard.ERC20,
                                null,
                                raw,
                                requireNotNull(
                                    fungible?.text("decimals")?.toIntOrNull()?.takeIf { it in 0..255 },
                                ) { "Token without decimals" },
                                cleanLabel(fungible?.text("asset"), "?", 20),
                                cleanLabel(fungible?.text("asset"), "Unknown token", 100),
                                flagged = false,
                            )
                    } else {
                        (item["nfts"] as? JsonObject)?.get("tokens")?.jsonArray?.forEachIndexed { index, nft ->
                            val token = nft.jsonObject
                            tokens +=
                                slug to
                                EvmTokenTransfer(
                                    hash,
                                    block,
                                    logIndex * 1000 + index,
                                    time,
                                    from,
                                    to ?: ZERO_ADDRESS,
                                    contract,
                                    if (category == "erc721") EvmTokenStandard.ERC721 else EvmTokenStandard.ERC1155,
                                    evmQuantity(token.text("tokenId")!!),
                                    if (category == "erc721") ExactInteger.parse("1") else evmQuantity(token.text("amount") ?: "0x1"),
                                    0,
                                    "NFT",
                                    "NFT",
                                    flagged = false,
                                )
                        }
                    }
                }
                else -> Unit
            }
        }
        return AlchemyTransfers(transactions, internal, tokens, root.text("pageKey"), partialErrors(root))
    }

    /** Native (contract null) and ERC-20 balances by network slug; token metadata where Alchemy has it. */
    fun parseTokenBalances(payload: String): Pair<List<Pair<String, EvmTokenBalance>>, List<AlchemyPartialError>> {
        val root = Json.parseToJsonElement(payload).jsonObject
        val data = root["data"]!!.jsonObject
        val balances =
            data["tokens"]!!.jsonArray.mapNotNull { element ->
                val item = element.jsonObject
                val slug = item.text("network")!!
                if (slug !in bySlug) return@mapNotNull null
                val contract = item.text("tokenAddress")?.let(::evmAddress)
                slug to
                    EvmTokenBalance(
                        contract ?: ZERO_ADDRESS,
                        if (contract == null) EvmTokenStandard.NATIVE else EvmTokenStandard.ERC20,
                        null,
                        evmQuantity(item.text("tokenBalance") ?: "0x0"),
                    )
            }
        return balances to partialErrors(root)
    }

    /** A JSON-RPC batch of receipt requests. */
    fun receiptsBody(hashes: List<String>): String {
        require(hashes.size in 1..RECEIPT_BATCH)
        return buildJsonArray {
            hashes.forEachIndexed { index, hash ->
                add(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", index)
                        put("method", "eth_getTransactionReceipt")
                        put("params", buildJsonArray { add(JsonPrimitive(hash(hash))) })
                    },
                )
            }
        }.toString()
    }

    fun parseReceipts(payload: String): List<EvmReceipt> =
        (Json.parseToJsonElement(payload) as JsonArray).map { element ->
            val response = element.jsonObject
            require(response["error"] == null || response["error"] is JsonNull) { "Receipt request failed" }
            val receipt = requireNotNull(response["result"] as? JsonObject) { "Receipt not available yet" }
            val gas = evmQuantity(receipt.text("gasUsed")!!)
            val price = evmQuantity(requireNotNull(receipt.text("effectiveGasPrice")) { "Receipt without gas price" })
            // OP-stack chains (Base, OP and others) charge an L1 data fee on top of L2 gas.
            val l1 = receipt.text("l1Fee")?.let(::evmQuantity) ?: ExactInteger.ZERO
            EvmReceipt(
                hash(receipt.text("transactionHash")),
                evmAddress(receipt.text("from")!!),
                evmQuantity(receipt.text("blockNumber")!!).toString().toLong(),
                evmQuantity(receipt.text("transactionIndex")!!).toString().toInt(),
                receipt.text("status") == "0x1",
                gas * price + l1,
            )
        }

    fun nonceBody(address: String): String =
        """{"jsonrpc":"2.0","id":1,"method":"eth_getTransactionCount","params":["${evmAddress(address)}","latest"]}"""

    fun parseNonce(payload: String): Long {
        val root = Json.parseToJsonElement(payload).jsonObject
        return evmQuantity(requireNotNull(root.text("result")) { "Transaction count not available" }).toString().toLong()
    }

    /** The address's sent-transaction count at [blockNumber] (archive state). */
    fun nonceAtBody(
        address: String,
        blockNumber: Long,
    ): String {
        require(blockNumber >= 0)
        return """{"jsonrpc":"2.0","id":1,"method":"eth_getTransactionCount","params":["${evmAddress(
            address,
        )}","0x${blockNumber.toString(16)}"]}"""
    }

    fun blockNumberBody(): String = """{"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}"""

    /** A single numeric JSON-RPC result, such as a block number or a count. */
    fun parseQuantity(payload: String): Long {
        val root = Json.parseToJsonElement(payload).jsonObject
        return evmQuantity(requireNotNull(root.text("result")) { "Result not available" }).toString().toLong()
    }

    /** A JSON-RPC batch of transaction lookups, for their nonces. */
    fun transactionsBody(hashes: List<String>): String {
        require(hashes.size in 1..RECEIPT_BATCH)
        return buildJsonArray {
            hashes.forEachIndexed { index, hash ->
                add(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", index)
                        put("method", "eth_getTransactionByHash")
                        put("params", buildJsonArray { add(JsonPrimitive(hash(hash))) })
                    },
                )
            }
        }.toString()
    }

    /** Nonces by transaction hash. */
    fun parseNonces(payload: String): Map<String, Long> =
        (Json.parseToJsonElement(payload) as JsonArray).associate { element ->
            val response = element.jsonObject
            val transaction = requireNotNull(response["result"] as? JsonObject) { "Transaction not available" }
            hash(transaction.text("hash")) to evmQuantity(transaction.text("nonce")!!).toString().toLong()
        }

    fun blockBody(blockNumber: Long): String {
        require(blockNumber >= 0)
        return """{"jsonrpc":"2.0","id":1,"method":"eth_getBlockByNumber","params":["0x${blockNumber.toString(16)}",true]}"""
    }

    /**
     * The transaction [address] sent with [nonce] in a full block, without its fee and status (those come from the
     * receipt), or null when the block has none.
     */
    fun parseBlockTransaction(
        payload: String,
        address: String,
        nonce: Long,
    ): EvmTransaction? {
        val block = requireNotNull(Json.parseToJsonElement(payload).jsonObject["result"] as? JsonObject) { "Block not available" }
        val time = evmQuantity(block.text("timestamp")!!).toString().toLong() * 1000
        val number = evmQuantity(block.text("number")!!).toString().toLong()
        return block["transactions"]!!
            .jsonArray
            .map { it.jsonObject }
            .firstOrNull { it.text("from")?.lowercase() == address && evmQuantity(it.text("nonce")!!).toString().toLong() == nonce }
            ?.let { tx ->
                EvmTransaction(
                    hash(tx.text("hash")),
                    number,
                    evmQuantity(tx.text("transactionIndex")!!).toString().toInt(),
                    time,
                    address,
                    tx.text("to")?.let(::evmAddress),
                    evmQuantity(tx.text("value") ?: "0x0"),
                    ExactInteger.ZERO,
                    success = true,
                )
            }
    }

    const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

    /** Token names are attacker-controlled: invisible and control characters are dropped. */
    private fun cleanLabel(
        value: String?,
        fallback: String,
        max: Int,
    ): String =
        value
            ?.filter {
                !it.isISOControl() &&
                    it.category != CharCategory.FORMAT &&
                    it.category != CharCategory.NON_SPACING_MARK &&
                    it.code != 0x17B4 &&
                    it.code != 0x17B5
            }?.trim()
            ?.take(max)
            ?.ifBlank { null } ?: fallback

    private fun partialErrors(root: JsonObject): List<AlchemyPartialError> =
        ((root["error"] as? JsonObject)?.get("partialErrors") as? JsonArray).orEmpty().map {
            val item = it.jsonObject
            AlchemyPartialError(item.text("network") ?: "?", item.text("state") ?: "error", item.text("message").orEmpty().take(200))
        }

    private fun hash(value: String?): String {
        require(value != null && value.matches(Regex("0x[0-9a-fA-F]{64}"))) { "Invalid transaction hash" }
        return value.lowercase()
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
