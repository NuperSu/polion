# Phase 8 — Product acceptance and release

Read the [roadmap index](../ui-model-functional-roadmap.md) and its delivery rules. Prerequisite: [Phase 7 — Finish alerts, order books and CSV import](7-finish-alerts-order-books-and-csv-import.md) has passed its exit gate. Deliver the implementation and evidence specified below; record the tested revision and remaining blockers in the [parity inventory](../ui-model-parity.md).

Run complete user journeys through the packaged Android and desktop applications: first run; market browsing without holdings; Bitcoin connection; price/history refresh; buying/selling/transferring manually; import; coin transaction inspection/correction; Insights; alerts; export; backup/restore; privacy lock; archive/restore; and recovery.

Verify 360×800, 390×844 and 1440×900, scrolled sections, enlarged text, landscape, long quantities/names, keyboard focus, screen-reader labels and gesture conflicts. Inspect real screenshots against the agreed model, including forms and populated lower sections. Check notification/FAB clearance and table alignment.

Run the JDK 25 wrapper checks for core JVM/Android host, shared UI, desktop, Android lint, app/test APKs and desktop packaging. Execute the native reliability harness on an emulator and supported flows on a connected phone in its current Android user. Verify upgrades and encrypted restore preserve identities, quantities, treatments, corrections and settings. Run hosted CI and distinguish its evidence from local builds and device launches.

Complete a current requirement audit of the original roadmap and open Bitcoin/security/reliability gates. UI completion alone cannot certify them. Publish supported asset/provider/accounting/notification limits and reproducible recovery guidance. Keep new integrations and hosted services outside the release unless separately approved and tested.

**Exit:** visual, interaction, financial and platform gates all pass, with explicit remaining scope. A screen with a permanently unavailable modeled feature is not called fully functional.
