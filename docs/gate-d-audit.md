# Gate D requirement audit — 2026-11-08

This audit checks the open Gate D items and the M6 release requirements against the original roadmap, at branch `review-fixes`. "Met" means the stated acceptance criterion has code and a test or recorded run; it is not a security certification.

## Open Gate D items

**Phase 7 — connector execution through the registry: met.**
- Hosts refresh sources through `PortfolioApplication.refreshSources(SourceRegistry)`. Bitcoin is the registered `BitcoinConnector` with its manifest (`BITCOIN_MANIFEST`); each network has at most one history connector, and a tracked network without one is reported.
- New families use the generic `ingestHistory` path (sync orchestrator, raw provenance, decoder). `SourceConnectorTest` adds a fake EVM family purely through the interface; its activity reaches holdings, export and the ledger without changes to core holdings or accounting, and a repeated refresh does not duplicate it. Earlier fake-family tests (`ConnectorTest`, `SyncPipelineTest`, `FinancialHarnessTest`) still pass.
- Remaining: discovery, balance and health capabilities have no generic interface in the app; Bitcoin keeps its own discovery and order-verification calls.

**Phase 12 — reconciliation integration: met for transfers; duplicates without a review screen.**
- Edits, labels and classifications survive re-decoding and reopening (`OverlayRepositoryTest`, `FinancialHarnessTest`); ambiguous suggestions stay unresolved and are labelled as such.
- Accepted transfer links now move lots with their cost and age in the FIFO report instead of a disposal and a new acquisition; rejecting a link restores both (`TransferLinkAccountingTest`). The transfer review shows each side's date, account and amount.
- Accepted duplicate decisions are honoured by holdings, accounting and export, and survive backup (`RestorePreservationTest`), but the product has no screen to make them.

**Phase 15 — mature descriptor implementation: met.** Key decoding, BIP32 derivation, address encoding and validation, and descriptor checksums use ACINQ's bitcoin-kmp with libsecp256k1. Polion's own code parses only its limited descriptor grammar. BIP 84, BIP 173/350 and BIP 380 vectors pass on JVM; the release APK (R8) validated an xpub and derived 83 addresses on an Android 15 emulator. SegWit witness versions above 1 are rejected.

**Phase 20 — Bitcoin financial acceptance: met against fixtures; independent real-chain evidence partial.**
- `BitcoinWholeHistoryAcceptanceTest`: a two-address, multi-page history (payments in, spends with change, an external payment and a move between the wallets) synced once, interrupted after 3, 8, 15 and 22 requests and resumed, and rebuilt from scratch gives identical exports, holdings, account holdings, FIFO lots and disposals, ledger and transaction graph. Interrupted runs accept nothing partial.
- `BitcoinMainnetEvidenceTest` re-derives real mainnet transaction IDs, headers and merkle roots; staged-versus-provider UTXO comparison runs on every refresh.
- Remaining: no comparison with a real provider's UTXO snapshot or with independent historical price and cost-basis data; the large-wallet memory limit (`BUGS.md`).

## Related phases

- **16 — providers:** Esplora history, UTXO retrieval and failover from blockstream.info to mempool.space for the default endpoint (custom endpoints never fail over).
- **17–19 — graph, reorgs, decoding:** bounded rewind, RBF and conflict handling at graph level, multi-input/change/fee decoding (see `BitcoinUtxoGraphTest`, `BitcoinFailoverAndReorgTest`, `EsploraTest`). RBF is not exercised through a full refresh.

## M6 release requirements

- **Packaged journeys:** release APK on an Android 15 emulator: first run, Main with live CoinGecko data, BTC page with the live Kraken order book, xpub import with discovery and history sync. Packaged desktop distribution launched with its own runtime and an isolated data folder. Earlier milestones' device journeys remain recorded in [milestone D](milestone-d.md).
- **Native harness:** `ReliabilityInstrumentation` on the Android 15 emulator: 21 checks, 0 failures (with bitcoin-kmp).
- **Viewports, text size, labels, focus:** `AccessibilityTest` at 360×800, 390×844 and 1440×900 at 1.0 and 1.5 text scale: every clickable has a label, icon-only controls are at least 48 dp, toggles publish their state, navigation stays in the window; desktop keyboard navigation is tested in `ModelWorkspaceRestorationTest`. No TalkBack audit on a device.
- **Upgrade and restore:** `RestorePreservationTest` (every kind of user record, no alert redelivery, byte-identical re-export) and older-schema restores in `BackupTest`. An in-place APK update keeping data was verified in earlier milestones; the switch to the release key requires a reinstall ([release](release.md)).
- **Hosted CI:** not run from this revision; the workflow builds the R8 APK and the desktop distribution in addition to the tests.
- **Published limits:** [release](release.md).

## Verdict

Gate D's four named items have implementations and tests. Gate D is not fully accepted while these remain: a duplicate-review screen, real-provider UTXO and independent price/cost-basis evidence, a device screen-reader audit, the large-wallet memory limit, and a hosted CI run.
