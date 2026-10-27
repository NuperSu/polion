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
