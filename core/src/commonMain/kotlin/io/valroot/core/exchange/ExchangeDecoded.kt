package io.valroot.core.exchange

import io.valroot.core.ledger.Activity
import io.valroot.core.model.ActivityId

/** An on-chain transfer behind an exchange activity: the coin's chain as the exchange names it, and the transaction ID. */
data class ExchangeTransfer(
    val chain: String,
    val txId: String,
)

data class ExchangeDecoded(
    val activities: List<Activity>,
    /** Activities that move coins on chain, by activity. */
    val transfers: Map<ActivityId, ExchangeTransfer>,
    /** Symbols without a coin; records that move them wait until the user chooses one. */
    val unresolved: Set<String>,
    /** Records that could not be represented, such as amounts finer than the asset's decimals. */
    val failures: List<String>,
)
