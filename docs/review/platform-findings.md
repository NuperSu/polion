# Platform findings (Bitcoin, Android, desktop, build/CI, docs)

Line references are as of commit 4a77ffd; the code has since been restructured (see docs/architecture.md).

## PLAT-1 · P1 · reliability · [V] — Android UI database work shares the sync thread

- Where: `androidApp/…/MainActivity.kt:99-100` (`executor = RefreshWorker.executor`, `dispatcher = executor.asCoroutineDispatcher()`); `BitcoinRefreshService.kt:25` (`workExecutor = RefreshWorker.executor`); `RefreshWorker.kt:44`.
- Scenario: a paced scan holds the single thread for tens of minutes. Every UI load and save, `unlock`, `setLockPassword`, `restoreBackup` and cold-start DB open queue behind it.
- Contradicts `sharedUi/…/PlatformActions.kt:49` ("hosts run provider requests on a separate worker"); desktop does this correctly via `BitcoinSyncQueue`.
- Fix: dedicated sync executor and connection (the debug `SyncServiceProbe` already uses one); then address CORE-3 on Android too.

## PLAT-2 · P2 · reliability · [V code; provider behaviour unverified] — Advisory UTXO fetch can fail the whole refresh

- Where: `core/…/PortfolioApplication.kt:1116-1119`, `bitcoin/BitcoinStagedUtxoComparison.kt:30` — `/address/{a}/utxo` with no catch. The KDoc in `BitcoinUtxoProvider.kt` says capped providers reject such requests.
- Scenario: a dust-heavy address exceeds the provider UTXO limit; every refresh fails at the comparison step even though history validated.
- Fix: record "comparison unavailable" and still apply verified history. Check against a live endpoint.

## PLAT-3 · P2 · UX / correctness · [V] — Wrong script type looks like an empty wallet

- Where: `bitcoin/BitcoinWallet.kt:33-37` (bare xpub → BIP84 wpkh); `RefreshWorker.kt:82-88` ignores the discovery result; `desktopApp/…/Main.kt:63-69` reports only "N addresses checked".
- Scenario: a legacy or nested-SegWit xpub, or a one-branch descriptor, finishes "complete" with zero or understated balance.
- Fix: warn on "0 used of N scanned" and on single-branch descriptors.

## PLAT-4 · P2 · Android scheduling

- [R] `RefreshWorker.kt:29` calls `setForegroundAsync(...).get()` unguarded in a periodic, non-expedited worker; on API 31+ background FGS starts can be refused. Evidence so far: API 29 emulator and one phone, no observed periodic run.
- [V] `RefreshWorker.kt:40` returns `Result.retry()` for every error, including permanent ones (reorg recovery required, lagging chain, oversized response), re-running heavy scans.

## PLAT-5 · P2 · reliability · [V] — Database reads on the UI thread

- Where: `sharedUi/…/ValrootApp.kt:260` (`app.assetListPreference()` in `remember`), `:525-527` (`app.marketFavorites()` in composition and click), `:370-371` (`refreshPrices` reads consent/currencies). [R] further host-preference reads in `Workflows.kt` and desktop `Main.kt`.
- Both drivers lock per connection for entire transactions, so the main thread/EDT blocks behind long exports, restores or snapshots (ANR risk).
- Fix: load through `host.dispatcher` or cache in the presenter.

## PLAT-6 · P2 · [R] — Privacy lock has no recovery path

If the Keystore key or Secret Service entry disappears, `unlock` always fails and `removeLockPassword` requires being unlocked. Offer erase-local-data from the lock screen.

## PLAT-7 · P2 · transport and SQLite

- [V] `androidApp/…/BlockstreamTransport.kt:77`: responses over 5 MiB throw `IllegalArgumentException` → generic "Could not finish sync", permanent for that address.
- [V] `:63-66, 83-88`: any non-2xx (including 502/503/504) → UNAVAILABLE "rejected the request" with no retry; TLS errors → OFFLINE.
- [V] No `journal_mode`, WAL or `busy_timeout` on either driver, while desktop opens three connections to one file. Impact needs a repro.
- [R] No chain-identity (genesis hash) check on custom endpoints.
- [R] More than 50 mempool transactions on an address loops on HISTORY_CHANGED (Esplora caps the mempool list).

## PLAT-8 · P3 · [V] — Order verification discards a resumable scan

`PortfolioApplication.kt:1198, 1251` call `bitcoinStage.clear()`, so an interrupted history scan restarts from zero.

## PLAT-9 · P3 · host details

- [V] `BitcoinRefreshService` returns `START_REDELIVER_INTENT`, replaying `recover_history` unattended; wake lock is 1 hour vs multi-hour paced scans.
- [V] `AndroidManifest.xml` locks the activity to portrait; Phase 8 lists landscape checks.
- [R] Android export opens with `"wt"` (truncates first); result dropped if the activity is recreated during the picker.
- [R] Desktop backup write is non-atomic and runs on the EDT.
- [R] Worker and service share notification ID 41.
- [R] No `FLAG_SECURE`/recents protection while the lock is enabled.
- [R] `secret-tool` resolved via PATH; desktop host-init failure swallowed; `XDG_DATA_HOME` ignored; restore password handled as `String`; `scheduleWithFixedDelay` stops silently after one exception.

## PLAT-10 · P3 · build/CI · [R]

- No release build type, minify or R8; CI builds and lints debug only.
- sqlite-jdbc, BouncyCastle, serialization versions inline in `core/build.gradle.kts`, bypassing the catalog.
- No dependency verification metadata or update bot.
- `:uiModel:test` not run in CI; `cancel-in-progress` also cancels main-branch runs.
- `.gitignore` lacks keystore, sqlite and hprof patterns.

## PLAT-11 · docs and workspace drift · [V]

- Local CLAUDE.md says JDK 21; AGENTS.md, README, CI, GOTCHAS say JDK 25.
- `docs/milestone-d.md` quotes stale test counts (84/28/10/3 vs 467 at Phase 4).
- `PlatformActions.kt:49` contradicts Android threading (PLAT-1).
- TODO.md, BUGS.md, AGENTS.md, CLAUDE.md are excluded via `.git/info/exclude`, so tracked history does not record the known OOM issue.
- TODO.md's newest entries are space-stripped and hard to read.
- `UI_TO_COPY/` holds six third-party (Delta app) reference screenshots — no personal wallet data seen — untracked; decide on licensing and ignore rules before any `git add .`.
