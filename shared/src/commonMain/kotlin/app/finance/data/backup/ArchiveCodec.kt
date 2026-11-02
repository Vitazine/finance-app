package app.finance.data.backup

import app.finance.core.hash.sha256
import app.finance.core.json.strictJsonBoundary
import app.finance.data.database.AiConfirmationEntity
import app.finance.data.database.BudgetEntity
import app.finance.data.database.BudgetReadRow
import app.finance.data.database.CategoryEntity
import app.finance.data.database.RateEntity
import app.finance.data.database.RateReadRow
import app.finance.data.database.SettingsEntity
import app.finance.data.database.TransactionEntity
import app.finance.data.repository.CorruptRecord
import app.finance.data.repository.toDomain
import app.finance.domain.backup.BackupError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
internal const val MAX_ARCHIVE_RECORDS = 10000
internal class ArchiveFault(val reason: BackupError) : RuntimeException("Invalid archive")
internal fun invalid(reason: BackupError = BackupError.INVALID_RECORD): Nothing =
    throw ArchiveFault(reason)
internal data class LedgerSnapshot(
    val settings: SettingsEntity?,
    val categories: List<CategoryEntity>,
    val transactions: List<TransactionEntity>,
    val rates: List<RateEntity>,
    val budgets: List<BudgetEntity>,
    val confirmations: List<AiConfirmationEntity>
) {
    override fun toString() = "LedgerSnapshot"
    fun payload(): JsonObject = buildJsonObject {
        put(
            "settings",
            rows(
                listOfNotNull(settings).map {
                    listOf(it.id.toString(), it.primaryCurrency, it.zoneId)
                }
            )
        )
        put(
            "categories",
            rows(
                categories.sortedBy {
                    it.id
                }.map { listOf(it.id, it.type, it.name, it.archivedAt) }
            )
        )
        put(
            "transactions",
            rows(
                transactions.sortedBy {
                    it.id
                }.map {
                    listOf(
                        it.id,
                        it.type,
                        it.amountMinor.toString(),
                        it.sourceCurrency,
                        it.categoryId,
                        it.occurredAtSeconds.toString(),
                        it.occurredAtNanos.toString(),
                        it.displayZoneId,
                        it.note,
                        it.source,
                        it.createdAt,
                        it.updatedAt,
                        it.baseCurrency,
                        it.convertedMinor.toString(),
                        it.rateDecimal,
                        it.fxEffectiveAt,
                        it.fxSourceId,
                        it.roundingPolicy,
                        it.revision.toString()
                    )
                }
            )
        )
        put(
            "rates",
            rows(
                rates.sortedWith(
                    compareBy({
                        it.sourceCurrency
                    }, {
                        it.baseCurrency
                    }, {
                        it.effectiveAtSeconds
                    }, {
                        it.effectiveAtNanos
                    }, {
                        it.sourceId
                    })
                ).map {
                    listOf(
                        it.sourceCurrency,
                        it.baseCurrency,
                        it.effectiveAtSeconds.toString(),
                        it.effectiveAtNanos.toString(),
                        it.sourceId,
                        it.rateDecimal,
                        it.retrievedAtSeconds.toString(),
                        it.retrievedAtNanos.toString()
                    )
                }
            )
        )
        put(
            "budgets",
            rows(
                budgets.sortedBy {
                    it.yearMonth
                }.map { listOf(it.yearMonth, it.currency, it.limitMinor.toString(), it.updatedAt) }
            )
        )
        put(
            "confirmations",
            rows(
                confirmations.sortedWith(
                    compareBy({
                        it.draftId
                    }, {
                        it.proposalIndex
                    })
                ).map {
                    listOf(
                        it.draftId,
                        it.proposalIndex.toString(),
                        it.transactionId,
                        it.payloadHash
                    )
                }
            )
        )
    }
    fun digest() = sha256(payload().toString().encodeToByteArray())
    fun archive(): String {
        val json = buildJsonObject {
            put("format_version", 1)
            put("database_version", 5)
            put("payload", payload())
            put("sha256", digest())
        }.toString()
        if (json.length > MAX_ARCHIVE_BYTES ||
            json.encodeToByteArray().size > MAX_ARCHIVE_BYTES
        ) {
            invalid(BackupError.TOO_LARGE)
        }
        return json
    }
}
private fun rows(rows: List<List<String?>>) = JsonArray(
    rows.map {
        JsonArray(
            it.map { v ->
                v?.let(::JsonPrimitive)
                    ?: JsonNull
            }
        )
    }
)
private fun JsonElement.string(): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid(BackupError.INVALID_FORMAT)
private fun List<String?>.text(i: Int) = get(i) ?: invalid()
private fun List<String?>.long(i: Int): Long = text(i).let { s ->
    s.toLongOrNull()?.takeIf {
        it.toString() ==
            s
    }
        ?: invalid()
}
private fun List<String?>.int(i: Int): Int = long(i).let {
    if (it !in
        Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
    ) {
        invalid()
    } else {
        it.toInt()
    }
}

internal fun decodeArchive(json: String): LedgerSnapshot {
    if (json.length > MAX_ARCHIVE_BYTES ||
        json.encodeToByteArray().size > MAX_ARCHIVE_BYTES
    ) {
        invalid(BackupError.TOO_LARGE)
    }
    if (strictJsonBoundary(json, MAX_ARCHIVE_BYTES, 8) != null) invalid(BackupError.INVALID_FORMAT)
    val root =
        try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (
            _: IllegalArgumentException
        ) {
            null
        }
            ?: invalid(BackupError.INVALID_FORMAT)
    if (root.keys != setOf("format_version", "database_version", "payload", "sha256") ||
        root["format_version"] != JsonPrimitive(1) ||
        root["database_version"] != JsonPrimitive(5)
    ) {
        invalid(BackupError.INVALID_FORMAT)
    }
    val p = root["payload"] as? JsonObject ?: invalid(BackupError.INVALID_FORMAT)
    if (p.keys !=
        setOf("settings", "categories", "transactions", "rates", "budgets", "confirmations")
    ) {
        invalid(BackupError.INVALID_FORMAT)
    }
    val checksum = root.getValue("sha256").string()
    if (!Regex("[0-9a-f]{64}").matches(checksum)) invalid(BackupError.INVALID_FORMAT)
    if (sha256(p.toString().encodeToByteArray()) != checksum) invalid(BackupError.BAD_CHECKSUM)
    var count = 0
    fun table(name: String, columns: Int): List<List<String?>> {
        val a = p[name] as? JsonArray ?: invalid(BackupError.INVALID_FORMAT)
        count += a.size
        if (count > MAX_ARCHIVE_RECORDS) invalid(BackupError.TOO_LARGE)
        return a.map { element ->
            val row = element as? JsonArray ?: invalid(BackupError.INVALID_FORMAT)
            if (row.size != columns) invalid(BackupError.INVALID_FORMAT)
            row.map { if (it == JsonNull)null else it.string() }
        }
    }
    val settings = table("settings", 3)
    if (settings.size > 1) invalid()
    val snapshot = LedgerSnapshot(
        settings.singleOrNull()?.let { SettingsEntity(it.int(0), it.text(1), it.text(2)) },
        table("categories", 4).map { CategoryEntity(it.text(0), it.text(1), it.text(2), it[3]) },
        table("transactions", 19).map {
            TransactionEntity(
                it.text(
                    0
                ),
                it.text(
                    1
                ),
                it.long(
                    2
                ),
                it.text(
                    3
                ),
                it.text(
                    4
                ),
                it.long(
                    5
                ),
                it.int(
                    6
                ),
                it.text(
                    7
                ),
                it[8],
                it.text(
                    9
                ),
                it.text(
                    10
                ),
                it.text(
                    11
                ),
                it.text(
                    12
                ),
                it.long(13), it.text(14), it.text(15), it.text(16), it.text(17), it.long(18)
            )
        },
        table("rates", 8).map {
            RateEntity(
                it.text(0),
                it.text(1),
                it.long(2),
                it.long(3),
                it.text(4),
                it.text(5),
                it.long(6),
                it.long(7)
            )
        },
        table("budgets", 4).map { BudgetEntity(it.text(0), it.text(1), it.long(2), it.text(3)) },
        table("confirmations", 4).map {
            AiConfirmationEntity(it.text(0), it.int(1), it.text(2), it.text(3))
        }
    )
    validateSnapshot(snapshot)
    return snapshot
}
internal fun validateSnapshot(s: LedgerSnapshot) {
    if ((if (s.settings == null) 0 else 1) + s.categories.size + s.transactions.size +
        s.rates.size + s.budgets.size +
        s.confirmations.size >
        MAX_ARCHIVE_RECORDS
    ) {
        invalid(BackupError.TOO_LARGE)
    }
    fun <T> unique(items: List<T>, key: (T) -> Any) {
        if (items.map(key).toSet().size !=
            items.size
        ) {
            invalid()
        }
    }
    unique(s.categories) { it.id }
    unique(s.transactions) { it.id }
    unique(s.budgets) { it.yearMonth }
    unique(s.rates) {
        listOf(
            it.sourceCurrency,
            it.baseCurrency,
            it.effectiveAtSeconds,
            it.effectiveAtNanos,
            it.sourceId
        )
    }
    unique(s.confirmations) { listOf(it.draftId, it.proposalIndex) }
    unique(s.confirmations) { it.transactionId }
    try {
        val settings = s.settings?.toDomain()
        val categories = s.categories.associate { it.id to it.toDomain() }
        val receipts = s.confirmations.associateBy { it.transactionId }
        for (r in s.confirmations) {
            if ((LedgerId.parse(r.draftId) as? LedgerResult.Success)?.value?.text != r.draftId ||
                (LedgerId.parse(r.transactionId) as? LedgerResult.Success)?.value?.text !=
                r.transactionId ||
                r.proposalIndex !in 0..9 ||
                !Regex("[0-9a-f]{64}").matches(r.payloadHash)
            ) {
                invalid()
            }
        }
        for (row in s.transactions) {
            val t = row.toDomain()
            if (categories[row.categoryId]?.type != t.entry.type) invalid()
            if (settings != null &&
                t.entry.conversion.baseCurrency != settings.primaryCurrency
            ) {
                invalid()
            }
            if ((row.source == "AI") != receipts.containsKey(row.id)) invalid()
        }
        for (row in s.rates) {
            val rate = RateReadRow(
                row,
                "text:text:integer:integer:text:text:integer:integer"
            ).toDomain()
            if (settings == null || rate.quote.baseCurrency != settings.primaryCurrency) invalid()
        }
        for (row in s.budgets) {
            val budget = BudgetReadRow(row, "text:text:integer:text").toDomain()
            if (settings == null || budget.limit.currency != settings.primaryCurrency) invalid()
        }
        if (settings == null && s.transactions.map { it.baseCurrency }.toSet().size > 1) invalid()
    } catch (_: CorruptRecord) {
        invalid()
    } catch (_: IllegalArgumentException) {
        invalid()
    }
}
