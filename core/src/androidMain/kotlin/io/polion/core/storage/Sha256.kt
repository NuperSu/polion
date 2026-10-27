package io.polion.core.storage

import java.security.MessageDigest

actual fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
