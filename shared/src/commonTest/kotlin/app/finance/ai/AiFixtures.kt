package app.finance.ai

import app.finance.ai.contract.AiContext
import app.finance.ai.contract.AiResult
import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.domain.ledger.Category
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import kotlinx.datetime.Instant

internal fun id(n: Int) = (
    LedgerId.parse(
        "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    ) as LedgerResult.Success
    ).value
internal fun currency(code: String) = (CurrencyCode.resolve(code) as MoneyResult.Success).value
internal fun category(
    n: Int = 1,
    name: String = "CafeFixture",
    type: EntryType = EntryType.EXPENSE,
    archived: Boolean = false
) = (
    Category.create(
        id(n),
        type,
        name,
        if (archived) Instant.parse("2026-01-01T00:00:00Z") else null
    ) as LedgerResult.Success
    ).value
internal fun settings() =
    (LedgerSettings.create(currency("USD"), "Asia/Manila") as LedgerResult.Success).value
internal fun context(categories: List<Category> = listOf(category())) = (
    AiContext.create(
        Instant.parse("2026-11-01T16:30:00Z"),
        settings(),
        categories
    ) as AiResult.Success
    ).value
internal fun transaction(
    amount: String = "12.50",
    code: String = "USD",
    hint: String = "CafeFixture",
    date: String = "2026-11-02",
    type: String = "expense",
    description: String = "SyntheticNote"
) = """
        {"type":"$type",
        "amount":"$amount",
        "currency":"$code",
        "category_hint":"$hint",
        "date":"$date",
        "description":"$description"}
""".trimIndent()
internal fun ready(vararg transactions: String) =
    """{"schema_version":1,"status":"ready","transactions":[${transactions.joinToString(
        ","
    )}],"missing_fields":[]}"""
