package app.finance.core.money

private val decimalSyntax = Regex("[0-9]+(?:\\.[0-9]+)?")

internal fun isUnsignedDecimal(text: String): Boolean = decimalSyntax.matches(text)
