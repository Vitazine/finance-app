package app.finance.android.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.finance.android.R
import app.finance.core.money.Money
import app.finance.core.money.MoneyResult
import app.finance.core.money.RoundingPolicy
import app.finance.domain.analytics.ExactSpendingRate
import app.finance.domain.ledger.LedgerFilter
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal fun Money.spendingLabel() = formatDecimal() + " " + currency.code
internal fun fullDay(date: LocalDate) = displayLocalDate(date.toString())
internal fun LedgerFilter.hasChartFilter() = type != null ||
    categoryId != null ||
    sourceCurrency != null ||
    from != null ||
    untilExclusive != null

@Composable private fun displayRate(rate: ExactSpendingRate): String =
    when (val result = rate.roundedForDisplay(RoundingPolicy.HALF_EVEN)) {
        is MoneyResult.Success -> if (rate.isExactMinor) {
            result.value.spendingLabel()
        } else {
            stringResource(
                R.string.spending_rounded,
                result.value.spendingLabel()
            )
        }
        is MoneyResult.Failure -> stringResource(R.string.spending_error)
    }

@Composable private fun RateMetric(label: String, value: String, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(tag))
    }
}

@Composable internal fun AnalyticsPanel(
    state: LedgerUiState,
    model: LedgerViewModel,
    detailed: Boolean = false
) {
    val settings = state.settings ?: return
    val periodKey = state.analyticsReference?.toLocalDateTime(
        TimeZone.of(settings.zoneId)
    )?.date?.toString().orEmpty()
    var daysExpanded by rememberSaveable(periodKey) { mutableStateOf(false) }
    var categoryCount by rememberSaveable(periodKey) { mutableIntStateOf(8) }
    OutlinedCard(Modifier.fillMaxWidth().testTag("analytics_panel")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.spending_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics {
                    heading()
                }
            )
            when {
                state.analyticsLoading -> {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.spending_loading),
                        Modifier.testTag("analytics_loading")
                    )
                }
                state.analyticsError != null -> {
                    ErrorText(stringResource(R.string.spending_error), "analytics_error")
                    Text(stringResource(state.analyticsError.message()))
                    Button(
                        onClick = model::refresh,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("analytics_retry")
                    ) {
                        Text(stringResource(R.string.ledger_retry))
                    }
                }
                else -> state.analytics?.let { view ->
                    val period =
                        stringResource(
                            R.string.spending_period,
                            fullDay(view.completed.startDate),
                            fullDay(view.completed.dates.last()),
                            view.completed.zoneId
                        )
                    Text(
                        period,
                        Modifier.testTag("analytics_period"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.spending_total, view.total.spendingLabel()),
                        Modifier.testTag("analytics_total"),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        stringResource(R.string.spending_today_excluded),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (view.categories.isEmpty()) {
                        Text(
                            stringResource(R.string.spending_empty),
                            Modifier.testTag("analytics_empty")
                        )
                    }
                    if (detailed) {
                        RateMetric(
                            stringResource(R.string.spending_daily),
                            displayRate(view.dailyAverage),
                            "analytics_daily_average"
                        )
                        RateMetric(
                            stringResource(R.string.spending_weekly),
                            displayRate(view.weeklyEquivalent),
                            "analytics_weekly"
                        )
                        Text(
                            stringResource(
                                R.string.spending_normalized,
                                view.normalized30Day.spendingLabel()
                            ),
                            Modifier.testTag("analytics_normalized")
                        )
                        Text(
                            stringResource(
                                R.string.spending_month_actual,
                                displayMonth(view.currentMonth.key),
                                view.currentMonthActual.spendingLabel()
                            ),
                            Modifier.testTag("analytics_month_actual")
                        )
                        Text(
                            stringResource(R.string.spending_rounding_note),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    DailySpendingChart(view, period, daysExpanded, {
                        daysExpanded = !daysExpanded
                    }, !state.busy)
                    if (detailed) {
                        CategoryBreakdown(view, categoryCount, {
                            categoryCount =
                                (categoryCount + 20).coerceAtMost(view.categories.size)
                        }, !state.busy)
                    }
                }
                    ?: Text(
                        stringResource(R.string.spending_loading),
                        Modifier.testTag("analytics_loading")
                    )
            }
        }
    }
}
