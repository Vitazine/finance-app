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
import app.finance.domain.exchange.CachedRate
import app.finance.domain.ledger.LedgerError
import kotlinx.datetime.Clock

@Composable
private fun rateSource(quote: ExchangeRateQuote): String =
    if (quote.sourceId == "manual") stringResource(R.string.fx_source_manual) else quote.sourceId

@Composable
internal fun RatePanel(state: LedgerUiState, model: LedgerViewModel) {
    val editor = state.editor ?: return
    LocalRatePanel(
        editor.fields,
        state.busy,
        editor.rateCandidate,
        editor.rateNotice,
        editor.rateProblem,
        model::findRate,
        model::rememberRate,
        model::useRateCandidate
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalRatePanel(
    fields: EntryFields,
    busy: Boolean,
    candidate: CachedRate?,
    notice: RateNotice?,
    problem: LedgerError?,
    onFind: () -> Unit,
    onRemember: () -> Unit,
    onUse: () -> Unit,
    prefix: String = "fx"
) {
    fun tag(suffix: String) = "${prefix}_$suffix"
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
                act(onFind)
            }, enabled = !busy, modifier = Modifier.testTag(tag("find"))) {
                Text(stringResource(R.string.fx_find))
            }
            TextButton(
                onClick = { act(onRemember) },
                enabled =
                !busy && fields.selectedQuote == null,
                modifier = Modifier.testTag(tag("remember"))
            ) {
                Text(stringResource(R.string.fx_remember))
            }
        }
        fields.selectedQuote?.let { chosen ->
            Text(
                stringResource(
                    R.string.fx_applied,
                    displayRateTime(chosen.effectiveAt, fields.zoneId),
                    rateSource(chosen)
                ),
                modifier = Modifier.testTag(tag("applied"))
            )
        }
        when (notice) {
            RateNotice.SAVED -> Text(
                stringResource(R.string.fx_saved),
                modifier = Modifier.testTag(tag("saved"))
            )
            RateNotice.EMPTY -> Text(
                stringResource(R.string.fx_empty),
                modifier = Modifier.testTag(tag("empty"))
            )
            null -> Unit
        }
        problem?.let { error ->
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
                tag("problem")
            )
        }
        candidate?.let { candidate ->
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
                        modifier = Modifier.testTag(tag("candidate_rate"))
                    )
                    Text(
                        stringResource(R.string.fx_source, rateSource(quote)),
                        modifier = Modifier.testTag(tag("candidate_source"))
                    )
                    Text(
                        stringResource(
                            R.string.fx_date,
                            displayRateTime(quote.effectiveAt, fields.zoneId)
                        ),
                        modifier = Modifier.testTag(tag("candidate_date"))
                    )
                    Text(
                        stringResource(
                            R.string.fx_retrieved,
                            displayRateTime(candidate.retrievedAt, fields.zoneId)
                        ),
                        modifier = Modifier.testTag(tag("candidate_retrieved"))
                    )
                    val age = when {
                        now < quote.effectiveAt -> stringResource(R.string.fx_future)
                        hours == 0L -> stringResource(R.string.fx_age_recent)
                        else -> stringResource(R.string.fx_age_hours, hours)
                    }
                    Text(age, modifier = Modifier.testTag(tag("candidate_age")))
                    Button(onClick = {
                        act(onUse)
                    }, enabled = !busy, modifier = Modifier.testTag(tag("use"))) {
                        Text(stringResource(R.string.fx_use))
                    }
                }
            }
        }
    }
}
