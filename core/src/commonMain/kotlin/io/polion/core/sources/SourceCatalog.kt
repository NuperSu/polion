package io.polion.core.sources

import io.polion.core.bitcoin.BITCOIN_CONNECTOR_ID
import io.polion.core.bitcoin.BITCOIN_MAINNET
import io.polion.core.model.AccountId
import io.polion.core.model.NetworkId
import io.polion.core.model.ProviderId
import io.polion.core.model.TrackingTargetId
import io.polion.core.ownership.TargetIdentity
import io.polion.core.ownership.TrackingTarget

@JvmInline
value class ConnectorId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class WalletBrandId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

/** Display metadata, independent of ownership, public target identity and provider routing. */
data class WalletBrand(
    val id: WalletBrandId,
    val label: String,
    val family: String,
) {
    init {
        require(label.isNotBlank() && family.isNotBlank())
    }
}

/** A saved account/target reference. Deduplication uses [identity], not this configuration's IDs. */
data class SourceTargetIdentity(
    val accountId: AccountId,
    val targetId: TrackingTargetId,
    val identity: TargetIdentity,
) {
    val networkId: NetworkId get() = identity.networkId

    companion object {
        fun from(target: TrackingTarget) = SourceTargetIdentity(target.accountId, target.id, target.identity)
    }
}

/** The adapter implementation and the selected data service have separate identities. */
data class SourceRoute(
    val connectorId: ConnectorId,
    val providerId: ProviderId,
)

enum class SourceWorkflow { BITCOIN_ADDRESS, BITCOIN_PUBLIC_WALLET, MANUAL_ACTIVITY }

enum class SourceCapability { PUBLIC_INPUT, BOUNDED_DISCOVERY, HISTORY_REFRESH, LOCAL_RECORD }

/** An implemented application entry path; these are not generic connector execution promises. */
data class SourceChoice(
    val id: String,
    val title: String,
    val description: String,
    val workflow: SourceWorkflow,
    val networkId: NetworkId?,
    val connectorId: ConnectorId?,
    val capabilities: Set<SourceCapability>,
    val requiresQueryConsent: Boolean,
)

/** Only workflows that the application can execute are offered as source choices. */
object SupportedSources {
    val choices: List<SourceChoice> =
        listOf(
            SourceChoice(
                "bitcoin-address",
                "Bitcoin address",
                "Track one public Bitcoin mainnet address using your configured Esplora provider.",
                SourceWorkflow.BITCOIN_ADDRESS,
                BITCOIN_MAINNET,
                ConnectorId(BITCOIN_CONNECTOR_ID),
                setOf(SourceCapability.PUBLIC_INPUT, SourceCapability.HISTORY_REFRESH),
                requiresQueryConsent = true,
            ),
            SourceChoice(
                "bitcoin-public-wallet",
                "Bitcoin public wallet",
                "Import a supported mainnet descriptor or xpub and discover addresses within configured bounds.",
                SourceWorkflow.BITCOIN_PUBLIC_WALLET,
                BITCOIN_MAINNET,
                ConnectorId(BITCOIN_CONNECTOR_ID),
                setOf(SourceCapability.PUBLIC_INPUT, SourceCapability.BOUNDED_DISCOVERY, SourceCapability.HISTORY_REFRESH),
                requiresQueryConsent = true,
            ),
            SourceChoice(
                "manual-activity",
                "Manual activity",
                "Record an activity in an existing account using local data.",
                SourceWorkflow.MANUAL_ACTIVITY,
                null,
                null,
                setOf(SourceCapability.LOCAL_RECORD),
                requiresQueryConsent = false,
            ),
        )

    fun supporting(capability: SourceCapability): List<SourceChoice> = choices.filter { capability in it.capabilities }
}
