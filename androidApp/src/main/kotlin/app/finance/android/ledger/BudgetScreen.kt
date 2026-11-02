package app.finance.android.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.finance.android.R
import app.finance.core.money.Money
import app.finance.domain.budget.BudgetOverview
import app.finance.domain.ledger.LedgerError

private fun Money.label() = formatDecimal() + " " + currency.code

@Composable internal fun BudgetSummary(state: LedgerUiState, model: LedgerViewModel) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.budget_title), style = MaterialTheme.typography.titleLarge)
            state.budget?.let { BudgetDetails(it) }
            state.budgetError?.let {
                ErrorText(stringResource(it.message()), "budget_error")
                Button(onClick = model::refresh, enabled = !state.busy) {
                    Text(stringResource(R.string.ledger_retry))
                }
            }
            if (state.budget == null &&
                state.budgetError == null
            ) {
                Text(stringResource(R.string.budget_loading))
            }
        }
    }
}

@Composable private fun BudgetDetails(view: BudgetOverview) {
    Text(
        stringResource(R.string.budget_month, view.month.key, view.zoneId),
        Modifier.testTag("budget_month")
    )
    Text(
        stringResource(R.string.budget_actual, view.spent.label()),
        Modifier.testTag("budget_spent")
    )
    val budget = view.budget
    if (budget ==
        null
    ) {
        Text(stringResource(R.string.analytics_empty), Modifier.testTag("budget_empty"))
    } else {
        Text(
            stringResource(R.string.budget_limit, budget.limit.label()),
            Modifier.testTag("budget_limit")
        )
        val remaining = view.remaining!!
        if (remaining.amountMinor >=
            0
        ) {
            Text(
                stringResource(R.string.budget_remaining, remaining.label()),
                Modifier.testTag("budget_remaining")
            )
        } else {
            Text(
                stringResource(R.string.budget_overrun, view.overrun!!.label()),
                Modifier.testTag("budget_overrun"),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BudgetScreen(state: LedgerUiState, model: LedgerViewModel) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageHeader(stringResource(R.string.analytics_screen_title))
        if (state.settings == null) {
            Text(
                stringResource(R.string.analytics_empty),
                modifier = Modifier.testTag("budget_empty")
            )
            SetupCard(state, model)
        } else {
            BudgetSummary(state, model)
            Text(stringResource(R.string.budget_rule), style = MaterialTheme.typography.bodyMedium)
            state.budget?.let { view ->
                var editing by rememberSaveable(view.month.key, state.budgetSaveVersion) {
                    mutableStateOf(false)
                }
                var draft by rememberSaveable(view.month.key) { mutableStateOf("") }
                if (!editing) {
                    Button(onClick = {
                        draft = view.budget?.limit?.formatDecimal().orEmpty()
                        model.clearBudgetSaveError()
                        editing =
                            true
                    }, enabled = !state.busy, modifier = Modifier.testTag("budget_edit")) {
                        Text(stringResource(R.string.budget_edit))
                    }
                } else {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            if (it.length <=
                                64
                            ) {
                                draft = it
                            }
                        },
                        enabled = !state.busy,
                        label = {
                            Text(stringResource(R.string.budget_amount, view.spent.currency.code))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("budget_amount")
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            focus.clearFocus()
                            keyboard?.hide()
                            model.saveBudget(view.month, draft)
                        }, enabled = !state.busy, modifier = Modifier.testTag("budget_save")) {
                            Text(stringResource(R.string.ledger_save))
                        }
                        TextButton(onClick = {
                            focus.clearFocus()
                            keyboard?.hide()
                            model.clearBudgetSaveError()
                            editing =
                                false
                        }, enabled = !state.busy, modifier = Modifier.testTag("budget_cancel")) {
                            Text(stringResource(R.string.ledger_cancel))
                        }
                    }
                }
                state.budgetSaveError?.let { error ->
                    ErrorText(
                        stringResource(
                            when (error) {
                                LedgerError.INVALID_BUDGET -> R.string.budget_invalid
                                LedgerError.INVALID_MONTH -> R.string.budget_month_changed
                                else -> error.message()
                            }
                        ),
                        "budget_save_error"
                    )
                }
            }
            AnalyticsPanel(state, model, detailed = true)
        }
    }
}
