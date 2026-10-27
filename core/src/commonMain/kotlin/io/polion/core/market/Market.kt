package io.polion.core.market

import io.polion.core.model.AssetId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.ExactDecimal
import io.polion.core.model.NetworkId
import io.polion.core.model.Price
import io.polion.core.model.ProviderId

/** Identity components must already use the canonical representation defined by their source. */
enum class AssetKind { NATIVE, TOKEN, NFT, CUSTOM, FIAT }

data class AssetIdentity(
    val kind: AssetKind,
    val network: NetworkId?,
    val reference: String,
    val tokenId: String? = null,
) {
    init {
        require(reference.isNotBlank() && reference == reference.trim())
        require(tokenId == null || tokenId.isNotBlank() && tokenId == tokenId.trim())
        require(
            when (kind) {
                AssetKind.NATIVE, AssetKind.TOKEN -> network != null && tokenId == null
                AssetKind.NFT -> network != null && tokenId != null
                AssetKind.CUSTOM, AssetKind.FIAT -> network == null && tokenId == null
            },
        ) { "Invalid asset identity components" }
    }
}

data class AssetDefinition(
    val id: AssetId,
    val identity: AssetIdentity,
)

data class AssetMetadata(
    val symbol: String,
    val name: String,
    val decimals: Int,
    val effectiveAtMillis: Long,
) {
    init {
        require(symbol.isNotBlank() && name.isNotBlank())
        require(decimals in 0..1000)
    }
}

/** validAt is market time; observedAt is when this record became available to the user. */
data class PriceObservation(
    val id: String,
    val price: Price,
    val providerId: ProviderId?,
    val validAtMillis: Long,
    val observedAtMillis: Long,
    val manual: Boolean = false,
) {
    init {
        require(id.isNotBlank())
        require(manual == (providerId == null)) { "Manual observations must not claim a provider" }
    }
}

data class PricePolicy(
    val providerPriority: List<ProviderId>,
    val maxAgeMillis: Long,
    val preferFresh: Boolean = true,
) {
    init {
        require(providerPriority.distinct().size == providerPriority.size)
        require(maxAgeMillis >= 0)
    }
}

enum class PriceStatus { FRESH, STALE, MISSING }

data class PriceResult(
    val status: PriceStatus,
    val observation: PriceObservation?,
) {
    init {
        require((status == PriceStatus.MISSING) == (observation == null))
    }

    /** Unknown price returns null; a stale quote remains explicitly marked by status. */
    fun value(
        amount: AtomicAmount,
        decimals: Int,
    ): ExactDecimal? = observation?.price?.valueOf(amount, decimals)
}
