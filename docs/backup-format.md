# Encrypted backup format (version 3)

A Valroot backup file is an encryption envelope around a stream of table rows. Export and restore both stream: neither holds the whole backup in memory, so backup size is limited by storage, not by memory. Earlier formats are not readable; there were no public releases that produced them.

Code: `core/…/backup/Backup.kt` (body), `ByteStreams.kt` (field encoding), and the `BackupEncryption` envelope: `expect object` in `core/…/backup/Backup.kt` (commonMain), with a single `actual` in `core/src/jvmSharedMain/kotlin/io/valroot/core/backup/BackupEncryption.kt` shared by JVM and Android.

## Envelope

```
header (34 bytes, authenticated with every chunk)
  5  magic        "VLRBK" ("PLNBK" in backups made under the name Polion, still accepted)
  1  version      3
  1  kdf          1 = PBKDF2-HMAC-SHA256
  4  iterations   big-endian, written as 600 000; accepted 100 000 – 10 000 000
  16 salt         random
  7  nonce prefix random
frames, repeated
  4  length       big-endian, 16 – 65 552
  n  AES-256-GCM ciphertext of one plaintext chunk (≤ 64 KiB) plus its 16-byte tag
```

- The key is PBKDF2-HMAC-SHA256 over the password and salt, using the iteration count from the header. Raising the default later keeps older files readable.
- The nonce of chunk *i* is `prefix ‖ i (4 bytes, big-endian) ‖ last`, where `last` is 1 only for the final chunk. The final chunk may be empty.
- The header is the additional authenticated data for every chunk.
- Consequences:
  - A wrong password fails on the first chunk.
  - Any changed byte fails that chunk.
  - Reordered chunks fail, because the counter is part of the nonce.
  - A file cut at a chunk boundary fails, because the new final chunk was not sealed as last. Data appended after the final chunk makes the real final chunk fail.
- Memory use is one chunk on each side.

## Body

A sequence of fields. Each field is a 4-byte big-endian length followed by that many UTF-8 bytes; a length of -1 is a null value.

```
"VALROOT-BACKUP" "3" <schema user_version> <table count>     ("POLION-BACKUP" in older backups, still accepted)
per durable table, in name order:
  <table> <column count> <column>...
  per row, in rowid order:  "R" <value>...
  "E" <row count>
"END"
```

- **Tables:** durable tables are every application table except the Bitcoin scan scratch tables, the market feed cache (`market_feed_cache`), device-only settings such as API keys (`device_settings`) and `android_metadata`. Tables with "secret" or "credential" in the name are refused, and binary columns are refused.
- **Export:** export runs inside one read transaction, so the snapshot is consistent, and pages rows by rowid.
- **Restore checks:** restore writes into a new staging database created by the current application. It rejects:
  - a newer schema version;
  - a different table set or column list;
  - out-of-order tables;
  - a mismatched subsystem `_schema` row;
  - a row count that does not match `E`;
  - anything after `END`;
  - a foreign-key violation.
- **Atomicity:** all inserts happen in one transaction, and the host replaces the live database only after the staging database passes.

## Hosts

`PlatformActions.saveBackup(password)` and `restoreBackup(password)` ask for a file and stream it on a background thread.

- **Desktop:** a backup is written to a temporary file in the destination folder and moved into place, so a failure never leaves a partial backup.
- **Android:** a backup streams into the Storage Access Framework document, which is deleted if the export fails.
- **Restore failures:** restore messages report a wrong password, a damaged file or an unsupported version.
