# UI map

Polion's production default uses the shared typed `ModelShell` and screen renderers, with **Main, Portfolio, Insights** in compact navigation and the desktop sidebar. The contextual menu opens Accounts, Settings and Advanced tools. Screen state is supplied from saved application data; each screen shows unavailable values where a calculation or source is not connected. The detailed data bindings, workflows, recreation state and refresh behavior are in [Frontend and refresh behavior](frontend-rewrite.md).

- Main presents saved market prices and favorites.
- Portfolio presents portfolio selection, priced balance, historical value and holdings. Saved hide-empty, hide-small and sort preferences apply; Sort / filter controls open contextual tools. Quote/currency changes are in Portfolio tools. Value changes and gain fields are unavailable in the shared screen; the floating Add action opens the full-screen shared form.
- Coin details provide General, Transactions, Alerts and Order book tabs. Owned quantity, market value and saved quote are bound; unsupported cost-basis metrics and unavailable services remain visibly unavailable. Coin-level search/type filters are not bound; Transactions links to contextual activity tools, which retain ledger search and filters. Coin detail returns with Back, while workflow forms use Close.
- Insights binds portfolio and priced balance; analytics and reports remain in contextual tools until their data is available to the shared screen.
- Accounts and Settings expose summary controls with deeper account and configuration workflows in Advanced tools.
- Advanced tools retains account, portfolio, activity, reporting and settings workflows, including existing transaction records and accounting review.
- The inline alert editor is fixture-only; production alert delivery is planned for Phase 7.

Financial presentation follows the [product contract](product-contract.md): saved evidence, partial coverage and unknown basis remain distinct from provider success. See [validation](validation.md) for executed responsive, platform and workflow checks.
