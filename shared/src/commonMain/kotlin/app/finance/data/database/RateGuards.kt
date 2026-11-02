package app.finance.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal object RateGuards {
    fun install(connection: SQLiteConnection) {
        val invalid = """
            NEW.sourceCurrency NOT GLOB '[A-Z][A-Z][A-Z]'
            OR NEW.baseCurrency NOT GLOB '[A-Z][A-Z][A-Z]' OR NEW.sourceCurrency = NEW.baseCurrency
            OR typeof(NEW.effectiveAtSeconds) != 'integer' OR typeof(NEW.effectiveAtNanos) != 'integer'
            OR NEW.effectiveAtNanos NOT BETWEEN 0 AND 999999999
            OR typeof(NEW.retrievedAtSeconds) != 'integer' OR typeof(NEW.retrievedAtNanos) != 'integer'
            OR NEW.retrievedAtNanos NOT BETWEEN 0 AND 999999999
            OR typeof(NEW.rateDecimal) != 'text' OR length(NEW.rateDecimal) NOT BETWEEN 1 AND 128
            OR NEW.rateDecimal GLOB '*[^0-9.]*'
            OR substr(NEW.rateDecimal, 1, 1) NOT GLOB '[0-9]'
            OR substr(NEW.rateDecimal, -1, 1) NOT GLOB '[0-9]'
            OR length(NEW.rateDecimal) - length(replace(NEW.rateDecimal, '.', '')) > 1
            OR replace(replace(NEW.rateDecimal, '0', ''), '.', '') = ''
            OR length(NEW.sourceId) NOT BETWEEN 1 AND 128
            OR substr(NEW.sourceId, 1, 1) NOT GLOB '[A-Za-z0-9]'
            OR NEW.sourceId GLOB '*[^A-Za-z0-9._:/-]*'
        """.trimIndent()
        for (operation in listOf("INSERT", "UPDATE")) {
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS rate_guard_${operation}_v3
                BEFORE $operation ON exchange_rates WHEN $invalid
                BEGIN SELECT RAISE(ABORT, 'rate_constraint'); END
                """.trimIndent()
            )
        }
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS rate_revision_guard_v3 BEFORE UPDATE ON exchange_rates
            WHEN NEW.sourceCurrency != OLD.sourceCurrency OR NEW.baseCurrency != OLD.baseCurrency
            OR NEW.effectiveAtSeconds != OLD.effectiveAtSeconds OR NEW.effectiveAtNanos != OLD.effectiveAtNanos
            OR NEW.sourceId != OLD.sourceId OR NEW.retrievedAtSeconds < OLD.retrievedAtSeconds
            OR (NEW.retrievedAtSeconds = OLD.retrievedAtSeconds AND NEW.retrievedAtNanos < OLD.retrievedAtNanos)
            OR (NEW.retrievedAtSeconds = OLD.retrievedAtSeconds AND NEW.retrievedAtNanos = OLD.retrievedAtNanos
                AND NEW.rateDecimal != OLD.rateDecimal)
            BEGIN SELECT RAISE(ABORT, 'rate_conflict'); END
            """.trimIndent()
        )
    }
}
