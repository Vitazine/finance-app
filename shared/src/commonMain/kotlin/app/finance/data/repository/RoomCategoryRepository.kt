package app.finance.data.repository

import app.finance.data.database.CategoryEntity
import app.finance.domain.ledger.Category
import app.finance.domain.ledger.CategoryRepository
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import kotlinx.datetime.Instant

internal class RoomCategoryRepository(private val access: DatabaseAccess) : CategoryRepository {
    override suspend fun create(
        id: LedgerId,
        type: EntryType,
        name: String
    ): LedgerResult<Category> = access.write {
        val category = Category.create(id, type, name)
        if (category is LedgerResult.Failure) return@write category
        if (access.dao.category(id.text) !=
            null
        ) {
            return@write LedgerResult.Failure(LedgerError.CONFLICT)
        }
        val value = (category as LedgerResult.Success).value
        access.dao.insertCategory(value.toEntity())
        category
    }

    override suspend fun rename(id: LedgerId, name: String): LedgerResult<Category> =
        mutate(id) { row ->
            when (
                val result = Category.create(
                    id,
                    EntryType.valueOf(row.type),
                    name,
                    row.archivedAt?.let(Instant::parse)
                )
            ) {
                is LedgerResult.Failure -> result
                is LedgerResult.Success -> LedgerResult.Success(result.value.toEntity())
            }
        }

    override suspend fun archive(id: LedgerId, at: Instant): LedgerResult<Category> = mutate(id) {
        LedgerResult.Success(it.copy(archivedAt = it.archivedAt ?: at.toString()))
    }

    override suspend fun restore(id: LedgerId): LedgerResult<Category> = mutate(id) {
        LedgerResult.Success(it.copy(archivedAt = null))
    }

    private suspend fun mutate(
        id: LedgerId,
        change: (CategoryEntity) -> LedgerResult<CategoryEntity>
    ): LedgerResult<Category> = access.write {
        val existing =
            access.dao.category(id.text) ?: return@write LedgerResult.Failure(LedgerError.NOT_FOUND)
        existing.toDomain()
        when (val result = change(existing)) {
            is LedgerResult.Failure -> result
            is LedgerResult.Success -> {
                access.dao.updateCategory(result.value)
                LedgerResult.Success(result.value.toDomain())
            }
        }
    }

    override suspend fun deleteUnused(id: LedgerId): LedgerResult<Unit> = access.write {
        val existing =
            access.dao.category(id.text) ?: return@write LedgerResult.Failure(LedgerError.NOT_FOUND)
        existing.toDomain()
        if (access.dao.categoryInUse(
                id.text
            )
        ) {
            return@write LedgerResult.Failure(LedgerError.CATEGORY_IN_USE)
        }
        access.dao.deleteCategory(id.text)
        LedgerResult.Success(Unit)
    }

    override suspend fun list(
        includeArchived: Boolean,
        limit: Int,
        offset: Int
    ): LedgerResult<List<Category>> {
        if (limit !in 1..200 || offset < 0) return LedgerResult.Failure(LedgerError.INVALID_PAGE)
        return access.read {
            LedgerResult.Success(
                access.dao.categories(includeArchived, limit, offset).map {
                    it.toDomain()
                }
            )
        }
    }
}
