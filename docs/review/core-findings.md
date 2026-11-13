# Core findings

Line references are as of commit 4a77ffd; the code has since been restructured (see docs/architecture.md).

Paths are relative to `core/src/commonMain/kotlin/io/valroot/core/` unless stated. `PA` = `application/PortfolioApplication.kt`.

## CORE-1 · P1 · correctness · [V] — Display currency and accounting currency are conflated

- Where: `PA:694` (`require(quotes.all { it.asset == quote … })`, caught at `PA:697`); trigger in `sharedUi/…/ValrootApp.kt:541-549`.
- Scenario: treatments record USD cost/proceeds; the user taps the headline currency and picks EUR. `accountingReport` now marks every USD-quoted activity invalid and drops its asset from FIFO. Basis and gains become unknown with no explanation.
- Fix: account in each treatment's own quote (convert only for display with explicit FX evidence), or warn and block the switch while treatments exist.
- Verify: save a Buy in USD, switch the portfolio to EUR, open coin breakdown.

## CORE-2 · P1 · data safety · [V] — Export can produce archives restore rejects

- Where: `backup/Backup.kt:54` (100,000,000 chars), `:116` (1,000,000 rows/table), `:162` (10,000,000 fields); `jvmMain|androidMain/…/BackupEncryption.kt:47` (100,000,000 bytes). `export` (`Backup.kt:30-47`) and `encrypt` check none.
- Scenario: a large wallet's raw payloads and graph tables exceed a limit. Export reports success; restore fails with a generic error. Export also holds several full copies in memory inside one transaction.
- Fix: enforce the same limits at export (or stream and raise them); add a scale round-trip test.

## CORE-3 · P1 · race / lost update · [V] — Discovery writes back a stale ownership snapshot

- Where: `PA:1971` (`ownership.load()`), network loop, then `PA:2050` (`ownership.replace(catalog.copy(…))`); `storage/OwnershipRepository.kt` `replaceInTransaction` deletes and reinserts every ownership table. `updateOwnership` (`PA:2063`) is also load-then-replace in two transactions.
- Scenario (desktop): discovery runs on `valroot-bitcoin-sync` with its own connection for minutes at 1 request/s. Any account, owner, portfolio or target saved meanwhile is reverted. Android hides it only because all work shares one executor (PLAT-1).
- Fix: re-load inside the final `db.transaction`, merge only the child targets, re-check wallet, scope and consent as `PA:1143` does.

## CORE-4 · P2 · correctness · [V] — Pending Bitcoin transactions are dated 1970

- Where: `bitcoin/Esplora.kt:156-157` (`time = 0` when unconfirmed), used as `occurredAt` in `BitcoinDecoder.decode`; rendered by `sharedUi/…/TransactionTimeline.kt:210-212`.
- Scenario: coin Transactions shows "1970-01-01T00:00:00Z · pending · Imported" and sorts it last.
- Fix: use observation retrieval time for pending display/order.

## CORE-5 · P2 · correctness · [V] — Example data never goes away

- Where: `PA:2255` sets `sample_data`; only `wipe()` clears it. `PA:2355-2367` records manual BTC/USD prices; `market/MarketRepository.kt:338` ranks manual before provider regardless of age.
- Scenario: a user loads the example, later connects a real wallet. "EXAMPLE DATA" stays, example owner/account remain, and BTC keeps valuing at the stale $65,000 instead of fresh Kraken quotes. `useProviderPrices` exists but is hard to find.
- Fix: a "Remove example data" action; scope demo prices so they cannot override real ones.

## CORE-6 · P2 · consistency · [V] — Different freshness windows for the same quote

- Where: `snapshot` uses `pricePolicy(current = true)` (2 h, `PA:323`); `history`, `analytics`, `accountOverview` use the 24 h default (`PA:444-448, 526, 548, 1752`).
- Scenario: one BTC quote is FRESH in Accounts and STALE on the dashboard.
- Fix: apply `current = at >= knownAt - 24h` consistently, as `historySeries` does.

## CORE-7 · P2 · consistency · [V] — Filter semantics differ between list, timeline and export

- Where: `PA:299-305` (separate `any{}` for account and asset) vs `PA:2132` and `PA:2444-2447` (same movement must match both).
- Scenario: account=A, asset=ETH; an activity with A:BTC and B:ETH is counted on screen but missing from export.
- Fix: one shared predicate.

## CORE-8 · P2 · UX / atomicity · [V] — Any ownership edit forgets the last successful sync

- Where: `PA:1824, 1847, 1925, 1959, 2051` delete `source_last_success`, often in a separate transaction from the edit.
- Scenario: renaming a target or toggling it drops coverage to UNKNOWN and the banner reads "Not synced yet" despite accepted history.
- Fix: invalidate only when the active address scope actually changes, inside the same transaction.

## CORE-9 · P2 · invariant · [V] — Zero provider price stored as FRESH

- Where: `market/CoingeckoPrices.kt:30-31`, regex `(0|[1-9][0-9]*)…` accepts 0; `parseChart` and Kraken require > 0.
- Fix: reject sign ≤ 0. Whether manual price 0 is intentional in the UI is an open question [R].

## CORE-10 · P2 · validation · [V] — Display currency accepts any asset

- Where: `PA:2212-2219` only checks the asset is registered (BTC passes). `MarketRepository.lookup` has an unguarded `require(base != quote)`.
- Fix: require FIAT kind.

## CORE-11 · P2 · performance · [V] — Full integrity check on every open

- Where: `storage/Storage.kt:124-125`; every worker/service run constructs `PortfolioApplication` (e.g. `RefreshWorker.doWork`, `performRefresh`).
- Fix: once per app start, or `PRAGMA quick_check`.

## CORE-12 · P2 · performance · [V] — Algorithmic hot spots

- `saveOverlay` loads the whole ledger to check one ID (`PA:2173`; also `PA:1937`).
- `confirmed.filter { entry !in bitcoin … }` uses deep data-class equality on a list (`PA:705-708`).
- FIFO `take()` re-filters and re-sorts all lots on every disposal (`accounting/Fifo.kt:134-138`).
- `OwnershipRepository.load()` runs one tag query per entity and is called 3+ times per snapshot.
- [R] `Reconciliation.suggest` is O(n²) over the ledger (`reconcile/Overlay.kt:90-114`).

Separate from the tracked snapshot OOM.

## CORE-13 · P2 · security hardening · [V] — Backup KDF parameters

- Where: `BackupEncryption.kt:11` — PBKDF2-HMAC-SHA256, 310,000 iterations (OWASP currently recommends 600,000). Count not in the header; minimum password length only enforced in UI. Salt, nonce, AAD and GCM use are fine.
- Fix: versioned header with KDF parameters; enforce length in core.

## CORE-14 · P3 · quality · [V]

- `sync/Sync.kt:159-167` swallows all exceptions; a user cancellation is recorded as a provider failure.
- `CoingeckoPrices.kt:169` formats the URL date with locale-dependent `%02d`.
- `analytics()` hard-codes coverage UNKNOWN (`PA:548`) while `snapshot()` reports PARTIAL (`PA:290`).
- `ledger()` reads `activities.current()` outside one transaction before caching — torn read possible on desktop.
- Three diverging "account in portfolio scope" implementations (`PA:402-425`, `holdings/Holdings.kt:275-299`, `ownership/Ownership.kt`).
- `BackupEncryption`, `BitcoinWalletCrypto`, `Sha256` are byte-identical in jvmMain and androidMain.

## CORE-15 · P3 · unwired code · [V unless marked]

- `Logger`/`ConsoleLogger` has no production caller although README describes fixed-event logging.
- Test-only: `BitcoinScanReconciler` (unsafe if rewired — reverses absent activities unscoped), `FailoverEsploraTransport` (milestone-a lists failover as delivered), `DashboardWidgetDialog` and dashboard-widget settings.
- Written, never read: `sync_sequence` (`PA:1033`), `utxo_mismatch_count` (`PA:1160`).
- `BitcoinWalletDiscovery.usedAddresses` is computed and dropped by both hosts (see PLAT-3).
- [R] Test-only: `PreparedTransaction.notes`, `HoldingsRepository.observe/reconcile`, `ImportJournal`, `BitcoinBlockOrdering`, `EsploraUtxoFetcher`.

## CORE-16 · P3 · [R]

- CoinGecko observation IDs include a random nonce, so repeated fetches append duplicate rows.
- A device clock behind the provider rejects the whole CoinGecko response.
- Kraken "Rate limit exceeded" in a 200 body is not mapped to the cooldown.
- `treatments()` swallows `IllegalArgumentException`; `TreatmentRepository.load` can throw `IllegalStateException`, aborting the report.
- `saveTreatments` skips validation for non-CONFIRMED activities.
- A classification rule with no matchers matches everything.
- Future-dated manual entries appear in `holdings.current` but not `historical(now)`.
- `ExactInteger.parse` compiles a Regex per call; division is digit-by-digit.

## CORE-17 · open question · needs repro

Manual Buy/Sell debits the quote asset (`application/TransactionDraft.kt`). Without a prior fiat deposit the quote balance goes negative and the headline total nets it. Confirm what the UI shows; consider a warning or a "paid from outside the portfolio" option.
