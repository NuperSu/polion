package io.polion.core.evm

import io.polion.core.ledger.Activity
import io.polion.core.ledger.ActivityKind
import io.polion.core.ledger.ActivityStatus
import io.polion.core.ledger.Movement
import io.polion.core.ledger.MovementRole
import io.polion.core.market.AssetDefinition
import io.polion.core.market.AssetIdentity
import io.polion.core.market.AssetKind
import io.polion.core.market.AssetMetadata
import io.polion.core.model.AccountId
import io.polion.core.model.ActivityId
import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.EventId
import io.polion.core.model.ExactInteger
import io.polion.core.model.SourceRecordId

/** Everything one transaction did to the tracked address on one chain, gathered from the provider. */
data class EvmTransactionGroup(
    val chainId: Long,
    val hash: String,
    val transaction: EvmTransaction?,
    val internal: List<EvmInternalTransfer>,
    val tokens: List<EvmTokenTransfer>,
) {
    val blockNumber: Long? get() = transaction?.blockNumber ?: tokens.firstOrNull()?.blockNumber ?: internal.firstOrNull()?.blockNumber
    val timestampMillis: Long get() =
        transaction?.timestampMillis ?: tokens.firstOrNull()?.timestampMillis
            ?: internal.first().timestampMillis

    /** A stable text form of the group, saved as the raw provider record. */
    fun canonical(): String =
        buildString {
            append("evm-1|$chainId|$hash\n")
            transaction?.let {
                append(
                    "tx|${it.blockNumber}|${it.position}|${it.timestampMillis}|${it.from}|${it.to}|${it.value}|${it.fee}|${it.success}\n",
                )
            }
            internal.sortedBy { it.index }.forEach { append("in|${it.index}|${it.from}|${it.to}|${it.value}|${it.success}\n") }
            tokens.sortedBy { it.logIndex }.forEach {
                append("tok|${it.logIndex}|${it.from}|${it.to}|${it.contract}|${it.standard}|${it.tokenId}|${it.amount}|${it.decimals}\n")
            }
        }
}

/** An asset an activity uses, with what the provider reported about it. */
data class EvmAssetInfo(
    val definition: AssetDefinition,
    val metadata: AssetMetadata,
)

/**
 * Builds one ledger activity per transaction from the tracked address's point of view, with net movements per asset and
 * the gas fee as its own movement. The fee counts whenever the address sent the transaction, including when it failed, in
 * which case no value moved. Activities record what happened; no accounting treatment is assumed.
 */
object EvmDecoder {
    const val VERSION = "evm-1"

    fun group(
        chainId: Long,
        transactions: List<EvmTransaction>,
        internal: List<EvmInternalTransfer>,
        tokens: List<EvmTokenTransfer>,
    ): List<EvmTransactionGroup> {
        val hashes = (transactions.map { it.hash } + internal.map { it.transactionHash } + tokens.map { it.transactionHash }).distinct()
        val byTx = transactions.associateBy { it.hash }
        val byInternal = internal.groupBy { it.transactionHash }
        val byToken = tokens.groupBy { it.transactionHash }
        return hashes.map { EvmTransactionGroup(chainId, it, byTx[it], byInternal[it].orEmpty(), byToken[it].orEmpty()) }
    }

    fun assets(group: EvmTransactionGroup): List<EvmAssetInfo> {
        val network = evmNetwork(group.chainId)
        val native =
            EvmAssetInfo(
                AssetDefinition(EvmAssets.native(group.chainId), AssetIdentity(AssetKind.NATIVE, network, "native")),
                AssetMetadata("NATIVE", "Native coin", 18, 0),
            )
        return listOf(native) +
            group.tokens.map { token ->
                when (token.standard) {
                    EvmTokenStandard.ERC20 ->
                        EvmAssetInfo(
                            AssetDefinition(
                                EvmAssets.token(group.chainId, token.contract),
                                AssetIdentity(AssetKind.TOKEN, network, token.contract),
                            ),
                            AssetMetadata(token.symbol, token.name, token.decimals!!, 0),
                        )
                    else ->
                        EvmAssetInfo(
                            AssetDefinition(
                                EvmAssets.nft(group.chainId, token.contract, token.tokenId!!),
                                AssetIdentity(AssetKind.NFT, network, token.contract, token.tokenId.toString()),
                            ),
                            AssetMetadata(token.symbol, token.name, 0, 0),
                        )
                }
            }
    }

    /** A staking withdrawal to the address: an incoming native transfer with no fee and no transaction. */
    fun decodeWithdrawal(
        chainId: Long,
        withdrawal: EvmWithdrawal,
        address: String,
        account: AccountId,
        source: SourceRecordId,
    ): Activity? {
        if (withdrawal.receiver != address || withdrawal.amount.sign <= 0) return null
        val id = "eip155:$chainId:withdrawal-${withdrawal.index}:$address"
        return Activity(
            ActivityId(id),
            withdrawal.timestampMillis,
            "evm-chain:$chainId:${withdrawal.blockNumber.toString().padStart(20, '0')}:9999999999:withdrawal-${withdrawal.index}",
            ActivityKind.DEPOSIT,
            ActivityStatus.CONFIRMED,
            listOf(
                Movement(EventId("$id:native"), account, AtomicAmount(EvmAssets.native(chainId), withdrawal.amount), MovementRole.TRANSFER),
            ),
            setOf(source),
            VERSION,
        )
    }

    /** Null when the transaction did not change any balance of [address] (for example a self-transfer with no fee). */
    fun decode(
        group: EvmTransactionGroup,
        address: String,
        account: AccountId,
        source: SourceRecordId,
    ): Activity? {
        val tx = group.transaction
        val deltas = linkedMapOf<AssetId, ExactInteger>()

        fun add(
            asset: AssetId,
            amount: ExactInteger,
        ) {
            deltas[asset] = (deltas[asset] ?: ExactInteger.ZERO) + amount
        }
        val native = EvmAssets.native(group.chainId)
        if (tx != null && tx.success) {
            if (tx.from == address) add(native, -tx.value)
            if (tx.to == address) add(native, tx.value)
        }
        group.internal.filter { it.success }.forEach {
            if (it.from == address) add(native, -it.value)
            if (it.to == address) add(native, it.value)
        }
        if (tx?.success != false) {
            group.tokens.forEach { token ->
                val asset =
                    if (token.standard == EvmTokenStandard.ERC20) {
                        EvmAssets.token(group.chainId, token.contract)
                    } else {
                        EvmAssets.nft(group.chainId, token.contract, token.tokenId!!)
                    }
                if (token.from == address) add(asset, -token.amount)
                if (token.to == address) add(asset, token.amount)
            }
        }
        // One activity per transaction and tracked address: a transfer between two tracked addresses is two activities.
        val id = "eip155:${group.chainId}:${group.hash}:$address"
        val movements =
            deltas.filterValues { it.sign != 0 }.map { (asset, amount) ->
                Movement(EventId("$id:${asset.value}"), account, AtomicAmount(asset, amount), MovementRole.TRANSFER)
            } +
                listOfNotNull(
                    tx?.takeIf { it.from == address && it.fee.sign > 0 }?.let {
                        Movement(EventId("$id:fee"), account, AtomicAmount(native, -it.fee), MovementRole.FEE)
                    },
                )
        if (movements.isEmpty()) return null
        val values = movements.filter { it.role != MovementRole.FEE }
        val kind =
            when {
                values.isEmpty() -> ActivityKind.PAYMENT
                values.any { it.amount.atomicUnits.sign > 0 } && values.any { it.amount.atomicUnits.sign < 0 } -> ActivityKind.TRADE
                values.all { it.amount.atomicUnits.sign > 0 } -> ActivityKind.DEPOSIT
                else -> ActivityKind.PAYMENT
            }
        val block = group.blockNumber
        val position = tx?.position ?: group.tokens.minOfOrNull { it.logIndex } ?: group.internal.minOfOrNull { it.index } ?: 0
        return Activity(
            ActivityId(id),
            group.timestampMillis,
            if (block ==
                null
            ) {
                "evm-pending:${group.hash}"
            } else {
                "evm-chain:${group.chainId}:${block.toString().padStart(
                    20,
                    '0',
                )}:${position.toString().padStart(10, '0')}:${group.hash}"
            },
            kind,
            if (block == null) ActivityStatus.PENDING else ActivityStatus.CONFIRMED,
            movements,
            setOf(source),
            VERSION,
        )
    }
}
