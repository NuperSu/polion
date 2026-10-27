# Portable backup and import

Portable backups contain versioned, checksummed, deterministic logical SQLite rows. Table discovery includes application tables and refuses credential or secret table names, so introducing secret-bearing storage requires an explicit separate policy. Restore targets a newly initialized staging database with matching trusted table names and columns, inside one transaction with foreign-key validation. The caller owns staging-file lifecycle and only replaces its live database after verifying the staged database; restore never edits the live database.

Encrypted backup uses PBKDF2-HMAC-SHA256 (310,000 iterations), a random salt, AES-256-GCM with a random nonce, and authenticated versioned header. Keep passwords outside logs. Decryption rejects tampering and wrong passwords. `RowImporter` supplies dry-run validation, row diagnostics, and intra-file duplicate detection; integrations must persist imported identity keys and perform commits transactionally for cross-import idempotence.

`ImportJournal.commit` persists per-importer keys and fingerprints in the same transaction as the caller's row writes; repeated rows are skipped and conflicting keys roll back the batch. The callback must perform writes on the supplied journal database and must not start another transaction.
