# CSV import

Code: `core/…/application/CsvImport.kt` (reading, template, dates, receipts), `CsvService.preview` / `importFile` / `reverse` (`app.csv`). Tests: `CsvImportTest`.

## Template

Columns: `date, type, asset, quantity, price, currency, fee, fee_currency, notes, reference`. Date, type, asset and quantity are required. Columns are matched by name, including common alternatives (for example "Timestamp", "Side", "Coin", "Amount", "TxID"); the user can change any column choice. Comma, semicolon and tab files are read, with quoted values as in RFC 4180. Files are limited to 5 MB and 20,000 rows.

- **Types:** buy, sell, deposit, withdrawal, reward (also staking, income, interest, mining, airdrop), fee, transfer in (receive) and transfer out (send). Transfers are external transfers into or out of the chosen account.
- **Numbers:** plain decimals such as `1234.56`. Thousands separators, decimal commas, exponents and negative amounts are rejected instead of guessed; the type gives the direction.
- **Dates:** ISO dates and times (`2026-01-15`, `2026-01-15 10:30`, `2026-01-15T10:30:00Z`, `…+02:00`) and Unix seconds or milliseconds. Times without an offset are in the device's time zone, which the preview names. Day-first and month-first dates such as `01/02/2026` are rejected.

## Explicit choices

Nothing is inferred from the data:

- **Account:** every row goes to one chosen account.
- **Price currency:** used when the row has no currency.
- **Fees without a fee currency:** in the price currency or in the row's asset, as chosen.
- **Assets:** text matches a known asset by ID or symbol only when exactly one asset matches. Anything else is listed as unresolved, and its rows stay invalid until the user picks the asset.

Buy and sell rows need a price; amounts are converted with each asset's precision exactly as in manual entry, with the same validation.

## Duplicates and provenance

Each row becomes a manual entry whose ID is a hash of the account and the row's reference, or of its content when there is no reference. A row imported before is therefore recognised in any later file and skipped. Identical rows within one file are kept as separate entries (two equal trades are possible), and the same file imported again matches them one to one. Two rows with the same reference are both rejected. Notes do not affect the ID.

## Preview, commit and receipt

The preview checks every row and saves nothing. Import saves every new row in one transaction with a receipt; if anything fails, nothing is saved. Rows are re-checked in that transaction, so a row saved meanwhile is skipped rather than duplicated. Invalid rows are listed on the receipt with their reason and are not saved.

Settings › Data › CSV Import History lists receipts: file, time, account and counts, with each row's outcome.

## Undo

Undo reverses every entry the import saved that is still active, in one transaction, including entries edited since. Reversed entries stay in the history. Importing the same rows again brings them back.
