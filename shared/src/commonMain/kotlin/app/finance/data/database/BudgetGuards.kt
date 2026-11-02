package app.finance.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal object BudgetGuards {
    fun install(connection: SQLiteConnection) {
        for (operation in listOf("INSERT", "UPDATE")) {
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS budget_guard_${operation}_v4
                BEFORE $operation ON monthly_budgets
                WHEN typeof(NEW.yearMonth) != 'text'
                OR NEW.yearMonth NOT GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]'
                OR substr(NEW.yearMonth,1,4) NOT BETWEEN '0001' AND '9998'
                OR substr(NEW.yearMonth,6,2) NOT BETWEEN '01' AND '12'
                OR typeof(NEW.currency) != 'text' OR NEW.currency NOT GLOB '[A-Z][A-Z][A-Z]'
                OR typeof(NEW.limitMinor) != 'integer' OR NEW.limitMinor <= 0
                OR typeof(NEW.updatedAt) != 'text' OR length(NEW.updatedAt) NOT BETWEEN 1 AND 40
                OR NOT EXISTS(SELECT 1 FROM ledger_settings WHERE id=1 AND primaryCurrency=NEW.currency)
                BEGIN SELECT RAISE(ABORT, 'budget_constraint'); END
                """.trimIndent()
            )
        }
    }
}
