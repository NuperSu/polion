package io.polion.ui

import io.polion.core.model.AssetId
import io.polion.core.model.ExactDecimal

/** Display rounding does not modify the persisted observation or calculation inputs. */
internal fun quotePrice(
    value: ExactDecimal,
    quote: AssetId,
): String = if (quote.value == "USD") money(value, quote) else "$value ${quote.value}"
