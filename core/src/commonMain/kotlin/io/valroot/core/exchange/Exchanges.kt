package io.valroot.core.exchange

import io.valroot.core.model.NetworkId

/** Exchanges Valroot reads through a read-only API key. Each tracks accounts by user ID on its own network. */
enum class ExchangeKind(
    val id: String,
    val label: String,
) {
    BYBIT("bybit", "Bybit"),
    BINANCE("binance", "Binance"),
    ;

    val network: NetworkId get() = NetworkId(id)

    companion object {
        fun ofNetwork(network: NetworkId): ExchangeKind? = entries.firstOrNull { it.network == network }
    }
}

/** What an exchange API key may do and whose account it reads. */
data class ExchangeKeyInfo(
    val uid: String,
    val readOnly: Boolean,
    /** When the key stops working; null when it does not expire. */
    val expiresAtMillis: Long?,
)
