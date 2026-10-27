# Local-first Kotlin Portfolio Tracker — Architecture and Long-term Roadmap

**Status:** Proposed architecture and implementation plan, 28 October 2026.

**Source baseline:** Public `rotki/rotki` upstream `develop` examined at planning time. The user's existing `../rotki` fork is a **reference implementation**, not the destination for a Kotlin rewrite. Local reference inspected at commit `42adc4623b8a2821d70e984d18a14d59bcc656fd`; see `docs/reference-audit.md`. Do not reuse source code or licensed test material without checking obligations.

## Product objective

Build a local-first cryptocurrency portfolio tracking and accounting application with Kotlin Multiplatform shared core and Compose UI for Android and desktop. No mandatory remote application server, accounts, or cloud. Blockchain explorers/nodes, exchanges, and prices are optional external data providers; the application keeps its authoritative user state in local storage. Start by implementing **Bitcoin mainnet**, but complete reusable foundational capabilities before treating the first release as architecturally sound.

This is not a wallet for holding private keys, signing, broadcasting, or custody. Watch-only tracking by addresses, output descriptors and public wallet information is the initial security posture. Design must allow future integrations without redesign of the underlying account, asset, transaction, provenance, sync, accounting, and portfolio layers.

**Definition of full core:** Real, tested implementations of identity and ownership, asset model, exact money, local persistence/migration, raw provenance, event normalization, idempotent source ingestion, task orchestration, data provider failover, reconciliation, user corrections, current and historical balance projections, valuation, cost-basis accounting, import/export framework, security/backups, shared use-cases and UI surfaces. Future blockchain adapters need not exist; no fictitious empty adapters are required.

## Non-goals in first Bitcoin-backed release

- Full Rotki database compatibility or code-for-code port.
- Every blockchain/exchange/DeFi protocol immediately.
- Cryptocurrency signing or custody, mnemonic/seed/private-key ingestion.
- Permanent background syncing on Android or own indexer/server.
- Mandatory remote authentication/cloud sync.
- Public third-party dynamic plugin execution before sandboxing/security can be designed.
- Legal or tax correctness for every jurisdiction without specific rules and tests.

## Key architectural invariants

1. **Sources are not accounts.** An indexer is a data provider; a BTC wallet is a tracking target; an exchange login is a source connector; a portfolio is a reporting scope. Model them separately.
2. **Ownership is explicit.** One wallet may contain multiple addresses. The same address might be imported twice. Portfolio totals must not multiply holdings by the number of providers, watchers or aliases.
3. **Stable identities.** Network/chain identifiers must be explicit (BTC mainnet and testnet never share identity). Use typed IDs at domain boundaries. Asset symbol is not an identity.
4. **Exact money.** Keep atomic chain quantities as arbitrary-precision integers where required, prices as exact decimal/rational representations, and explicit rounding at display/export only. Never use floating-point for persisted financial values.
5. **Raw data are preserved.** Retain the original source payload, retrieval metadata, source-native identifiers, timestamps, and context needed for re-decoding. Redaction/encryption and storage policies apply.
6. **Derivations are versioned/replayable.** Decoding, categorization, reconciliation and accounting results must be regenerable when code/rules change. User corrections remain separate and are re-applied deterministically.
7. **Synchronization is idempotent and resumable.** Persist data and checkpoint updates transactionally. A provider timeout must not manufacture an empty balance or mark history complete.
8. **Completeness is part of data.** Distinguish unknown, stale, partial, pending, unpriced and confirmed from true zero.
9. **Reorgs and replacements are first-class.** Chain observations can be superseded; preserve audit history and rebuild affected projections.
10. **Application core never imports UI or platform framework code.** Android/Desktop implement platform services using narrow ports.
11. **Capabilities, not giant interfaces.** Bitcoin's UTXO scanning, EVM tokens, exchange trades and staking should share the domain ledger but can use specialized capabilities and provider contracts.
12. **Offline-first ≠ offline discovery.** Locally saved history/views work offline; live balances and fresh prices need a provider or node.
13. **User data never becomes disposable cache.** Imported records, corrected events, rules, encrypted credentials and ownership configuration receive durability/backup treatment.
14. **No silent accounting assumptions.** Unknown acquisition costs, source gaps, missing historical prices and ambiguous matches are visible and prevent misleading precise PnL.
15. **Privacy is explicit.** Direct public explorer queries reveal wallet addresses to third parties; allow configurable/self-hosted endpoints and disclose this in onboarding.

## High-level architecture

```text
Compose Desktop / Compose Android (shared presentation where practical)
                         |
                 Application use cases
                         |
        +----------------+----------------+
        |                |                |
  Portfolio/holdings  History/activity  Reports/accounting
        |                |                |
        +----------- Domain engines ------+
                         |
      Source ingestion -> raw records -> decoders
                         |                 |
        Provider registry                 normalized events
        Chain adapters                    overrides/linkage
        Exchange adapters                       |
        CSV/import adapters              derived projections
                         |                 |
                   Transactional repositories
                         |
                     Local SQLite
                         |
         Android/Desktop platform services
   (filesystem, encryption, jobs, notifications, secure storage)
```

**Terminology and roles:**

- **Portfolio:** report/filtering scope with owners, tracking targets, valuation currency and account membership.
- **Ownership entity:** a person/entity or logical owner used to determine whether movements between accounts are internal transfers.
- **Tracking target:** individual address, descriptor-derived wallet, EVM account, exchange subaccount, manual holding, validator, DeFi position, etc.
- **Account:** user-facing ledger/account identity; hierarchy supports addresses under a wallet and accounts under an exchange connector.
- **Source connector:** implementation that retrieves or imports source observations.
- **Data provider:** endpoint/service for chain state, historical data, token metadata, prices, etc. One connector may use multiple providers.
- **Source observation:** immutable or versioned raw record with provenance and stable source-native identity.
- **Economic activity:** one transaction/trade/swapping/staking action consisting of one or more normalized asset movements and fees, with references to sources.
- **Reconciliation link:** connects two records/activities representing the same underlying economic action (e.g. withdrawal and on-chain transfer) without deleting either.
- **Derived projection:** balance, lot inventory, valuation or report constructed from normalized events and rules; may be incrementally cached and fully rebuilt.

## Core model (illustrative, names are not mandatory)

`PortfolioId`, `OwnerId`, `TrackingTargetId`, `AccountId`, `NetworkId`, `AssetId`, `SourceId`, `ProviderId`, `SourceRecordId`, `ActivityId`, `EventId`, `SyncJobId` should be typed IDs. Consider standard chain/asset namespaces (e.g. CAIP where appropriate) plus explicit chain-specific native IDs and a custom-asset namespace. Distinguish asset identity from price-provider symbols and contract addresses from display names.

`Amount(assetId, atomicUnits)` uses a suitable arbitrary-precision representation, scale/decimals defined by the asset/denomination at the relevant time. `Price(baseAssetId, quoteAssetId, decimalValue, source, observedAt, validAt, confidence/status)` must preserve origin and precision. All timestamps UTC instants; source-specific block heights, sequence numbers, and source timestamps separately.

`RawRecord` contains sourceId, sourceType, externalId, networkId if applicable, retrievalTime, payload or normalized raw representation, payloadVersion, checksum and revision/status. `Activity` has stable ID, source links, occurredAt, ordering keys, classification, status, provenance, decoder version. `Movement` contains account identity, signed asset amount, role (transfer/fee/trade leg/etc.), external counterparty as needed; do not require one single debit and one single credit. `Rule`/`Override`/`Link` store human and automated categorization with conflict resolution.

**Future-specific extension records:** `Utxo`, `BitcoinBlock`, `DerivationIndex`, `EvmTransaction`, `EvmLog`, `SolanaInstruction`, `ExchangeTrade`, `ExchangeTransfer`, `StakingPosition`, `DeFiPosition`, etc. Keep chain-specific raw structures out of the generic event model.

### Important design choices

- Prefer **compiled, registered adapters** first, not dynamic jar/plugin loading on Android. Define an integration manifest and capabilities but add dynamic third-party plugins only if needed later.
- Consider a small family of interfaces (`AccountDiscoverer`, `BalanceObserver`, `HistoryFetcher`, `RawRecordDecoder`, `PriceSource`, `Importer`, `ProviderHealthChecker`), not a compulsory universal `BlockchainAdapter` with unimplemented methods.
- Avoid binding adapters directly to Compose, SQL tables, or one commercial provider. Use application services and repositories.
- A market quote and an on-chain holding are observations of different types. Current balance observations should be reconcilable against ledger-derived balances.
- Never make an external provider's pagination cursor the *only* durable progress marker. Store sufficient overlap/finality metadata to catch reorgs, edits, and late reports.

## Persistence model (conceptual, not prescriptive SQL)

`portfolios`, `owners`, `portfolio_memberships`, `tracking_targets`, `wallets`, `wallet_descriptors`, `derived_addresses`, `accounts`, `providers`, `provider_settings`, `secret_references`, `assets`, `asset_aliases`, `asset_metadata_versions`, `sources`, `raw_records`, `raw_record_revisions`, `activities`, `movements`, `activity_sources`, `overrides`, `manual_entries`, `reconciliation_links`, `sync_jobs`, `sync_checkpoints`, `sync_errors`, `price_observations`, `price_selection_rules`, `balance_observations`, `balance_projections`, `accounting_rules`, `lots`, `lot_consumptions`, `reports`, `schema_migrations`. Chain-specific tables introduced by adapters as required, e.g. `btc_transactions`, `btc_tx_inputs`, `btc_tx_outputs`, `btc_blocks`, `btc_utxos`, `btc_derivation_state`.

Raw records and manual edits are durable. Snapshots/projections may be recomputed. Use constraints/unique keys for deterministic identity, foreign keys, indexes, data retention policy and transactional migrations. SQLDelight is an appropriate default candidate for shared SQL and type-safe queries; verify per-platform SQLite driver, encryption support and migration behavior early.

## Functional scenarios that the core must support

1. A Bitcoin wallet contains receive/change addresses: wallet holdings count once, not once per address and again per wallet.
2. Same address appears in two imported watch lists, one explicitly grouped with a wallet: aggregate only once per owner/scope; ambiguities are visible.
3. A Bitcoin spend has two owned inputs and one owned change output: show the correct external payment, fee, change and wallet net delta without calling change an acquisition.
4. Internal transfer between two tracked wallets owned by the same owner: net portfolio balance unchanged, separate fees retained, two source records linked.
5. A transaction seen pending then confirmed then reorganized or replaced: status and balance projections update without duplicate income or disappearing audit evidence.
6. An indexer returns 500/errors or pagination ends prematurely: sync incomplete, previously correct balances not silently overwritten with zero.
7. Sync from two providers returns the same transaction: one normalized activity, both source observations retained, conflict detectable.
8. Updated decoder changes classification: replay derived data without removing the user's annotation.
9. A BTC acquisition lacks purchase history: unknown cost basis is explicitly represented, no invented profit number.
10. Prices unavailable at a specific time: coin quantity still shown, fiat valuation marked missing/stale.
11. Importing the same CSV file twice is idempotent; ambiguous exchange/on-chain matching is not automatically merged.
12. Export then import preserves financial values, relationships, classifications and known missing-data states exactly.

# Phased roadmap

Each phase must have tests and a written decision record for important tradeoffs. Completion means working behavior; stubs or placeholders do not qualify. Suggested phases are dependencies, not rigid file-by-file implementation instructions. Parallelize independent work only when contracts stabilize.

## Milestone A: Platform, identities, storage and extension contracts

### Phase 0 — Audit and project inception
- Inspect actual `../rotki` fork (commit, relevant files and tests) and public upstream. Record observations, examples and licensing boundaries.
- Define product requirements, privacy/threat model, supported platforms and watch-only rules.
- Decide new project/repo location separate from `../rotki`; do not erase fork.
- Acceptance: architecture decision record, source map, version baseline, tracked risks.

### Phase 1 — KMP build foundation
- Create shared Kotlin core and minimal Android + desktop Compose hosts.
- Configure dependency management, logging without secrets, CI, static analysis and unit tests.
- Keep Android-specific code out of common domain logic.
- Initial validation targets are Linux x86-64 desktop and Android API 26+. Use JDK 21 and separate Android application and shared library modules.
- Acceptance: both targets build and launch; record builds, launches and hosted CI execution separately. Core common tests run on at least one platform and CI is reproducible.

### Phase 2 — Exact monetary and identity primitives
- Implement asset/network/account/source IDs, exact atomic amount and decimal price/value types, serialization and conversions.
- Prove no precision loss for 8-, 18-, 24- and higher-decimal assets; huge EVM integers; negative and fee quantities; locale-independent round-tripping.
- Acceptance: exhaustive arithmetic, serialization and property tests; no Float/Double domain money.

### Phase 3 — Accounts, portfolios and ownership
- Model owners, wallet groups, accounts, tracking targets, account hierarchy, labels, tags and reporting scopes.
- Define chain-network-aware identity, overlap detection, shared targets, independently enabled/disabled trackers.
- Acceptance: synthetic BTC wallet with 100 derived addresses counts as one wallet target and does not double count when aliases are added.

### Phase 4 — Storage and migrations
- Implement transactional repositories and SQL schema, referential integrity, versioned migrations, backup-safe operations.
- Delineate durable user data from caches; choose encryption approach that works on Android and desktop or visibly declare interim limitations.
- Acceptance: clean database, reopen, migration, corrupt/partial upgrade simulation and concurrency tests succeed.

### Phase 5 — Raw source ingestion and provenance
- Implement source identities, canonical source-native keys, raw payload storage, revisions and fetch metadata.
- Keep immutable source history and internal auditing separate from normalized events; implement deterministic deduplication.
- Acceptance: provider-switch and reimport tests store one logical record plus both provenance trails where applicable.

### Phase 6 — Activity and movement ledger
- Define normalized multi-leg activities, references, fees, pending/reversed status, timestamp/order semantics and decoder versions.
- Store user-created activities without conflating them with provider records.
- Acceptance: synthetic deposits, spends, transfers, trades, airdrops, and fees produce correct ledger deltas and grouping.

### Phase 7 — Connector capability model
- Design thin contracts for discovery, history, balances, source observations, decoding, importing and provider health.
- Compile-time registry and metadata describe supported networks, account types, operations, auth requirements and sync coverage.
- Use fake Bitcoin, fake EVM and fake exchange connectors in tests to prove independent extensibility; do not build real unused connectors.
- Acceptance: adding fake connectors requires no edits in core holdings/accounting logic.

### Phase 8 — Synchronization orchestrator
- Add resumable jobs, checkpoint commits, cancellation, backoff, rate limits, bounded parallelism, progress, provider routing, coverage and errors.
- Define chain-specific finality/reorg policy extension points, idempotent commits, safe scheduling across process death.
- Acceptance: injected crashes, repeated pages, two concurrent jobs, timeouts, gaps and manual retry behave predictably.

## Milestone B: Portfolio, valuation, accounting and durability engines

### Phase 9 — Asset registry and market data
- Support native assets, token contracts, NFTs and custom assets as identities without needing actual future connectors.
- Add provider mapping, timestamped observations, historical and current lookup, selection policies, fiat pairs, overrides and stale/missing status.
- Acceptance: unknown prices never become zero and symbols shared by different contracts cannot be mistaken for one asset.

### Phase 10 — Holdings and historical-balance projections
- Compute quantities by account, wallet, owner, portfolio and asset from normalized movements; expose reported-balance reconciliation and status.
- Support checkpoints/materialized projections with replay, invalidation and historical queries at precise timestamps.
- Acceptance: fixture portfolios with transfers/change/reorgs produce correct current/historical quantities, including incremental vs full replay parity.

### Phase 11 — Accounting core
- Define acquisition lots, disposals, fees, cost allocation, internal transfers, realized vs unrealized gain, opening balance and unknown basis.
- Implement one or more well-tested deterministic lot selection methods (e.g. FIFO as first; configurable additions later), rules and tax-independent audit reports.
- Avoid claiming tax-report compliance for jurisdictions not modeled.
- Acceptance: synthetic multi-asset trade and BTC cases reproduce exact lot inventory and PnL, with uncertainty explicitly represented.

### Phase 12 — Overrides, reconciliation and rule replay
- Version normalized classifications and persist user edits as overlays; preserve originals and explain sources of derived outcomes.
- Match linked movements cautiously, with scores and explicit review for ambiguous cases. Rebuild on rule or decoder changes.
- Acceptance: reimport/redecode cannot erase custom labels, classifications or manually fixed values; ambiguous matches remain unresolved.

### Phase 13 — Backups, import/export framework and recovery
- Establish portable versioned export schema, checksum/integrity, secret exclusion or separately protected secret handling, encrypted backup and restore.
- Add importer contract, validation, dry-run preview, idempotence, per-row diagnostic reporting.
- Acceptance: export/import/replay reproduces essential state; interrupted restore never destroys the only good copy.

### Phase 14 — Contract and performance harness
- Build a reusable test harness with fake providers, chain fixture files, deterministic clocks, corruption injection and cross-platform tests.
- Run core invariants above in CI. Benchmark a large synthetic portfolio and repeated recomputation.
- Acceptance: fake additional chain and exchange connectors each produce correct balances/accounting without modifying core engines.

**Gate B — Full generic core:** No blockchain-specific assumptions in the core; persisted/replayable source→activity→projection pipeline works end-to-end with synthetic fixtures. Accounts, sync, valuation, accounting, overrides and backup are not placeholders.

## Milestone C: Real Bitcoin implementation

### Phase 15 — Bitcoin networks, addresses and descriptors
- Support Bitcoin mainnet first; network identifiers designed to prevent mixing testnet/signet/mainnet.
- Validate Bech32/Bech32m/Base58 addresses; import watch-only addresses, descriptors or public extended keys with clear origin/derivation details.
- Support legacy, nested SegWit, native SegWit and Taproot where practical; robust receive/change derivation and configurable gap scanning.
- Select/audit a mature cryptography/descriptor implementation instead of hand-rolling consensus-critical parsing.
- Acceptance: known BIP test vectors, malformed descriptors, duplicate watch sets, and receive/change derivation cases pass.

### Phase 16 — Bitcoin providers
- Implement at least one real Bitcoin history/UTXO provider (e.g. Esplora) with pagination, tip height, transaction/status/prevout information and retry behavior.
- Configure private/self-hosted endpoints; ideally a second provider of the same capability to prove failover.
- Explicitly report provider trust/privacy limits; avoid unsupported claims of full transaction verification.
- Acceptance: retrieve long-history addresses (multiple pages), unconfirmed activity and UTXOs; switch providers without duplicates.

### Phase 17 — Raw Bitcoin transaction, block and UTXO model
- Store txid, inputs, outputs, outpoints, amounts, scripts/addresses as appropriate, block anchoring, status, confirmation, spends and ownership relationships.
- Track known UTXOs, spending inputs, change, unconfirmed outputs and conflicts; checkpoint discovery independently from balance querying.
- Acceptance: correct UTXO set and balance for multi-address wallets, change outputs, multiple inputs, coinbase and nonstandard/missing-address output metadata.

### Phase 18 — Reorg, mempool and replacement handling
- Implement bounded chain rewind, block mismatch detection, mempool conflicts/replacements/evictions, confirmed vs pending projection treatment.
- Account for time ambiguity in unconfirmed vs confirmed events and preserve superseded observations.
- Acceptance: deterministic RBF, chain-reorg and recovery fixtures maintain accurate final balances without losing record history.

### Phase 19 — Bitcoin activity decoding and reconciliation
- Build multi-leg send/receive/internal transfer/fee activities from raw Bitcoin transaction graph and explicit owned outputs.
- Distinguish self-change, different owned wallets, external payments, mixed inputs and uncertain ownership.
- Preserve raw data for future more sophisticated decoders; replay edits and rules.
- Acceptance: cases with two owned inputs, one change output, several recipients, fee and unknown counterparties decode correctly.

### Phase 20 — End-to-end Bitcoin financial validation
- Verify chain-reported UTXOs vs reconstructed holdings, wallet/account/reporting scopes, historical balances, historical prices, cost-basis and exported records.
- Compare fixed Bitcoin cases with the local Rotki fork where useful, and verify against independent chain data/test fixtures; document differences.
- Acceptance: entire Bitcoin history can be synced from scratch, interrupted/restarted, rebuilt and exported with identical deterministic core results.

**Gate C — First real integration:** Bitcoin is a fully functional adapter atop the generic core, not the place where portfolio/accounting primitives live. Add a fake EVM adapter again to check extensibility regression.

## Milestone D: User-facing product and device reliability

### Phase 21 — Shared Compose workflows
- UI for portfolios, owners, account groups, tracked wallets/addresses/descriptors, balances, history, asset details, fiat valuations and sync status.
- Include source details, stale/partial/unpriced flags, corrections, rules and account reconciliation; never imply pending data is final.
- Acceptance: Bitcoin portfolio workflow works on Android and desktop using exactly the same shared engines.

### Phase 22 — Platform integration and privacy
- Android lifecycle and WorkManager-appropriate background updates; desktop background refresh and local file dialogs; use platform secure storage.
- Implement auto-lock if useful, crash-resilient sync, privacy disclosure for querying public indexers, sensitive log redaction and data wipe/export.
- Acceptance: process death, airplane mode, low storage, app upgrade and wrong passwords produce safe understandable results.

### Phase 23 — Charts, reporting and customization
- Current and historical asset allocation, value chart, category/account breakdown, filters/search, currency preference, manual price changes, exports and accounting explanations.
- Ensure performance through pagination, projection caching and view-state management.
- Acceptance: analytics uses same authoritative projection engines as textual balances and yields traceable values.

**Gate D — First comprehensive product release:** Functional Android + desktop local-first Bitcoin portfolio tracker with a general-purpose, extensible core, accounting and user corrections, not a thin balance-only prototype.

## Milestone E: New integration families without rewriting core

### Phase 24 — EVM chain-family adapter
- Add EVM chain IDs, addresses, native-coin transfers, receipts/logs, indexed transaction retrieval and state/balance providers.
- Support chain-specific networks and provider capabilities without forking generic core models.
- Acceptance: Ethereum account history and ETH holdings use the same ledger, prices, account grouping and accounting interfaces.

### Phase 25 — EVM assets, tokens and NFTs
- ERC-20 transfer discovery, contract-address asset identities, decimals/version quirks, NFT holdings and multi-token balance observations.
- Distinguish tokens, wrapped tokens, LP receipts and underlying economic exposures; prevent double-counting.
- Acceptance: multiple same-symbol tokens, unknown/spam tokens and large integer values handled correctly.

### Phase 26 — Protocol/DeFi decoder framework
- Build registered versioned protocol decoders with chain/contract/event filters, enrichment hooks and replayable outputs.
- Implement a few representative swaps, bridges, staking and lending activities, documenting accounting uncertainty.
- Acceptance: new protocol decoder adds no new hard-coded branch to the central transaction engine.

### Phase 27 — Exchange connectors
- Authenticated read-only APIs, account/subaccount identity, balances, trades, movements, fees, funding/rewards and time-range limits.
- Implement provider-specific pagination, timestamp normalization, API key rotation and partial-history status.
- Acceptance: fake exchange plus at least one real exchange; stable dedup and no confusion between exchange source, account and chain.

### Phase 28 — Generic importers and reconciliation
- CSV/JSON imports with templates, preview, mapping, multi-language date/decimal parsing, duplicate resolution, conflict diagnostics and cross-source matching.
- Match exchange withdrawals to chain deposits and detect internal transfers without erasing source evidence.
- Acceptance: repeated imports produce no duplicates and partial invalid rows do not corrupt valid imported data.

### Phase 29 — Other blockchain families
- Extend with UTXO variants (Litecoin and Bitcoin Cash where applicable), XRPL account/reserve/tag semantics, Solana account/token/instruction semantics, and other ecosystems based on value.
- Each integration owns its raw representation and normalizers; generic accounting engines remain unchanged unless justified by a new financial primitive.
- Acceptance: adding a non-UTXO, non-EVM chain validates contracts and reveals no need to redesign portfolio identities.

### Phase 30 — Advanced positions and accounting policies
- Staking, lending, borrowing/liabilities, derivative positions, LP look-through valuation, NFT appraisal, cross-chain bridges and configurable tax/reporting rules.
- Separate positions (economic exposure) from wallet balances (custody); avoid counting both receipt token and underlying position unless valuation policy demands it.
- Acceptance: test fixtures cover leverage/liabilities, bridge transfers, partial swaps and unknown basis with no hidden double counts.

### Phase 31 — Local multi-device synchronization (optional)
- Design sync separately from import/export: encrypted user-controlled files/backup targets, versioned object IDs, conflicts and robust edit merging.
- Never assume an always-online backend; preserve privacy and account integrity.
- Acceptance: divergent Android and desktop edits reconcile deterministically without losing source provenance or duplicating events.

### Phase 32 — Hardening, distribution, ecosystem
- Reproducible builds, license/dependency audit, threat review, fuzz/property tests for parsers, provider conformance suite, migration fixtures, performance/battery benchmarks and release channels.
- Extension authoring documentation, examples, API contracts, quality gates and regression suite; investigate dynamic plugins only if truly justified.
- Acceptance: repeatable release process with verified recovery from typical offline/sync/migration failures.

## Suggested deliverable/review template for every phase

- **Design:** public contracts, invariants, data migrations, alternatives considered.
- **Implementation:** new code plus only necessary cross-module changes; remove throwaway code.
- **Tests:** deterministic unit tests, integration tests, errors/edge cases, where relevant Android+desktop smoke tests.
- **Data compatibility:** source schema/cursor version and upgrade/replay implications.
- **Security/privacy:** relevant permissions, secrets, provider exposure, input validation.
- **Evidence:** commands run and results, unresolved blockers, known limitations.
- **Gate:** exact user-observable behavior that now works; no "complete" status for unexecuted tests.

## Architectural anti-patterns to avoid

- Making `BitcoinTransaction` or `BitcoinAddress` the generic transaction/account object.
- Tying money to display currency; `Double` arithmetic; treating missing price as zero.
- Treating address, wallet, owner, exchange login and portfolio as interchangeable identities.
- Modeling current balances as simple sum of externally observed snapshots across all providers.
- Destructively rewriting imported source rows during decoding.
- Storing only prettified events and discarding source payloads.
- Performing business logic inside Compose ViewModels, DAO classes or provider clients.
- Building a REST server internally only to talk between Kotlin UI and Kotlin core.
- Prematurely creating hundreds of interfaces or Gradle modules for nonexistent integrations.
- Pretending every blockchain supports identical history RPC, cursor model, finality, fee rules or token system.
- Treating public indexers as independently verifiable truth.
- Merging suspiciously similar movements automatically without reliable provenance.
- Generating tax advice when purchase price, timestamp or original cost basis is unknown.

## Recommended execution order and parallelism

Phases 0–8 establish a stable platform and data/contracts spine; 9–14 complete generic financial engines; 15–20 build Bitcoin using that spine; 21–23 make it a daily-use app. UI shell work can begin in parallel after phase 3, but business screens should remain tied to real application use-cases. Future phases 24–32 are integration expansion and production polish. Prefer vertical integration tests throughout instead of waiting until the end. Require synthetic EVM/exchange contract tests **before** claiming Bitcoin-centric implementation is extensible.

## Core-specific external references

- Rotki source: https://github.com/rotki/rotki
- Architecture notes: https://github.com/rotki/rotki/blob/develop/CLAUDE.md
- Rotki generic events: https://github.com/rotki/rotki/blob/develop/rotkehlchen/history/events/structures/base.py
- Rotki user DB schema: https://github.com/rotki/rotki/blob/develop/rotkehlchen/db/schema.py
- Rotki Bitcoin adapter: https://github.com/rotki/rotki/blob/develop/rotkehlchen/chain/bitcoin/manager.py
- Rotki xpub manager: https://github.com/rotki/rotki/blob/develop/rotkehlchen/chain/bitcoin/xpub.py
- Rotki accounting: https://github.com/rotki/rotki/blob/develop/rotkehlchen/accounting/accountant.py
- Kotlin KMP+Ktor+SQLDelight guide: https://kotlinlang.org/docs/multiplatform/multiplatform-ktor-sqldelight.html
- SQLDelight: https://sqldelight.github.io/sqldelight/
- Bitcoin descriptors: https://bitcoin.org/bip/380/
- Esplora API: https://github.com/Blockstream/esplora/blob/master/API.md
