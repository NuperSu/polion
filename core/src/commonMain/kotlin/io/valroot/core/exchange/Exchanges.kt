package io.valroot.core.exchange

import io.valroot.core.model.NetworkId

/** Exchanges Valroot reads through a read-only API key. Each tracks accounts by user ID on its own network. */
enum class ExchangeKind(
    val id: String,
    val label: String,
) {
    BYBIT("bybit", "Bybit"),
    BINANCE("binance", "Binance"),
    MEXC("mexc", "MEXC"),
    ;

    val network: NetworkId get() = NetworkId(id)

    /** Exchanges whose trades are read per trading pair, which the account screen lists. */
    val readsPerSymbol: Boolean get() = this == BINANCE || this == MEXC

    /** Whether [identity] is how this exchange names an account: a numeric user ID, or MEXC's referral code. */
    fun validIdentity(identity: String): Boolean =
        if (this ==
            MEXC
        ) {
            identity.length in 1..64 && identity.all { it.isLetterOrDigit() && it.code < 128 }
        } else {
            identity.all(Char::isDigit)
        }

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
