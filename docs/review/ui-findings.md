# UI and UX findings

Line references are as of commit 4a77ffd; the code has since been restructured (see docs/architecture.md).

Paths are relative to `sharedUi/src/commonMain/kotlin/io/valroot/ui/`. Captures are in `/tmp/valroot-phase4-review/` (seeded example data, not copied here).

## UI-1 · P2 · state loss · [V] — App lock discards the whole UI tree

- Where: `ValrootApp.kt:129-133` (`if (locked) LockScreen else OnboardingProduct`); Android locks in `MainActivity.onStop`.
- Scenario: with a lock set, switching to an exchange app to read a price loses the open manual form, connect dialog, selected coin, tab and filters.
- Fix: render the lock as an overlay, or keep saveable state across the swap.

## UI-2 · P2 · data entry · [V] — Closed draft reopens on the next New Transaction

- Where: `ValrootApp.kt:1376-1393` (`removeState` only in `onSaved`); `TransactionEntry.kt:92-108`.
- Scenario: start an ETH entry, close with ×, open New Transaction from BTC — the form shows ETH, the old amount and stale timestamp.
- Fix: clear on close, or key by context and confirm discard.

## UI-3 · P2 · misleading / noise · [V] — "Sync complete" banner never goes away

- Where: `ValrootApp.kt:2323` (`SyncBanner`), `ModelWorkspace.kt:379` (shown whenever phase ≠ IDLE); hosts never reset SUCCESS to IDLE.
- Scenario: after one sync, Main/Portfolio/Insights show "Sync complete" until restart, while its own message says coverage is unverified.
- Fix: show only running, error or interrupted; word success as "Refresh accepted · history coverage partial".

## UI-4 · P2 · layout · [V, capture] — Pinned Review button covers the last field

- Captures: `production/360x800-1.0-manual-buy.png`, `360x800-1.0-manual-bottom.png` (Fee asset, Notes under the button), `1440x900-1.0-manual-buy.png` (Date & time clipped).
- Fix: bottom content inset equal to the pinned action height.

## UI-5 · P2 · form design · [V]

- "Exchange: Local manual account" and "Trading pair: BTC/USD" are static text styled like fields (captures above).
- Default account = first account with a balance (`TransactionEntry.kt:109-121`), regardless of the header label.
- "New local account" sub-form appears on every transaction type.
- Date & time is a hand-typed UTC ISO string with no zone note (`TransactionEntry.kt:101-103`).
- Mixed control styles: green link-like dropdowns, outlined fields, capsules.
- [R] Disabled Review gives no reason; validation is one grey line; no per-field errors, keyboard types or IME actions.

## UI-6 · P2 · flow · [V] — Connect wallet dialog

- Where: `BitcoinAccountFlows.kt:145-150` clear the owner when a portfolio is chosen; Confirm stays enabled and fails with "Select an owner in this portfolio" (`:201-204`).
- "Review connection" is silently disabled while consent is unticked (`:261`).
- Raw `e.message` is shown on failure (`:253`).

## UI-7 · P2 · financial presentation · [V] — Headline is an unlabelled partial total

- Where: `ModelWorkspace.kt:660`, `AssetList.kt:18-29` (sum of priced rows including STALE); "N unpriced assets excluded" appears after chart, ranges and the always-on disclaimer.
- Fix: label "Priced value" with the unpriced/stale note directly under it.

## UI-8 · P2 · formatting · [V, capture]

- Headline "$97,500.00" vs coin metrics "6050.00 USD", "93250.00 USD" (`360x800-1.0-coin-general.png`).
- Raw ISO-8601 UTC timestamps on Accounts, price observations and transaction cards.
- [R] Sub-cent and non-USD quotes round or print inconsistently.

## UI-9 · P2 · settings / a11y · [V, capture]

- `360x800-1.0-settings.png`: model Settings has two switches; theme, currency, backup, lock, erase and price consent sit behind "Appearance, privacy, backup and price settings" on a differently styled legacy page.
- `model/ModelAccountsSettings.kt:106-120`: switches are not associated with their labels (no `toggleable`); same pattern for consent checkboxes.

## UI-10 · P2 · layout · [V, capture] — Clipped chip rows at 360 dp

Coin tabs cut "Order book" (`360x800-1.0-coin-general.png`); type chips cut "Withdrawal" (`360x800-1.0-manual-buy.png`). No scroll affordance.

## UI-11 · P2 · layout · [V, capture] — FAB overlaps controls

`360x800-1.0-portfolio.png`: "+" covers the "Highest Holdings" sort capsule at rest; `360x800-1.0-portfolio-holdings.png`: covers the first holding's value and change.

## UI-12 · P2 · desktop layout · [V, capture]

`1440x900-1.0-portfolio.png`, `1440x900-1.0-manual-buy.png`: no max content width (fields ~1400 px), holdings entirely below the fold, gear/back arrow detached from content edge.

## UI-13 · P2 · semantics · [V]

On Accounts and Settings the header refresh icon refreshes prices only, while wallet history is a separate text link. Recorded Phase 4 decision, but confusing on those pages.

## UI-14 · P2 · Accounts · [V, capture]

`accounts/390x844-1.0-account-details.png`: "Source provider: https://blockstream.info/api" printed for a local manual account directly above "No public provider queries are configured" (`AccountsWorkspace.kt:142`); card description is the enum "wallet" (`:108`); seven identical green links including Archive; "＋ Connect wallet" capsule touches the card and is ~33 dp tall.

## UI-15 · P2 · currency control · [V]

Model currency control is bare "USD" text (~25 dp, no chevron/role) in `model/ModelPortfolioSummary.kt:114-129`. Legacy pickers (`ValrootApp.kt:787, 1233-1235`) call only `app.setCurrency`, which a selected portfolio's `valuationCurrency` overrides — they report "Saved" and change nothing.

## UI-16 · P2 · performance · [V] — Search keystrokes blank and recompute the timeline

`ModelWorkspace.kt:142-153, 159`: `timeline` is keyed on the search text and reset to null; the effect recomputes full-history metrics per keystroke with no debounce. Avg Buy/Sell and counts silently follow the filters.

## UI-17 · P2 · robustness · [V] — Unguarded effect

`ValrootApp.kt:236-246`: `LaunchedEffect(modelEditingId)` calls `app.transactionDraft` without try/catch; a storage error crashes instead of showing a message. [R] further unguarded effects in `ValrootApp.kt` and `Workflows.kt`.

## UI-18 · P2 · [R]

- Dialogs close before save outcome; generic failure toast; no in-flight guard.
- Internals in dialogs: atomic units and asset IDs in "What's going on?" (`PortfolioMovementSummary.kt:21`) and Activity details; enums in review.
- No Enter-to-submit or autofocus (no `keyboardActions` in sharedUi), including the lock screen.
- Charts lack accessibility semantics; several tap targets under 48 dp.

## UI-19 · P3

- [V] Stale `selectedPortfolio` after wipe/restore → "Unknown portfolio" in `scopedAccounts`; stale snapshot stays with a generic toast.
- [V] `ProductPresenter.kt` restore uses `Page.valueOf` and can throw on stale keys.
- [V] Every coin gets Bitcoin orange (`ModelWorkspace.kt:399`).
- [R] Light-theme chart details nearly invisible (`HistoryInspector.kt:42`); pagination "1–0 of 0" before load; CSV filename contains `:`; scroll lost returning from a coin (`model/ModelShell.kt:166`); two theme systems; onboarding open failure has no retry.

## UI-20 · P3 · duplication · [R]

Legacy duplicates remain reachable: `WalletDialog` and the "account" workflow vs `BitcoinConnectDialog`, `RevisedMain`, legacy Accounts/Settings; onboarding hands off to legacy dialogs. Unused: `QuickActions`, `symbol()`, `newId()`, `SelectHistoryRange`.
