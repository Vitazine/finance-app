package app.finance.domain.ledger

class LedgerId private constructor(val text: String) {
    override fun equals(other: Any?): Boolean = other is LedgerId && text == other.text
    override fun hashCode(): Int = text.hashCode()
    override fun toString(): String = "LedgerId"

    companion object {
        private val syntax =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun parse(text: String): LedgerResult<LedgerId> {
            if (text.length != 36 || !syntax.matches(text)) {
                return LedgerResult.Failure(LedgerError.INVALID_ID)
            }
            return LedgerResult.Success(LedgerId(text.lowercase()))
        }
    }
}
