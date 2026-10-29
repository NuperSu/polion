# Product contract

This contract maps the crypto-first roadmap to the application inspected on 29 October 2026. It defines product language and capability boundaries; it does not certify release readiness. See [UI map](ui-map.md), [design direction](design-direction.md), and [validation](validation.md) for presentation plans and executed checks.

## Invariants

Polion is a watch-only, local-first portfolio tracker. It accepts public tracking information and manual records, never recovery phrases, private keys, signing requests or custody of funds. Android and Linux desktop share domain logic and presentation; platform hosts own scheduling, file access, notifications and secure credential storage.

Local portfolios, exact accounting, manual entries, offline access, backup, export and user-configured integrations remain free capabilities. A future managed service may charge for remote data access and quotas. Subscription expiry, provider failure or service shutdown must leave saved local data accessible. Managed service and billing are future capabilities, not prerequisites for current workflows.

Financial quantities and money remain exact. Missing quotes, history, acquisition basis and ordering evidence remain visible. Portfolio value, price change, contributions, realized/unrealized P&L and money-flow-adjusted performance are distinct metrics. A historical value chart is not evidence of investment return.

An owner, portfolio, account, tracking target, wallet-software brand, network, connector and provider represent different identities. A brand label cannot add a balance. Wallet containers organize derived addresses; balance-bearing leaf identities must not be counted twice. Display scope must not silently establish accounting ownership or basis-pooling policy.

## Capability inventory

“Supported now” means a workflow exists in the inspected application, subject to its documented limits. “In progress” describes this roadmap's planned work, not an implemented feature or completed acceptance gate. See [Milestone D](milestone-d.md) for remaining original-plan gates.

| Area | Supported now | In progress: next planned changes | Later |
| --- | --- | --- | --- |
| Local tracking | Owners, account groups, portfolios, Bitcoin mainnet addresses, bounded descriptor/xpub import and discovery, archive/restore | Capability-driven source inventory and clearer account/target hierarchy | EVM, exchanges and additional chain families |
| Connection | Public Bitcoin validation, configured Esplora endpoint, explicit query consent, refresh and recovery actions | Separate saved/validated/connected/synced status; structured per-target progress | Secure exchange permission verification and generalized provider routing |
| Portfolio | Exact quantities, priced subtotal, missing-price notice, portfolio selection, historical value chart | Shared screen states, original adaptive design, dashboard/asset-row improvements | Saved card layouts, richer grouping and interactive comparisons |
| Activity | Search, account/kind filters, pagination, movements, provenance, corrections, rules and reconciliation review | Preserve workflows through presentation extraction; audit scope of every action | Additional date/network/status filters and broader source decoders |
| Prices | Manual saved quotes and consented historical CoinGecko workflow, freshness/provenance | Explicit valuation coverage model and provider-limit UX | Global token resolution, additional market providers and optional benchmarks |
| Accounting | FIFO, treatment and lot reports, missing/invalid treatment and chronology warnings | Explain metric-specific coverage and basis continuity | Independently tested TWR/MWR/drawdown, benchmarks and additional cost-basis methods |
| Privacy and storage | Local SQLite, app privacy lock, encrypted backup/staging restore, CSV, offline saved data | Preserve migration/restore compatibility and improve diagnostics | Optional encrypted multi-device sync |
| Scheduling | Android foreground manual sync and best-effort WorkManager; desktop refresh while open | Structured lifecycle/status integration | Local watchlist and alert workflows |
| Onboarding | Individual account/wallet/source dialogs; explicitly tagged sample data | Deterministic resumable source wizard after presentation foundations | Optional assistant presentation |
| Commercial/AI | No required account, subscription or AI model | No production service implied by this contract | Optional managed data, billing, scoped AI and optional desktop MCP |

The current connector registry contains capability metadata but its registered execution interface is history-focused. Metadata alone does not make discovery, balances, imports or health checks available through a working connection wizard. Do not advertise unsupported source families as connectable.

## Status vocabulary

Each badge must name its scope and supporting evidence. The same source can be saved, have stale prices and have incomplete accounting simultaneously.

| Term | Required meaning and evidence | Safe UI language |
| --- | --- | --- |
| Saved | Configuration was persisted locally. No network success is implied. | “Saved on this device” |
| Validated | Public input matches the supported grammar/network. No ownership or history completeness is implied. | “Ready to sync” |
| Read-only | The accepted input supports observation without signing. Future exchange connections additionally require verified credential permissions. | “Public tracking information only” |
| Connected | The named provider successfully served a supported request for the named source/target; retain observation time. | “Provider reached · checked at …” |
| Synced | A requested scan finished validation and its accepted state was committed. Show source, scope, observation time and limitations. | “Refresh accepted · history coverage partial” |
| Priced | A usable saved quote exists for that asset, quote currency and valuation time. Identify manual/provider provenance and freshness. | “Priced using … · observed …” |
| P&L complete | The specific scoped metric has sufficient acquisitions/disposals, basis, fee treatment, ordering and quotes. No unresolved inputs affecting it remain. | Use a completion claim only when the metric's evidence supports it; otherwise show “Unknown basis” or “Provisional ordering” |
| Current | The observation meets an explicit freshness policy for the displayed fact. Show timestamp and policy; connectivity is insufficient. | “Observed …” / “Stale quote” |

The current snapshot marks history coverage `UNKNOWN` before a successful refresh and `PARTIAL` afterward. A successful host operation must not promote it to complete. A consistent provider scan does not independently prove that the provider supplied every historical event. Known zero is displayed as zero only when the relevant projection supplies that quantity; unavailable value is displayed as unavailable.

## Independent state dimensions

- Operation: idle, running, accepted, failed or interrupted.
- Source evidence: unknown, partial or sufficient for the requested scope, with provider and accepted chain anchor where applicable.
- Valuation: priced/unpriced, fresh/stale and manual/provider provenance per asset and time.
- Accounting: available, partial, unknown or provisional, with reasons per metric.
- Access: consent required, allowed, permission denied, authentication required or quota limited where implemented.

Do not infer these dimensions from one success callback, nonzero asset count or absence of an error. The existing host `SyncStatus` is an operation indicator, not a completeness model.

## Reference and compatibility boundaries

CoinStats screenshot descriptions inform discovery, navigation and information hierarchy. The actual ten images were not available in this repository audit; mappings are description-derived until the images are reviewed. Polion wording, components, marks and visual assets must be original or appropriately licensed.

Wealthfolio is a selective reference for financial metrics, dashboard/allocations, lot-method UX and optional AI/provider tooling. Financial implementation work must pin and inspect its implementation/tests and produce independent crypto-native Kotlin designs. It does not replace Polion's chain evidence, ledger, identity or architecture.

Presentation extraction preserves existing actions and navigation, facade behavior, persisted IDs, database contents and backup compatibility. Any later schema change requires versioned forward migration and reopen/restore evidence. Proposed screens must consume implemented projections; unsupported metrics and source types stay hidden or explicitly informational.
