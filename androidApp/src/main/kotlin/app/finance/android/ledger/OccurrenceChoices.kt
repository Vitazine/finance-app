package app.finance.android.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.finance.android.R

@Composable
internal fun OccurrenceChoices(
    fields: EntryFields,
    tag: String,
    enabled: Boolean,
    choose: (String) -> Unit
) {
    val result = parseOccurrence(fields.localTime, fields.zoneId)
    if (result is InputResult.Failure && result.issue == InputIssue.TIME_AMBIGUOUS) {
        Column {
            Text(stringResource(R.string.ledger_choose_offset))
            result.offsets.forEachIndexed { index, offset ->
                FilterChip(
                    selected = fields.offset == offset,
                    onClick = { choose(offset) },
                    enabled = enabled,
                    modifier = Modifier.testTag(tag + "_occurrence_$index"),
                    label = {
                        Text(
                            stringResource(
                                if (index == 0) {
                                    R.string.ledger_first_occurrence
                                } else {
                                    R.string.ledger_second_occurrence
                                }
                            )
                        )
                    }
                )
            }
        }
    }
}
