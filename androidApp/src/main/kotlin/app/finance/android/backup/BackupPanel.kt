package app.finance.android.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.finance.android.R

@Composable
internal fun BackupPanel(model: BackupViewModel, canReplace: Boolean) {
    val state by model.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val json =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri !=
                null
            ) {
                model.export(resolver, uri, false)
            }
        }
    val csv =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv")
        ) { uri ->
            if (uri !=
                null
            ) {
                model.export(resolver, uri, true)
            }
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri !=
                null
            ) {
                model.read(resolver, uri)
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.backup_plaintext))
        Button(onClick = {
            json.launch("finance-backup.json")
        }, enabled = !state.busy, modifier = Modifier.testTag("backup_json")) {
            Text(stringResource(R.string.backup_export))
        }
        Button(onClick = {
            csv.launch("finance-transactions.csv")
        }, enabled = !state.busy, modifier = Modifier.testTag("backup_csv")) {
            Text(stringResource(R.string.backup_csv))
        }
        Button(
            onClick = {
                open.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
            enabled =
            !state.busy && canReplace,
            modifier = Modifier.testTag("backup_import")
        ) {
            Text(stringResource(R.string.backup_import))
        }
        if (state.busy) CircularProgressIndicator()
        state.error?.let {
            Text(
                stringResource(R.string.backup_error, it.name),
                modifier = Modifier.testTag("backup_error"),
                color = MaterialTheme.colorScheme.error
            )
        }
        if (state.completed) {
            Text(
                stringResource(R.string.backup_complete),
                modifier = Modifier.testTag("backup_complete")
            )
        }
    }
    state.preview?.let { preview ->
        var confirmed by remember(preview) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(stringResource(R.string.backup_replace_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.backup_replace_warning))
                    Text(
                        stringResource(
                            R.string.backup_counts,
                            preview.transactionCount,
                            preview.categoryCount
                        )
                    )
                    Text(
                        stringResource(
                            R.string.backup_currencies,
                            preview.currentCurrency ?: "—",
                            preview.incomingCurrency ?: "—"
                        )
                    )
                    Text(stringResource(R.string.backup_pre_export))
                    TextButton(onClick = {
                        json.launch("finance-before-import.json")
                    }, enabled = !state.busy) { Text(stringResource(R.string.backup_export)) }
                    Row {
                        Checkbox(
                            checked = confirmed,
                            onCheckedChange = {
                                confirmed = it
                            },
                            enabled = !state.busy,
                            modifier = Modifier.testTag(
                                "backup_replace_check"
                            )
                        )
                        Text(stringResource(R.string.backup_replace_agree))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = model::confirmReplace,
                    enabled =
                    confirmed && !state.busy && canReplace,
                    modifier = Modifier.testTag("backup_replace_confirm")
                ) {
                    Text(stringResource(R.string.backup_replace))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = model::dismiss,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("backup_cancel")
                ) {
                    Text(stringResource(R.string.ai_cancel))
                }
            }
        )
    }
}
