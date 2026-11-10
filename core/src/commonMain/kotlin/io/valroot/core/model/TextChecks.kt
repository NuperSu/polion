package io.valroot.core.model

/*
 * Character scans for formats checked on every provider response. A regular expression would be compiled per call,
 * which is slow on Android when thousands of values are validated during a sync.
 */

/** A lowercase 64-character hex string, such as a transaction ID or block hash. */
fun String.isHash64(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

/** A non-negative integer without leading zeros, optionally limited to [maxDigits]. */
fun String.isCanonicalUnsigned(maxDigits: Int = Int.MAX_VALUE): Boolean =
    isNotEmpty() && length <= maxDigits && all { it in '0'..'9' } && (length == 1 || this[0] != '0')

/** A lowercase identifier: a letter, then letters, digits or underscores. */
fun String.isLowerIdentifier(): Boolean = isNotEmpty() && this[0] in 'a'..'z' && all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
