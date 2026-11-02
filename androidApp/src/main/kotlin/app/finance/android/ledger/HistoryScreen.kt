package app.finance.android.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.finance.android.R

@Composable
internal fun HistoryScreen(state: LedgerUiState, model: LedgerViewModel) {
    var filters by rememberSaveable { mutableStateOf(false) }
    var chart by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.testTag("history_list"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { PageHeader(stringResource(R.string.transactions)) }
        item { state.error?.let { ErrorText(stringResource(it.message())) } }
        if (state.settings == null) {
            item { Text(stringResource(R.string.transactions_empty)) }
            item { SetupCard(state, model) }
        } else {
            item {
                Button(
                    onClick = model::createEntry,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("history_add")
                ) {
                    Text(stringResource(R.string.ledger_add))
                }
            }
            item {
                OutlinedButton(onClick = {
                    filters = !filters
                }, modifier = Modifier.testTag("filters_toggle")) {
                    Text(stringResource(R.string.ledger_filters))
                }
            }
            if (filters) item { HistoryFilters(state, model) }
            item {
                if (state.filter.hasChartFilter()) {
                    Text(
                        stringResource(R.string.spending_history_scope),
                        Modifier.testTag("analytics_history_scope")
                    )
                } else {
                    OutlinedButton(
                        onClick = { chart = !chart },
                        modifier = Modifier.testTag("history_chart_toggle")
                    ) {
                        Text(
                            stringResource(
                                if (chart) {
                                    R.string.spending_hide_chart
                                } else {
                                    R.string.spending_show_chart
                                }
                            )
                        )
                    }
                }
            }
            if (chart && !state.filter.hasChartFilter()) item { AnalyticsPanel(state, model) }
            item {
                state.historyError?.let {
                    ErrorText(stringResource(it.message()))
                    Button(
                        onClick = model::refresh,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("history_retry")
                    ) {
                        Text(stringResource(R.string.ledger_retry))
                    }
                }
            }
            if (state.historyError == null &&
                state.history.isEmpty()
            ) {
                item {
                    Text(
                        stringResource(
                            if (state.filter ===
                                app.finance.domain.ledger.LedgerFilter.ALL
                            ) {
                                R.string.transactions_empty
                            } else {
                                R.string.ledger_filter_empty
                            }
                        ),
                        modifier = Modifier.testTag("history_empty")
                    )
                }
            }
            items(state.history, key = {
                it.transaction.entry.id.text
            }) { TransactionRow(it, model, state.busy) }
            if (state.cursor !=
                null
            ) {
                item {
                    Button(
                        onClick = model::moreHistory,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("history_more")
                    ) {
                        Text(stringResource(R.string.ledger_more_history))
                    }
                }
            }
        }
        item { Text(stringResource(R.string.foundation_notice)) }
    }
}
