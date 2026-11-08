# Foundation validation

## Release acceptance (M6) — 2026-11-08

- **Local suites:** 411 core JVM, 149 shared UI and 30 desktop tests, zero failures; ktlint clean; R8 release APK (7.7 MB, libsecp256k1 for four ABIs) and the self-contained desktop distribution build.
- **Android 15 emulator (x86_64):** `ReliabilityInstrumentation` 21 checks, 0 failures. The release APK ran first run, Main with live market data, the live Kraken order book, and an xpub import that derived 83 addresses and synced history.
- **Desktop:** the packaged distribution started with its own runtime under Xvfb with an isolated data folder and stayed up; rendering fell back to software, as expected there.
- **Not run:** hosted CI, a device TalkBack audit, the phone (still debug-signed; moving it to the release key needs a backup, uninstall and restore).

## JDK 25 build — 2026-11-01

The full CI task set passed locally with JDK 25 and Gradle 9.8.1, including JVM and Android host tests, Android lint, both APKs and desktop packaging. The Android Java target is 25. The desktop process started under Xvfb; window creation was not independently verified. No Android device or emulator was connected, and hosted CI has not been run for this change.

## Large-wallet snapshot limit — 2026-10-30

The generated 100,000-confirmed-transaction fixture completes two Bitcoin refreshes under a 64 MiB JVM heap. Loading an unfiltered portfolio snapshot afterward exhausts that heap, before the network-filtered snapshot can run. A 10,000-transaction run with the network filter returned all 10,049 expected activities under the same heap limit. The full snapshot and network-filtered 100,000-transaction acceptance gate remains open; pagination of the displayed list does not by itself bound the ledger projection.

The same 100,000-transaction fixture replayed its complete activity stream into holdings under 64 MiB and produced the exact 100,000-unit balance. This fixture has no user corrections or duplicate decisions. The subsequent full snapshot still exhausted the heap. Android's disposable native SQLite harness passed 11 checks, including streamed holdings rollback and replay.

The fixture also replayed classifications and corrections for 100,049 activities in bounded batches under the same heap limit. The fixture has no duplicate or transfer decisions, and this result does not validate a globally reconciled snapshot. Android's native harness passed 12 checks, including batched classification rollback.

A transaction-scoped scratch projection reconstructed all 100,049 fixture activities under the same JVM heap limit. The full snapshot still exhausts the heap. The projection's correction, stale-input and cleanup tests run on JVM.

The scratch projection resolves duplicate and transfer decisions globally, using the same matching logic as the existing replay. JVM parity tests cover chained and conflicting decisions, shared movement claims, and 150 decisions across page boundaries. The Pixel's native harness passed 13 checks, including duplicate and transfer projection. Snapshot integration remains open. The 100,000-transaction fixture contains no duplicate or transfer decisions and still fails when the existing full snapshot loads; it does not establish large-scale decision performance.

## Disk-backed Bitcoin graph integration — 2026-10-29

The integrated run passed formatting, Android lint and both APK builds, with **184 core JVM, 29 Android host, 18 shared UI and 7 desktop tests**, zero failures/errors/skips: `BUILD SUCCESSFUL in 25s`, 173 tasks. Tests cover staged graph and UTXO comparison parity, delta persistence, unchanged refresh row growth, old archives, reorgs, recovery, scope safety and bounded review.

The API 29 emulator passed **9 native checks with 0 failures**, including the Bitcoin accounting workflow and encrypted graph restore through the production schema-6 path. Large constrained-memory end-to-end stress testing and independent real-chain acceptance remain open.


## Scoped UTXO review — 2026-10-29

At `f7dd1ce`, the integrated run passed formatting, Android lint and APK assembly, with **166 core JVM, 29 Android host, 17 shared UI and 7 desktop tests**, zero failures/errors/skips. The serial stable-source repeat succeeded with 173 tasks. Tests cover paginated comparisons, scope changes, missing-parent deduplication and a watched account changing during an in-flight scan.

The API 29 emulator passed **9 native checks with 0 failures**, including Bitcoin accounting, graph persistence and encrypted restore. The Pixel was disconnected, so this build was not installed on it. Native checks do not separately exercise the comparison dialog.


## Explicit Bitcoin history recovery — 2026-10-29

The integrated recovery run at `be94d00` passed formatting checks, **162 core JVM, 29 Android host, 16 shared UI and 7 desktop tests**, Android app/test APK assembly and Android lint: `BUILD SUCCESSFUL in 18s`, 173 tasks. All 214 tests report zero failures/errors/skips. Recovery-specific tests cover consent/acknowledgement, dispatch/busy state, deep and lower-tip recovery, failed validation, atomic baseline rollback and portable restore. The standalone Android formatter also passes after wrapping two host expressions.

The same build passes **9 native API 29 checks with 0 failures**, covering storage, Bitcoin graph/accounting and encrypted restore. Recovery-specific scenarios run on JVM; native instrumentation does not separately exercise the recovery flag or confirmation UI. No personal-address recovery was performed.


## Durable Bitcoin graph and operational chain acceptance — 2026-10-29

The integrated graph/reorg run passed `ktlintCheck`, core JVM and Android-host tests, shared UI and desktop tests, both Android APK builds and Android lint: `BUILD SUCCESSFUL in 25s`, 173 tasks. XML reports contain **155 core JVM, 29 Android host, 15 shared UI and 7 desktop tests**, with zero failures, errors or skips. A final added regression for a chain change during block validation passed with **156 core JVM tests** and formatting checks. The Android transport file also passes the standalone ktlint CLI.

New tests cover persisted outpoints/scripts/states, reopen, scoped membership, possible and confirmed replacement evidence, version-4 archive restore into schema 5, graph/ledger rollback, conflict descendants, confirmation/reorg/disappearance revisions, and operational shallow/deep/lagging-chain acceptance. The shared financial workflow preserves its exact valuation/FIFO/CSV assertions and tests a shallow reorg with an unchanged ancestor; its encrypted restore compares persisted graph evidence too.

The API 29 disposable emulator reports **9 native checks, 0 failures**, including the shared Bitcoin accounting and graph-restore workflow. The green APK was installed and opened on the Pixel's current user 10 after verifying that sync was idle; the package update preserves records. These results establish deterministic storage and application paths; they do not establish independent real-chain completeness or close full-history recovery UI, bounded-memory normalized graph construction, hosted CI, or remaining product/device gates. See [chain evidence](bitcoin-chain-state.md).

Baseline: Kotlin 2.4.21, Compose Multiplatform plugin 1.12.2, AGP 9.2.0, Gradle 9.8.1, JDK 25, Android compile/target SDK 37 and min SDK 26.

## Reproduce

Install JDK 25 and Android SDK platform 37 (`platforms;android-37.0`)/build tools 36.0.0. Configure a consistent SDK location with `ANDROID_HOME` or `local.properties`; if `ANDROID_SDK_ROOT` is present it must agree.

```sh
./gradlew ktlintCheck :core:jvmTest :core:testAndroidHostTest :androidApp:lintDebug :androidApp:assembleDebug :desktopApp:packageUberJarForCurrentOS
./gradlew :desktopApp:run
```

CI pins action revisions, validates the Gradle wrapper, verifies tests and static analysis, and builds both applications on Linux. It uploads the debug APK and desktop JAR. The wrapper verifies its distribution SHA-256. Dependency versions are centralized in the version catalog.

## Local evidence — 2026-10-28

Full verification command above passed with JDK 21.0.13 on Linux x86-64: `BUILD SUCCESSFUL in 33s`, 125 actionable tasks. Results:

- `ktlintCheck`: passed across all four modules and Gradle scripts.
- `:core:jvmTest`: two common tests passed, zero failures or errors.
- `:core:testAndroidHostTest`: the same two common tests passed, zero failures or errors.
- `:androidApp:lintDebug`: “No issues found.” Warnings are treated as errors.
- `:androidApp:assembleDebug`: APK produced with min SDK 26 and target SDK 37.
- `:desktopApp:packageUberJarForCurrentOS`: Linux x86-64 runnable JAR produced.

Outputs are ignored build artifacts:

- `androidApp/build/outputs/apk/debug/androidApp-debug.apk` — SHA-256 `b8cfab5827174acd21945310fc27ef482e3ceb0524d1cabd9481167f3738b9ec`.
- `desktopApp/build/compose/jars/desktopApp-linux-x64-1.0.0.jar` — SHA-256 `cfad782d2e31d7a228da6dd8a41df5000121021dbd5dbc3c211e98d18c46a99a`.

APK metadata inspection confirms no Internet permission. AndroidX contributes a package-scoped receiver permission.

An isolated SDK was installed under `/tmp/polion-android-sdk` to provide the project SDK platform 37 and build tools 36.0.0. The system SDK was not changed.

Android runtime validation requires a connected device or an emulator. `adb devices -l` found no connected device; this laptop has no installed emulator or configured AVD. Android launch therefore remains unverified locally.

The initial AGP 9.0.1/Gradle 9.1.0 candidate was rejected by Android AAR metadata checks: Compose 1.12.2 resolves AndroidX 1.12.1 components requiring compile SDK 37 and AGP 9.1.0 or newer. The selected baseline satisfies those requirements.

Desktop launch passed with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktopApp:run --offline`: the process emitted `polion:APPLICATION_STARTED` and X11 window inspection identified `WM_NAME="Polion"`. The validation process was then closed. Window creation was verified; the screen contents were not visually inspected.

## Milestone A phases 2–8 — 2026-10-28

Final integrated verification at `f96f9db` ran the full reproduce command above with JDK 21 and the isolated SDK: `BUILD SUCCESSFUL in 9s`, 131 actionable tasks (22 executed, 109 up-to-date). JVM XML reports contain 32 tests and Android host reports contain 10 tests, with zero failures, errors or skipped tests. Android lint reports “No issues found.” The debug APK and desktop runnable JAR both build with the shared SQLite/core dependency graph.

The acceptance map and subsystem decisions are in `milestone-a.md`. Tests include the independent large-integer oracle, 100-address ownership deduplication, persisted ownership, transactional migration rollback/retry, corrupt/future/missing schemas, concurrent ingestion, canonical provider identity, snapshot reopen, append-only ledger revisions, fake Bitcoin/EVM/exchange synchronization and ledger replay, provider routing, cursor cycles, atomic page/checkpoint rollback, process recovery, stale-worker fencing, cancellation, rate limits and competing connections.

SQLite integration tests execute the desktop JDBC driver. The Android driver compiles into the APK; device-level SQLite and lifecycle validation remain unverified because no Android device/emulator is available. Hosted CI has not been executed in this session. Database encryption is an explicitly documented interim limitation. Desktop SQLite snapshots are supported; portable encrypted backups and Android backup integration belong to later work.

## Milestone B phases 9–14 — 2026-10-28

Final integrated verification at `f1c4052` ran the full reproduce command above offline with JDK 21 and the isolated Android SDK: `BUILD SUCCESSFUL in 17s`, 133 actionable tasks (38 executed, 95 up-to-date). Core tests had passed immediately beforehand after the final source changes; the full run reused those results. XML reports show **59 JVM tests and 19 Android host tests**, zero failures/errors/skips. Formatting, Android lint, APK assembly and desktop runnable JAR packaging passed. Android lint reports no issues.

`docs/milestone-b.md` maps all six phases to implementation and tests. The financial harness exercises new-chain and exchange providers through raw ingestion, normalized activity persistence, retained user corrections after decoder replay, scoped holdings, market prices and exact FIFO accounting. A 10,000-activity benchmark measured initial materialization, two incremental refreshes and full replay at **1,332 ms**; it asserts equality rather than a machine-specific speed threshold.

Backup tests cover encryption/authentication, tamper and wrong-password rejection, corrupted exports, interrupted transaction rollback, staging schema-version/column/table checks, import-key conflicts and idempotence. Whole-state restore preserves ownership/portfolio relationships, annotations, reviewed duplicate decisions and unknown basis; restored effective activities replay through FIFO accounting. Mixed accounting quote currencies are rejected. Historical manual-price withdrawal and schema migration are tested.

Android host tests validate common logic, not device crypto, SQLite driver execution, filesystem replacement or background lifecycle. Android device runtime and hosted CI remain unverified locally. The application UI is the welcome shell; no real provider integration or jurisdictional tax compliance is claimed. Live SQLite data is plaintext; exported backups support authenticated encryption. Kotlin emits the expect/actual-class beta warning for the platform backup crypto binding; both targets compile.


## Milestone D work in progress — 2026-10-28

The integrated run before short-landscape refinements passed formatting, **80 core JVM tests, 27 Android host tests, 5 shared UI tests and 1 desktop test**, Android lint, both APKs and desktop packaging: `BUILD SUCCESSFUL in 18s`, 175 actionable tasks. UI tests exercise 360×800, 800×900 and 1440×1000 surfaces; manual activity and price persistence; correction/search/CSV export; source settings while the worker dispatcher is blocked; and reversible account removal. Follow-up verification for 800×360 landscape navigation and projection-cache refinement passed **all 6 shared UI tests**, formatting, Android lint and desktop packaging: `BUILD SUCCESSFUL in 14s`, 110 tasks.

The Android API 29 emulator executed `io.polion.android.ReliabilityInstrumentation` with **checks=4, failures=0**. It uses disposable databases and the actual Android driver/crypto bindings for persisted projections/reopen, wrong-password staging restore, SQLite-full transaction rollback/integrity and password verification. The harness builds with `:androidApp:assembleDebugAndroidTest`; run it using `adb -s <emulator> shell am instrument -w io.polion.android.test/io.polion.android.ReliabilityInstrumentation` and inspect the failures count.

A separate emulator UI check configured a privacy lock, force-stopped and reopened Polion, rejected a wrong password, accepted the correct password and removed the test lock. An unused public-address fixture sync was started, then the emulator switched to Home. `dumpsys activity services io.polion.android` showed `BitcoinRefreshService` with `isForeground=true`; returning to the app showed progress and subsequently **Sync complete**. This validates the foreground-service transition and a real zero-history source request, not a full Bitcoin financial fixture.

The Pixel 8 Pro is connected on Android 17. Polion is installed and opens in the current Main profile (user 10); package updates preserve data. A user-provided address scan on an earlier product build fetched 17,527 raw records and failed before materialization; no successful financial sync is claimed from that attempt. Source settings remained saved. The foreground-service/account-removal update was subsequently installed in Main. Phone interaction is left to the user during development.

Current outstanding Gate D evidence and implementation requirements are listed in [milestone-d.md](milestone-d.md). Hosted CI has not run in this session and no changes have been pushed.


### Offline and periodic-worker runtime checks

On the API 29 emulator, airplane mode was enabled before starting sync through the normal source dialog, followed by switching to Home. Sync completed with the fixed message “The provider could not be reached. Check your connection and endpoint.” A consistent SQLite snapshot confirmed all four fixture activity revisions and every ledger movement/source row were unchanged. Airplane mode was disabled in cleanup. This check did not alter the connected Pixel.

Periodic refresh was enabled for the emulator’s unused public-address fixture. WorkManager logged `RETRY` while the manual foreground sync was active, then `SUCCESS` for `io.polion.android.RefreshWorker`. Android JobScheduler registered the next network-constrained job with a minimum latency of approximately 30 minutes. The emulator’s periodic schedule was disabled after validation. These checks verify offline preservation and real scheduled-worker execution; process death during an active network scan remains a separate acceptance case.


### Wallet, secure storage and interrupted-scan checks

The Android emulator’s native harness reports **checks=6, failures=0**, including published Bitcoin public derivation/address/checksum vectors and disposable Android Keystore encryption/tamper tests. Wallet parser/discovery tests and the wallet dialog test pass on JVM. Desktop secure-storage tests use an injected command adapter; actual Secret Service integration is not claimed from those tests.

An active fixture scan was force-stopped after its persisted in-progress marker was observed. After emulator restart, the UI displayed **Sync interrupted** and the ledger matched the saved pre-interruption fixture. The endpoint remained saved; retry completed, cleared the interruption marker/error and left the original ledger unchanged. The first UI assertion was interrupted by emulator disconnection, so the restarted state and recovery were explicitly rechecked before recording this result.


### Desktop distribution and representative UI workload

The merged desktop JAR is regenerated without dependency archive signature files, which cannot authenticate a merged archive. Runtime loading initially exposed a signature digest failure despite successful packaging; after regeneration, the actual Secret Service adapter passed disposable store, lookup, password verification and clear operations. The local marker contained no verifier, and the fixture entry/directory were removed.

The shared UI workload seeded 10,000 additional activities, projected and rendered the portfolio, opened paginated history, and searched for one exact ID. All assertions passed; the full seed/projection/render/search sequence measured **3,225 ms** on this machine. It asserts pagination and search correctness rather than a machine-specific latency threshold. Multi-movement correction/reset and the current accessibility/layout tests also pass.


The JVM `BitcoinAccountingWorkflowTest` exercises consented history fetch, raw provenance, confirmed holdings, explicit treatments, block-order verification across decreasing timestamps, and authenticated backup restore. Before verification, basis is unknown. Afterwards the fixture disposal has basis9USD and gain81USD, the fee consumes basis1USD, and the remaining lot retains basis100USD. Restored accounting and CSV match exactly. The targeted test and formatting pass; this is a deterministic provider fixture, not a claim about independent chain verification.


### Shared Bitcoin accounting and final integration

The same `testSupport` Bitcoin workflow runs against desktop JDBC and native Android SQLite. The native harness reports **checks=7, failures=0**. It verifies decreasing block timestamps, exact FIFO disposal/fee basis, ordinary resync retention of verified order, reorg invalidation and re-verification, and encrypted restore/CSV parity. These are deterministic provider fixtures, not independent chain verification.

The final integrated run passed formatting, core JVM and Android host tests, shared UI and desktop tests, Android lint, both APK builds and desktop packaging: **BUILD SUCCESSFUL in 27s**, 175 actionable tasks. The validated APK was installed successfully on the Pixel and opened in Main profile user 10, preserving existing app data. No phone sync was active at installation. Hosted CI remains unexecuted.


### Final responsive review and upgrade acceptance

The final packaged desktop application was launched with disposable sample data and its renders inspected at **360×800, 800×900, 1440×1000 and 800×360**. Layouts use compact bottom navigation or a scrollable sidebar, appropriately stacked/wide content and vertically scrolling pages. Interaction tests also cover 320 dp width at 1.5× text. A full screen-reader audit has not been performed. Direct Xvfb capture was used; no personal desktop or phone contents were captured.

An emulator in-place APK update preserved **all 37 application tables**, including four fixture activity revisions. SQLite integrity passed before and after installation, and reopening preserved ledger/application records. The final XML reports contain **84 core JVM, 28 Android host, 10 shared UI and 3 desktop tests**, with zero failures, errors or skips. The current requirement-by-requirement acceptance map is in [Milestone D](milestone-d.md). Separate Milestone C chain verification and later production hardening are not certified by this product acceptance.

## Assessment remediation: account state, erasure and scoped activity

The integrated JDK21 run passes 91 core JVM tests, 28 Android-host tests and 14 shared UI tests, with ktlint checks, Android lint, app and test APK assembly. New regressions cover nested archive intents/reopen/rollback, active SQLite data erasure under DELETE/PERSIST/TRUNCATE/WAL, and exact scoped activity amounts/directions. API29 emulator instrumentation passes 8 checks with 0 failures, including native data erasure under DELETE/PERSIST/WAL. Native erasure verifies private-note canaries are absent from active database/journal files, with integrity retained. The connected phone was not modified. These checks establish the repaired behaviors, not completion of all roadmap gates.

## Integrated UTXO advisory and historical-price workflow

The 2026-10-29 integrated JDK21 run passes 133 core JVM tests, 29 Android-host tests, 15 shared UI tests and 7 desktop tests, with Android lint, app APK and native test APK assembly. The shared financial fixture spends a height42 coinbase at height142, retains decreasing block timestamps and exact FIFO/fee/CSV/restore assertions, and checks UTXO match-to-mismatch-to-match advisory state. A graph regression rejects a spend one block before maturity and accepts the maturity boundary.

API29 emulator instrumentation passes 9 checks with 0 failures, including the shared Bitcoin financial workflow and exact historical-price persistence after consent revocation. Core regressions cover explicit consent, exact provider valuation/manual priority/offline reuse, missing/error prices, request pacing and cancellation after erasure or consent replacement. Desktop transport tests verify sensitive header placement and access/rate-limit failures. UI tests cover historical date/consent flow. Live CoinGecko access with a personal Demo key has not been exercised; fake transports and native persistence establish the local call paths.

Local evidence: `/tmp/polion-integrated-prices-utxo-final.log` and `/tmp/polion-native-prices-utxo.txt`. These checks do not close the remaining descriptor audit, durable Bitcoin graph, operational reorg/RBF, independent chain acceptance, rule-management and broader connector-execution requirements.

## Automatic hourly price verification

On the Pixel 8 Pro, a disposable Android database fetched live public Kraken hourly Bitcoin candles, stored the completed hours and passed the native reliability harness: 15 checks, 0 failures. The fixture did not access the user's portfolio. A separate deterministic Android check covers cancellation rollback. This verifies the Android network and storage path; it does not verify WorkManager scheduling or the user's portfolio chart.

## Revised frontend — 2026-11-02

The integrated JDK25 run passes **270 core JVM, 47 Android-host, 81 shared UI and 28 desktop tests** (426 total; zero failures, errors or skips). Formatting checks, Android lint, app/test APK assembly and desktop JAR packaging pass: `BUILD SUCCESSFUL in 1m 27s`, 182 tasks. Evidence: `/tmp/polion-rewrite-integrated-final.log` and the module XML reports.

The shared UI suite captures **360×800, 390×844 and 1440×900**, plus 390×844 at 1.5× font scale. Main, Portfolio/holdings, all four coin tabs, Insights/allocation/exports, Accounts and Settings/appearance are captured, including scrolled sections. Rendered PNGs under `sharedUi/build/rewrite-previews` were inspected for navigation, readable metrics, holdings alignment, horizontal tab access, enlarged text and floating-action clearance. Consecutive mouse-click filter tests verify save notifications leave controls accessible. The packaged desktop JAR also launched with disposable demo data under software rendering on Xvfb; its rendered window was inspected.

New regression evidence covers market prices without holdings or ownership, exact per-unit historical quotes and manual-price priority, market-only detail without owned/gain figures, persisted favorites, first-use manual-account creation, exact one-satoshi records, manual payment/fee roles and UTC timestamps, unsupported classifications and invalid-input rejection, recreation of asset/account/classification/quantity, price-only queued/running duplicate suppression, explicit consent for additional portfolio currencies, and portable refresh preference migration/restore. Existing core/host financial, backup, ledger, correction, archive/reconciliation and export checks remain green. These tests use disposable data and deterministic provider fixtures; no personal portfolio was read or refreshed.

`adb devices -l` was empty at both device checks. No phone installation/launch or native reliability instrumentation was performed for this rewrite; Android build and host tests are separate evidence from native execution. Hosted CI, a full screen-reader audit and the remaining roadmap gates are not certified by this run. Current navigation and preserved workflow locations are documented in [frontend behavior](frontend-rewrite.md).

## Shared presentation Phase 1 — 2026-11-02

At source commit `11f91ea`, formatting and strict lint passed. The full shared UI and desktop run from the preceding visual polish passed 95 shared UI and 28 desktop tests; the latest focused form, restoration and production-capture run passed 12 tests. The separate model capture test passed and generated 140 fixture PNGs. Android debug lint and APK assembly, plus desktop packaging, passed. Latest logs: `/tmp/eth-outline-format.log`, `/tmp/eth-outline-lint.log`, `/tmp/eth-outline-tests.log`, `/tmp/eth-outline-model.log`, and `/tmp/eth-outline-platform.log`.

The [comparison gallery](/tmp/polion-phase1-comparison/index.html) provides 140 mapped Phase 0/current fixture comparisons, 140 directly linked current model previews, and 108 production binding captures. All mappings are marked compared, and archive CRC validation passed for `/tmp/polion-phase1-comparison.zip`. User visual review is pending; Phase 1 exit is not complete. No Android device execution or native desktop interaction review is claimed. See [Phase 1 evidence](ui-model-phases/phase-1-evidence.md) for the current scope and remaining gates.

## Frontend Phase 2 — 2026-11-03

Portfolio and coin General bindings passed 449 automated tests, strict core/shared/model formatting, Android lint/debug/test APK builds and desktop packaging. The connected Pixel was updated in Android user 10 without clearing data; the disposable native harness passed 17 checks with zero failures and the app was reopened. USD displays use two decimals without changing stored precision. Global Main scope, portfolio return restoration and reserved notification space have regression coverage. See [Phase 2 evidence](ui-model-phases/phase-2-evidence.md). User visual review remains pending; later roadmap and release gates remain open.

## Frontend Phase 3 — 2026-11-03

Core `6803895` and UI `f0163f6` passed 460 automated tests (277 core JVM, 47 Android host, 107 shared UI, 28 desktop, one model) with zero failures/errors/skips. Strict core/shared/model formatting, Android lint/APK/test APK and desktop packaging passed. The connected Pixel was updated without clearing data in current Android user 10; the disposable native suite passed 18 checks with zero failures, including linked manual trades, internal/external transfers, rewards, withdrawals, third-asset fees, rollback and backup restore. MainActivity was confirmed resumed. The final source-compatible native APK rebuild passed after discarding an unrelated formatter-only change. Logs: `/tmp/phase3-delivery-{lint,full,model,platform,native}.log` and `/tmp/phase3-delivery-platform-native-rebuild.log`.

Shared workflows cover phone/desktop submission and retry, edit/reversal, scoped weighted averages and export, saved review/draft/filter state, and quote-identity/fee-conversion evidence. Review artifacts contain 117 seeded captures at the requested viewports with scrolled cards and enlarged text. See [Phase 3 evidence](ui-model-phases/phase-3-evidence.md). User visual approval and later roadmap/release gates remain open.

## Frontend Phase 4 — 2026-11-04

The pending chart-selection fix was committed first as `d12456c` after focused presenter tests. Phase 4 core `539c328`, UI `0cd403c` and platforms `1e8eebe` passed 467 automated tests (280 core JVM, 47 Android host, 111 shared UI, 28 desktop, one model), with zero failures/errors/skips. Formatting, Android lint/APK/test APK and desktop packaging passed. Logs: `/tmp/phase4-final-{lint,full,model,platform}.log`.

The Pixel was updated in current Android user 10 without clearing data. Disposable native instrumentation passed 20 checks, zero failures, including the independently recorded public-transaction account workflow and periodic scheduling constraints/cadences/cancellation. MainActivity was confirmed resumed. Native log: `/tmp/phase4-final-native.log`. This run verifies queued scheduling store/reopen and cancellation rather than claiming a new forced-process-death scheduling audit. Source acceptance remains provider-based; no incremental network fetching is enabled. See [Phase 4 evidence](ui-model-phases/phase-4-evidence.md), [public fixture provenance](phase4-public-fixture.md), and [incremental project](incremental-bitcoin-history.md). The 118 seeded captures await user visual review; broader financial/chain/release acceptance remains open.

## Queued wallet discovery service fix — 2026-11-05

Android manual service starts enqueue every captured operation on the serial executor. Each completion calls `stopSelfResult(startId)` so an older request cannot stop later work or remove its foreground notification. The bounded wake lock is acquired once per service instance. Discovery acknowledgement describes a request that can wait behind current work; rejected starts direct the user to Accounts rediscovery and preserve another operation's running status.

Affected Android formatting, `lintDebug`, debug/test APK assembly and release assembly passed (`/tmp/discovery-fix-format2.log`, `/tmp/discovery-fix-build.log`, `/tmp/discovery-fix-release.log`). APK manifests were checked: the probe activity/service are present only in debug and absent from release.

The native suite adds `queuedWalletDiscoveryService`: a debug-only foreground-service fixture holds a history request, queues two wallet discoveries, fails the first request, and checks both wallet IDs run in FIFO order without early service destruction. It uses an isolated executor and no database or transport. The updated 21-check native suite compiled but **was not executed**: `adb devices -l` found no device/emulator, and the local SDK has no emulator runtime/system image. Phone installation is pending an available device. Earlier Phase 4 native results do not cover this fix.
