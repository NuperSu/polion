# Full financial core

Milestone B implements roadmap phases 9–14 with synthetic integrations. Real blockchain adapters and product screens belong to later milestones.

| Phase | Behavior | Evidence |
| --- | --- | --- |
| 9 | Network-aware native/token/NFT/custom registry, metadata versions, provider mappings, exact fiat/asset prices, historical/current selection, overrides and explicit stale/missing states | Market repository tests for symbol collisions, time boundaries, manual precedence, freshness and provider fallback |
| 10 | Materialized account balances with reversible per-activity checkpoints, historical queries, owner/wallet/portfolio scopes and provider reconciliation | Holdings tests for transfer totals, inclusive timestamps, reorg invalidation, full/incremental parity and reopen |
| 11 | FIFO acquisition lots, disposals, proportional basis, internal transfers, opening balances, fees, audit trails and realized/unrealized unknowns | Common exact rational/FIFO tests, durable treatment tests and cross-family financial harness |
| 12 | Persistent versioned user annotations/corrections and rules, deterministic replay and explanations, scored review suggestions and explicit duplicate review | Common rule/matching tests, SQLite overlay/redecode tests, duplicate suppression conflict tests |
| 13 | Versioned checksummed portable export, authenticated encrypted backups, schema-checked transactional staging restore, importer preview and durable idempotence journal | Wrong-password/tamper/corrupt/interrupted-restore tests and whole-state financial snapshot round-trip |
| 14 | Reusable fake providers, deterministic clock/corruption injection, fixture data, full source-to-ledger-to-financial replay, measured large portfolio harness | New chain/exchange pipeline tests and 10,000-activity replay benchmark |

Read `phase9.md` through `phase14.md` for interfaces, policies and tradeoffs. No price is invented for missing data, and unknown acquisition cost remains unknown gain. Accounting treatments are explicit decisions; no jurisdictional tax policy is implied. Provider trust/completeness and price freshness remain observable inputs.

SQLite repositories use platform drivers. The live database is plaintext; portable backups can be encrypted with platform AES-GCM and PBKDF2. Staged restore requires application-created matching schemas and leaves the source database intact. Automatic installation of a restored database is a platform integration responsibility. Plaintext raw data must not contain credentials; backup discovery rejects credential/secret tables.

JVM integration tests exercise SQLite, restore and encryption; Android host tests exercise common engines, and APK compilation validates Android drivers/crypto bindings. Device runtime and hosted CI remain unverified locally. Final commands, test totals and benchmark evidence are recorded in `validation.md`.
