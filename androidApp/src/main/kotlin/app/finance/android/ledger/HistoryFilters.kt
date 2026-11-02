package app.finance.android.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.finance.core.money.CurrencyCode
import app.finance.core.money.MoneyResult
import app.finance.domain.ledger.EntryType
import app.finance.domain.ledger.LedgerFilter
import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryFilters(state: LedgerUiState, model: LedgerViewModel) {
    val settings = state.settings ?: return
    var typeName by rememberSaveable { mutableStateOf<String?>(state.filter.type?.name) }
    var category by rememberSaveable { mutableStateOf<String?>(state.filter.categoryId?.text) }
    var currency by rememberSaveable { mutableStateOf(state.filter.sourceCurrency?.code.orEmpty()) }
    var from by rememberSaveable {
        mutableStateOf(filterDate(state.filter.from, settings.zoneId))
    }
    var through by rememberSaveable {
        mutableStateOf(
            filterDate(state.filter.untilExclusive, settings.zoneId, exclusiveEnd = true)
        )
    }
    var issue by rememberSaveable { mutableStateOf(false) }
    val type = typeName?.let(EntryType::valueOf)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TypeChoices(type, all = true, enabled = !state.busy) {
            typeName = it?.name
            category = null
        }
        val all = stringResource(R.string.ledger_filter_category_all)
        val selected = state.categories.find { it.id.text == category }?.name ?: all
        ChoiceMenu(
            selected,
            "filter_category",
            !state.busy,
            listOf(all to { category = null }) +
                state.categories.filter { type == null || it.type == type }.map { item ->
                    item.name to
                        { category = item.id.text }
                }
        )
        if (state.moreCategories) {
            TextButton(
                onClick = model::moreCategories,
                enabled = !state.busy
            ) {
                Text(stringResource(R.string.ledger_more_categories))
            }
        }
        LedgerField(
            currency,
            stringResource(
                R.string.ledger_filter_currency
            ),
            "filter_currency",
            8,
            !state.busy,
            {
                currency =
                    it
            }
        )
        LedgerField(
            from,
            stringResource(
                R.string.ledger_filter_from
            ),
            "filter_from",
            10,
            !state.busy,
            {
                from =
                    it
            }
        )
        LedgerField(
            through,
            stringResource(
                R.string.ledger_filter_through
            ),
            "filter_through",
            10,
            !state.busy,
            {
                through =
                    it
            }
        )
        if (issue) ErrorText(stringResource(R.string.ledger_err_filter), "filter_error")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val dates = parseDateRange(from, through, settings.zoneId)
                val code = if (currency.isBlank()) {
                    null
                } else if (currency.length <=
                    8
                ) {
                    CurrencyCode.resolve(currency.trim().uppercase())
                } else {
                    MoneyResult.Failure(app.finance.core.money.MoneyError.INVALID_CURRENCY_CODE)
                }
                if (dates !is InputResult.Success ||
                    code is MoneyResult.Failure
                ) {
                    issue = true
                } else {
                    val filter = LedgerFilter.create(
                        type,
                        category?.let {
                            (LedgerId.parse(it) as LedgerResult.Success).value
                        },
                        (code as? MoneyResult.Success)?.value,
                        dates.value.from,
                        dates.value.untilExclusive
                    )
                    if (filter is LedgerResult.Success) {
                        issue = false
                        model.filter(filter.value)
                    } else {
                        issue =
                            true
                    }
                }
            }, enabled = !state.busy, modifier = Modifier.testTag("filters_apply")) {
                Text(stringResource(R.string.ledger_apply))
            }
            TextButton(onClick = {
                typeName = null
                category = null
                currency = ""
                from = ""
                through =
                    ""
                issue = false
                model.filter(LedgerFilter.ALL)
            }, enabled = !state.busy, modifier = Modifier.testTag("filters_reset")) {
                Text(stringResource(R.string.ledger_reset))
            }
        }
    }
}
