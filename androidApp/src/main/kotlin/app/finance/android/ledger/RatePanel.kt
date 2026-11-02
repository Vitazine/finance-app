package app.finance.android.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.finance.android.R
import app.finance.core.money.ExchangeRateQuote
import app.finance.domain.ledger.LedgerError
import kotlinx.datetime.Clock

@Composable
private fun rateSource(quote: ExchangeRateQuote): String =
    if (quote.sourceId == "manual") stringResource(R.string.fx_source_manual) else quote.sourceId

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RatePanel(state: LedgerUiState, model: LedgerViewModel) {
    val editor = state.editor ?: return
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun act(block: () -> Unit) {
        focus.clearFocus()
        keyboard?.hide()
        block()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.fx_local), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                act(model::findRate)
            }, enabled = !state.busy, modifier = Modifier.testTag("fx_find")) {
                Text(stringResource(R.string.fx_find))
            }
            TextButton(
                onClick = { act(model::rememberRate) },
                enabled =
                !state.busy && editor.fields.selectedQuote == null,
                modifier = Modifier.testTag("fx_remember")
            ) {
                Text(stringResource(R.string.fx_remember))
            }
        }
        editor.fields.selectedQuote?.let { chosen ->
            Text(
                stringResource(
                    R.string.fx_applied,
                    displayRateTime(chosen.effectiveAt, editor.fields.zoneId),
                    rateSource(chosen)
                ),
                modifier = Modifier.testTag("fx_applied")
            )
        }
        when (editor.rateNotice) {
            RateNotice.SAVED -> Text(
                stringResource(R.string.fx_saved),
                modifier = Modifier.testTag("fx_saved")
            )
            RateNotice.EMPTY -> Text(
                stringResource(R.string.fx_empty),
                modifier = Modifier.testTag("fx_empty")
            )
            null -> Unit
        }
        editor.rateProblem?.let { error ->
            ErrorText(
                stringResource(
                    if (error ==
                        LedgerError.CONFLICT
                    ) {
                        R.string.fx_conflict
                    } else {
                        R.string.fx_problem
                    }
                ),
                "fx_problem"
            )
        }
        editor.rateCandidate?.let { candidate ->
            val quote = candidate.quote
            val now = Clock.System.now()
            val hours = (now - quote.effectiveAt).inWholeHours
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.fx_found),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(
                            R.string.fx_pair_rate,
                            quote.sourceCurrency.code,
                            quote.rate.canonicalText,
                            quote.baseCurrency.code
                        ),
                        modifier = Modifier.testTag("fx_candidate_rate")
                    )
                    Text(
                        stringResource(R.string.fx_source, rateSource(quote)),
                        modifier = Modifier.testTag("fx_candidate_source")
                    )
                    Text(
                        stringResource(
                            R.string.fx_date,
                            displayRateTime(quote.effectiveAt, editor.fields.zoneId)
                        ),
                        modifier = Modifier.testTag("fx_candidate_date")
                    )
                    Text(
                        stringResource(
                            R.string.fx_retrieved,
                            displayRateTime(candidate.retrievedAt, editor.fields.zoneId)
                        ),
                        modifier = Modifier.testTag("fx_candidate_retrieved")
                    )
                    val age = when {
                        now < quote.effectiveAt -> stringResource(R.string.fx_future)
                        hours == 0L -> stringResource(R.string.fx_age_recent)
                        else -> stringResource(R.string.fx_age_hours, hours)
                    }
                    Text(age, modifier = Modifier.testTag("fx_candidate_age"))
                    Button(onClick = {
                        act(model::useRateCandidate)
                    }, enabled = !state.busy, modifier = Modifier.testTag("fx_use")) {
                        Text(stringResource(R.string.fx_use))
                    }
                }
            }
        }
    }
}
