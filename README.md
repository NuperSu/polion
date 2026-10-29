# Polion

A Kotlin Multiplatform, watch-only portfolio tracker for Android and desktop. Its shared interface provides portfolio, account, public Bitcoin address, activity, reporting and settings workflows. The shared core provides exact amounts, ownership scopes, SQLite persistence, raw provenance, a versioned activity ledger, Esplora history, historical holdings, asset prices, FIFO accounting, user corrections, reconciliation and encrypted portable backup. Public descriptors and xpub wallets support bounded address discovery; see [supported wallet imports](docs/bitcoin-wallets.md). Full Bitcoin financial acceptance remains in progress. Polion does not sign transactions or accept private keys.

## Build and run

Use JDK 21 and an Android SDK with platform 37 (`platforms;android-37.0`) and build tools 36.0.0. Set `ANDROID_HOME` to the SDK directory or set `sdk.dir` in untracked `local.properties`.

```sh
./gradlew ktlintCheck :core:jvmTest :core:testAndroidHostTest :sharedUi:jvmTest :desktopApp:test :androidApp:lintDebug :androidApp:assembleDebug :desktopApp:packageUberJarForCurrentOS
./gradlew :desktopApp:run
```

Headless shared UI tests need an X display; use Xvfb with `SKIKO_RENDER_API=SOFTWARE` on Linux.

Open the project in Android Studio or install `androidApp/build/outputs/apk/debug/androidApp-debug.apk` on an Android 8.0+ device, then launch Polion.

When a phone and emulator are connected, select the phone explicitly. Find its serial with `adb devices -l`, then build and install into its current Android profile:

```sh
export ANDROID_SERIAL=YOUR_PHONE_SERIAL
phone_user=$(adb shell am get-current-user) || exit
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :androidApp:assembleDebug &&
  adb install --user "$phone_user" -r androidApp/build/outputs/apk/debug/androidApp-debug.apk &&
  adb shell am start --user "$phone_user" -n io.polion.android/.MainActivity
```

If Gradle selects a different SDK from the one you prepared, set `sdk.dir=/absolute/path/to/sdk` in local `local.properties`. The selected SDK must contain the required platform, build tools and accepted licenses. Keep `ANDROID_HOME` and `ANDROID_SDK_ROOT` consistent when setting both.

The wrapper pins Gradle 9.4.1 with a distribution checksum. Dependency versions are in `gradle/libs.versions.toml`. CI uses JDK 21, installs the required SDK, validates the wrapper, runs checks, and uploads both host artifacts.

## Structure

- `core`: shared financial/identity primitives, ownership, transactional storage, provenance, ledger, connector contracts and synchronization, asset/price registry, holdings, accounting, overlays and portable backup; common tests run on JVM and Android host, with SQLite integration tests on JVM.
- `sharedUi`: shared responsive Compose portfolio workflows for Android and JVM.
- `androidApp`: activity, foreground sync service and notification, document picker, WorkManager refresh and native reliability harness.
- `desktopApp`: Compose desktop window, file dialogs, scheduled refresh and runnable JAR packaging.

Source requests require consent and disclose watched addresses to the chosen HTTPS indexer. The live database is plaintext; the optional privacy lock is a screen barrier, while portable backups are encrypted. Logs use fixed event names without free-form messages, exception payloads or portfolio data. There is no analytics or telemetry integration.

See [Milestone D acceptance](docs/milestone-d.md), [financial-core acceptance](docs/milestone-b.md), [Milestone A acceptance](docs/milestone-a.md), [architecture](docs/architecture.md), [reference audit](docs/reference-audit.md), and [validation evidence](docs/validation.md) for scope and verification details.
