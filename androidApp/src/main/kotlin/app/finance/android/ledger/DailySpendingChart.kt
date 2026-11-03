package app.finance.android.ledger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.finance.android.R
import app.finance.domain.analytics.SpendingSnapshot

@Composable internal fun DailySpendingChart(
    view: SpendingSnapshot,
    period: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean
) {
    val maximum = view.daily.maxOf { it.amount.amountMinor }
    val fill = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description =
        stringResource(R.string.spending_chart_description, period, view.total.spendingLabel())
    Canvas(
        Modifier.fillMaxWidth().height(120.dp).testTag("daily_chart").semantics {
            contentDescription =
                description
            role = Role.Image
        }
    ) {
        val top = 4.dp.toPx()
        val bottom = size.height - 4.dp.toPx()
        val available = bottom - top
        for (step in 0..2) {
            val y = bottom - available * step / 2f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        val slot = size.width / 30f
        view.daily.forEachIndexed { index, day ->
            if (day.amount.amountMinor > 0) {
                val fraction = chartUnits(day.amount.amountMinor, maximum).toFloat() / 1000f
                val height = maxOf(2.dp.toPx(), available * fraction).coerceAtMost(available)
                drawRoundRect(
                    fill,
                    Offset(slot * (index + 0.175f), bottom - height),
                    Size(slot * 0.65f, height),
                    CornerRadius(2.dp.toPx())
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(shortDay(view.daily.first().date), style = MaterialTheme.typography.bodySmall)
        Text(shortDay(view.daily.last().date), style = MaterialTheme.typography.bodySmall)
    }
    val maximumMoney = view.daily.maxBy { it.amount.amountMinor }.amount
    Text(
        stringResource(R.string.spending_chart_scale, maximumMoney.spendingLabel()),
        style = MaterialTheme.typography.bodySmall
    )
    Text(stringResource(R.string.spending_chart_note), style = MaterialTheme.typography.bodySmall)
    TextButton(
        onClick = onToggle,
        enabled = enabled,
        modifier = Modifier.testTag("analytics_days_toggle")
    ) {
        Text(
            stringResource(
                if (expanded) R.string.spending_hide_days else R.string.spending_show_days
            )
        )
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            view.daily.forEach { day ->
                Text(
                    stringResource(
                        R.string.spending_day_value,
                        fullDay(day.date),
                        day.amount.spendingLabel()
                    ),
                    Modifier.testTag("analytics_day")
                )
            }
        }
    }
}
