# Market data on Main

Main's market sections come from CoinGecko's public API. Kraken still prices owned Bitcoin for portfolio values.

Code: `core/…/market/MarketFeeds.kt` (parsing and cache), `MarketService.refreshFeeds` / `overview` (`app.market`), `sharedUi/…/MarketPresentation.kt` (sections), and `HttpCoingeckoTransport` in `core/src/jvmSharedMain/kotlin/io/valroot/core/market/`.

## Provider

- **Requests.** Two requests per refresh, with no API key required:
  - `/coins/markets?vs_currency=<display currency>&order=market_cap_desc&per_page=250&page=1&sparkline=true&price_change_percentage=1h,24h,7d` returns the top 250 coins with prices, market caps, changes and 7-day hourly sparklines (about 1 MB);
  - `/search/trending` returns CoinGecko's trending searches.
- **Rate limit.** The keyless limit is a few requests per minute.
  - The market list is refetched at most once a minute on manual refresh, and every 15 minutes on schedule or when Main opens with Auto-sync on.
  - Trending is refetched at most every 10 minutes.
  - An HTTP 429 pauses every feed request for one minute, and the saved lists stay on screen.
- **API key.** An optional CoinGecko Demo key (Settings › API Keys › External services) is sent as `x-cg-demo-api-key` for feeds and price history. It is kept in `device_settings`, which is not part of backups.
- **Consent.** CoinGecko receives the display currency and the device's IP address, and no wallet data. DefiLlama, which prices coins on EVM chains in USD, receives those coin IDs and the IP address. Market requests need market-data consent: the "Load market data" prompt on Main, or turning on Auto-sync. Consent is stored in `application_settings` and therefore backed up.
- **Attribution.** "Data by CoinGecko" appears under the Main sections.

## Identity and caching

- **Identity.** Coins are identified by CoinGecko coin ID, never by symbol. `bitcoin` maps to the portfolio's `bitcoin:btc`, so favorites and coin pages match holdings; other coins are `coingecko:<id>`.
- **Caching.** Each feed is cached per display currency, with the time CoinGecko returned it, in `market_feed_cache`. The cache is excluded from backups and refetched after a restore. Each feed is saved independently: a failed trending request keeps the market list, and every failure keeps the previous copy.
- **Trending prices.** A trending coin's price is its `price_btc` times CoinGecko's Bitcoin price in the display currency from the market list. Its 24-hour change comes from the trending entry in that currency.

## Sections and semantics

- **Top Coins:** the first 20 of the top 250 by market cap. Each card shows the 24-hour change and a line of the last 24 hourly points.
- **Trending:** CoinGecko's trending searches; their order is CoinGecko's.
- **Top Gainers / Top Losers:** the five largest 24-hour rises and falls *within the top 250 by market cap*, labelled "TOP 250 BY MARKET CAP · 24H". They are not whole-market rankings.
- **Favorites:** a star on any list adds or removes a coin; favorites are stored in `application_settings` as asset IDs.
- **Status line:** shows when the lists were updated, and any rate limit (with the wait), offline state or unreadable response.
- **Coin pages for coins you do not hold:**
  - they show the CoinGecko price, 24-hour and 7-day change, market cap and rank, and the 7-day hourly chart labelled 1W;
  - transactions can only be recorded for Bitcoin, so Add Transaction is disabled there.

## Logos

- **Source:** each coin's logo is the `image` URL from the market list, or `small` from trending, converted to CoinGecko's small size. Only `https://coin-images.coingecko.com` and `https://assets.coingecko.com` image URLs are accepted.
- **When they load:** logos load only after market-data consent, at most four downloads at a time, without redirects, up to 256 KiB each.
- **Caching:** logos are cached in `market_logo_cache` (excluded from backups) and refetched after 30 days. A logo that fails is not retried until the next launch.
- **Where they appear:** the same logo is used on Main, Portfolio holdings and coin pages, because coins share one asset ID. Coins without a logo URL show a coloured mark with their initial.

## Not covered

Price alerts and order books are M5.
