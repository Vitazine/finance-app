package app.finance.android.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.finance.android.R
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.HistoryItem

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TransactionRow(item: HistoryItem, model: LedgerViewModel, busy: Boolean) {
    val entry = item.transaction.entry
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("transaction_row").semantics {
            isTraversalGroup =
                true
        },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(item.category.name, style = MaterialTheme.typography.titleMedium)
        Text(
            "${if (entry.type == EntryType.INCOME) "+" else "−"}${entry.conversion.sourceAmount.formatDecimal()} ${entry.conversion.sourceCurrency.code}",
            style = MaterialTheme.typography.titleLarge
        )
        Text(stringResource(entry.type.label()), style = MaterialTheme.typography.labelLarge)
        if (entry.conversion.sourceCurrency !=
            entry.conversion.baseCurrency
        ) {
            Text(
                stringResource(
                    R.string.ledger_converted,
                    entry.conversion.baseAmount.formatDecimal(),
                    entry.conversion.baseCurrency.code
                )
            )
        }
        Text(
            displayTime(entry),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        entry.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                model.editEntry(item)
            }, enabled = !busy, modifier = Modifier.testTag("edit_${entry.id.text}")) {
                Text(stringResource(R.string.ledger_edit))
            }
            TextButton(onClick = {
                model.askDelete(item.transaction)
            }, enabled = !busy, modifier = Modifier.testTag("delete_${entry.id.text}")) {
                Text(stringResource(R.string.ledger_delete))
            }
        }
        HorizontalDivider()
    }
}
