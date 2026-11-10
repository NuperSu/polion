# Phase 0 capture index

Reference and production renderers were captured on 2 November 2026 from production and reference source revision `80169eb67c14c8a243214b174969c8fe991c5206`, with capture harness committed at `b9d621d` (formatting applied after earlier test runs). Production uses the local demo seed and an in-memory database; the prototype uses its own fixed sample data. Values, timestamps, scroll offsets and data availability are therefore not equivalent. These images establish a baseline, not a pixel-diff oracle or a visual acceptance pass.

## Reproduce

With JDK 25, Xvfb and software Skia, run serially:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk SKIKO_RENDER_API=SOFTWARE xvfb-run -a ./gradlew :uiModel:test --rerun-tasks
JAVA_HOME=/usr/lib/jvm/java-25-openjdk SKIKO_RENDER_API=SOFTWARE xvfb-run -a ./gradlew :sharedUi:jvmTest --tests io.valroot.ui.RewriteAcceptanceTest --rerun-tasks
```

The successful runs used the same commands without `--rerun-tasks` after changing the test sources. The model test and the targeted production capture method passed with the menu additions; the production class had previously passed all six tests. `:uiModel:ktlintCheck :sharedUi:ktlintCheck` passed after formatting. Captures are build artifacts, not packaged-app or device evidence. The [SHA-256 manifest](evidence/phase-0-captures.sha256) identifies the 140 reference and 92 production images generated in the recorded runs. The model test clears its output first; the production output directory should be cleared before a fresh run. The tests were formatted after those runs; formatting does not change their behavior.

Both sets use `WIDTHxHEIGHT-FONTSCALE-STATE.png`: `360x800-1.0`, `390x844-1.0`, `1440x900-1.0`, and `390x844-1.5`. Reference images are under `uiModel/build/previews/`; production images are under `sharedUi/build/rewrite-previews/`. Compact paired sheets show structural comparisons at [360](evidence/phase-0-pairs-360x800-1.0-1.png), [390](evidence/phase-0-pairs-1.png), [1440](evidence/phase-0-pairs-1440x900-1.0-1.png), and [390 enlarged text](evidence/phase-0-pairs-390x844-1.5-1.png); each set has four numbered sheets in the evidence directory. The [menu sheet](evidence/phase-0-menu-pairs.png) compares the popup at all four settings. Inspect original captures for text, clipping, and full-size review.

| State | Reference capture suffix | Production capture suffix |
| --- | --- | --- |
| Shell menu | `menu` | `menu` |
| Main top | `main` | `main` |
| Main lower sections | `main-favorites`, `main-trending`, `main-top-gainers`, `main-top-losers`, `main-valroot-·-design-prev` | `main-discover`, `main-discover-bottom` (unavailable discovery) |
| Portfolio | `portfolio` | `portfolio`, `portfolio-assets` |
| Coin General | `coin-general`, `coin-general-bottom` | `coin-general` |
| Coin Transactions | `coin-transactions`, `coin-transactions-scrolled`, `coin-transactions-bottom`, `coin-manual-transaction` | `coin-transactions`, `coin-transactions-scrolled`, `coin-manual-transaction` |
| Coin Alerts and order book | `coin-alerts`, `coin-alert-dialog`, `coin-order-book`, `coin-order-book-bottom` | `coin-alerts`, `coin-order-book` |
| Add and manual form | `add-method`, `manual-transaction` (Buy), `manual-sell`, `manual-transfer`, `manual-scrolled` | `add-method`, `add-method-bottom`, `manual-transaction`, `manual-scrolled`, `manual-bottom` |
| Insights | `insights`, `insights-portfolio-performanc`, `insights-decisions`, `insights-portfolio-diversity`, `insights-gains-reporting`, `insights-asset-location`, `insights-trade-statistics`, `insights-fees`, `insights-asset-worth`, `insights-valroot-·-design-prev` | `insights`, `insights-allocation`, `insights-export` |
| Accounts and Settings | `accounts`, `settings` | `accounts`, `settings`, `settings-appearance` |

The footer anchors show content below section-heading anchors: in particular Main loser rows, the last transaction card's fee and worth fields, and Asset Worth. Some heading anchors only place the heading at the bottom edge. Production Accounts and Settings screenshots follow an export action and can include its transient notification; settings switches were changed during the test. The reference manual lower capture is on Transfer. The screenshots are not matched fixture renderings: production lacks Buy/Sell/Transfer and alert creation, and no production counterpart exists for those reference states.

## Observations and limits

The paired sheets at all four viewport settings and representative full-size captures were inspected for the structural baseline. The lower reference section captures and production unavailable-state captures establish the modeled sections and their current counterparts; pixel-level fidelity remains an open gate. The model uses a centered header and an unboxed portfolio balance/chart with compact holdings; production has a left-aligned header, boxed value panel and different controls. Model coin detail is a screen with centered identity, a six-metric grid, underlined tabs and detailed transaction cards; production opens a dialog with different metric and transaction layouts. The model menu opens at the left with Accounts and Settings; production opens at the right with those actions plus Advanced tools and Refresh details. Menu captures show different underlying pages and the production capture follows export. Model Main contains market cards and ranking sections while production exposes saved quotes and unavailable discovery. Model Insights contains History, Performance and the modeled lower sections, while production starts with return status and the older value/report panels. Add-method, manual entry, Accounts and Settings also differ materially in composition and capabilities. Alert and order-book production states explicitly report that the features are unavailable.

At 390×844 with 1.5× text the reference manual lower capture shows the fields and footer but its type controls are above the viewport. At 360×800 the transaction footer capture is needed to see the last card's lower values; the intermediate scrolled image cuts them at the navigation edge. At enlarged text the production Accounts header wraps a word across lines. At enlarged text the production manual form requires scrolling beyond the label to reach its save action (`manual-bottom`); its Close control is at the top of the same scrolling dialog. The reference detail tab row clips the Order book label until horizontally scrolled. These observations are not a complete accessibility or visual-parity review. Enlarged text is sampled at 390 only; landscape, keyboard focus, screen readers, real production data and native Android/desktop windows remain unverified. Neither text assertions nor screenshot generation establishes parity, financial correctness, or working unsupported features.
