# UI inventory and transition map

This is the Phase 1 presentation specification for the [product contract](product-contract.md). Current behavior and proposed destinations are listed separately. Screenshot mapping uses the roadmap's textual descriptions; original screenshot image files were not available for this audit.

## Screen inventory

| Current surface | Existing content/actions to preserve | Proposed destination |
| --- | --- | --- |
| Overview | Portfolio picker, priced value, history chart, assets, health, recent activity, sync and add actions | Home summary plus dedicated Portfolio |
| Activity | Search, account/kind filters, pagination, manual entry, activity detail | Activity |
| Accounts | Owners/groups/accounts, public addresses, descriptor/xpub wallets, derived addresses, discovery, archive/restore, portfolio configuration | Sources; portfolio configuration remains reachable |
| Reports | Portfolio scope, analytics, FIFO/treatments, ordering verification, reconciliation, CSV | Portfolio analytics and Reports reachable through More |
| Settings | Quote currency, manual prices, consent/provider endpoint, background refresh, lock, backup/restore, CSV and local data controls | More → Settings |
| Asset dialog | Quantity, quote and observation detail | Asset detail, incrementally expanded using implemented projections |
| Activity dialogs | Movements/provenance, correction, treatment and reconciliation workflows | Activity detail and contextual panels |
| Source dialogs | Address/account form, wallet import, consent, refresh/recovery and UTXO review | Source wizard and source detail |

No extraction may drop rule management, accepted transfer/conflict review, archive restoration, detailed UTXO review, accounting-order verification, manual quote entry, app lock or encrypted restore simply because they are absent from a reference screenshot.

## Responsive navigation

Account management is reachable from **Manage accounts** on Home and Portfolio, **More → Accounts** on compact layouts, and **Accounts** in the desktop rail. This list covers every portfolio. **Archive account** pauses its public queries and removes the account and descendants from active portfolio scopes while retaining saved addresses, activity and accounting decisions. **Archived accounts → Restore account** restores tracking according to the saved account and target settings. Archive and restore actions are unavailable during a refresh or another saved-data operation.

During Phase 2, preserve the five existing destinations: Overview, Activity, Accounts, Reports and Settings. Extract their state and events before changing information architecture. Phase 3 can introduce the adaptive shell using the mapping above.

Target compact navigation:

| Item | Purpose |
| --- | --- |
| Home | Summary, source attention, recent activity and supported shortcuts |
| Portfolio | Portfolio/quote scope, priced value, assets, history and allocation |
| Add | Action menu, not a persistent page: supported public address, descriptor/xpub and manual-entry workflows |
| Activity | Searchable ledger and detail |
| More | Sources, Reports, Settings and other implemented tools |

Wide layouts use a left rail with Home, Portfolio, Activity, Accounts, Reports and Settings when space permits. Add remains an action. Portfolio customization saves the order and visibility of allocation, assets, data health and recent activity; the portfolio value remains visible. Asset/activity/source detail can occupy a contextual second pane. Keyboard focus, scrolling and dismiss/back behavior must work at every width. Avoid resizing a phone card into an otherwise empty desktop screen.

Proposed Home watchlists, alerts, saved widget layouts and assistant cards remain absent until their underlying workflows exist. Unsupported DeFi, Positions and NFT sections must be hidden or clearly informational. The active portfolio and quote currency must remain visible wherever they affect figures.

## Description-derived screenshot mapping

| Reference behavior | Polion destination | Implementation condition |
| --- | --- | --- |
| Welcome/goals conversation | Optional onboarding presentation over deterministic wizard | Goals affect display preferences only; no AI/sign-up/network requirement |
| Wallet/chain/exchange tiles | Source category and capability picker | Only implemented connections are actionable; brand is not a connector |
| Successful connection card | Source detail and wizard progress | Separate validated input, provider connectivity, accepted sync and accounting coverage |
| Asset/history/P&L checklist | Independent evidence/status rows | No automatic all-green checklist after a refresh |
| Portfolio preview | Same projections used by Portfolio | Clearly tagged demo data; missing prices and basis visible |
| Suggested portfolio questions | Later deterministic insight/query entry points | Only expose questions backed by implemented answers |
| Alert opt-in | Later local-alert setup | Permission and best-effort scheduler limits explicit |
| Daily portfolio screen | Portfolio with original hero/chart/rows | Historical value and performance remain separately named |

## Workflow and state transitions

Planned wizard:

```text
Welcome → optional preferences → supported source category → public input
       → validation → provider/privacy consent → locally saved source
       → optional sync monitor → honest preview → portfolio
```

Back, skip, resume and exit-to-portfolio must be possible. The first launch may finish without any source, account, model, subscription or network request. Persist resumable step/input state when implementing Phase 5; do not persist secrets in ordinary wizard state.

Source operation:

```text
Saved → ready to sync → discovering/fetching → validating → accepted
                         ↘ offline / rate limited / failed
                         ↘ interrupted → retry/refetch and validate
                         ↘ recovery required → explicit recovery action
```

Input errors return to the field with a reason. Consent refusal leaves a saved/offline-capable source. Provider limits show retry guidance when known. Failure/interruption retain previously accepted ledger data; accepted state changes only after validation and atomic commit. Recovery must preserve accepted data until its replacement passes validation. Current refresh/recovery workflows remain available through extraction; per-step durable wizard state is planned.

Valuation and accounting do not advance automatically with the operation:

```text
Accepted source state → holdings projection
                     → quotes: priced/unpriced + fresh/stale + provenance
                     → accounting: available/partial/unknown/provisional + reasons
```

Changing the portfolio updates scope-dependent figures together. Filtering Activity changes its result page, not the accounting ownership policy or historical chart definition. Changing a method in a future accounting strategy recalculates derived reports, never raw source evidence.

## Presentation contract and acceptance

Extract immutable screen states and typed events for navigation, dialogs, filters, selection and operations. Model loading, empty, offline, partial, stale, unpriced, limited, denied, error and available states explicitly. Keep asynchronous work in existing host/use-case owners; recomposition must not trigger repeated financial replay or network requests.

Phase 1 is ready for implementation when each advertised action maps to an implemented application/platform operation, future features are clearly labeled, current actions have reachable target destinations and status language satisfies the product contract. This document does not establish Phase 2/3 tests or new runtime behavior. Subsequent extraction requires behavior tests; design work requires screenshot/semantics checks, large text, compact landscape, keyboard and accessibility validation.
