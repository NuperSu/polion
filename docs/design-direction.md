# Design direction

The production default uses the shared typed `ModelShell` and screen renderers, with Main/Portfolio/Insights destinations and semantic colors for Dark, Light and System appearances. Host adapters bind saved data where available and pass unavailable values for unsupported calculations or services. See [UI map](ui-map.md), [frontend behavior](frontend-rewrite.md) and [product contract](product-contract.md) for current workflows and capability boundaries.

## Visual hierarchy

Use an original, restrained financial workspace: dark, light and automatic modes; clear typography; compact status language; and generous space around the headline while keeping holdings rows dense enough to compare. Deep neutral surfaces, a distinct teal action color and separate semantic gain/loss colors are proposed. Do not rely on green/red alone: include signs, labels and accessible descriptions.

Define shared tokens for surface/text/border/action/status colors, spacing, shape, typography, elevation, chart strokes, focus/hover and icon sizes. Initial layout examples can use a 4 dp spacing rhythm, 16 dp phone gutters and 24 dp wide-pane gutters; verify actual fit and text scaling before treating these as final tokens. Keep financial tables aligned, with exact values available through accessible detail rather than clipping them without recourse.

Use licensed neutral icons or original marks. Wallet/exchange brand names describe optional metadata; their logos require a redistribution review. Do not copy reference artwork, exact card layouts, proprietary glyphs, copy or animations.

## Content examples

The following are wording/layout examples, not real portfolio data:

```text
Portfolio                              USD
Priced value                       $12,340
2 assets unpriced · View coverage
Observed 10:42 · History coverage partial

[Historical value]                     [1M]
Chart gaps indicate unavailable valuations

Bitcoin · Bitcoin mainnet
0.125 BTC                          $12,340
Quote observed 10:40 · Provider …
```

The hero says “Priced value” when it excludes unpriced assets. With no usable values show an unavailable marker and reason, not an invented zero. Chart tooltips should expose exact timestamp/value and coverage; gaps remain gaps. A separate performance view is introduced only when a tested money-flow-aware projection exists.

```text
Source attention
Refresh interrupted
Saved portfolio data remains available.
[Retry refresh] [View details]

Accounting
Unrealized P&L unavailable
Acquisition basis is missing for part of this holding.
[Review basis]
```

Do not use “P&L ready,” “all history found” or “fully connected” as generic completion copy. Do not invent portfolio health scores or financial advice. A timestamp needs an explicit fact: “Quote observed …” or “Refresh accepted …,” not an ambiguous “Updated.”

## Reusable surfaces

The shared presentation includes status rows, priced-value summaries, quantity and holdings rows, empty states, retry actions and provenance detail. Keep saved data readable during refresh and failure, and distinguish loading from known empty and failed states. Shared screen values come from typed host state; keep unavailable calculations visibly unavailable until their data is connected.

Compact layouts prioritize the headline and core assets; contextual detail opens as a screen or dialog with back and dismiss behavior. Wide layouts use a desktop sidebar and a width-bounded content column. Add analytical views only when their supporting projections are available.

## Verification for implementation phases

Use labeled synthetic fixtures to preview priced, unpriced, stale, partial, offline, rate-limited, denied, error and empty states. Review dark/light contrast, text scaling, narrow widths, landscape, long names/quantities, keyboard focus, screen-reader labels and reduced motion. Financial values remain exact in domain/state; conversion to drawing coordinates is permitted only at the chart boundary. Visual acceptance remains subject to user review.
