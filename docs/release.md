# Release 1.0.0 — limits and recovery

Valroot 1.0.0 is a local-first, watch-only portfolio tracker for Android (8.0 and later) and Linux desktop. It has no account and no server. This page states what the release supports and how to recover data.

## Builds

- **Android:** `./gradlew :androidApp:assembleRelease` builds an R8-shrunk APK. It is signed with the release key in `~/.config/valroot-release/` when that folder exists, and with the debug key otherwise (CI and other machines). An app signed with one key cannot be updated by an APK signed with the other: switching keys requires uninstalling, which erases the app's data, so restore from a backup afterwards. Keep a copy of the release key; without it, installed copies can no longer be updated.
- **Desktop:** `./gradlew :desktopApp:createDistributable` builds a self-contained folder with its own Java runtime (`desktopApp/build/compose/binaries/main/app/Valroot`). `packageDeb` builds a Debian package where `dpkg` is available; `packageUberJarForCurrentOS` builds a single JAR that needs Java 25. Desktop data lives in `~/.local/share/valroot`.

## Supported sources

- **Bitcoin mainnet only**, watch-only. Accepted inputs: single addresses, mainnet `xpub` keys (native SegWit receive and change branches) and the descriptors `addr`, `pkh`, `wpkh` and `sh(wpkh)` with unhardened derivation. Legacy and nested-SegWit extended-key versions (`ypub`, `zpub`), Taproot descriptors and multipath syntax are not accepted; see [wallets](bitcoin-wallets.md). Key handling uses ACINQ's bitcoin-kmp with libsecp256k1.
- **History provider:** an Esplora-compatible API. The default is blockstream.info, which fails over to mempool.space while it is unavailable. A custom endpoint is used alone and must answer as Bitcoin mainnet. Providers are trusted: Valroot checks the consistency of what they return (block continuity, reorganisations, UTXO comparison), but cannot prove that a provider returned every transaction.
- **EVM addresses** on Ethereum, BNB Smart Chain, Base, Polygon, Arbitrum One, OP Mainnet, Gnosis, Celo and Ronin, through Alchemy with the user's free key. Coverage gaps are shown per chain; see [EVM](evm.md).
- **Other sources:** CSV import of transactions in Valroot's template, with explicit account, currency, fee and asset choices ([CSV import](csv-import.md)). Exchange connections and other networks are not available. New source families plug into the connector registry (`SourceConnector`); Bitcoin and EVM are registered.

## Prices and market data

- Bitcoin prices for USD, EUR and GBP come from Kraken; historical prices for other coins from CoinGecko. Market lists, trending coins and alerts use CoinGecko's top-250 list without a key; an optional free Demo key raises CoinGecko's limits ([market data](market-data.md)).
- Order books are Kraken's, for one pair, refreshed every 10 seconds while open ([order book](order-book.md)).
- A missing or stale price is shown as missing, never estimated.

## Accounting

- FIFO per owner, account and asset, in the portfolio currency. Transfers between your own accounts, including accepted transfer links, keep each lot's cost and age.
- A transaction without a classification, or priced in another currency, leaves its cost basis and gains unknown rather than guessed. Rewards may optionally count with zero cost.
- Returns are money-weighted ([insights](insights.md)). No tax-jurisdiction rules, reports or filing are provided.

## Notifications

Alerts and notifications use CoinGecko's top-250 list and are checked at intervals, not continuously: every 5 minutes while the desktop app is open, and about every 15 minutes on Android, which the system may delay to save battery ([notifications](notifications.md)). Notifications on the Android lock screen never show amounts. A notification the system fails to show is not resent.

## Privacy and storage

- Everything stays on the device. The providers you enable receive what they need: the wallet provider your addresses and IP address; Kraken and CoinGecko price and market requests and your IP address. Wallet addresses are never sent to Kraken or CoinGecko.
- The live database is plaintext SQLite. The privacy lock protects the screen, not the file; use full-disk encryption. Backups are encrypted with a password you choose.
- Desktop refresh and notification checks run only while the app is open.

## Recovery

- **Back up:** Settings › Devices & Data › Back up creates an encrypted file. It contains accounts, wallets, transactions, corrections, rules, links, alerts, notification history, CSV import receipts and settings; it does not contain market caches, API keys or the lock password.
- **Restore:** Settings › Devices & Data › Restore checks the password and the whole file in a staging database before anything is replaced. A wrong password or a damaged file leaves current data untouched. Restore also accepts backups from earlier schema versions.
- **Forgotten lock password:** the lock screen offers to erase this device's data and remove the lock after typed confirmation. Data can then come back only from a backup.
- **Wrong or partial Bitcoin history:** Accounts › Recover full history fetches the history again from the provider; an interrupted sync resumes without accepting partial data.
- **Moving to the release key on Android:** back up, uninstall the debug-signed app, install the release APK, restore.

## Known limits

- Very large wallets (around 100,000 transactions) can exceed memory when the whole portfolio is loaded; see `BUGS.md`.
- Duplicate decisions are honoured by holdings, accounting and export, but there is no screen to review duplicate suggestions; transfer links are reviewed in the transfer review.
- Screen-reader labels, roles and 48 dp touch targets are tested on desktop; a full TalkBack audit on a device has not been done. Charts have no spoken summary.
- The Advanced-mode coin dialog keeps its unavailable Alerts and Order book tabs; the main coin page has working ones.
