# Code review after UI-model Phase 4 — 2026-11-06

This is a review report, not a certification. It does not close any roadmap gate.

## Scope

The whole repository at `4a77ffd` (plus the uncommitted `BitcoinHeapStress.kt` change) was reviewed for bugs, unfinished work not scheduled in a later phase, and awkward UI. "Phase 4" means the UI-model roadmap (`docs/ui-model-phases/4-…`).

Excluded on purpose:

- Later phases: Main discovery feeds and favorites population on Main (Phase 5), Insights sections (Phase 6), alerts, order book and CSV import (Phase 7), release acceptance (Phase 8).
- Already tracked: the 100k-transaction snapshot OOM (BUGS.md), incremental history (design only), the plaintext live database.

## Method

1. Three parallel read-only audits: core/application, Bitcoin plus hosts plus build/CI, shared Compose UI.
2. A direct read of the highest-risk code: `PortfolioApplication.kt` in full, backup, holdings, sync, market repository, both SQLite drivers, host threading, connect/accounts/settings screens, the Bitcoin decoder and wallet crypto.
3. Inspection of 14 seeded captures from `/tmp/valroot-phase4-review/` (360×800, 390×844 at 1.5× text, 1440×900).

No build or test was run; this is a static review.

## Conventions

- **[V]** confirmed by reading the cited source or capture. **[R]** reported by an audit pass and plausible, not independently confirmed — verify before acting.
- Severity: **P0** data loss or wrong money; **P1** wrong results or unusable in realistic use; **P2** edge case, misleading or awkward; **P3** cleanup and hardening.
- Line numbers refer to the reviewed revision and may drift.

## What held up

No P0 was found. BIP32/BIP380/Bech32/Bech32m/Base58 derivation and checksums, FIFO arithmetic, Bitcoin fee and change attribution, SQL parameterisation, and CancellationException handling in the UI checked out. There is no TODO/FIXME, no `@Ignore`, and no Float/Double in financial paths (Double is used only for chart coordinates).

## Fix first

1. CORE-1 — changing display currency invalidates all accounting.
2. PLAT-1 — Android UI database work queues behind the whole Bitcoin sync.
3. CORE-3 — wallet discovery overwrites ownership edits made meanwhile (desktop).
4. CORE-2 — large backups export successfully but cannot be restored.
5. UI-1 / UI-2 — in-progress forms are lost on lock, or a stale draft reopens.
6. CORE-4 — pending Bitcoin transactions are dated 1970.
7. UI-3 — a permanent "Sync complete" banner.
8. PLAT-2 — an advisory UTXO fetch failure aborts the whole refresh.
9. PLAT-3 — a legacy xpub silently shows an empty wallet.

## Files

- [core-findings.md](core-findings.md) — CORE-1…17
- [platform-findings.md](platform-findings.md) — PLAT-1…11 (Bitcoin, Android, desktop, build/CI, doc drift)
- [ui-findings.md](ui-findings.md) — UI-1…20

Counts: 4 P1, 33 P2, remaining items P3 or open questions.

## Fix status (branch `review-fixes`)

Verified with `:core:jvmTest`, `:sharedUi:jvmTest`, `:desktopApp:test`, `ktlintCheck`, `:androidApp:compileDebugKotlin` and `:androidApp:lintDebug`. None of this was run on an Android device or emulator, and no packaged desktop app was launched.

- **CORE-1 changed approach.** A treatment priced in another currency keeps its lot with unknown basis and is listed in `AccountingView.currencyMismatch`. It is no longer rejected as invalid, and its asset is no longer dropped from FIFO. This changes the contract that `AccountingScopeTest` encoded, and that test was updated.
- **CORE-2 fixed.** Backups use format 3 ([backup format](../backup-format.md)): a chunked authenticated encryption envelope around a streamed row body. Export and restore stream on both hosts, so backup size no longer depends on memory. Earlier backup files are not readable.
- **CORE-3 fixed.** Ownership read-modify-write runs in one transaction. Discovery merges into the catalog at commit time and re-checks the wallet, consent and endpoint.
- **CORE-4 fixed.** Pending Bitcoin activities use the retrieval time. The decoder test fixture previously used a non-timestamp string, and now uses a real instant.
- **PLAT-1 fixed, not device-verified.** Bitcoin sync (service and periodic worker) has its own executor. Backup restore runs on the sync thread so it cannot overlap a scan. The UI thread no longer queues behind sync. SQLite busy-timeout and WAL (PLAT-7) are still open.
- **PLAT-2 narrowed.** Only a permanent provider rejection (`UNAVAILABLE`) makes the UTXO comparison advisory. The snapshot is recorded as not compared, so no clean match is claimed. Offline, timeout and rate-limit failures still interrupt and keep the resumable session, as the existing acceptance tests require.
- **PLAT-3 partial.** Discovery reports how many addresses were used, and warns on zero use and on single-branch descriptors. Legacy and nested xpub support is not added.
- **UI-1 fixed, not device-verified.** The product stays composed under the lock overlay. Back-navigation behind the lock is not yet blocked.
- **UI-2 changed approach.** Closing a draft keeps it, which the Phase 3 contract and an existing test require. Drafts are now scoped by their starting coin, so an entry opened from another coin no longer reuses a stale draft. No test was added for the cross-coin case.
- **UI-3 fixed.** The sync banner shows only running, failed or interrupted work. A failed run stays visible until the next run.
- **CORE-5 fixed.** Settings > Devices & Data offers Remove example data. Example transactions are reversed (the ledger is append-only), example prices are withdrawn, and the example account is archived. The example portfolio is removed and the example owner renamed.
- **CORE-6 fixed.** Valuations within 24 hours of now use the current-price window on every view.
- **CORE-9 fixed.** A zero historical CoinGecko quote is treated as missing.
- **PLAT-5 fixed for the reported sites.** Preferences, favorites, price-refresh consent and refresh details load on the host dispatcher; favorites save in the background.
- **PLAT-6 fixed.** The lock screen offers erase-and-remove-lock after typed confirmation, so a backup can be restored.
- **PLAT-7 fixed.**
  - Desktop connections wait up to five seconds for a writer and use write-ahead logging, as Android already does. Restore refuses to replace the database while a write-ahead log is still open.
  - HTTP 502/503/504 count as temporary and fail over.
  - Responses may be up to 16 MiB; a larger one fails with its own message instead of a generic error.
  - A changed custom endpoint must return the mainnet genesis block before it is saved. Endpoints saved earlier are not re-checked.
  - An address with more than 50 unconfirmed transactions syncs: confirmed history is complete, the 50 listed pending transactions are accepted, and Data health says that more exist.
