package app.finance.android.ledger

import java.math.BigInteger

/** Fixed-point geometry only. Monetary labels/totals never use chart coordinates. */
internal fun chartUnits(value: Long, maximum: Long): Int {
    require(maximum >= 0 && value >= 0 && value <= maximum)
    if (maximum == 0L) return 0
    return BigInteger.valueOf(value).multiply(BigInteger.valueOf(1000))
        .divide(BigInteger.valueOf(maximum)).toInt()
}
