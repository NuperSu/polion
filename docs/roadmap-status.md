# Product roadmap status

Polion is a local-first, watch-only portfolio tracker. The free local app must remain usable without a hosted account, subscription or AI service. Bitcoin is the first supported network.

This is a progress map for the crypto-first roadmap. A working feature does not by itself satisfy its phase's acceptance gate. Phase numbers here are from the crypto-first roadmap; they differ from the earlier milestone A–D phase numbering.

| Phases | Direction | Current position |
| --- | --- | --- |
| 0–6 | Reliable baseline, responsive UI, source identity, offline onboarding and connection workflows | Core Bitcoin tracking, background sync, source consent, onboarding and responsive navigation are implemented. Full device, accessibility and workflow gates remain open. |
| 7–8 | Portfolio dashboard, activity explorer, asset and source details | Dashboard sections, allocation with asset drilldowns, activity filters, asset account/FIFO details, observation evidence and per-address fetch status are available. Complete source audit, grouping, chart presentation and end-to-end traceability remain open. |
| 9–12 | Financial correctness, provider routing, prices and Bitcoin robustness | Exact quantities, FIFO foundations, manual corrections, Bitcoin graph/reorg handling and opt-in automatic hourly Bitcoin prices exist. Performance calculations, provider routing, broader price coverage and full Bitcoin acceptance remain open. |
| 13–15 | Ethereum/EVM, DeFi, additional chains and imports | Deferred until the user explicitly requests additional integrations. |
| 16–18 | Watchlists, alerts, deterministic insights and optional AI | Planned; local operation must remain independent of optional AI. |
| 19–24 | Optional managed services, metering, billing, release hardening and ongoing operations | Later work; hosted services and billing are optional and must not be required for the local tracker. |

## Current sequence

1. Validate automatic Bitcoin price refresh on devices and improve history coverage, freshness and chart presentation.
2. Complete asset, source, dashboard and activity workflows with traceable evidence and explicit missing, stale and partial states.
3. Expand financial and provider validation and Bitcoin recovery.
4. Add Ethereum/EVM and other integrations only when explicitly requested and their provenance, accounting and independent test gates are met.
5. Consider optional alerts, insights, AI and hosted convenience after the local experience is useful and reliable.

The 100,000-transaction snapshot memory limit is a known open issue and is deferred. The bounded reconciliation work is not connected to portfolio snapshots. Large-wallet acceptance remains open; see [validation](validation.md) for the measured behavior.

The tracked architecture and detailed milestone records are linked from the [documentation index](../README.md). The active detailed crypto-first roadmap is maintained in the project workspace as `polion_roadmap_wealthfolio_references.md`.
