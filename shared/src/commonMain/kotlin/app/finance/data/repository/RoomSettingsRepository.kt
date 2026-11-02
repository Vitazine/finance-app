package app.finance.data.repository

import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.data.database.SettingsEntity
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.SettingsRepository

internal fun SettingsEntity.toDomain(): LedgerSettings {
    val currency =
        (CurrencyCode.resolve(primaryCurrency) as? MoneyResult.Success)?.value
            ?: throw CorruptRecord()
    val result =
        (LedgerSettings.create(currency, zoneId) as? LedgerResult.Success)?.value
            ?: throw CorruptRecord()
    if (id != 1 ||
        result.primaryCurrency.code != primaryCurrency ||
        result.zoneId != zoneId
    ) {
        throw CorruptRecord()
    }
    return result
}

internal class RoomSettingsRepository(private val access: DatabaseAccess) : SettingsRepository {
    override suspend fun current(): LedgerResult<LedgerSettings?> = access.read {
        LedgerResult.Success(access.dao.settings()?.toDomain())
    }

    override suspend fun existingBasis(): LedgerResult<CurrencyCode?> = access.snapshot {
        scanBasis()
    }

    override suspend fun initialize(settings: LedgerSettings): LedgerResult<LedgerSettings> =
        access.write {
            val existing = access.dao.settings()?.toDomain()
            if (existing != null) {
                return@write if (existing.primaryCurrency == settings.primaryCurrency &&
                    existing.zoneId == settings.zoneId
                ) {
                    LedgerResult.Success(existing)
                } else {
                    LedgerResult.Failure(LedgerError.CONFLICT)
                }
            }
            when (val basis = scanBasis()) {
                is LedgerResult.Failure -> return@write basis
                is LedgerResult.Success -> if (basis.value != null &&
                    basis.value != settings.primaryCurrency
                ) {
                    return@write LedgerResult.Failure(LedgerError.BASE_CURRENCY_MISMATCH)
                }
            }
            access.dao.insertSettings(
                SettingsEntity(
                    primaryCurrency = settings.primaryCurrency.code,
                    zoneId = settings.zoneId
                )
            )
            LedgerResult.Success(settings)
        }

    private suspend fun scanBasis(): LedgerResult<CurrencyCode?> {
        var after: String? = null
        var basis: CurrencyCode? = null
        while (true) {
            val rows = access.dao.transactionBatch(after, 100)
            if (rows.isEmpty()) break
            for (row in rows) {
                val currency = row.toDomain().entry.conversion.baseCurrency
                if (basis != null &&
                    basis != currency
                ) {
                    return LedgerResult.Failure(LedgerError.LEGACY_BASE_CONFLICT)
                }
                basis = currency
            }
            after = rows.last().id
        }
        return LedgerResult.Success(basis)
    }
}
