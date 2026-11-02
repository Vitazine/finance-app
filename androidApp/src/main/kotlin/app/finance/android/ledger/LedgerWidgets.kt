package app.finance.android.ledger

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.finance.android.R
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerError

@Composable
internal fun LedgerField(
    value: String,
    label: String,
    tag: String,
    limit: Int,
    enabled: Boolean,
    onChange: (String) -> Unit,
    hint: String? = null,
    decimal: Boolean = false,
    multiline: Boolean = false,
    readOnly: Boolean = false
) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(limit + 1)) },
        label = { Text(label) }, supportingText = hint?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth().testTag(tag), enabled = enabled, readOnly = readOnly,
        singleLine = !multiline, minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = if (multiline) ImeAction.Default else ImeAction.Next
        )
    )
}

@Composable
internal fun ErrorText(text: String, tag: String = "ledger_error") {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics {
            liveRegion =
                LiveRegionMode.Polite
        }
    )
}

@Composable
internal fun ChoiceMenu(
    label: String,
    tag: String,
    enabled: Boolean,
    choices: List<Pair<String, () -> Unit>>
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = {
                expanded = true
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(
                min = 48.dp
            ).testTag(tag)
        ) {
            Text(label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded =
                        false
                    action()
                })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TypeChoices(
    selected: EntryType?,
    all: Boolean = false,
    enabled: Boolean = true,
    onSelect: (EntryType?) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (all) {
            FilterChip(selected = selected == null, onClick = {
                onSelect(null)
            }, enabled = enabled, label = { Text(stringResource(R.string.ledger_filters_all)) })
        }
        EntryType.entries.forEach { type ->
            FilterChip(selected = selected == type, onClick = {
                onSelect(type)
            }, enabled = enabled, label = {
                Text(stringResource(type.label()))
            }, modifier = Modifier.testTag("type_${type.name}"))
        }
    }
}

@StringRes internal fun EntryType.label() = if (this ==
    EntryType.INCOME
) {
    R.string.ledger_income
} else {
    R.string.ledger_expense
}

@StringRes internal fun InputIssue.message(): Int = when (this) {
    InputIssue.INVALID_AMOUNT -> R.string.ledger_err_amount
    InputIssue.AMOUNT_OVERFLOW -> R.string.ledger_err_overflow
    InputIssue.INVALID_CURRENCY -> R.string.ledger_err_currency
    InputIssue.MISSING_RATE -> R.string.ledger_err_missing_rate
    InputIssue.INVALID_RATE -> R.string.ledger_err_rate
    InputIssue.MISSING_CATEGORY -> R.string.ledger_err_category
    InputIssue.INVALID_NOTE -> R.string.ledger_err_note
    InputIssue.INVALID_TIME -> R.string.ledger_err_time
    InputIssue.TIME_GAP -> R.string.ledger_err_gap
    InputIssue.TIME_AMBIGUOUS -> R.string.ledger_err_ambiguous
    InputIssue.INVALID_FILTER -> R.string.ledger_err_filter
}

@StringRes internal fun LedgerError.message(): Int = when (this) {
    LedgerError.INVALID_BUDGET -> R.string.ledger_err_amount
    LedgerError.INVALID_MONTH -> R.string.ledger_err_filter
    LedgerError.INVALID_NAME -> R.string.ledger_err_name
    LedgerError.INVALID_NOTE -> R.string.ledger_err_note
    LedgerError.INVALID_ZONE -> R.string.ledger_err_time
    LedgerError.INVALID_PAGE, LedgerError.INVALID_FILTER -> R.string.ledger_err_filter
    LedgerError.INVALID_ID, LedgerError.NOT_FOUND -> R.string.ledger_err_missing
    LedgerError.CATEGORY_ARCHIVED -> R.string.ledger_err_archived
    LedgerError.CATEGORY_IN_USE -> R.string.ledger_err_in_use
    LedgerError.TYPE_MISMATCH -> R.string.ledger_err_type
    LedgerError.CONFLICT -> R.string.ledger_err_conflict
    LedgerError.STORAGE_FAILURE -> R.string.ledger_err_storage
    LedgerError.CORRUPT_DATA -> R.string.ledger_err_corrupt
    LedgerError.NOT_INITIALIZED -> R.string.ledger_err_setup
    LedgerError.BASE_CURRENCY_MISMATCH -> R.string.ledger_err_base
    LedgerError.LEGACY_BASE_CONFLICT -> R.string.ledger_err_legacy
    LedgerError.AMOUNT_OVERFLOW -> R.string.ledger_err_overflow
}
