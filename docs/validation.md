# Foundation validation

Baseline: Kotlin 2.4.21, Compose Multiplatform plugin 1.12.2, AGP 9.2.0, Gradle 9.4.1, JDK 21, Android compile/target SDK 37 and min SDK 26.

## Reproduce

Install JDK 21 and Android SDK platform 37 (`platforms;android-37.0`)/build tools 36.0.0. Configure a consistent SDK location with `ANDROID_HOME` or `local.properties`; if `ANDROID_SDK_ROOT` is present it must agree.

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
