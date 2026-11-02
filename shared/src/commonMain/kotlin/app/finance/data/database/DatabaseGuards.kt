package app.finance.data.database

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Versioned guards supplement Room schema validation. No row values in errors. */
internal object DatabaseGuards : RoomDatabase.Callback() {
    override fun onOpen(connection: SQLiteConnection) {
        RateGuards.install(connection)
        BudgetGuards.install(connection)
        for (op in listOf("INSERT", "UPDATE")) {
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS ai_transaction_receipt_$op BEFORE $op ON ledger_transactions
                WHEN (NEW.source='AI' AND NOT EXISTS (SELECT 1 FROM ai_confirmations WHERE transactionId=NEW.id))
                OR (NEW.source!='AI' AND EXISTS (SELECT 1 FROM ai_confirmations WHERE transactionId=NEW.id)) BEGIN SELECT RAISE(ABORT, 'ai_confirmation_required'); END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS ai_receipt_guard_$op BEFORE $op ON ai_confirmations
                WHEN typeof(NEW.draftId)!='text' OR length(NEW.draftId)!=36
                OR typeof(NEW.proposalIndex)!='integer' OR NEW.proposalIndex NOT BETWEEN 0 AND 9
                OR typeof(NEW.transactionId)!='text' OR length(NEW.transactionId)!=36
                OR typeof(NEW.payloadHash)!='text' OR length(NEW.payloadHash)!=64
                OR NEW.payloadHash GLOB '*[^0-9a-f]*'
                BEGIN SELECT RAISE(ABORT, 'ai_receipt_constraint'); END
                """.trimIndent()
            )
        }
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS transaction_origin_immutable BEFORE UPDATE OF source ON ledger_transactions
            WHEN NEW.source!=OLD.source BEGIN SELECT RAISE(ABORT, 'origin_immutable'); END
            """.trimIndent()
        )
        val categoryInvalid = """
            NEW.type NOT IN ('INCOME', 'EXPENSE') OR length(NEW.name) NOT BETWEEN 1 AND 120
            OR trim(NEW.name) = '' OR length(NEW.id) != 36
        """.trimIndent()
        val transactionInvalid = """
            typeof(NEW.amountMinor) != 'integer' OR NEW.amountMinor <= 0
            OR typeof(NEW.convertedMinor) != 'integer' OR NEW.convertedMinor < 0
            OR NEW.type NOT IN ('INCOME', 'EXPENSE') OR NEW.source NOT IN ('MANUAL','AI')
            OR NEW.roundingPolicy != 'HALF_EVEN'
            OR typeof(NEW.occurredAtSeconds) != 'integer'
            OR typeof(NEW.occurredAtNanos) != 'integer' OR NEW.occurredAtNanos NOT BETWEEN 0 AND 999999999
            OR length(NEW.id) != 36
            OR NEW.sourceCurrency NOT GLOB '[A-Z][A-Z][A-Z]'
            OR NEW.baseCurrency NOT GLOB '[A-Z][A-Z][A-Z]'
            OR length(NEW.displayZoneId) NOT BETWEEN 1 AND 128
            OR (NEW.note IS NOT NULL AND length(NEW.note) > 2000)
            OR length(NEW.rateDecimal) NOT BETWEEN 1 AND 128
            OR NEW.rateDecimal GLOB '*[^0-9.]*'
            OR substr(NEW.rateDecimal, 1, 1) NOT GLOB '[0-9]'
            OR substr(NEW.rateDecimal, -1, 1) NOT GLOB '[0-9]'
            OR length(NEW.rateDecimal) - length(replace(NEW.rateDecimal, '.', '')) > 1
            OR replace(replace(NEW.rateDecimal, '0', ''), '.', '') = ''
            OR length(NEW.fxSourceId) NOT BETWEEN 1 AND 128
        """.trimIndent()
        for (operation in listOf("INSERT", "UPDATE")) {
            connection.execSQL("DROP TRIGGER IF EXISTS transaction_guard_$operation")
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS category_guard_$operation
                BEFORE $operation ON categories WHEN $categoryInvalid
                BEGIN SELECT RAISE(ABORT, 'category_constraint'); END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS transaction_guard_$operation
                BEFORE $operation ON ledger_transactions WHEN $transactionInvalid
                BEGIN SELECT RAISE(ABORT, 'ledger_constraint'); END
                """.trimIndent()
            )
        }
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS category_type_immutable
            BEFORE UPDATE OF type ON categories WHEN NEW.type != OLD.type
            BEGIN SELECT RAISE(ABORT, 'category_type_immutable'); END
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS transaction_active_category_insert
            BEFORE INSERT ON ledger_transactions
            WHEN EXISTS(SELECT 1 FROM categories WHERE id = NEW.categoryId AND archivedAt IS NOT NULL)
            BEGIN SELECT RAISE(ABORT, 'category_archived'); END
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS transaction_active_category_update
            BEFORE UPDATE OF categoryId, type ON ledger_transactions
            WHEN (NEW.categoryId != OLD.categoryId OR NEW.type != OLD.type)
            AND EXISTS(SELECT 1 FROM categories WHERE id = NEW.categoryId AND archivedAt IS NOT NULL)
            BEGIN SELECT RAISE(ABORT, 'category_archived'); END
            """.trimIndent()
        )
        for (operation in listOf("INSERT", "UPDATE")) {
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS transaction_revision_guard_${operation}_v2
                BEFORE $operation ON ledger_transactions
                WHEN typeof(NEW.revision) != 'integer' OR NEW.revision < 0
                BEGIN SELECT RAISE(ABORT, 'revision_constraint'); END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS settings_guard_${operation}_v2
                BEFORE $operation ON ledger_settings
                WHEN NEW.id != 1 OR NEW.primaryCurrency NOT GLOB '[A-Z][A-Z][A-Z]'
                     OR length(NEW.zoneId) NOT BETWEEN 1 AND 128
                BEGIN SELECT RAISE(ABORT, 'settings_constraint'); END
                """.trimIndent()
            )
        }
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS transaction_revision_step_v2
            BEFORE UPDATE ON ledger_transactions
            WHEN NEW.id != OLD.id OR NEW.createdAt != OLD.createdAt OR NEW.revision != OLD.revision + 1
            BEGIN SELECT RAISE(ABORT, 'revision_conflict'); END
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS settings_immutable_v2
            BEFORE UPDATE ON ledger_settings
            WHEN NEW.id != OLD.id OR NEW.primaryCurrency != OLD.primaryCurrency OR NEW.zoneId != OLD.zoneId
            BEGIN SELECT RAISE(ABORT, 'settings_immutable'); END
            """.trimIndent()
        )
    }
}
