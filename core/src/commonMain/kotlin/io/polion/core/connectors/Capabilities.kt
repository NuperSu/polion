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

/**
 * Explicit registration of integrations. Each entry pairs manifest metadata with the connector that executes its
 * capabilities; duplicate integration identities are rejected.
 */
class ConnectorRegistry(
    entries: List<RegisteredConnector>,
) {
    private val byId = entries.associateBy { it.manifest.id }

    init {
        require(byId.size == entries.size) { "Duplicate integration ID" }
    }

    val manifests: List<IntegrationManifest> get() = byId.values.map { it.manifest }.sortedBy { it.id }

    fun find(id: String): IntegrationManifest? = byId[id]?.manifest

    fun supporting(
        network: NetworkId,
        capability: Capability,
    ): List<IntegrationManifest> = manifests.filter { network in it.networks && capability in it.capabilities }

    /** Returns the history connector registered for the network; failing when none or several are registered keeps routing explicit. */
    fun historyFetcher(network: NetworkId): HistoryFetcher {
        val candidates = byId.values.filter { network in it.manifest.networks && Capability.HISTORY in it.manifest.capabilities }
        require(candidates.size == 1) { "Exactly one history connector must support the network" }
        return candidates.single().history
    }
}

/** A manifest with the history connector that executes it. */
class RegisteredConnector(
    val manifest: IntegrationManifest,
    val history: HistoryFetcher,
)
