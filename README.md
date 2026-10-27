# Polion

A Kotlin Multiplatform, watch-only portfolio tracker for Android and desktop. The application displays a shared welcome screen and privacy boundary. Its shared core provides exact amounts, ownership scopes, SQLite repositories, raw provenance, a versioned activity ledger, connector contracts, and durable sync jobs. Real provider integrations and portfolio workflows are planned. Polion does not sign transactions or accept private keys.

## Build and run

Use JDK 21 and an Android SDK with platform 37 (`platforms;android-37.0`) and build tools 36.0.0. Set `ANDROID_HOME` to the SDK directory or set `sdk.dir` in untracked `local.properties`.

```sh
./gradlew ktlintCheck :core:jvmTest :core:testAndroidHostTest :androidApp:lintDebug :androidApp:assembleDebug :desktopApp:packageUberJarForCurrentOS
./gradlew :desktopApp:run
```

Open the project in Android Studio or install `androidApp/build/outputs/apk/debug/androidApp-debug.apk` on an Android 8.0+ device, then launch Polion.

The wrapper pins Gradle 9.4.1 with a distribution checksum. Dependency versions are in `gradle/libs.versions.toml`. CI uses JDK 21, installs the required SDK, validates the wrapper, runs checks, and uploads both host artifacts.

## Structure

- `core`: shared financial/identity primitives, ownership, transactional storage, provenance, ledger, connector contracts and synchronization; common tests run on JVM and Android host, with SQLite integration tests on JVM.
- `sharedUi`: shared Compose welcome screen for Android and JVM.
- `androidApp`: Android activity and manifest.
- `desktopApp`: Compose desktop window and runnable JAR packaging.

Logs contain fixed event names without free-form messages, exception payloads, or portfolio data. Android requests no network permission. There is no analytics or telemetry integration.

See [Milestone A acceptance](docs/milestone-a.md), [architecture](docs/architecture.md), [reference audit](docs/reference-audit.md), and [validation evidence](docs/validation.md) for scope and verification details.
