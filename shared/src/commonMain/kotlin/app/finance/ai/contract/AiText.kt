package app.finance.ai.contract

/** Reject unpaired UTF-16 and invisible directional overrides; no normalization of user text. */
internal fun validAiText(value: String, limit: Int, multiline: Boolean = false): Boolean {
    if (value.length > limit) return false
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if ((c.isISOControl() && !(multiline && (c == '\n' || c == '\t'))) ||
            c.code in 0x202a..0x202e ||
            c.code in 0x2066..0x2069 ||
            c.code in 0x200e..0x200f
        ) {
            return false
        }
        if (c.isHighSurrogate()) {
            if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return false
            i++
        } else if (c.isLowSurrogate()) {
            return false
        }
        i++
    }
    return true
}
