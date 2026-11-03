package app.finance.android.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import kotlinx.datetime.TimeZone

@Composable
internal fun SetupCard(state: LedgerUiState, model: LedgerViewModel) {
    var currency by rememberSaveable(state.legacyBasis?.code) {
        mutableStateOf(state.legacyBasis?.code.orEmpty())
    }
    val zone by rememberSaveable {
        mutableStateOf(
            TimeZone.currentSystemDefault().id.let {
                if (it in
                    TimeZone.availableZoneIds
                ) {
                    it
                } else {
                    "UTC"
                }
            }
        )
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.ledger_setup_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(stringResource(R.string.ledger_setup_detail))
            state.legacyBasis?.let { Text(stringResource(R.string.ledger_existing_basis, it.code)) }
            LedgerField(
                currency,
                stringResource(
                    R.string.ledger_currency
                ),
                "setup_currency",
                8,
                !state.busy,
                {
                    currency =
                        it
                },
                stringResource(R.string.ledger_iso_hint),
                readOnly = state.legacyBasis != null,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            )
            state.setupIssue?.let { ErrorText(stringResource(it.message())) }
            Button(onClick = {
                model.initialize(currency, zone)
            }, enabled = !state.busy, modifier = Modifier.testTag("setup_save")) {
                Text(stringResource(R.string.ledger_start))
            }
        }
    }
}
