# Architecture and inception decisions

Polion is an AGPL-3.0 local-first, watch-only portfolio tracker. Initial validation targets are Android API 26+ and Linux x86-64 desktop. This repository is independent of the adjacent Rotki checkout.

## Boundaries

Shared core owns domain and application behavior without Compose or platform framework dependencies. It provides exact financial primitives, ownership scopes, SQLite persistence, source provenance, versioned activities, connector and synchronization contracts, historical holdings and prices, explicit FIFO accounting, user overlays and reviewed reconciliation, and portable backups. `PortfolioApplication` coordinates those engines and caches projections. Shared Compose consumes that application service for portfolio workflows; Android and desktop hosts provide storage, networking, background scheduling, file dialogs and privacy-lock integration. The hosts serialize application operations and reload projections after background writes.

Bitcoin mainnet public-address history is fetched from a user-consented Esplora endpoint. Scans validate pagination counts and consistency and retain original transaction provenance before atomically updating the ledger. Block-position enrichment is optional; it does not delay the initial balance sync. Source coverage remains partial: independent chain verification and descriptor/xpub discovery are not complete. Bitcoin accounting reports identify uncertain FIFO ordering where timestamps do not establish chain order. The roadmap's full Bitcoin and product acceptance gates remain open; see [Milestone D](milestone-d.md).

## Privacy and threat model

Authoritative user data resides locally. Public explorer requests disclose watched addresses and network metadata to the configured endpoint. The application never ingests seeds or private keys, signs or broadcasts transactions, or requires a cloud account. Local malware, unlocked-device access, backups and diagnostic output can expose financial information. The live SQLite database is plaintext. A privacy lock protects the visible screen, not the underlying database; encrypted portable backups protect exported archives. OS credential-store integration is outstanding, and no provider credentials are currently stored. Diagnostic events must not include addresses, payloads, credentials or arbitrary exception text.

## Decisions

Four modules separate platform entry points from shared UI and core. The Gradle wrapper and dependency versions are pinned; JDK 21 is the development baseline. Linux and Android are initial acceptance targets; other desktop platforms need independent validation. [Validation evidence](validation.md) distinguishes automated checks from device and hosted CI verification.
