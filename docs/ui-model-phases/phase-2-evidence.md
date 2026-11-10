# Phase 2 — Portfolio and coin General bindings

Phase 2 connects the shared screens to existing projections. The user authorized this phase on 3 November 2026 following the presentation review. Final visual review is user-owned; backend and release gates in later phases remain separate.

## Current behavior

- Portfolio selection, quote selection, priced balance, historical value, value change, sorting and filtering use persisted application workflows. Quote changes update the selected portfolio's currency, or the global currency for All portfolios. The selected target is captured before asynchronous mutation.
- Value change is the last endpoint valuation less the first. Percentage change divides that difference by a positive starting value. Missing or stale endpoint valuations make change unavailable. Contributions and withdrawals are included; neither figure is investment return.
- Portfolio ranges are 1H, 1D, 1W, 1M, 3M, YTD, 1Y and ALL. They mean one hour, 24 hours, seven days, 30 days, 90 days, UTC year-to-date, 365 days and earliest eligible scoped activity respectively. Every range has its own boundaries. Large-text controls can scroll horizontally.
- Sort and filter dropdowns are anchored to their controls. Existing hide-empty, hide-small and name/value preferences affect holdings only, not portfolio totals. Result notifications occupy their own area rather than cover the controls.
- The explanation shows endpoint value change, native deposit/payment movement differences from scoped analytics, unpriced positions and recorded history coverage. Transfers are excluded from the native contribution summary; recorded fees are included and disclosed. It does not invent historic quote conversions or a return calculation.
- Coin General reads per-unit observations independently of ownership. Each range requests 61 as-of samples from `priceSeries` using the application's evaluation clock. Only fresh eligible observations are plotted; missing or stale samples remain gaps. Fewer than two distinct eligible observations does not establish price movement. Range selection reads saved data and does not fetch a new provider history window.
- Price labels identify the actual provider/manual source and observation time. Observation details expose saved valid/observed timestamps and IDs. There is no global-average claim. Provider coverage and resolution can be sparse; a range selector does not manufacture missing candles.
- The six coin figures distinguish owned quantity, position value, remaining open-lot basis, unrealized gain, realized gain and their supported total. Missing/invalid treatments or missing acquisition basis leave affected figures unknown. Uncertain FIFO ordering marks figures provisional. Market-only assets do not gain ownership or basis from browsing.
- Main coin details use global scope independently of a previously selected portfolio. Contextual activity/accounting tools use that same scope, and Back restores the preceding portfolio choice. Portfolio coin details retain their selected scope.
- Refresh failures retain saved same-scope price/accounting results. Scope, currency, asset or range changes cannot attribute a preceding result to a new selection. Price pulls and explicit wallet-history refresh retain their existing consent and scheduling behavior.

## Display precision

Following the user's instruction, displayed USD amounts use two decimals. Nonzero sub-cent USD values use a bound such as `<$0.01`. Other stored price precision and all exact ledger/accounting inputs remain unchanged. Price and holding labels, chart axes and saved-observation displays use this policy; user-entered values are not truncated. Accounting values retain the existing approximation/provisional markers. Exact evidence remains in stored records, backups and existing lot/provenance tools.

## Verification

Final verification passed **449 tests** (270 core JVM, 47 Android host, 103 shared UI, 28 desktop, one model capture test), with zero failures, errors or skips. Core/shared/model formatting, Android lint, debug and test APK builds, and desktop packaging passed. Logs are `/tmp/phase2-final-{lint,full,model,platform}.log`.

`PhaseTwoProjectionTest` independently checks exact endpoint changes, missing/stale/zero boundaries, sub-cent price ratios, known accounting sums, unknown opening basis, provisional results, native contribution differences and distinct UTC/intraday boundaries. `PhaseTwoControlsTest` exercises real currency, sort, filter and explanation actions, and the global-market versus selected-portfolio return path. Existing restoration and market-only tests remain in place. Production captures wait for price and accounting projections before recording coin General.

The final APK and test APK were updated with `-r` in Android user 10, preserving app data. The disposable native harness passed **17 checks, zero failures** (`/tmp/phase2-final-native.log`). Fresh current-user and idle-service checks preceded installation and launch. Valroot is resumed on the connected Pixel in user 10; no wallet sync or personal-data inspection was performed.

Review captures are available in `/tmp/valroot-phase2-review/index.html` and `/tmp/valroot-phase2-review.zip`. They contain 108 seeded production-screen captures, including 360×800, 390×844 and 1440×900 plus enlarged text and scrolled states. These are test data, not personal-wallet captures.

## Acceptance boundaries

Financial projections, controls and restoration are verified by automated examples; screenshots are review artifacts rather than proof of numerical correctness. User visual approval of the final populated screens remains pending. No trade-entry backend, market-wide feeds, TWR, notifications, order-book service, CSV import, incremental wallet scan, or full release/security gate is implied by this phase.
