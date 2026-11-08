package app.finance.android.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.finance.android.R
import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EntryEditor(state: LedgerUiState, model: LedgerViewModel, padding: PaddingValues) {
    val editor = state.editor ?: return
    val settings = state.settings ?: return
    val fields = editor.fields
    val currency = if (fields.currency.length <= 8) {
        (CurrencyCode.resolve(fields.currency.trim().uppercase()) as? MoneyResult.Success)?.value
    } else {
        null
    }
    val amountHint = when {
        currency == null -> stringResource(R.string.ledger_amount_hint)
        currency.exponent == 0 -> stringResource(R.string.ledger_amount_whole_hint, currency.code)
        else -> stringResource(
            R.string.ledger_amount_precision_hint,
            currency.code,
            currency.exponent
        )
    }
    Column(
        Modifier.fillMaxSize().padding(
            padding
        ).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(
                if (editor.original ==
                    null
                ) {
                    R.string.ledger_new_entry
                } else {
                    R.string.ledger_edit_entry
                }
            ),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics {
                heading()
            }
        )
        TypeChoices(fields.type, enabled = !state.busy) { selected ->
            if (selected !=
                null
            ) {
                model.fields {
                    it.copy(
                        type = selected,
                        categoryId = if (selected ==
                            it.type
                        ) {
                            it.categoryId
                        } else {
                            null
                        }
                    )
                }
            }
        }
        LedgerField(
            fields.amount,
            stringResource(
                R.string.ledger_amount
            ),
            "editor_amount",
            64,
            !state.busy,
            { text ->
                model.fields { it.copy(amount = text) }
            },
            amountHint,
            decimal = true,
            error = state.editorIssue?.issue?.takeIf {
                it == InputIssue.INVALID_AMOUNT || it == InputIssue.AMOUNT_OVERFLOW
            }?.let { stringResource(it.message()) },
            modifier = Modifier.revealEditorIssue(
                state,
                InputIssue.INVALID_AMOUNT,
                InputIssue.AMOUNT_OVERFLOW
            )
        )
        LedgerField(
            fields.currency,
            stringResource(
                R.string.ledger_source_currency
            ),
            "editor_currency",
            8,
            !state.busy,
            { text ->
                model.fields { it.withCurrency(text) }
            },
            stringResource(R.string.ledger_iso_hint),
            modifier = Modifier.revealEditorIssue(state, InputIssue.INVALID_CURRENCY)
        )
        val current =
            editor.selectedCategory ?: state.categories.find { it.id == fields.categoryId }
        val label =
            current?.let {
                if (it.archivedAt !=
                    null
                ) {
                    stringResource(R.string.ledger_archived_suffix, it.name)
                } else {
                    it.name
                }
            }
                ?: stringResource(R.string.ledger_choose_category)
        ChoiceMenu(
            label,
            "editor_category",
            !state.busy,
            state.categories.filter {
                it.type ==
                    fields.type &&
                    (it.archivedAt == null || it.id == editor.original?.entry?.categoryId)
            }.map { category ->
                category.name to
                    { model.fields { it.copy(categoryId = category.id) } }
            },
            modifier = Modifier.revealEditorIssue(state, InputIssue.MISSING_CATEGORY)
        )
        TextButton(onClick = {
            model.categoryDialog(fields.type, fromEditor = true)
        }, enabled = !state.busy, modifier = Modifier.testTag("editor_new_category")) {
            Text(stringResource(R.string.ledger_new_category))
        }
        if (state.moreCategories) {
            TextButton(
                onClick = model::moreCategories,
                enabled = !state.busy
            ) {
                Text(stringResource(R.string.ledger_more_categories))
            }
        }
        if (fields.currency.trim().uppercase() != settings.primaryCurrency.code) {
            LedgerField(
                fields.rate,
                stringResource(
                    R.string.ledger_rate,
                    fields.currency.trim().uppercase(),
                    settings.primaryCurrency.code
                ),
                "editor_rate",
                128,
                !state.busy,
                { text ->
                    model.fields { it.withRate(text) }
                },
                stringResource(R.string.ledger_rate_hint),
                decimal = true,
                modifier = Modifier.revealEditorIssue(
                    state,
                    InputIssue.MISSING_RATE,
                    InputIssue.INVALID_RATE
                )
            )
            editor.original?.entry?.conversion?.takeIf {
                it.sourceCurrency.code ==
                    fields.currency.trim().uppercase()
            }?.let { quote ->
                Text(
                    stringResource(
                        R.string.ledger_applied_rate,
                        quote.rateDecimal,
                        displayRateTime(quote.effectiveAt, fields.zoneId)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (fields.currency.trim().uppercase() !=
            settings.primaryCurrency.code
        ) {
            RatePanel(state, model)
        }
        Column(
            Modifier.revealEditorIssue(
                state,
                InputIssue.INVALID_TIME,
                InputIssue.TIME_GAP,
                InputIssue.TIME_AMBIGUOUS
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EntryDateTimeField(fields.localTime, "editor_time", !state.busy) { text ->
                model.fields { it.withLocalTime(text) }
            }
            OccurrenceChoices(fields, "editor_time", !state.busy) { offset ->
                model.fields { it.copy(offset = offset) }
            }
        }
        LedgerField(
            fields.note,
            stringResource(
                R.string.ledger_note
            ),
            "editor_note",
            2000,
            !state.busy,
            { text ->
                model.fields { it.copy(note = text) }
            },
            multiline = true,
            modifier = Modifier.revealEditorIssue(state, InputIssue.INVALID_NOTE)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorActions(state: LedgerUiState, model: LedgerViewModel) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.editorIssue?.let { ErrorText(stringResource(it.issue.message()), "editor_error") }
        state.error?.let {
            ErrorText(stringResource(it.message()))
            if (state.editor?.original !=
                null
            ) {
                TextButton(
                    onClick = model::reloadEditor,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("editor_reload")
                ) {
                    Text(stringResource(R.string.ledger_reload_entry))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                focus.clearFocus()
                keyboard?.hide()
                model.saveEditor()
            }, enabled = !state.busy, modifier = Modifier.testTag("editor_save")) {
                Text(stringResource(R.string.ledger_save))
            }
            TextButton(
                onClick = model::cancelEditor,
                enabled = !state.busy,
                modifier = Modifier.testTag("editor_cancel")
            ) {
                Text(stringResource(R.string.ledger_cancel))
            }
        }
    }
}
