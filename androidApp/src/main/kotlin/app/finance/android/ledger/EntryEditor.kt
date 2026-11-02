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
import androidx.compose.material3.FilterChip
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EntryEditor(state: LedgerUiState, model: LedgerViewModel, padding: PaddingValues) {
    val editor = state.editor ?: return
    val settings = state.settings ?: return
    val fields = editor.fields
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
            stringResource(R.string.ledger_amount_hint),
            decimal = true
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
            stringResource(R.string.ledger_iso_hint)
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
            }
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
                decimal = true
            )
            editor.original?.entry?.conversion?.takeIf {
                it.sourceCurrency.code ==
                    fields.currency.trim().uppercase()
            }?.let { quote ->
                Text(
                    stringResource(
                        R.string.ledger_applied_rate,
                        quote.rateDecimal,
                        quote.effectiveAt.toString()
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
        LedgerField(
            fields.localTime,
            stringResource(
                R.string.ledger_local_time
            ),
            "editor_time",
            64,
            !state.busy,
            { text ->
                model.fields { it.withLocalTime(text) }
            },
            stringResource(R.string.ledger_local_time_hint, fields.zoneId)
        )
        val time = parseOccurrence(fields.localTime, fields.zoneId)
        if (time is InputResult.Failure && time.issue == InputIssue.TIME_AMBIGUOUS) {
            Text(stringResource(R.string.ledger_choose_offset))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                time.offsets.forEach { offset ->
                    FilterChip(selected = fields.offset == offset, onClick = {
                        model.fields { it.copy(offset = offset) }
                    }, enabled = !state.busy, label = { Text(offset) })
                }
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
            multiline = true
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
