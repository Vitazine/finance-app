package app.finance.android

import androidx.test.platform.app.InstrumentationRegistry
import app.finance.android.ledger.CategoryBootstrap
import app.finance.data.database.openLedgerStore
import app.finance.domain.ledger.Category
import app.finance.domain.ledger.CategoryRepository
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryBootstrapTest {
    @android.annotation.SuppressLint("UseKtx")
    @Test
    fun failedCommitVisibleInMemoryMustRetryDurablyBeforeReportingSuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "failed-marker-" + UUID.randomUUID()
        val disk = context.getSharedPreferences(name, 0)
        val store = openLedgerStore(context, name + ".db")
        try {
            var memory = false
            var attempts = 0
            val preferences = object : android.content.SharedPreferences by disk {
                override fun getBoolean(key: String, defValue: Boolean) = memory
                override fun edit(): android.content.SharedPreferences.Editor =
                    object : android.content.SharedPreferences.Editor by disk.edit() {
                        private var pending = false
                        private var pendingKey = ""
                        override fun putBoolean(
                            key: String,
                            value: Boolean
                        ): android.content.SharedPreferences.Editor {
                            pending = value
                            pendingKey = key
                            return this
                        }
                        override fun commit(): Boolean {
                            memory = pending // Android updates memory before attempting disk I/O.
                            attempts++
                            return attempts > 1 &&
                                disk.edit().putBoolean(pendingKey, pending).commit()
                        }
                    }
            }
            val bootstrap = CategoryBootstrap(store.categories, preferences)
            assertEquals(LedgerResult.Failure(LedgerError.STORAGE_FAILURE), bootstrap.ensure())
            assertTrue(memory)
            assertTrue(bootstrap.ensure() is LedgerResult.Success)
            val cafe = (store.categories.list() as LedgerResult.Success).value
                .single { it.name == "Кафе и рестораны" }
            store.categories.deleteUnused(cafe.id)
            // A new process reads only the persisted preferences, not the failed memory update.
            assertTrue(CategoryBootstrap(store.categories, disk).ensure() is LedgerResult.Success)
            assertEquals(9, (store.categories.list() as LedgerResult.Success).value.size)
        } finally {
            store.close()
            context.deleteDatabase(name + ".db")
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun importedCatalogIsNeverExpandedByStartupDefaults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "imported-catalog-" + UUID.randomUUID()
        val preferences = context.getSharedPreferences(name, 0)
        val store = openLedgerStore(context, name + ".db")
        try {
            val id = (
                LedgerId.parse("00000000-0000-4000-8000-000000000001")
                    as LedgerResult.Success
                ).value
            store.categories.create(id, EntryType.EXPENSE, "Synthetic imported category")
            val bootstrap = CategoryBootstrap(store.categories, preferences)
            bootstrap.preserveImportedCatalog()
            assertTrue(bootstrap.ensure() is LedgerResult.Success)
            assertEquals(
                listOf(id),
                (store.categories.list() as LedgerResult.Success).value.map {
                    it.id
                }
            )
        } finally {
            store.close()
            context.deleteDatabase(name + ".db")
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun partialFailureRetriesButCompletedInstallationNeverRecreatesDeletedCategories() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "category-bootstrap-" + UUID.randomUUID()
            val preferences = context.getSharedPreferences(name, 0)
            val store = openLedgerStore(context, name + ".db")
            try {
                var writes = 0
                val failing = object : CategoryRepository by store.categories {
                    override suspend fun create(
                        id: LedgerId,
                        type: EntryType,
                        name: String
                    ): LedgerResult<Category> {
                        writes++
                        if (writes == 3) return LedgerResult.Failure(LedgerError.STORAGE_FAILURE)
                        return store.categories.create(id, type, name)
                    }
                }
                assertEquals(
                    LedgerResult.Failure(LedgerError.STORAGE_FAILURE),
                    CategoryBootstrap(failing, preferences).ensure()
                )
                assertEquals(2, (store.categories.list() as LedgerResult.Success).value.size)
                assertTrue(
                    CategoryBootstrap(
                        store.categories,
                        preferences
                    ).ensure() is LedgerResult.Success
                )
                val categories = (store.categories.list() as LedgerResult.Success).value
                assertEquals(10, categories.size)
                store.categories.deleteUnused(
                    categories.single {
                        it.name == "Кафе и рестораны"
                    }.id
                )
                assertTrue(
                    CategoryBootstrap(
                        store.categories,
                        preferences
                    ).ensure() is LedgerResult.Success
                )
                assertEquals(9, (store.categories.list() as LedgerResult.Success).value.size)
            } finally {
                store.close()
                context.deleteDatabase(name + ".db")
                context.deleteSharedPreferences(name)
            }
        }
}
