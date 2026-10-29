package io.polion.core.market

enum class CoingeckoProviderFailure(
    val message: String,
) {
    CANCELLED("Price request was cancelled because local data or provider consent changed. Retry if needed."),
    ACCESS_DENIED("Price provider access was denied. Check your Demo API key; manual prices remain available."),
    RATE_LIMITED("Price requests are rate limited. Wait a minute and retry."),
    INVALID_RESPONSE("The price provider returned an invalid response. Saved prices are unchanged."),
    UNAVAILABLE("The price provider could not return this date. Saved prices are unchanged."),
    OFFLINE("Could not reach the price provider. Check your connection and retry."),
}

class CoingeckoProviderException(
    val failure: CoingeckoProviderFailure,
) : Exception(failure.message)

/** Rejects rapid requests without sleeping; a provider rejection extends the local cooldown. */
class PacedCoingeckoTransport(
    private val delegate: CoingeckoTransport,
    private val clock: () -> Long,
    private val intervalMillis: Long = 6_000L,
    private val cooldownMillis: Long = 60_000L,
) : CoingeckoTransport {
    private var nextRequestAt: Long? = null

    init {
        require(intervalMillis >= 0 && cooldownMillis >= intervalMillis)
    }

    override fun get(url: String): String {
        val at = clock()
        if (nextRequestAt?.let { at < it } == true) throw CoingeckoProviderException(CoingeckoProviderFailure.RATE_LIMITED)
        nextRequestAt = at + intervalMillis
        try {
            return delegate.get(url)
        } catch (failure: CoingeckoProviderException) {
            if (failure.failure == CoingeckoProviderFailure.RATE_LIMITED) nextRequestAt = clock() + cooldownMillis
            throw failure
        }
    }
}
