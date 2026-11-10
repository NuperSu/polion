package io.polion.core.evm

import io.polion.core.model.ExactInteger

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
    /** Where the transaction was found when not in Alchemy's transfer list, such as `nonce-search`; saved with the record. */
    val source: String? = null,
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
    /** Where the transfer was read when not from Alchemy, such as `1inch-trace`; saved with the record. */
    val source: String? = null,
)

/** NATIVE marks a chain's own coin in balance lists. */
enum class EvmTokenStandard { NATIVE, ERC20, ERC721, ERC1155 }

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
    /** The provider flagged the token or a party as a scam. */
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

/** A token balance as the provider reports it, for the balance check. */
data class EvmTokenBalance(
    val contract: String,
    val standard: EvmTokenStandard,
    val tokenId: ExactInteger?,
    val value: ExactInteger,
)
