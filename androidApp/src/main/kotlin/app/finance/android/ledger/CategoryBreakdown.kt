package app.finance.android.ledger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.finance.android.R
import app.finance.domain.analytics.SpendingSnapshot

@Composable internal fun CategoryBreakdown(
    view: SpendingSnapshot,
    visible: Int,
    onMore: () -> Unit,
    enabled: Boolean
) {
    if (view.categories.isEmpty()) return
    val fill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val maximum = view.categories.maxOf { it.amount.amountMinor }
    Text(
        stringResource(R.string.spending_categories),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("analytics_categories_title").semantics {
            heading()
        }
    )
    view.categories.take(visible).forEach { category ->
        Column(
            Modifier.fillMaxWidth().testTag("analytics_category"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(
                Modifier.testTag(
                    "analytics_category_" + category.id.text
                ).semantics(mergeDescendants = true) {
                }
            ) {
                Text(category.name, style = MaterialTheme.typography.bodyLarge)
                Text(category.amount.spendingLabel(), style = MaterialTheme.typography.titleMedium)
                if (category.archivedAt !=
                    null
                ) {
                    Text(
                        stringResource(R.string.spending_archived),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Canvas(Modifier.fillMaxWidth().height(8.dp).clearAndSetSemantics {}) {
                drawRoundRect(track, size = size, cornerRadius = CornerRadius(4.dp.toPx()))
                if (category.amount.amountMinor > 0) {
                    val width = maxOf(
                        2.dp.toPx(),
                        size.width * chartUnits(
                            category.amount.amountMinor,
                            maximum
                        ).toFloat() / 1000f
                    ).coerceAtMost(size.width)
                    drawRoundRect(
                        fill,
                        size = Size(width, size.height),
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )
                }
            }
        }
    }
    if (view.categories.size >
        8
    ) {
        Text(
            stringResource(
                R.string.spending_category_count,
                visible.coerceAtMost(view.categories.size),
                view.categories.size
            ),
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (visible <
        view.categories.size
    ) {
        TextButton(
            onClick = onMore,
            enabled = enabled,
            modifier = Modifier.testTag("analytics_more_categories")
        ) {
            Text(stringResource(R.string.spending_more_categories))
        }
    }
}
