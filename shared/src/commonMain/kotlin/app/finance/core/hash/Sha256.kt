package app.finance.core.hash

internal expect fun platformSha256(bytes: ByteArray): ByteArray
fun sha256(bytes: ByteArray): String = platformSha256(bytes).joinToString("") {
    (it.toInt() and 255).toString(16).padStart(2, '0')
}
