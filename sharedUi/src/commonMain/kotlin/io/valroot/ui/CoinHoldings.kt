package io.valroot.ui

import io.valroot.core.application.AssetValuation
import io.valroot.core.evm.EvmAssets
import io.valroot.core.market.EvmPlatform
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal

/** One held asset of a coin, as the coin page's per-chain breakdown shows it. */
internal data class CoinChainLine(
    val chain: String,
    val quantity: String,
    val value: String,
)

/**
 * The held assets of one coin summed across chains. [quantity] and [value] are null when any part cannot be summed (no
 * decimals or no price), so the page shows a dash rather than a partial total.
 */
internal data class CoinTotals(
    val quantity: ExactDecimal?,
    val value: ExactDecimal?,
    val chains: List<CoinChainLine>,
)

internal fun isEvmNft(asset: AssetId): Boolean = asset.value.contains("/nft:")

/** An EVM token contract (not the native coin and not an NFT). */
internal fun isEvmToken(asset: AssetId): Boolean = EvmAssets.chainOf(asset) != null && !isEvmNft(asset) && !asset.value.endsWith("/native")

/** The chain name for an EVM asset, or "Other" for an asset that is not on an EVM chain. */
internal fun assetChainName(
    asset: AssetId,
    choices: List<EvmPlatform>,
): String = EvmAssets.chainOf(asset)?.let { evmChainName(choices, it) } ?: "Other"

/** Sums the held assets of one coin. [holdings] are the held assets that are the same coin, in the snapshot currency. */
internal fun coinTotals(
    holdings: List<AssetValuation>,
    currency: AssetId,
    choices: List<EvmPlatform>,
): CoinTotals {
    val quantities = holdings.map { holding -> holding.metadata?.let { ExactDecimal(holding.amount.atomicUnits, it.decimals) } }
    val values = holdings.map { it.value }
    return CoinTotals(
        quantity = sumOrNull(quantities),
        value = sumOrNull(values),
        chains =
            holdings.map { holding ->
                CoinChainLine(
                    chain = assetChainName(holding.asset, choices),
                    quantity = holdingQuantity(holding),
                    value = holding.value?.let { money(it, currency) } ?: "—",
                )
            },
    )
}

/** A held asset's quantity with its symbol, or the raw atomic units when its metadata is missing. */
internal fun holdingQuantity(holding: AssetValuation): String =
    holding.metadata?.let { "${formatUnits(holding.amount.atomicUnits, it.decimals)} ${it.symbol}" }
        ?: "${holding.amount.atomicUnits} atomic units"

private fun sumOrNull(values: List<ExactDecimal?>): ExactDecimal? =
    if (values.isEmpty() || values.any { it == null }) null else values.filterNotNull().reduce { total, next -> total + next }
