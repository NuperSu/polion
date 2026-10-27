package io.polion.core.connectors

import io.polion.core.ledger.Activity
import io.polion.core.model.AccountId
import io.polion.core.model.AtomicAmount
import io.polion.core.model.NetworkId
import io.polion.core.model.ProviderId
import io.polion.core.model.SourceId
import io.polion.core.storage.RawObservation

enum class Capability { DISCOVERY, BALANCE, HISTORY, DECODING, IMPORT, HEALTH }

enum class Coverage { COMPLETE, PARTIAL, UNKNOWN }

data class IntegrationManifest(
    val id: String,
    val networks: Set<NetworkId>,
    val accountTypes: Set<String>,
    val capabilities: Set<Capability>,
    val requiresAuthentication: Boolean,
    val coverage: Coverage,
) {
    init {
        require(id.isNotBlank())
        require(accountTypes.none { it.isBlank() })
    }
}

data class HistoryRequest(
    val source: SourceId,
    val provider: ProviderId,
    val network: NetworkId,
    val account: AccountId,
    val cursor: String? = null,
    val overlap: String? = null,
    val finality: String? = null,
)

data class HistoryPage(
    val observations: List<RawObservation>,
    val nextCursor: String?,
    val coverage: Coverage,
)

data class BalanceObservation(
    val amount: AtomicAmount,
    val coverage: Coverage,
)

data class DiscoveredAccount(
    val account: AccountId,
    val network: NetworkId,
    val accountType: String,
)

data class ProviderHealth(
    val available: Boolean,
    val detail: String? = null,
)

interface HistoryFetcher {
    fun fetch(request: HistoryRequest): HistoryPage
}

interface AccountDiscoverer {
    fun discover(network: NetworkId): List<DiscoveredAccount>
}

interface BalanceObserver {
    fun balance(account: AccountId): List<BalanceObservation>
}

interface RawRecordDecoder {
    val version: String

    fun decode(observation: RawObservation): List<Activity>
}

interface Importer {
    fun import(content: String): List<RawObservation>
}

interface ProviderHealthChecker {
    fun health(provider: ProviderId): ProviderHealth
}

/** Registration is explicit and duplicate integration identities are rejected. */
class ConnectorRegistry(
    entries: List<IntegrationManifest>,
) {
    private val entries = entries.associateBy { it.id }

    init {
        require(this.entries.size == entries.size) { "Duplicate integration ID" }
    }

    fun find(id: String): IntegrationManifest? = entries[id]

    fun supporting(
        network: NetworkId,
        capability: Capability,
    ): List<IntegrationManifest> = entries.values.filter { network in it.networks && capability in it.capabilities }.sortedBy { it.id }
}
