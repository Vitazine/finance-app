package app.finance.android.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.finance.android.R

@Composable
internal fun PageHeader(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics {
                heading()
            }
        )
    }
}

@Composable
internal fun HomeScreen(state: LedgerUiState, model: LedgerViewModel, onHistory: () -> Unit) {
    LazyColumn(
        modifier = Modifier.testTag("home_list"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { PageHeader(stringResource(R.string.home_title)) }
        item {
            TextButton(onClick = onHistory, modifier = Modifier.testTag("home_history")) {
                Text(stringResource(R.string.open_transactions))
            }
        }
        item { state.error?.let { ErrorText(stringResource(it.message())) } }
        if (state.settings == null) {
            item { SetupCard(state, model) }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.ledger_balance),
                            style = MaterialTheme.typography.titleMedium
                        )
                        state.overview?.let { overview ->
                            Text(
                                "${overview.balance.formatDecimal()} ${overview.balance.currency.code}",
                                style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.fillMaxWidth().testTag("home_balance")
                            )
                            Text(
                                stringResource(R.string.ledger_count, overview.count),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        state.overviewError?.let {
                            ErrorText(stringResource(it.message()))
                            Button(onClick = model::refresh, enabled = !state.busy) {
                                Text(stringResource(R.string.ledger_retry))
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = model::createEntry,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("home_add")
                ) {
                    Text(stringResource(R.string.ledger_add))
                }
            }
            item { BudgetSummary(state, model) }
            item { AnalyticsPanel(state, model) }
            item {
                Text(
                    stringResource(R.string.ledger_recent),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            if (state.overview?.count ==
                0L
            ) {
                item { Text(stringResource(R.string.transactions_empty)) }
            }
            items(state.overview?.recent.orEmpty(), key = {
                it.transaction.entry.id.text
            }) { TransactionRow(it, model, state.busy) }
        }
        item {
            Text(
                stringResource(R.string.foundation_notice),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
