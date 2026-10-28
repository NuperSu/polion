package io.polion.core.bitcoin

enum class BitcoinProviderFailure(
    val userMessage: String,
) {
    RATE_LIMITED("The provider is rate limiting requests. Wait a few minutes, then retry."),
    TIMEOUT("The provider timed out. Saved data is unchanged; retry when the connection improves."),
    OFFLINE("The provider could not be reached. Check your connection and endpoint."),
    UNAVAILABLE("The provider rejected the request. Check the endpoint or try again later."),
    HISTORY_CHANGED("Public history changed during the scan. Saved data is unchanged; retry."),
    REORGANIZED("The Bitcoin chain reorganized during the scan. Saved data is unchanged; retry."),
}

/** Contains a fixed public message, never an address, URL or transport exception text. */
class BitcoinProviderException(
    val reason: BitcoinProviderFailure,
) : IllegalStateException(reason.userMessage)
