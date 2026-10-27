# Architectural foundation acceptance

This milestone implements roadmap phases 2–8. It does not implement the financial engines, providers, reporting or product workflows from later milestones.

| Phase | Implementation | Verification |
| --- | --- | --- |
| 2 | Typed IDs, arbitrary signed integers, exact decimals, amounts and prices; explicit rounding and string round-trips | Common deterministic arithmetic/conversion tests plus JVM BigInteger oracle over large signed values |
| 3 | Owners, portfolios, account hierarchy, network-aware targets, overlaps, enabled tracker selection | Synthetic 100-address wallet, alias deduplication, ownership ambiguity and scope tests |
| 4 | JVM/Android SQLite drivers, transactional schema migrations, ownership snapshots, integrity and foreign keys | Reopen, rollback/retry migration, corrupt/future database, concurrent ingestion tests; Android driver compilation |
| 5 | Canonical source-native records, checksummed immutable payload revisions, separate provider observations | Repeat import, cross-provider deduplication, metadata conflict and provenance retention tests |
| 6 | Multi-leg signed activities, fees, source links, manual origin, status, append-only decoder revisions | Synthetic deposits/spends/transfers/trades/rewards, pending/reversed deltas, persisted replay/reopen |
| 7 | Compiled capability manifests and registry; narrow discovery/history/balance/decoder/import/health contracts | Fake Bitcoin, EVM and exchange integrations using the same ledger and persistence |
| 8 | Durable leased jobs, page/checkpoint transactions, cancellation, backoff, rate limits, provider failover and explicit coverage | Crash/reopen, repeated pages, rollback, gaps, stale-worker fencing and retry tests |

See `phase2.md`, `phase3.md`, `phase4-5.md`, `phase6.md`, `phase7.md` and `phase8.md` for decisions and contracts. Database encryption is not implemented; local database files and closed-file copies contain sensitive plaintext and require filesystem protection. Transport timeouts and platform background scheduling are supplied by integration hosts. No real provider is included at this milestone.

Final integrated build evidence is recorded in `validation.md`. Android host tests exercise common code on the JVM; they do not prove Android SQLite runtime behavior on a device. Android device runtime and hosted CI remain separate unverified acceptance items from the initial build foundation.
