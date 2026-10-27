# Architecture and inception decisions

Polion is an AGPL-3.0 local-first watch-only portfolio tracker. Initial validation targets are Android API 26+ and Linux x86-64 desktop. This repository is independent of the adjacent Rotki checkout.

## Boundaries

Shared core owns domain and application behavior without Compose or platform framework dependencies. Shared Compose UI consumes core contracts. Separate Android and JVM desktop hosts supply platform entry points. The UI hosts display the initial welcome shell. Shared core provides exact financial primitives, ownership scopes, SQLite persistence, raw provenance, versioned activities, connector contracts and durable synchronization. Portfolio projections, accounting and real provider implementations belong to later roadmap phases.

## Privacy and threat model

Authoritative user data will reside locally. Future public explorer requests disclose watched addresses and network metadata; onboarding must disclose this and allow private endpoints. The application must never ingest seeds or private keys, sign or broadcast transactions, or require a cloud account. Local malware, unlocked-device access, backups and diagnostic output can expose financial information. Encryption, secure credentials and recovery require explicit later implementation. The UI shell stores no financial data or credentials and makes no provider requests. Core repositories can persist sensitive ownership and raw source records in plaintext; application integration must protect local storage and disclose provider privacy boundaries. Diagnostic events must not include addresses, payloads, credentials or arbitrary exception text.

## Decisions

Four modules separate platform entry points from shared UI and core. The Gradle wrapper and dependency versions are pinned; JDK 21 is the development baseline. Linux and Android are initial acceptance targets; other desktop platforms need independent validation. No speculative adapter or database scaffolding is required.
