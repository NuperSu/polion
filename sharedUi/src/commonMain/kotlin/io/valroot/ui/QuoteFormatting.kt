package io.valroot.ui

import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal

/** Display rounding does not modify the persisted observation or calculation inputs. */
internal fun quotePrice(
    value: ExactDecimal,
    quote: AssetId,
): String = if (quote.value == "USD") money(value, quote) else "$value ${quote.value}"
