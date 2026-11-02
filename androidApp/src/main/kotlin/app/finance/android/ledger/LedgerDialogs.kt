package app.finance.android.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import app.finance.android.R

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun LedgerDialogs(state: LedgerUiState, model: LedgerViewModel) {
    state.categoryDialog?.let { category ->
        AlertDialog(
            modifier = Modifier.semantics {
                testTagsAsResourceId = true
            },
            onDismissRequest = model::closeCategory,
            title = {
                Text(
                    stringResource(
                        if (category.existing ==
                            null
                        ) {
                            R.string.ledger_new_category
                        } else {
                            R.string.ledger_rename_category
                        }
                    )
                )
            },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TypeChoices(
                        category.type,
                        enabled =
                        !state.busy && category.existing == null && !category.fromEditor
                    ) {
                        it?.let(model::categoryType)
                    }
                    LedgerField(
                        category.name,
                        stringResource(R.string.ledger_category_name),
                        "category_name",
                        120,
                        !state.busy,
                        model::categoryName
                    )
                    state.error?.let { ErrorText(stringResource(it.message()), "category_error") }
                }
            },
            confirmButton = {
                Button(
                    onClick = model::saveCategory,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("category_save")
                ) {
                    Text(stringResource(R.string.ledger_save))
                }
            },
            dismissButton = {
                TextButton(onClick = model::closeCategory, enabled = !state.busy) {
                    Text(stringResource(R.string.ledger_cancel))
                }
            }
        )
    }
    if (state.deleteTransaction != null) {
        AlertDialog(
            modifier = Modifier.semantics {
                testTagsAsResourceId = true
            },
            onDismissRequest = model::cancelDelete,
            title = { Text(stringResource(R.string.ledger_delete_entry_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.ledger_delete_entry_detail))
                    state.error?.let { ErrorText(stringResource(it.message())) }
                }
            },
            confirmButton = {
                Button(
                    onClick = model::confirmDelete,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("delete_confirm")
                ) {
                    Text(stringResource(R.string.ledger_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = model::cancelDelete,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("delete_cancel")
                ) {
                    Text(stringResource(R.string.ledger_cancel))
                }
            }
        )
    }
    if (state.deleteCategory != null) {
        AlertDialog(
            modifier = Modifier.semantics {
                testTagsAsResourceId = true
            },
            onDismissRequest = model::cancelDeleteCategory,
            title = { Text(stringResource(R.string.ledger_delete_category_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.ledger_delete_category_detail))
                    state.error?.let { ErrorText(stringResource(it.message())) }
                }
            },
            confirmButton = {
                Button(
                    onClick = model::confirmDeleteCategory,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("category_delete_confirm")
                ) {
                    Text(stringResource(R.string.ledger_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = model::cancelDeleteCategory, enabled = !state.busy) {
                    Text(stringResource(R.string.ledger_cancel))
                }
            }
        )
    }
}
