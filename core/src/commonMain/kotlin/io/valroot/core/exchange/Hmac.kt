package io.valroot.core.exchange

/** HMAC-SHA256 of [message] under [key], as exchanges use to sign API requests. */
expect fun hmacSha256(
    key: ByteArray,
    message: ByteArray,
): ByteArray

/** Lowercase hexadecimal, as exchanges expect signatures. */
fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/**
 * Query signing shared by Binance and MEXC: the query, then `timestamp` and `recvWindow`, then `signature`, the hex
 * HMAC-SHA256 of everything before it under the secret.
 */
object HmacQuery {
    fun query(params: List<Pair<String, String>>): String = params.joinToString("&") { (name, value) -> "$name=$value" }

    /** The query to sign: [params] with the timestamp and receive window appended. */
    fun timed(
        params: List<Pair<String, String>>,
        timestampMillis: Long,
        recvWindowMillis: Long,
    ): String = query(params + listOf("timestamp" to "$timestampMillis", "recvWindow" to "$recvWindowMillis"))

    fun signature(
        secret: String,
        query: String,
    ): String = hmacSha256(secret.encodeToByteArray(), query.encodeToByteArray()).toHex()

    /** [baseUrl] and [path] with the query, timed and signed when [secret] is given. */
    fun url(
        baseUrl: String,
        path: String,
        params: List<Pair<String, String>>,
        secret: String?,
        timestampMillis: Long,
        recvWindowMillis: Long,
    ): String {
        val text =
            if (secret != null) {
                val timed = timed(params, timestampMillis, recvWindowMillis)
                "$timed&signature=${signature(secret, timed)}"
            } else {
                query(params)
            }
        return baseUrl + path + if (text.isEmpty()) "" else "?$text"
    }
}
