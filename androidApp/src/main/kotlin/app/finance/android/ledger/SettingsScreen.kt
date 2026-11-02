package app.finance.android.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.finance.android.R
import app.finance.domain.ledger.EntryType

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(
    state: LedgerUiState,
    model: LedgerViewModel,
    backup: @Composable () -> Unit = {}
) {
    LazyColumn(
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { PageHeader(stringResource(R.string.settings)) }
        item {
            Text(
                stringResource(R.string.settings_empty),
                style = MaterialTheme.typography.titleLarge
            )
        }
        item { backup() }
        item { state.error?.let { ErrorText(stringResource(it.message())) } }
        if (state.settings == null) {
            item { SetupCard(state, model) }
        } else {
            item {
                Text(stringResource(R.string.ledger_base_info, state.settings.primaryCurrency.code))
                Text(stringResource(R.string.ledger_zone_info, state.settings.zoneId))
            }
            item {
                Text(
                    stringResource(R.string.ledger_categories),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            item {
                Button(onClick = {
                    model.categoryDialog(EntryType.EXPENSE)
                }, enabled = !state.busy, modifier = Modifier.testTag("settings_new_category")) {
                    Text(stringResource(R.string.ledger_new_category))
                }
            }
            if (state.categories.isEmpty()) {
                item {
                    Text(stringResource(R.string.ledger_categories_empty))
                }
            }
            items(state.categories, key = { it.id.text }) { category ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (category.archivedAt ==
                            null
                        ) {
                            category.name
                        } else {
                            stringResource(R.string.ledger_archived_suffix, category.name)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(stringResource(category.type.label()))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            model.categoryDialog(category.type, category)
                        }, enabled = !state.busy) { Text(stringResource(R.string.ledger_rename)) }
                        TextButton(
                            onClick = {
                                if (category.archivedAt ==
                                    null
                                ) {
                                    model.archive(category)
                                } else {
                                    model.restore(category)
                                }
                            },
                            enabled = !state.busy,
                            modifier = Modifier.testTag(
                                "archive_${category.id.text}"
                            )
                        ) {
                            Text(
                                stringResource(
                                    if (category.archivedAt ==
                                        null
                                    ) {
                                        R.string.ledger_archive
                                    } else {
                                        R.string.ledger_restore
                                    }
                                )
                            )
                        }
                        TextButton(onClick = {
                            model.askDeleteCategory(category)
                        }, enabled = !state.busy) { Text(stringResource(R.string.ledger_delete)) }
                    }
                    HorizontalDivider()
                }
            }
            if (state.moreCategories) {
                item {
                    Button(onClick = model::moreCategories, enabled = !state.busy) {
                        Text(stringResource(R.string.ledger_more_categories))
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.foundation_notice),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
