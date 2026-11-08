# Order book

Code: `core/…/market/KrakenOrderBook.kt`, `PortfolioApplication.krakenPair` / `orderBook`, `loadOrderBook` in `PlatformActions.kt`. Tests: `KrakenOrderBookTest`, `OrderBookApplicationTest`.

## Venue and pair

The coin page shows Kraken's book for one pair in the display currency, labelled with the venue and pair. It is never presented as a global order book.

- Bitcoin uses Kraken's XBT pair.
- Other coins use the Kraken markets CoinGecko lists for that coin's ID (one request, cached on the device for a week), so a coin is never matched to a Kraken market by ticker alone.
- A coin without a Kraken market in the display currency says so.

## Requests and consent

Loading needs a one-time consent: Kraken receives the pair and the device's IP address. Requests use Kraken's public Depth endpoint through the paced Kraken transport.

## Updates, gaps and staleness

While the tab is open, the app fetches a complete 10-level snapshot every 10 seconds and stops when the tab closes. Each snapshot replaces the previous one, so there are no incremental updates that a gap could corrupt; a missed or failed request is recovered by the next snapshot.

Every snapshot is validated: no Kraken error, positive prices and volumes, bids strictly descending, asks strictly ascending and best bid below best ask. A failed request keeps the last book, marks it out of date once it is older than 30 seconds, and offers a retry. Values are shown exactly as Kraken sent them.
