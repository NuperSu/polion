package io.valroot.core.bitcoin

enum class BitcoinProviderFailure(
    val userMessage: String,
) {
    RATE_LIMITED("The provider is rate limiting requests. Wait a few minutes, then retry."),
    CANCELLED("The Bitcoin request was cancelled. Saved data is unchanged; retry if needed."),
    TIMEOUT("The provider timed out. Saved data is unchanged; retry when the connection improves."),
    OFFLINE("The provider could not be reached. Check your connection and endpoint."),
    UNAVAILABLE("The provider rejected the request. Check the endpoint or try again later."),
    TEMPORARILY_UNAVAILABLE("The provider is temporarily unavailable. Saved data is unchanged; retry in a few minutes."),
    RESPONSE_TOO_LARGE(
        "The provider sent more data than Valroot accepts in one response (16 MB), usually because of a very large transaction. Saved data is unchanged.",
    ),
    WRONG_NETWORK("This endpoint is not on Bitcoin mainnet. Choose a mainnet Esplora endpoint. Saved data is unchanged."),
    HISTORY_CHANGED("Public history changed during the scan. Saved data is unchanged; retry."),
    REORGANIZED("The Bitcoin chain reorganized during the scan. Saved data is unchanged; retry."),
    LAGGING_CHAIN("The provider is behind the saved Bitcoin checkpoint. Saved data is unchanged; retry with an up-to-date provider."),
    REORG_RECOVERY_REQUIRED(
        "The saved Bitcoin chain has no verified ancestor within the automatic rewind bound. Saved data is unchanged; a full history recovery is required.",
    ),
}

/** Contains a fixed public message, never an address, URL or transport exception text. */
class BitcoinProviderException(
    val reason: BitcoinProviderFailure,
    /** Validated provider delay; absent when the server supplied no usable Retry-After value. */
    val retryAfterMillis: Long? = null,
) : IllegalStateException(reason.userMessage)

/** Hash of Bitcoin mainnet's block 0. */
const val BITCOIN_GENESIS_HASH = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"

/** Throws [BitcoinProviderFailure.WRONG_NETWORK] unless the endpoint's block 0 is the mainnet genesis block. */
fun requireMainnetEndpoint(transport: EsploraTransport) {
    if (transport.get("/block-height/0").trim() != BITCOIN_GENESIS_HASH) {
        throw BitcoinProviderException(BitcoinProviderFailure.WRONG_NETWORK)
    }
}
