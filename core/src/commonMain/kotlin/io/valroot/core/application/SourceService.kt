package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_CONNECTOR_ID
import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.bitcoin.bitcoinMainnetAddress
import io.valroot.core.connectors.HistoryFetcher
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.PortfolioId
import io.valroot.core.model.ProviderId
import io.valroot.core.model.SourceId
import io.valroot.core.model.SyncJobId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.TargetRole
import io.valroot.core.storage.sha256
import io.valroot.core.sync.JobState
import io.valroot.core.sync.SyncClock
import io.valroot.core.sync.SyncJob
import io.valroot.core.sync.SyncOrchestrator
import io.valroot.core.sync.SyncProvider

/** Data sources in general: running connectors, ingesting provider history, consent, endpoint and refresh status. */
class SourceService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    /** Saved scan jobs describe fetch progress; acceptance is established separately for the whole scan. */
    fun scanJobs(targetId: TrackingTargetId): List<SourceAddressScan> {
        val targets = ownership.load().targets
        val target = requireNotNull(targets.firstOrNull { it.id == targetId }) { "Unknown source" }
        val leaves = if (target.role == TargetRole.WALLET) targets.filter { it.walletId == targetId } else listOf(target)
        val hasJobs = db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='sync_jobs'").isNotEmpty()
        return leaves
            .filter { it.role == TargetRole.LEAF && it.identity.networkId == BITCOIN_MAINNET && it.identity.kind == "address" }
            .sortedBy { it.identity.canonicalKey }
            .map { leaf ->
                val suffix = "-" + sha256(bitcoinMainnetAddress(leaf.identity.canonicalKey)).take(16)
                val row =
                    if (hasJobs) {
                        db
                            .query(
                                "SELECT state, pages, coverage, last_provider FROM sync_jobs WHERE account=? AND network=? " +
                                    "AND (source='esplora' OR source LIKE 'esplora:%') AND id LIKE 'bitcoin-%' AND substr(id, -17)=? LIMIT 1",
                                listOf(leaf.accountId.value, BITCOIN_MAINNET.value, suffix),
                            ).singleOrNull()
                    } else {
                        null
                    }
                SourceAddressScan(leaf, row?.let { SourceScanJob(it[0]!!, it[1]!!.toInt(), it[2]!!, it[3]) })
            }
    }

    /** Observation metadata is evidence of saved revisions, not proof of which revision a decoder used. */
    fun evidence(
        activityId: ActivityId,
        portfolio: PortfolioId? = null,
    ): List<ActivitySourceEvidence>? {
        val activity = app.accounting.effectiveActivity(activityId, portfolio) ?: return null
        return activity.original.sources.sortedBy { it.value }.map { source ->
            val record = db.query("SELECT namespace, external_id, network FROM raw_records WHERE id=?", listOf(source.value)).singleOrNull()
            val observations =
                db.query(
                    "SELECT o.source_id, o.retrieved_at, o.checksum, r.payload_version FROM raw_observations o " +
                        "JOIN raw_revisions r ON r.record_id=o.record_id AND r.checksum=o.checksum " +
                        "WHERE o.record_id=? ORDER BY o.retrieved_at DESC, o.source_id, o.checksum LIMIT 11",
                    listOf(source.value),
                )
            ActivitySourceEvidence(
                source.value,
                record?.get(0),
                record?.get(1),
                record?.get(2),
                observations.take(10).map { SavedObservation(it[0]!!, it[1]!!, it[2]!!, it[3]!!) },
                observations.size > 10,
            )
        }
    }

    internal fun sync(
        fetcher: HistoryFetcher,
        source: SourceId = SourceId("esplora"),
    ): SyncOrchestrator =
        SyncOrchestrator(
            db,
            store,
            listOf(SyncProvider(ProviderId(source.value), fetcher, setOf(BITCOIN_MAINNET), setOf(source))),
            SyncClock { now() },
        )

    /** Networks with an enabled tracked address or wallet. */
    fun trackedNetworks(): Set<io.valroot.core.model.NetworkId> =
        ownership
            .load()
            .targets
            .filter { it.enabled }
            .map { it.identity.networkId }
            .toSet()

    /**
     * Refreshes every source through [registry]: each registered connector runs once, and a tracked network without a
     * history connector is reported instead of skipped silently. Connectors run in registration order; a failure stops
     * the refresh and propagates, so callers report it as they do for one source.
     */
    fun refresh(
        registry: SourceRegistry,
        options: SourceRefreshOptions = SourceRefreshOptions(),
    ): List<SourceRefreshOutcome> {
        val missing = trackedNetworks().filter { registry.forNetwork(it) == null && it !in options.withoutConsent }
        return registry.connectors.map { it.refresh(app, options) } +
            missing.map { SourceRefreshOutcome("none", emptyList(), listOf("No connector is available for ${it.value}.")) }
    }

    /**
     * The generic history path for a connector: fetches pages for [accounts] on [network] through the sync orchestrator
     * (leases, retries, cursors and raw provenance), then saves what [decoder] makes of each new observation. Decoded
     * activities may only move tracked accounts. Holdings and accounting read them like any other source.
     */
    fun ingestHistory(
        connector: String,
        history: HistoryFetcher,
        decoder: io.valroot.core.connectors.RawRecordDecoder,
        network: io.valroot.core.model.NetworkId,
        accounts: List<AccountId>,
    ): List<JobState> {
        require(connector.isNotBlank() && connector != BITCOIN_CONNECTOR_ID) { "Bitcoin uses its own scan" }
        val known =
            ownership
                .load()
                .accounts
                .map { it.id }
                .toSet()
        require(accounts.all { it in known }) { "Unknown account" }
        val fetched = mutableListOf<io.valroot.core.storage.RawObservation>()
        val source = SourceId(connector)
        val orchestrator =
            SyncOrchestrator(
                db,
                store,
                listOf(
                    SyncProvider(
                        ProviderId(connector),
                        fetcher { request ->
                            history.fetch(request).also { fetched += it.observations }
                        },
                        setOf(network),
                        setOf(source),
                    ),
                ),
                SyncClock { now() },
            )
        // Each refresh is a new job; completed jobs keep their status as a record of earlier refreshes.
        val generation = (setting("connector_refresh:$connector")?.toLongOrNull() ?: 0L) + 1
        putSetting("connector_refresh:$connector", generation.toString())
        val jobs = accounts.map { SyncJobId("$connector-$generation-${sha256(it.value).take(16)}") }
        accounts.zip(jobs).forEach { (account, job) -> orchestrator.enqueue(SyncJob(job, source, network, account)) }
        while (orchestrator.runOne()) { }
        val activitiesToSave = fetched.distinctBy { it.id to it.checksum }.flatMap(decoder::decode)
        require(activitiesToSave.all { activity -> activity.movements.all { it.accountId in known } && !activity.manual }) {
            "A connector may only record source activity in tracked accounts"
        }
        // A decoded activity identical to the saved one adds no revision.
        db.transaction { activitiesToSave.filter { activities.currentById(it.id) != it }.forEach(activities::saveInTransaction) }
        invalidate()
        return jobs.map { orchestrator.status(it)?.state ?: JobState.FAILED }
    }

    fun failureMessage(): String? = setting("source_error")

    /** Epoch millis of the last successful wallet refresh, or null when none has completed. */
    fun lastSuccessMillis(): Long? = setting("source_last_success")?.toLongOrNull()

    /** True when the last wallet refresh found no change and left the accepted ledger as it was. */
    fun lastRefreshSkipped(): Boolean = setting("source_last_refresh_skipped") == "true"

    /**
     * Clears the last-success marker and the accepted-scan snapshot together. Any change to accepted wallet state calls
     * this, so the skip path never trusts a snapshot that no longer describes the ledger.
     */
    internal fun forgetSuccess() {
        db.execute("DELETE FROM application_settings WHERE key IN ('source_last_success','bitcoin_accepted_scan')")
    }

    fun consent(): Boolean = setting("source_consent") == "true"

    fun setConsent(accepted: Boolean) = putSetting("source_consent", accepted.toString())

    fun endpoint(): String = setting("source_endpoint") ?: DEFAULT_ENDPOINT

    fun setEndpoint(endpoint: String) {
        require(
            endpoint.startsWith("https://") &&
                endpoint.substringAfter("https://").substringBefore('/').isNotBlank() &&
                endpoint.length <= 2048 &&
                endpoint.none { it.isWhitespace() } &&
                !endpoint.substringAfter("https://").substringBefore('/').contains('@') &&
                !endpoint.contains('?') &&
                !endpoint.contains('#'),
        ) { "Use an HTTPS provider URL with a host and without credentials, query or fragment" }
        putSetting("source_endpoint", endpoint.trimEnd('/'))
    }
}
