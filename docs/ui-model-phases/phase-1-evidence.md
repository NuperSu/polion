# Phase 1 presentation evidence

Phase 1 remains open. The model preview supplies deterministic sample values to shared shell, Main, Portfolio, coin detail, transaction dialog, Insights, Accounts and Settings renderers. The production binding is exercised with seeded local data; visual review remains pending. The model's fixed sample values do not establish production data binding or financial correctness.

The Phase 0 capture record and its SHA-256 manifest identify the reference baseline. A side-by-side review must compare each shared-renderer capture against those frozen reference images at 360×800, 390×844, 1440×900, and 390×844 at 1.5× text. Review all captured scrolled sections, forms, coin tabs and dialogs. Capture the real application binding separately; sample fixture captures do not establish its data or interaction behavior. Record the tested revision, commands, image locations, actual differences and review results before claiming the visual gate.

`ModelPortfolioSummaryTest` exercises range callbacks, host-provided display values and disabled ranges. `ModelTransactionDialogTest` exercises typed form actions and submission availability. The production-host tests exercise detail navigation and draft recreation, and cover portfolio scope, quote and range restoration. The coordinated focused run passed draft, coin-tab, portfolio scope/quote/range restoration, typed callbacks and Escape close after fixes. Desktop keyboard Tab/Shift-Tab traversal and Enter activation, plus Activity account/kind/status recreation, passed in the full JVM suite. Android and native desktop journeys remain separate evidence.

## Renderer and host contracts

`ModelShell` receives `ModelShellState`; `ModelMain` receives `ModelMainState`; Portfolio uses `ModelPortfolioSummaryState` and `ModelPortfolioHoldingsState`; coin detail uses `ModelCoinDetailState`; the transaction dialog uses `ModelTransactionDialogState`; Insights uses `ModelInsightsState`; and Accounts and Settings use their corresponding typed states. Renderers receive actions from their hosts. The `uiModel` host supplies deterministic examples to these production components, while `ModelWorkspace` adapts application snapshots and actions. This shared implementation establishes which components are being compared; the pending visual review determines whether their composition matches the frozen reference.

## Comparison gallery

The capture test clears `uiModel/build/previews` before writing. Preserve the manifest-verified Phase 0 PNGs in a separate directory first; the [Phase 0 record](phase-0-captures.md) documents their source revision and manifest. Run the new fixture capture using its command, then create full-size side-by-side and amplified-difference images with Pillow installed:

```sh
python scripts/compare-ui-model-captures.py /path/to/phase0-reference uiModel/build/previews /tmp/polion-phase1-comparison
```

The agreed inline alert editor replaces the alert dialog: the gallery explicitly pairs each Phase 0 `coin-alert-dialog` image with the current `coin-alert-editor` image and records both filenames in `metrics.csv`. The approved reference also uses a fullscreen add flow, a grey disabled exchange choice, and the revised order-book styling. Alert delivery belongs to Phase 7, with system notifications rather than modal alerts. These agreed changes require review against the updated contract; their differences from Phase 0 are intentional.

The output includes one image per reference state and `metrics.csv`, which reports missing images and pixel differences. Metrics locate changes; they cannot decide visual acceptance. Review is pending. Captures of the live application binding are separate from the fixture gallery.

The refreshed [comparison gallery](/tmp/polion-phase1-comparison/index.html) provides 140 reference/current fixture comparisons, 140 directly linked current model previews, and 108 production binding captures in separate indexes. The [archive](/tmp/polion-phase1-comparison.zip) contains both capture sets and passed ZIP CRC validation. All 140 reference mappings have matching dimensions and a `compared` result; visual review remains pending.

The latest captured source is commit `afca632`. It refines seven shared renderers: coin screens, forms, Insights, Main, portfolio holdings, portfolio summary and shell. The changes include pill treatment, section spacing, chart styling and the Settings icon. Formatting and strict lint passed. The full shared UI and desktop run passed 95 shared UI and 28 desktop tests; the separate model capture test passed and regenerated 140 previews. Android debug lint and APK assembly, plus desktop packaging, passed. Logs are `/tmp/ui-polish-format.log`, `/tmp/ui-polish-lint.log`, `/tmp/ui-polish-tests.log`, `/tmp/ui-polish-model.log`, and `/tmp/ui-polish-platform.log`. Android device execution and native desktop interaction review were not performed for this update. Phase 1 remains open pending user visual review and remaining acceptance gates.

## Acceptance audit

| Requirement | Evidence | Status |
| --- | --- | --- |
| Shared shell, components and typed screen states | Shared renderers and host adapters at the tested commits; fixture and seeded production captures | Implemented; visual review pending |
| Scope, quote, tab, range, draft and Activity filters across recreation | `ModelWorkspaceRestorationTest` and presenter tests in the passing shared UI suite | Passed for covered interactions |
| Back, close and desktop keyboard | Production-host Escape and Tab/Shift-Tab/Enter tests, dialog tests and navigation regressions | Passed in JVM tests; native journeys pending |
| Real-data and unsupported actions | Seeded production capture and interaction tests; unavailable controls disabled | Covered for current bindings; later service and financial gates remain open |
| Side-by-side visual review at four settings | 140 paired fixture images and 108 separate production images | Pending user review |
