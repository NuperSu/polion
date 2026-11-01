# Roadmap: make the uiModel design the working Polion application

Planning baseline: 2 November 2026. This is an implementation and acceptance plan, not a claim that the current frontend reproduces the model or that the financial backend is complete.

## Outcome

Deliver the application shown in `uiModel`, with its actual screen composition, information hierarchy, charts, rows, transaction cards and forms backed by real services. Similar colors, navigation labels and button placement are insufficient. Preserve local databases, identities, evidence, corrections, backups and exact financial calculations.

The initial tracking release remains Bitcoin-first. Multiple coins in market discovery do not imply that their wallets or chains are supported. Additional chain integrations require their own authorized scope and acceptance gates. Cloud accounts, subscriptions and AI must not be prerequisites for the local application.

## Current gaps established from the source

| Surface | Model contract | Current gap to close |
| --- | --- | --- |
| Shell | Centered title, menu/back at left, refresh at right, restrained outlined navigation | Production shell and Material defaults differ in composition, sizing and interaction styling. |
| Main | Top Coins with changes/sparklines, Favorites, Trending, Top Gainers, Top Losers | Saved-price cards and unavailable discovery blocks provide only a subset of the content and layout. |
| Portfolio | Unboxed headline balance/change, prominent chart, model range controls, holdings toolbar and compact holding rows | Production still composes `BalanceHero` and `AssetsCard`, including the earlier dashboard's hierarchy and controls. |
| Coin detail | Centered identity, six-metric breakdown, model tab treatment, price chart and transaction summary/cards | Tabs exist, but production presentation and transaction content differ materially. |
| Manual entry | Buy/Sell/Transfer, account/venue, trading pair, time, unit price, amount, fee and notes | Current form supports deposits/payments/rewards. It does not implement the model's trade workflow. |
| Insights | History, Portfolio Performance, Decisions, Diversity, Gains Reporting, locations, statistics, fees and Asset Worth | Production reuses the earlier value hero and analytics/accounting panels instead of reproducing these sections. Several required calculations/services are missing. |
| Alerts and Order book | Working alert management and market-depth display | Unavailable states are honest interim states, but do not fulfill the final functionality. |
| Accounts and Settings | Focused model-style account cards and concise controls | Existing management capabilities need to be organized into these flows, with detailed tools behind contextual actions. |

The model contains fixed examples and several undefined financial concepts. Its layout is the reference; its sample numbers are not financial specifications. Resolve ambiguous meanings before implementing their calculations.

## Delivery rules

1. Track visual fidelity, working interactions and financial correctness as separate gates. All three must pass for a screen to be complete.
2. Reuse the model's composition directly. Move its reusable renderers into production shared UI and inject typed data/actions. Let `uiModel` preview those same renderers with fixtures. Avoid maintaining two independent implementations that drift.
3. Reuse existing domain services behind new screen presenters. Do not insert the old dashboard/report composables into the new screens as substitutes for the model sections.
4. Preserve the model section order, card structure, density and primary interactions. Any intentional change needs a recorded reason and an updated agreed reference.
5. Production uses real data. Deterministic fixtures belong to previews and tests. A temporary unavailable state must occupy the intended component and remain tracked as unfinished work.
6. Keep everyday screens concise. Missing evidence belongs to the affected metric and its explanation. Provider failures use concise notifications and on-demand details. Advanced diagnostic tools remain reachable.
7. Review rendered screens before expanding scope. Tests that only find text or verify navigation cannot establish design fidelity.

## Phases

Each phase is separately executable; its exit gate is defined in its document. Phase 0 establishes the contract and honest baseline without requiring later services to exist. Complete its capture and review evidence before declaring that phase complete.

- **Complete (baseline)** · [Phase 0 — Establish the exact contract and honest baseline](ui-model-phases/0-establish-the-exact-contract-and-honest-baseline.md)
- **Not started** · [Phase 1 — Build one faithful presentation layer](ui-model-phases/1-build-one-faithful-presentation-layer.md)
- **Not started** · [Phase 2 — Finish Portfolio and coin General with existing data](ui-model-phases/2-finish-portfolio-and-coin-general-with-existing-data.md)
- **Not started** · [Phase 3 — Implement the model's transaction system](ui-model-phases/3-implement-the-model-s-transaction-system.md)
- **Not started** · [Phase 4 — Finish Accounts, history refresh and recovery](ui-model-phases/4-finish-accounts-history-refresh-and-recovery.md)
- **Not started** · [Phase 5 — Make Main a real market-discovery screen](ui-model-phases/5-make-main-a-real-market-discovery-screen.md)
- **Not started** · [Phase 6 — Implement Insights in the model's composition](ui-model-phases/6-implement-insights-in-the-model-s-composition.md)
- **Not started** · [Phase 7 — Finish alerts, order books and CSV import](ui-model-phases/7-finish-alerts-order-books-and-csv-import.md)
- **Not started** · [Phase 8 — Product acceptance and release](ui-model-phases/8-product-acceptance-and-release.md)

## Execution and completion tracking

Start with Phases 0–1, then deliver Portfolio/coin General and transactions as complete vertical slices. Follow with account reliability, market discovery, Insights, remaining services and final release validation. Provider research and financial specification work can proceed independently once the shared screen contracts are stable; dependent implementation follows the relevant evidence gate.

Track each section with separate fields for reference capture, production renderer, data source, interaction coverage, financial oracle, Android evidence, desktop evidence and remaining blocker. Record the exact tested revision. Commit focused changes after their relevant checks, preserve unrelated work and do not push without instruction.

Phase 0 is complete as a baseline. Phase 1 is the next separate goal, subject to authorization. Expanding the old dashboard under the new colors does not satisfy the shared-renderer task.
