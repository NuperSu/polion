package io.polion.core.evm

import io.polion.core.model.ExactInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** A transaction sent from or to the tracked address. [fee] is the total paid, including any L1 data fee. */
data class EvmTransaction(
    val hash: String,
    val blockNumber: Long?,
    val position: Int?,
    val timestampMillis: Long,
    val from: String,
    val to: String?,
    val value: ExactInteger,
    val fee: ExactInteger,
    val success: Boolean,
)

/** Native coin moved by a contract during a transaction. */
data class EvmInternalTransfer(
    val transactionHash: String,
    val blockNumber: Long,
    val index: Int,
    val timestampMillis: Long,
    val from: String,
    val to: String?,
    val value: ExactInteger,
    val success: Boolean,
)

enum class EvmTokenStandard { ERC20, ERC721, ERC1155 }

/** One token movement from a transaction log. [amount] is in the token's smallest unit; an ERC-721 moves 1. */
data class EvmTokenTransfer(
    val transactionHash: String,
    val blockNumber: Long,
    val logIndex: Int,
    val timestampMillis: Long,
    val from: String,
    val to: String,
    val contract: String,
    val standard: EvmTokenStandard,
    val tokenId: ExactInteger?,
    val amount: ExactInteger,
    val decimals: Int?,
    val symbol: String,
    val name: String,
    /** Blockscout flagged the token or a party as a scam. */
    val flagged: Boolean,
)

/** A staking (beacon chain) withdrawal credited to the address without a transaction; [amount] is in wei. */
data class EvmWithdrawal(
    val index: Long,
    val blockNumber: Long,
    val timestampMillis: Long,
    val receiver: String,
    val amount: ExactInteger,
)

/** A token balance as Blockscout reports it, for the balance check. */
data class EvmTokenBalance(
    val contract: String,
    val standard: EvmTokenStandard,
    val tokenId: ExactInteger?,
    val value: ExactInteger,
)

data class EvmPage<T>(
    val items: List<T>,
    /** Query parameters for the next page, or null on the last page. */
    val next: Map<String, String>?,
)

/** Blockscout REST v2 requests and strict parsing. Addresses come back in lower case. */
object Blockscout {
    const val BASE_URL = "https://api.blockscout.com"
    const val MAX_PAGES = 400

    fun url(
        chainId: Long,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        require(chainId > 0)
        require(
            path.matches(Regex("addresses/0x[0-9a-f]{40}(/(transactions|internal-transactions|token-transfers|tokens|withdrawals))?")),
        ) {
            "Unsupported Blockscout path"
        }
        val query = params.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return "$BASE_URL/$chainId/api/v2/$path" + if (query.isEmpty()) "" else "?$query"
    }

    private fun encode(value: String): String {
        require(value.length <= 200)
        return value
            .map { c ->
                if (c.isLetterOrDigit() ||
                    c in "-_.:"
                ) {
                    c.toString()
                } else {
                    "%" +
                        c.code
                            .toString(16)
                            .padStart(2, '0')
                            .uppercase()
                }
            }.joinToString("")
    }

    /** The native balance in the chain's smallest unit. */
    fun parseBalance(payload: String): ExactInteger = evmQuantity(Json.parseToJsonElement(payload).jsonObject.text("coin_balance") ?: "0")

    fun parseTransactions(payload: String): EvmPage<EvmTransaction> =
        page(payload) { item ->
            val status = item.text("status")
            EvmTransaction(
                hash = hash(item.text("hash")),
                blockNumber = item.long("block_number"),
                position = item.long("position")?.toInt(),
                timestampMillis = millis(item.text("timestamp")),
                from = address(item["from"]),
                to = (item["to"] as? JsonObject)?.let { address(it) },
                value = evmQuantity(item.text("value")!!),
                fee = (item["fee"] as? JsonObject)?.text("value")?.let(::evmQuantity) ?: ExactInteger.ZERO,
                success = status == "ok" || status == null && item.text("result") == "success",
            ).also { require(status in setOf(null, "ok", "error")) { "Unknown transaction status" } }
        }

    fun parseInternalTransfers(payload: String): EvmPage<EvmInternalTransfer> =
        page(payload) { item ->
            EvmInternalTransfer(
                transactionHash = hash(item.text("transaction_hash")),
                blockNumber = item.long("block_number")!!,
                index = item.long("index")!!.toInt(),
                timestampMillis = millis(item.text("timestamp")),
                from = address(item["from"]),
                to = (item["to"] as? JsonObject)?.let { address(it) },
                value = evmQuantity(item.text("value")!!),
                success = (item["success"] as? JsonPrimitive)?.content == "true",
            )
        }

    fun parseTokenTransfers(payload: String): EvmPage<EvmTokenTransfer> =
        page(payload) { item ->
            val token = item["token"]!!.jsonObject
            val standard = standard(item.text("token_type") ?: token.text("type"))
            val total = item["total"]!!.jsonObject
            val tokenId = total.text("token_id")?.let(::evmQuantity)
            val decimals = (total.text("decimals") ?: token.text("decimals"))?.toIntOrNull()?.takeIf { it in 0..255 }
            EvmTokenTransfer(
                transactionHash = hash(item.text("transaction_hash")),
                blockNumber = item.long("block_number")!!,
                logIndex = item.long("log_index")!!.toInt(),
                timestampMillis = millis(item.text("timestamp")),
                from = address(item["from"]),
                to = address(item["to"]),
                contract = evmAddress(token.text("address_hash") ?: token.text("address")!!),
                standard = standard,
                tokenId = if (standard == EvmTokenStandard.ERC20) null else requireNotNull(tokenId) { "NFT transfer without token ID" },
                amount = if (standard == EvmTokenStandard.ERC721) ExactInteger.parse("1") else evmQuantity(total.text("value")!!),
                decimals = if (standard == EvmTokenStandard.ERC20) requireNotNull(decimals) { "Token without decimals" } else 0,
                symbol = (token.text("symbol") ?: "").take(20).ifBlank { "?" },
                name = (token.text("name") ?: "").take(100).ifBlank { "Unknown token" },
                flagged =
                    listOf(item["from"], item["to"]).any { (it as? JsonObject)?.flag() == true } ||
                        token.text("reputation").let { it != null && it != "ok" },
            )
        }

    /** Chains with beacon chain withdrawals: Ethereum and Gnosis. */
    val WITHDRAWAL_CHAINS = setOf(1L, 100L)

    fun parseWithdrawals(payload: String): EvmPage<EvmWithdrawal> =
        page(payload) { item ->
            EvmWithdrawal(
                index = item.long("index")!!,
                blockNumber = item.long("block_number")!!,
                timestampMillis = millis(item.text("timestamp")),
                receiver = address(item["receiver"]),
                amount = evmQuantity(item.text("amount")!!),
            )
        }

    fun parseTokenBalances(payload: String): EvmPage<EvmTokenBalance> =
        page(payload) { item ->
            val token = item["token"]!!.jsonObject
            EvmTokenBalance(
                evmAddress(token.text("address_hash") ?: token.text("address")!!),
                standard(token.text("type")),
                item.text("token_id")?.let(::evmQuantity),
                evmQuantity(item.text("value")!!),
            )
        }

    private fun <T> page(
        payload: String,
        read: (JsonObject) -> T,
    ): EvmPage<T> {
        val root = Json.parseToJsonElement(payload).jsonObject
        val items = root["items"]!!.jsonArray.map { read(it.jsonObject) }
        val next =
            (root["next_page_params"] as? JsonObject)?.mapValues { (_, value) ->
                require(value is JsonPrimitive) { "Unsupported page parameter" }
                value.content
            }
        return EvmPage(items, next)
    }

    private fun standard(type: String?): EvmTokenStandard =
        when (type) {
            "ERC-20" -> EvmTokenStandard.ERC20
            "ERC-721" -> EvmTokenStandard.ERC721
            "ERC-1155" -> EvmTokenStandard.ERC1155
            else -> throw IllegalArgumentException("Unsupported token type $type")
        }

    private fun hash(value: String?): String {
        require(value != null && value.matches(Regex("0x[0-9a-fA-F]{64}"))) { "Invalid transaction hash" }
        return value.lowercase()
    }

    private fun address(element: JsonElement?): String =
        evmAddress(
            requireNotNull((element as? JsonObject)?.text("hash")) {
                "Missing address"
            },
        )

    private fun millis(value: String?): Long =
        kotlin.time.Instant
            .parse(requireNotNull(value) { "Missing timestamp" })
            .toEpochMilliseconds()

    private fun JsonObject.flag(): Boolean =
        (this["is_scam"] as? JsonPrimitive)?.content == "true" || text("reputation").let { it != null && it != "ok" }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.long(key: String): Long? = text(key)?.toLongOrNull()
}
