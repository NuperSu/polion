package io.polion.core.bitcoin

/** One Esplora-compatible endpoint; [id] is the provenance label recorded for responses it serves. */
data class EsploraEndpoint(
    val id: String,
    val transport: EsploraTransport,
)

data class EsploraServedResponse(
    val endpointId: String,
    val body: String,
)

/**
 * Tries endpoints in order and fails over only on availability failures. Content failures (history changed,
 * reorganized) and malformed responses propagate unchanged, because another provider would mask disagreement.
 *
 * The endpoint that last answered stays preferred, so one scan reads a single provider's view while it stays
 * available; on failure the remaining endpoints are tried in list order, wrapping around. Call [reset] before a
 * new scan to prefer the first endpoint again.
 */
class FailoverEsploraTransport(
    private val endpoints: List<EsploraEndpoint>,
    private val onFailover: (failedEndpointId: String, reason: BitcoinProviderFailure) -> Unit = { _, _ -> },
) : EsploraTransport {
    init {
        require(endpoints.isNotEmpty() && endpoints.map { it.id }.distinct().size == endpoints.size) { "Endpoint ids must be unique" }
    }

    private var preferred = 0

    /** The endpoint that served the most recent successful response, or null before any response. */
    var lastServedBy: String? = null
        private set

    private val served = linkedSetOf<String>()

    /** Endpoints that have served at least one response since the last [reset], in first-use order. */
    val servedBy: Set<String> get() = served.toSet()

    fun reset() {
        preferred = 0
        lastServedBy = null
        served.clear()
    }

    override fun get(path: String): String = fetch(path).body

    fun fetch(path: String): EsploraServedResponse {
        var last: BitcoinProviderException? = null
        for (offset in endpoints.indices) {
            val index = (preferred + offset) % endpoints.size
            val endpoint = endpoints[index]
            try {
                val body = endpoint.transport.get(path)
                preferred = index
                lastServedBy = endpoint.id
                served.add(endpoint.id)
                return EsploraServedResponse(endpoint.id, body)
            } catch (error: BitcoinProviderException) {
                if (error.reason !in FAILOVER_REASONS) throw error
                onFailover(endpoint.id, error.reason)
                last = error
            }
        }
        throw last!!
    }

    companion object {
        val FAILOVER_REASONS =
            setOf(
                BitcoinProviderFailure.RATE_LIMITED,
                BitcoinProviderFailure.TIMEOUT,
                BitcoinProviderFailure.OFFLINE,
                BitcoinProviderFailure.UNAVAILABLE,
            )
    }
}
