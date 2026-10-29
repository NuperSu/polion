package io.polion.core.bitcoin

data class EsploraRetryProgress(
    val retry: Int,
    val maxRetries: Int,
    val delayMillis: Long,
) {
    val userMessage: String
        get() = "Provider rate limit · waiting ${(delayMillis + 999) / 1000}s before retry $retry/$maxRetries"
}

/** Bounds apply to a serial worker's transport instance, including all requests in that scan. */
data class EsploraPacingPolicy(
    val requestIntervalMillis: Long = 1_000,
    val initialRetryMillis: Long = 2_000,
    val maxRetriesPerRequest: Int = 2,
    val maxRetriesPerScan: Int = 6,
    val maxRetryDelayMillis: Long = 30_000,
    val maxRetryWaitPerScanMillis: Long = 30_000,
) {
    init {
        require(requestIntervalMillis in 0..30_000)
        require(initialRetryMillis in 1..30_000)
        require(maxRetriesPerRequest in 0..5 && maxRetriesPerScan in 0..20)
        require(maxRetryDelayMillis in 1..300_000 && maxRetryWaitPerScanMillis in 0..300_000)
    }
}

/**
 * Serial, synchronous transport for worker threads. Hosts supply monotonic milliseconds and a blocking
 * sleeper. Cancellation/consent checks run before every attempt and at most 100 ms apart while waiting.
 * Only explicit rate-limit failures are retried; malformed data and all other failures propagate.
 */
class PacedEsploraTransport(
    private val delegate: EsploraTransport,
    private val nowMillis: () -> Long,
    private val sleepMillis: (Long) -> Unit,
    private val ensureActive: () -> Unit,
    private val onRetry: (EsploraRetryProgress) -> Unit = {},
    private val policy: EsploraPacingPolicy = EsploraPacingPolicy(),
) : EsploraTransport {
    private var lastAttemptAt: Long? = null
    private var scanRetries = 0
    private var scanRetryWaitMillis = 0L
    private var exhaustedRateLimit: BitcoinProviderException? = null

    override fun get(path: String): String {
        ensureActive()
        exhaustedRateLimit?.let { throw it }
        var retries = 0
        while (true) {
            ensureActive()
            lastAttemptAt?.let { previous ->
                await((policy.requestIntervalMillis - (nowMillis() - previous)).coerceAtLeast(0))
            }
            ensureActive()
            lastAttemptAt = nowMillis()
            try {
                return delegate.get(path)
            } catch (failure: BitcoinProviderException) {
                if (failure.reason != BitcoinProviderFailure.RATE_LIMITED) throw failure
                if (retries >= policy.maxRetriesPerRequest || scanRetries >= policy.maxRetriesPerScan) {
                    stopForRateLimit(failure)
                }
                val backoff = policy.initialRetryMillis * (1L shl retries)
                val delay = maxOf(backoff, failure.retryAfterMillis?.coerceAtLeast(0) ?: 0)
                // Never retry earlier than a provider's requested cooldown to satisfy a local wait cap.
                if (delay > policy.maxRetryDelayMillis || delay > policy.maxRetryWaitPerScanMillis - scanRetryWaitMillis) {
                    stopForRateLimit(failure)
                }
                retries++
                scanRetries++
                scanRetryWaitMillis += delay
                onRetry(EsploraRetryProgress(retries, policy.maxRetriesPerRequest, delay))
                await(delay)
            }
        }
    }

    private fun stopForRateLimit(failure: BitcoinProviderException): Nothing {
        exhaustedRateLimit = failure
        throw failure
    }

    private fun await(delayMillis: Long) {
        val started = nowMillis()
        var remaining = delayMillis
        while (remaining > 0) {
            ensureActive()
            sleepMillis(minOf(remaining, 100))
            remaining = (delayMillis - (nowMillis() - started)).coerceAtLeast(0)
        }
        ensureActive()
    }
}
