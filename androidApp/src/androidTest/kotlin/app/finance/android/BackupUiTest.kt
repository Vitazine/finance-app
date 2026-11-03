package app.finance.android

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.finance.android.backup.BackupViewModel
import app.finance.android.ledger.LedgerViewModel
import app.finance.core.money.ConversionSnapshot
import app.finance.core.money.CurrencyCode
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.data.database.openLedgerStore
import app.finance.data.repository.LedgerStore
import app.finance.domain.backup.BackupResult
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import app.finance.domain.ledger.ManualEntry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BackupUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "fin020-ui-" + UUID.randomUUID() + ".db"
    private lateinit var store: LedgerStore
    private lateinit var backup: BackupViewModel
    private lateinit var ledger: LedgerViewModel
    private val models = ViewModelStore()
    private lateinit var fixture: File
    private var beforeReplace: suspend () -> Unit = {}
    private fun id(n: Int) = (
        LedgerId.parse(
            "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
        ) as LedgerResult.Success
        ).value

    @Before fun setup() = runBlocking<Unit> {
        store = openLedgerStore(context, name)
        val usd = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
        store.settings.initialize((LedgerSettings.create(usd, "UTC") as LedgerResult.Success).value)
        store.categories.create(id(1), EntryType.EXPENSE, "Synthetic")
        val archive = (store.backups.exportJson() as BackupResult.Success).value
        fixture = File(context.cacheDir, name + ".json").also { it.writeText(archive) }
        val at = Instant.parse("2026-11-02T12:00:00Z")
        val fx = (
            ConversionSnapshot.identity(
                Money.ofMinor(125, usd),
                at
            ) as MoneyResult.Success
            ).value
        store.transactions.postManual(
            (
                ManualEntry.create(
                    id(10),
                    EntryType.EXPENSE,
                    id(1),
                    fx,
                    at,
                    "UTC",
                    null,
                    at
                ) as LedgerResult.Success
                ).value
        )
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = models
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                LedgerViewModel::class.java -> LedgerViewModel(
                    store.settings,
                    store.categories,
                    store.transactions,
                    store.rates,
                    store.budgets,
                    store.analytics
                )
                else -> BackupViewModel(
                    store.backups,
                    ledger::lockForImport,
                    ledger::importFinished,
                    beforeReplace = { beforeReplace() }
                )
            } as T
        }
        compose.runOnUiThread {
            ledger = ViewModelProvider(owner, factory)[LedgerViewModel::class.java]
            backup = ViewModelProvider(owner, factory)[BackupViewModel::class.java]
        }
        compose.setContent { FinanceTheme { FinanceApp(ledger, backupModel = backup) } }
        compose.waitUntil(15000) { !ledger.state.value.busy }
        compose.onNodeWithTag("nav_SETTINGS").performClick()
    }

    @After fun close() {
        context.contentResolver.call(
            Uri.parse("content://app.finance.android.test.stalled/pipe"),
            "release",
            null,
            null
        )
        val jobs =
            listOf(
                ledger.viewModelScope.coroutineContext[Job],
                backup.viewModelScope.coroutineContext[Job]
            )
        compose.runOnUiThread { models.clear() }
        runBlocking { withTimeout(15000) { jobs.forEach { it?.join() } } }
        fixture.delete()
        store.close()
        context.deleteDatabase(name)
    }
    private fun preview() {
        compose.runOnUiThread { backup.read(context.contentResolver, Uri.fromFile(fixture)) }
        compose.waitUntil(15000) { backup.state.value.preview != null }
    }
    private fun count() = runBlocking {
        (store.transactions.overview() as LedgerResult.Success).value.count
    }

    @Test fun catalogMarkerFailureAbortsReplacementAndLeavesLedgerAvailable() {
        beforeReplace = { error("Synthetic catalog marker failure") }
        preview()
        compose.onNodeWithTag("backup_replace_check").performScrollTo().performClick()
        compose.onNodeWithTag("backup_replace_confirm").performClick()
        compose.waitUntil(15000) { backup.state.value.error != null && !ledger.state.value.busy }
        assertEquals(1L, count())
        assertTrue(!backup.state.value.completed)
        compose.onNodeWithTag("nav_HOME").performClick()
        compose.onNodeWithTag("home_balance").assertIsDisplayed()
    }

    @Test fun previewAndCancelNeverReplaceUntilExplicitCheckboxAndConfirm() {
        preview()
        if (InstrumentationRegistry.getArguments().getString("saveProof") == "true") {
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            requireNotNull(bitmap)
            File(context.filesDir, "backup-preview.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
        assertEquals(1L, count())
        compose.onNodeWithTag("backup_replace_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("backup_cancel").performClick()
        assertEquals(1L, count())
        preview()
        compose.onNodeWithTag("backup_replace_check").performScrollTo().performClick()
        compose.onNodeWithTag("backup_replace_confirm").performClick()
        compose.waitUntil(15000) { backup.state.value.completed && !ledger.state.value.busy }
        assertEquals(0L, count())
        assertTrue(ledger.state.value.history.isEmpty())
    }

    @Test fun invalidUtf8AndArchiveLeaveOriginalData() {
        fixture.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        compose.runOnUiThread { backup.read(context.contentResolver, Uri.fromFile(fixture)) }
        compose.waitUntil(15000) { backup.state.value.error != null }
        assertEquals(1L, count())
        assertNull(backup.state.value.preview)
    }

    @Test fun canceledStalledProviderLeavesLedgerAvailableAndUnchanged() {
        compose.runOnUiThread {
            backup.read(
                context.contentResolver,
                Uri.parse("content://app.finance.android.test.stalled/pipe")
            )
        }
        compose.waitUntil(15000) { backup.state.value.busy }
        compose.onNodeWithTag("backup_cancel_operation").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !backup.state.value.busy }
        assertEquals(1L, count())
        compose.onNodeWithTag("nav_HOME").performClick()
        compose.onNodeWithTag("home_balance").assertIsDisplayed()
    }

    @Test fun stalledProviderWriteCanBeCanceledAndDescriptorReleased() = runBlocking {
        val job = async(kotlinx.coroutines.Dispatchers.IO) {
            app.finance.android.backup.SafDocumentIo.write(
                context.contentResolver,
                Uri.parse("content://app.finance.android.test.stalled/pipe"),
                ByteArray(1024 * 1024)
            )
        }
        try {
            kotlinx.coroutines.delay(200)
            assertTrue(!job.isCompleted)
            withTimeout(5000) { job.cancelAndJoin() }
            assertTrue(job.isCompleted)
        } finally {
            job.cancelAndJoin()
        }
    }
}
