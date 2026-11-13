package io.valroot.core.bitcoin

import io.valroot.core.application.AppContext
import io.valroot.core.application.AppService
import io.valroot.core.application.BITCOIN_MANIFEST
import io.valroot.core.application.BitcoinScanProgress
import io.valroot.core.application.MEMPOOL_PAGE
import io.valroot.core.connectors.ConnectorRegistry
import io.valroot.core.connectors.RegisteredConnector
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.market.AssetDefinition
import io.valroot.core.market.AssetMetadata
import io.valroot.core.model.SourceId
import io.valroot.core.model.SyncJobId
import io.valroot.core.model.isHash64
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.sha256
import io.valroot.core.sync.JobState
import io.valroot.core.sync.SyncJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Bitcoin history: refreshing tracked addresses through an Esplora provider, scan state, UTXO review and order verification. */
class BitcoinService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    /** Accepted advisory evidence for the enabled Bitcoin leaf addresses, independent of portfolio display filters. */
    fun utxoReview(
        offset: Int = 0,
        limit: Int = 25,
        expectedSnapshotId: Long? = null,
    ): io.valroot.core.bitcoin.BitcoinUtxoReviewPage {
        val claims =
            ownership
                .load()
                .targets
                .filter {
                    it.enabled &&
                        it.role == io.valroot.core.ownership.TargetRole.LEAF &&
                        it.identity.networkId == BITCOIN_MAINNET &&
                        it.identity.kind == "address"
                }.groupBy { bitcoinMainnetAddress(it.identity.canonicalKey) }
        require(claims.values.all { group -> group.map { it.accountId }.distinct().size == 1 })
        return bitcoinGraph.utxoReview(claims.mapValues { it.value.first().accountId }, offset, limit, expectedSnapshotId)
    }

    /**
     * Fetches tracked mainnet addresses after consent and applies only a complete, consistent scan.
     * Explicit [recoverHistory] starts fresh history and establishes a new continuity baseline on successful apply.
     * [providerEndpoint] binds a pre-created transport to its captured endpoint throughout the scan.
     */
    @OptIn(ExperimentalTime::class)
    fun refresh(
        transport: EsploraTransport,
        verifyBlockPositions: Boolean = false,
        recoverHistory: Boolean = false,
        providerEndpoint: String = app.sources.endpoint(),
        onProgress: (BitcoinScanProgress) -> Unit = {},
    ): List<JobState> {
        require(app.sources.consent()) { "Source consent required" }
        putSetting("source_in_progress", "true")
        return try {
            fun requireEndpoint() {
                require(app.sources.endpoint() == providerEndpoint) {
                    throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED)
                }
            }
            requireEndpoint()
            val boundTransport =
                EsploraTransport { path ->
                    requireEndpoint()
                    transport.get(path).also { requireEndpoint() }
                }
            refreshScan(boundTransport, verifyBlockPositions, recoverHistory, providerEndpoint, onProgress)
        } catch (failure: Exception) {
            if (failure is BitcoinProviderException &&
                (failure.reason == BitcoinProviderFailure.REORGANIZED || failure.reason == BitcoinProviderFailure.HISTORY_CHANGED)
            ) {
                bitcoinStage.clear()
            }
            putSetting(
                "source_error",
                (failure as? BitcoinProviderException)?.reason?.userMessage
                    ?: "Could not finish sync. Check storage and provider, then retry.",
            )
            throw failure
        } finally {
            db.execute("DELETE FROM application_settings WHERE key='source_in_progress'")
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun refreshScan(
        transport: EsploraTransport,
        verifyBlockPositions: Boolean,
        recoverHistory: Boolean,
        providerEndpoint: String,
        onProgress: (BitcoinScanProgress) -> Unit,
    ): List<JobState> {
        require(app.sources.consent()) { "Source consent required" }
        val bitcoinTargets =
            ownership.load().targets.filter {
                it.role == io.valroot.core.ownership.TargetRole.LEAF &&
                    it.identity.networkId == BITCOIN_MAINNET &&
                    it.identity.kind == "address"
            }
        val targets = bitcoinTargets.filter { it.enabled }
        val ownedClaims = bitcoinTargets.groupBy { bitcoinMainnetAddress(it.identity.canonicalKey) }
        require(
            ownedClaims.values.all { group ->
                group.map { it.accountId }.distinct().size == 1
            },
        ) { "Address assigned to multiple accounts" }
        val ownedAddresses = ownedClaims.mapValues { it.value.first().accountId }
        val reconciledAccounts = bitcoinTargets.groupBy { it.accountId }.filterValues { group -> group.all { it.enabled } }.keys
        val claims = targets.groupBy { bitcoinMainnetAddress(it.identity.canonicalKey) }
        require(claims.values.all { group -> group.map { it.accountId }.distinct().size == 1 }) { "Address assigned to multiple accounts" }
        val addresses = claims.mapValues { it.value.first().accountId }
        if (addresses.isEmpty()) {
            bitcoinStage.clear()
            return emptyList()
        }
        if (market.asset(BITCOIN_ASSET) == null) {
            app.assets.register(
                AssetDefinition(
                    BITCOIN_ASSET,
                    io.valroot.core.market
                        .AssetIdentity(io.valroot.core.market.AssetKind.NATIVE, BITCOIN_MAINNET, "BTC"),
                ),
                AssetMetadata("BTC", "Bitcoin", 8, 0),
            )
        }
        val startTip = transport.get("/blocks/tip/hash").trim()
        require(startTip.isHash64()) { "Invalid chain tip" }
        val anchor = Json.parseToJsonElement(transport.get("/block/$startTip")).jsonObject
        require(anchor.getValue("id").jsonPrimitive.content == startTip) { "Invalid chain anchor" }
        val anchorHeight = anchor.getValue("height").jsonPrimitive.long
        require(anchorHeight >= 0) { "Invalid chain anchor height" }

        fun summary(address: String): Pair<Long, Long> {
            val json = Json.parseToJsonElement(transport.get("/address/$address")).jsonObject
            val chain =
                json
                    .getValue("chain_stats")
                    .jsonObject
                    .getValue("tx_count")
                    .jsonPrimitive.long
            val mempool =
                json
                    .getValue("mempool_stats")
                    .jsonObject
                    .getValue("tx_count")
                    .jsonPrimitive.long
            require(chain >= 0 && mempool >= 0)
            return chain to mempool
        }
        val providerSource = SourceId("esplora:" + sha256(providerEndpoint))
        putSetting("provider_endpoint:${providerSource.value}", providerEndpoint)
        val fingerprint =
            sha256(
                "${providerSource.value}|" + addresses.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value.value}" },
            )
        val acceptedAnchors = bitcoinGraph.anchors(addresses)
        val previousCheckpoint = acceptedAnchors.maxByOrNull { it.height }
        // A non-canonical checkpoint means the accepted history may have changed, so the skip below must not run.
        var checkpointCanonical = true
        if (!recoverHistory && previousCheckpoint != null) {
            require(previousCheckpoint.height <= anchorHeight) {
                throw BitcoinProviderException(BitcoinProviderFailure.LAGGING_CHAIN)
            }
            val canonical = transport.get("/block-height/${previousCheckpoint.height}").trim()
            require(canonical.isHash64()) { "Invalid checkpoint response" }
            if (canonical != previousCheckpoint.hash) {
                checkpointCanonical = false
                val continuity =
                    try {
                        io.valroot.core.bitcoin
                            .BitcoinReorgDetector(transport)
                            .check(acceptedAnchors, anchorHeight)
                    } catch (_: io.valroot.core.bitcoin.BitcoinReorgBeyondBoundException) {
                        throw BitcoinProviderException(BitcoinProviderFailure.REORG_RECOVERY_REQUIRED)
                    }
                require(continuity.aboveTip.isEmpty() && continuity.commonAncestor != null) {
                    throw BitcoinProviderException(BitcoinProviderFailure.REORG_RECOVERY_REQUIRED)
                }
                bitcoinStage.clear()
            }
        }
        if (recoverHistory) bitcoinStage.clear()
        val pending = bitcoinStage.session()
        if (pending == null &&
            !recoverHistory &&
            checkpointCanonical &&
            previousCheckpoint != null &&
            acceptedScanUnchanged(fingerprint, addresses.keys, verifyBlockPositions) { address -> summary(address) }
        ) {
            db.transaction {
                putSetting("source_last_refresh_skipped", "true")
                putSetting("source_last_success", now().toString())
                db.execute("DELETE FROM application_settings WHERE key='source_error'")
            }
            invalidate()
            return addresses.keys.map { JobState.COMPLETED }
        }
        val resumable = pending != null && pending.fingerprint == fingerprint && pending.anchorHeight <= anchorHeight
        if (!resumable) {
            bitcoinStage.createSession(
                BitcoinScanSession(
                    (pending?.generation ?: 0L) + 1,
                    fingerprint,
                    startTip,
                    anchorHeight,
                ),
                addresses.map { (address, account) -> BitcoinScanAddress(address, account, null) },
            )
            db.transaction {
                val jobs = "SELECT id FROM sync_jobs WHERE source='esplora' OR source LIKE 'esplora:%'"
                db.execute("DELETE FROM sync_cursors WHERE job_id IN ($jobs)")
                db.execute("DELETE FROM sync_errors WHERE job_id IN ($jobs)")
                db.execute("DELETE FROM sync_jobs WHERE source='esplora' OR source LIKE 'esplora:%'")
            }
        }
        val session = requireNotNull(bitcoinStage.session())
        val scanned = bitcoinStage.scanAddresses()
        var providerFailure: BitcoinProviderException? = null
        var integrityFailure: BitcoinProviderException? = null
        val recordingTransport =
            EsploraTransport { path ->
                try {
                    transport.get(path).also { contents ->
                        if (path.endsWith("/txs/mempool")) {
                            bitcoinStage.recordMempool(path.removePrefix("/address/").removeSuffix("/txs/mempool"), sha256(contents))
                        }
                    }
                } catch (
                    failure: BitcoinProviderException,
                ) {
                    providerFailure = failure
                    throw failure
                }
            }
        val result = mutableListOf<JobState>()
        var pagesFetched = 0
        var transactionsFetched = 0
        for ((addressIndex, scan) in scanned.withIndex()) {
            val historyFetcher =
                ConnectorRegistry(
                    listOf(
                        RegisteredConnector(
                            BITCOIN_MANIFEST,
                            EsploraHistoryFetcher(mapOf(scan.accountId to scan.address), recordingTransport) {
                                Instant.fromEpochMilliseconds(now()).toString()
                            },
                        ),
                    ),
                ).historyFetcher(BITCOIN_MAINNET)
            val counter = (setting("sync_sequence")?.toLong() ?: 0L) + 1
            putSetting("sync_sequence", counter.toString())
            val jobId = SyncJobId("bitcoin-${session.generation}-${sha256(scan.address).take(16)}")
            val orchestrator =
                app.sources.sync(
                    fetcher { request ->
                        try {
                            historyFetcher.fetch(request).also { page ->
                                bitcoinStage.addPage(page.observations, scan.address)
                                pagesFetched++
                                transactionsFetched += page.observations.size
                                runCatching {
                                    onProgress(BitcoinScanProgress(addressIndex + 1, scanned.size, pagesFetched, transactionsFetched))
                                }
                            }
                        } catch (failure: BitcoinProviderException) {
                            if (failure.reason == BitcoinProviderFailure.HISTORY_CHANGED ||
                                failure.reason == BitcoinProviderFailure.REORGANIZED
                            ) {
                                bitcoinStage.clear()
                                integrityFailure = failure
                            }
                            throw failure
                        }
                    },
                    providerSource,
                )
            if (orchestrator.status(jobId) == null) {
                orchestrator.enqueue(SyncJob(jobId, providerSource, BITCOIN_MAINNET, scan.accountId))
            }
            orchestrator.retry(jobId)
            while (orchestrator.runOne()) { }
            val state = orchestrator.status(jobId)?.state ?: JobState.FAILED
            result.add(state)
            if (state != JobState.COMPLETED) break
        }
        integrityFailure?.let { throw it }
        if (result.size == scanned.size && result.all { it == JobState.COMPLETED }) {
            if (verifyBlockPositions) {
                runCatching { onProgress(BitcoinScanProgress(scanned.size, scanned.size, pagesFetched, transactionsFetched, "blocks")) }
                bitcoinStage.resolveBlocks(transport) { resolved, total ->
                    onProgress(
                        BitcoinScanProgress(scanned.size, scanned.size, pagesFetched, transactionsFetched, "blocks", resolved, total),
                    )
                }
            }
            runCatching { onProgress(BitcoinScanProgress(scanned.size, scanned.size, pagesFetched, transactionsFetched, "verify")) }
            require(
                bitcoinStage.scanAddresses().all { scan ->
                    scan.mempoolDigest != null && scan.mempoolDigest == sha256(transport.get("/address/${scan.address}/txs/mempool"))
                },
            ) { throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED) }
            // Esplora lists at most 50 unconfirmed transactions per address; seeing exactly that many of a larger pool is
            // complete for what the provider can show, and the page was already recorded as partial coverage.
            require(
                scanned.all { scan ->
                    val (chain, pending) = summary(scan.address)
                    val (seenChain, seenPending) = bitcoinStage.seenCounts(scan.address)
                    chain == seenChain && (pending == seenPending || pending > MEMPOOL_PAGE && seenPending == MEMPOOL_PAGE)
                },
            ) { throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED) }
            val acceptedCounts = scanned.associate { it.address to bitcoinStage.seenCounts(it.address) }
            val pendingCapped = scanned.count { scan -> summary(scan.address).second > MEMPOOL_PAGE }
            require(transport.get("/block-height/${session.anchorHeight}").trim() == session.anchorHash) {
                throw BitcoinProviderException(BitcoinProviderFailure.REORGANIZED)
            }
            require(transport.get("/block-height/$anchorHeight").trim() == startTip) {
                throw BitcoinProviderException(BitcoinProviderFailure.REORGANIZED)
            }
            bitcoinStage.validateBlocks(
                transport,
                io.valroot.core.bitcoin
                    .BitcoinBlockAnchor(anchorHeight, startTip),
            ) { validated, total ->
                onProgress(
                    BitcoinScanProgress(scanned.size, scanned.size, pagesFetched, transactionsFetched, "verify", validated, total),
                )
            }
            val stagedGraph =
                io.valroot.core.bitcoin
                    .BitcoinStagedGraph(db)
            stagedGraph.rebuild(bitcoinStage.payloads(), addresses)
            require(
                db
                    .query(
                        "SELECT 1 FROM bitcoin_graph_work_transactions WHERE height>? LIMIT 1",
                        listOf(anchorHeight.toString()),
                    ).isEmpty(),
            ) {
                throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED)
            }
            // The comparison is advisory. When the provider permanently rejects the UTXO request (for example a capped
            // address), history is still applied and the snapshot is recorded as not compared, so no clean "matching"
            // result is claimed. Transient failures (offline, timeout, rate limit) still interrupt and keep the session.
            val mismatchCount: Long? =
                try {
                    io.valroot.core.bitcoin
                        .BitcoinStagedUtxoComparison(db)
                        .fetchAndCompare(transport)
                } catch (failure: BitcoinProviderException) {
                    if (failure.reason != BitcoinProviderFailure.UNAVAILABLE) throw failure
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            runCatching { onProgress(BitcoinScanProgress(scanned.size, scanned.size, pagesFetched, transactionsFetched, "apply")) }
            require(
                transport.get("/block-height/${session.anchorHeight}").trim() == session.anchorHash &&
                    transport.get("/block-height/$anchorHeight").trim() == startTip,
            ) {
                throw BitcoinProviderException(BitcoinProviderFailure.REORGANIZED)
            }
            db.transaction {
                require(app.sources.consent()) { "Source consent required" }
                require(app.sources.endpoint() == providerEndpoint) {
                    throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED)
                }
                val currentTargets =
                    ownership.loadInTransaction().targets.filter {
                        it.role == io.valroot.core.ownership.TargetRole.LEAF &&
                            it.identity.networkId == BITCOIN_MAINNET &&
                            it.identity.kind == "address"
                    }

                fun scanScope(targets: List<TrackingTarget>) =
                    targets.associate {
                        it.id to Triple(it.accountId, bitcoinMainnetAddress(it.identity.canonicalKey), it.enabled)
                    }
                require(scanScope(currentTargets) == scanScope(bitcoinTargets)) {
                    throw BitcoinProviderException(BitcoinProviderFailure.HISTORY_CHANGED)
                }
                io.valroot.core.bitcoin.BitcoinGraphDeltaRepository(db).saveInTransaction(
                    providerSourceId = providerSource.value,
                    anchor =
                        io.valroot.core.bitcoin
                            .BitcoinBlockAnchor(anchorHeight, startTip),
                    observedAt = Instant.fromEpochMilliseconds(now()).toString(),
                    compared = mismatchCount != null,
                    recoveryBaseline = recoverHistory,
                )
                bitcoinStage.applyCompleteInTransaction(
                    ownedAddresses,
                    reconciledAccounts,
                    requireBlockPositions = verifyBlockPositions,
                    graphState = stagedGraph::state,
                )
                putSetting("utxo_comparison_available", (mismatchCount != null).toString())
                putSetting("pending_capped", pendingCapped.toString())
                mismatchCount?.let { putSetting("utxo_mismatch_count", it.toString()) }
                bitcoinStage.clearInTransaction()
                putSetting("bitcoin_accepted_scan", acceptedScanJson(fingerprint, acceptedCounts, verifyBlockPositions))
                putSetting("source_last_refresh_skipped", "false")
                putSetting("source_last_success", now().toString())
                db.execute("DELETE FROM application_settings WHERE key='source_error'")
            }
            invalidate()
        } else {
            putSetting("source_error", providerFailure?.reason?.userMessage ?: "History is incomplete. Saved data is unchanged; retry.")
        }
        return result
    }

    /** Verify saved confirmed transactions against active block lists without refetching address history. */
    fun verifyAccountingOrder(
        transport: EsploraTransport,
        onProgress: (BitcoinScanProgress) -> Unit = {},
    ) {
        require(app.sources.consent()) { "Source consent required" }
        val targets =
            ownership.load().targets.filter {
                it.role == io.valroot.core.ownership.TargetRole.LEAF &&
                    it.identity.networkId == BITCOIN_MAINNET &&
                    it.identity.kind == "address"
            }
        val claims = targets.groupBy { bitcoinMainnetAddress(it.identity.canonicalKey) }
        require(claims.values.all { group -> group.map { it.accountId }.distinct().size == 1 })
        val addresses = claims.mapValues { it.value.first().accountId }
        val decoder =
            io.valroot.core.bitcoin
                .BitcoinDecoder(addresses)
        val entries =
            ledger().activities.map { it.original }.filter {
                !it.manual &&
                    it.decoderVersion?.startsWith("bitcoin-") == true &&
                    it.status == io.valroot.core.ledger.ActivityStatus.CONFIRMED
            }
        putSetting("source_in_progress", "true")
        try {
            bitcoinStage.clear()
            val canonicalHashes = mutableMapOf<Long, String>()
            entries.forEach { activity ->
                val rows =
                    db.query(
                        "SELECT r.id, r.namespace, r.external_id, r.network, o.source_id, o.retrieved_at, v.payload, v.payload_version, v.checksum, o.context FROM raw_records r JOIN raw_revisions v ON v.record_id=r.id JOIN raw_observations o ON o.record_id=v.record_id AND o.checksum=v.checksum WHERE r.id=? ORDER BY o.retrieved_at DESC, o.source_id DESC",
                        listOf(activity.id.value),
                    )
                val raw =
                    rows
                        .asSequence()
                        .map { row ->
                            io.valroot.core.storage.RawObservation(
                                row[0]!!,
                                row[1]!!,
                                row[2]!!,
                                row[3]!!,
                                row[4]!!,
                                row[5]!!,
                                row[6]!!,
                                row[7]!!,
                                row[8]!!,
                                row[9]!!,
                            )
                        }.firstOrNull { observation ->
                            val decoded = runCatching { decoder.decode(observation).singleOrNull() }.getOrNull()
                            if (decoded == null ||
                                activity.copy(orderingKey = decoded.orderingKey, sources = decoded.sources) != decoded
                            ) {
                                false
                            } else {
                                val status =
                                    Json
                                        .parseToJsonElement(observation.payload)
                                        .jsonObject
                                        .getValue("status")
                                        .jsonObject
                                val height = status.getValue("block_height").jsonPrimitive.long
                                val hash = status.getValue("block_hash").jsonPrimitive.content
                                canonicalHashes.getOrPut(height) { transport.get("/block-height/$height").trim() } == hash
                            }
                        } ?: error("Saved Bitcoin transaction provenance is incomplete; refresh history")
                bitcoinStage.addPage(listOf(raw))
            }
            bitcoinStage.validateBlocks(transport)
            bitcoinStage.resolveBlocks(transport) { resolved, total ->
                onProgress(BitcoinScanProgress(0, 0, 0, entries.size, "blocks", resolved, total))
            }
            bitcoinStage.validateBlocks(transport)
            bitcoinStage.applyVerifiedOrder(addresses)
            invalidate()
        } finally {
            try {
                bitcoinStage.clear()
            } finally {
                db.execute("DELETE FROM application_settings WHERE key='source_in_progress'")
            }
        }
    }

    internal data class AcceptedScan(
        val fingerprint: String,
        /** Per address: (confirmed transaction count, mempool transaction count) seen by the last applied scan. */
        val counts: Map<String, Pair<Long, Long>>,
        /** Whether the last applied scan verified transaction block positions. */
        val blockPositionsVerified: Boolean,
    )

    internal fun acceptedScan(): AcceptedScan? {
        val text = setting("bitcoin_accepted_scan") ?: return null
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            AcceptedScan(
                root.getValue("fingerprint").jsonPrimitive.content,
                root.getValue("addresses").jsonObject.mapValues { (_, pair) ->
                    val counts = pair.jsonArray
                    counts[0].jsonPrimitive.long to counts[1].jsonPrimitive.long
                },
                root.getValue("blockPositionsVerified").jsonPrimitive.boolean,
            )
        }.getOrNull()
    }

    private fun acceptedScanJson(
        fingerprint: String,
        counts: Map<String, Pair<Long, Long>>,
        blockPositionsVerified: Boolean,
    ): String =
        buildJsonObject {
            put("fingerprint", fingerprint)
            put("blockPositionsVerified", blockPositionsVerified)
            putJsonObject("addresses") {
                counts.forEach { (address, pair) ->
                    putJsonArray(address) {
                        add(pair.first)
                        add(pair.second)
                    }
                }
            }
        }.toString()

    /**
     * True when the last applied scan covers the same provider and addresses, recorded no mempool activity, and the
     * provider still reports the same chain and mempool counts for every address. While the accepted anchor is
     * canonical, nothing at or below it has changed, and any new confirmed or pending transaction changes a count.
     * A refresh that asks for block-position verification is never skipped by a scan that did not verify them.
     */
    private fun acceptedScanUnchanged(
        fingerprint: String,
        addresses: Set<String>,
        verifyBlockPositions: Boolean,
        liveCounts: (String) -> Pair<Long, Long>,
    ): Boolean {
        val accepted = acceptedScan() ?: return false
        if (accepted.fingerprint != fingerprint || accepted.counts.keys != addresses) return false
        if (verifyBlockPositions && !accepted.blockPositionsVerified) return false
        if (accepted.counts.values.any { it.second != 0L }) return false
        return addresses.all { address -> liveCounts(address) == accepted.counts.getValue(address) }
    }

    /** Endpoint identities survive configuration changes and portable backup/restore. */
    fun providerEndpoints(): Map<String, String> =
        db
            .query("SELECT key, value FROM application_settings WHERE key LIKE 'provider_endpoint:%'")
            .associate { it[0]!!.removePrefix("provider_endpoint:") to it[1]!! }

    fun configureSource(
        endpoint: String,
        consent: Boolean,
    ) = db.transaction {
        app.sources.setEndpoint(endpoint)
        app.sources.setConsent(consent)
    }
}
