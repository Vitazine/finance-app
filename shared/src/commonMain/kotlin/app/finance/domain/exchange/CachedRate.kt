package app.finance.domain.exchange

import app.finance.core.money.ExchangeRateQuote
import kotlinx.datetime.Instant

/** Observation metadata only; caller decides whether and when to offer it. */
class CachedRate(val quote: ExchangeRateQuote, val retrievedAt: Instant) {
    override fun toString(): String = "CachedRate"
}
