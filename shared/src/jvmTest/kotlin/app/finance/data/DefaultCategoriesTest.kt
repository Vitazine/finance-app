package app.finance.data

import androidx.room.Room
import app.finance.data.database.FinanceDatabase
import app.finance.data.repository.LedgerStore
import app.finance.data.repository.buildLedgerDatabase
import app.finance.domain.ledger.DefaultCategories
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

class DefaultCategoriesTest {
    @Test fun installAndRetryKeepTenPersistedCategoriesWithoutRenamingOrRestoringUserData() =
        runBlocking {
            val directory = Files.createTempDirectory("default-categories-")
            fun open() = LedgerStore(
                buildLedgerDatabase(
                    Room.databaseBuilder<FinanceDatabase>(
                        name = directory.resolve("test.db").toString()
                    )
                )
            )
            var store = open()
            try {
                val customId = (
                    LedgerId.parse(
                        "00000000-0000-4000-8000-000000000001"
                    ) as LedgerResult.Success
                    ).value
                store.categories.create(customId, EntryType.EXPENSE, "Продукты")
                assertIs<LedgerResult.Success<Unit>>(DefaultCategories.install(store.categories))
                val first = (
                    store.categories.list(includeArchived = true)
                        as LedgerResult.Success
                    ).value
                assertEquals(10, first.size)
                assertEquals(customId, first.single { it.name == "Продукты" }.id)
                val cafe = first.single { it.name == "Кафе и рестораны" }
                store.categories.archive(cafe.id, Instant.parse("2026-11-02T00:00:00Z"))
                store.categories.rename(cafe.id, "Synthetic renamed")
                assertIs<LedgerResult.Success<Unit>>(DefaultCategories.install(store.categories))
                store.close()
                store = open()
                val restored = (
                    store.categories.list(includeArchived = true)
                        as LedgerResult.Success
                    ).value
                assertEquals(10, restored.size)
                assertEquals("Synthetic renamed", restored.single { it.id == cafe.id }.name)
                assertEquals(
                    Instant.parse("2026-11-02T00:00:00Z"),
                    restored.single { it.id == cafe.id }.archivedAt
                )
            } finally {
                store.close()
                directory.toFile().deleteRecursively()
            }
        }
}
