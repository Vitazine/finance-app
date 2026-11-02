package app.finance.core.hash

import java.security.MessageDigest

internal actual fun platformSha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)
