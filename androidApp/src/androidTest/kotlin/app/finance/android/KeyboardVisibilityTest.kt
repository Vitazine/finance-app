package app.finance.android

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import app.finance.ai.contract.AiResult
import app.finance.ai.contract.LocalModelRef
import app.finance.ai.inference.FakeInferenceEngine
import app.finance.android.ai.AiViewModel
import app.finance.android.ledger.LedgerViewModel
import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.data.database.openLedgerStore
import app.finance.data.repository.LedgerStore
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.LedgerSettings
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

/** Real IME geometry, synthetic isolated ledger, no fake insets or test writes to user data. */
class KeyboardVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val caseName = TestName()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "keyboard-" + UUID.randomUUID() + ".db"
    private lateinit var store: LedgerStore
    private val models = ViewModelStore()
    private lateinit var ledger: LedgerViewModel
    private lateinit var ai: AiViewModel
    private var previousKeyboard = "null"
    private val raw = """
        {"schema_version":1,"status":"unsupported",
         "transactions":[],"missing_fields":[]}
    """.trimIndent()
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    @Before fun setup() {
        previousKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        store = openLedgerStore(context, name)
    }

    @After fun cleanup() {
        try {
            val jobs = listOfNotNull(
                if (::ledger.isInitialized) ledger.viewModelScope.coroutineContext[Job] else null,
                if (::ai.isInitialized) ai.viewModelScope.coroutineContext[Job] else null
            )
            compose.runOnUiThread {
                androidx.core.view.WindowCompat.getInsetsController(
                    compose.activity.window,
                    compose.activity.window.decorView
                ).hide(WindowInsetsCompat.Type.ime())
                models.clear()
            }
            runBlocking { withTimeout(15000) { jobs.forEach { it.join() } } }
            store.close()
            context.deleteDatabase(name)
        } finally {
            if (previousKeyboard == "null") {
                shell("settings delete secure show_ime_with_hard_keyboard")
            } else {
                shell("settings put secure show_ime_with_hard_keyboard $previousKeyboard")
            }
        }
    }
    private fun render(initialized: Boolean = true, fontScale: Float = 1f, output: String = raw) {
        if (initialized) {
            runBlocking {
                val usd = (CurrencyCode.resolve("USD") as MoneyResult.Success).value
                store.settings.initialize(
                    (LedgerSettings.create(usd, "UTC") as LedgerResult.Success).value
                )
            }
        }
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = models
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(c: Class<T>): T = when (c) {
                AiViewModel::class.java -> AiViewModel(
                    store,
                    FakeInferenceEngine(output),
                    (LocalModelRef.create("fixture") as AiResult.Success).value,
                    experimental = true
                )
                else -> LedgerViewModel(
                    store.settings,
                    store.categories,
                    store.transactions,
                    store.rates,
                    store.budgets,
                    store.analytics
                )
            } as T
        }
        compose.runOnUiThread {
            ledger = ViewModelProvider(owner, factory)[LedgerViewModel::class.java]
            ai = ViewModelProvider(owner, factory)[AiViewModel::class.java]
        }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                FinanceTheme { FinanceApp(ledger, ai) }
            }
        }
        compose.waitUntil(10000) { !ledger.state.value.loading && !ledger.state.value.busy }
    }
    private fun currentWindow() = WindowInspector.getGlobalWindowViews()
        .firstOrNull { it.hasWindowFocus() } ?: compose.activity.window.decorView

    // A floating Dialog entirely above the IME can correctly report zero IME insets.
    private fun keyboardInsets() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
    private fun keyboardTop(): Int {
        val view = compose.activity.window.decorView
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[1] + view.height -
            (keyboardInsets()?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
    }
    private fun openKeyboard(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        try {
            waitForKeyboard()
        } catch (failure: Throwable) {
            saveGeometry(tag)
            throw failure
        }
    }
    private fun waitForKeyboard() {
        var lastBottom = -1
        var stableSince = android.os.SystemClock.elapsedRealtime()
        compose.waitUntil(10000) {
            val insets = keyboardInsets()
            val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            val now = android.os.SystemClock.elapsedRealtime()
            if (bottom != lastBottom) {
                lastBottom = bottom
                stableSince = now
            }
            insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                bottom > 0 &&
                now - stableSince >= 350
        }
    }
    private fun fieldIsAboveKeyboard(tag: String): Boolean {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val frame = Rect()
        val view = currentWindow()
        view.getWindowVisibleDisplayFrame(frame)
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val bottom = node.positionInWindow.y + node.size.height + location[1]
        return bottom <= minOf(frame.bottom, keyboardTop()) + 2 &&
            node.positionInWindow.y >= 0 &&
            node.boundsInWindow.height >= node.size.height - 2
    }
    private fun saveGeometry(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val view = currentWindow()
        val insets = keyboardInsets()!!
        val frame = Rect().also(view::getWindowVisibleDisplayFrame)
        val data = org.json.JSONObject().put("tag", tag).put("node_y", node.positionInWindow.y)
            .put("node_height", node.size.height).put("view_height", view.height)
            .put("visible_bottom", minOf(frame.bottom, keyboardTop()))
            .put("ime_bottom", insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            .put("clipped_height", node.boundsInWindow.height)
        java.io.File(context.filesDir, "keyboard-geometry.json").writeText(data.toString(2))
        java.io.File(
            context.filesDir,
            "keyboard-${caseName.methodName}-$tag-${view.width}x${view.height}.json"
        )
            .writeText(data.toString(2))
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.filesDir, "keyboard-geometry.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        java.io.File(
            context.filesDir,
            "keyboard-${caseName.methodName}-$tag-${view.width}x${view.height}.png"
        )
            .outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        bitmap.recycle()
    }
    private fun assertVisible(tag: String) {
        try {
            compose.waitUntil(5000) { fieldIsAboveKeyboard(tag) }
        } catch (failure: Throwable) {
            saveGeometry(tag)
            throw failure
        }
        assertTrue("Focused field must stay above actual keyboard: $tag", fieldIsAboveKeyboard(tag))
    }

    @Test fun aiPromptRemainsVisibleWhenKeyboardOpens() {
        render()
        compose.onNodeWithTag("nav_AI").performClick()
        compose.waitUntil(10000) { ai.available }
        compose.runOnUiThread { ai.diagnostics(true) }
        openKeyboard("ai_prompt")
        saveGeometry("ai_prompt")
        assertVisible("ai_prompt")
    }

    @Test fun setupCurrencyRemainsVisibleWithoutTimeZoneInput() {
        render(initialized = false)
        openKeyboard("setup_currency")
        assertVisible("setup_currency")
        compose.onNodeWithTag("setup_zone").assertDoesNotExist()
    }
    private fun verifyFields(tags: List<String>) {
        tags.forEach { tag ->
            openKeyboard(tag)
            assertVisible(tag)
            val original = compose.onNodeWithTag(tag).fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text
            compose.onNodeWithTag(tag).performTextInput("1")
            assertVisible(tag)
            compose.onNodeWithTag(tag).performTextReplacement(original)
            assertVisible(tag)
        }
        assertZeroWrites()
    }
    private fun assertZeroWrites() {
        BundledSQLiteDriver().open(context.getDatabasePath(name).path).use { connection ->
            connection.prepare("SELECT COUNT(*) FROM ledger_transactions").use { statement ->
                assertTrue(statement.step())
                org.junit.Assert.assertEquals(0L, statement.getLong(0))
            }
        }
    }
    private fun editEntry() {
        compose.runOnUiThread {
            ledger.createEntry()
            ledger.fields { it.withCurrency("EUR") }
        }
    }

    @Test fun setupDoneDismissesKeyboardAndKeepsStartActionAvailable() {
        render(initialized = false)
        openKeyboard("setup_currency")
        assertVisible("setup_currency")
        compose.onNodeWithTag("setup_currency").performImeAction()
        compose.waitUntil(10000) {
            keyboardInsets()?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        compose.onNodeWithTag("setup_zone").assertDoesNotExist()
        assertVisible("setup_save")
        assertZeroWrites()
    }

    @Test fun manualEditorFieldsAndActionsStayAboveKeyboard() {
        render()
        editEntry()
        verifyFields(
            listOf("editor_amount", "editor_currency", "editor_rate", "editor_note")
        )
        assertVisible("editor_save")
        assertVisible("editor_cancel")
    }

    @Test fun filtersStayVisibleWhileTyping() {
        render()
        compose.onNodeWithTag("nav_TRANSACTIONS").performClick()
        compose.onNodeWithTag("filters_toggle").performClick()
        verifyFields(listOf("filter_currency", "filter_from", "filter_through"))
    }

    @Test fun budgetAmountStaysVisibleWhileTyping() {
        render()
        compose.onNodeWithTag("nav_ANALYTICS").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("budget_edit").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("budget_edit").performScrollTo().performClick()
        verifyFields(listOf("budget_amount"))
    }

    @Test fun categoryDialogNameStaysVisibleWhileTyping() {
        render()
        compose.runOnUiThread { ledger.categoryDialog(EntryType.EXPENSE) }
        // Dialog semantics can exist before Android has granted its window input focus.
        compose.waitUntil(10000) {
            val focused = currentWindow()
            focused !== compose.activity.window.decorView && focused.hasWindowFocus()
        }
        verifyFields(listOf("category_name"))
        saveGeometry("category_name")
    }

    @Test fun aiPreviewFieldsStayVisibleWithoutConfirmation() {
        val ready = """
            {"schema_version":1,"status":"ready","transactions":[
              {"type":"expense","amount":"12.50","currency":"EUR",
               "category_hint":"Fixture","date":"2026-11-02","description":"Synthetic"}
            ],"missing_fields":[]}
        """.trimIndent()
        render(output = ready)
        compose.onNodeWithTag("nav_AI").performClick()
        compose.waitUntil(10000) { ai.available }
        compose.runOnUiThread {
            ai.prompt("Synthetic input")
            ai.generate()
        }
        compose.waitUntil(10000) { !ai.state.value.busy && ai.state.value.previews.isNotEmpty() }
        verifyFields(
            listOf(
                "ai_amount_0",
                "ai_currency_0",
                "ai_rate_0",
                "ai_note_0"
            )
        )
    }

    @Test fun largeFontAiPromptStaysVisible() {
        render(fontScale = 2f)
        compose.onNodeWithTag("nav_AI").performClick()
        compose.waitUntil(10000) { ai.available }
        compose.runOnUiThread { ai.diagnostics(true) }
        openKeyboard("ai_prompt")
        assertVisible("ai_prompt")
        saveGeometry("ai_prompt")
        assertZeroWrites()
    }

    @Test fun landscapeEditorStaysVisible() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10000) {
            compose.activity.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        render()
        editEntry()
        verifyFields(
            listOf("editor_amount", "editor_currency", "editor_rate", "editor_note")
        )
        val paragraph = (1..8).joinToString("\n") { "Synthetic line $it" }
        compose.onNodeWithTag("editor_note").performTextReplacement(paragraph)
        assertVisible("editor_note")
        org.junit.Assert.assertEquals(
            paragraph,
            compose.onNodeWithTag("editor_note")
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        )
        saveGeometry("editor_note")
        assertZeroWrites()
    }
}
