package app.finance.android.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.finance.ai.contract.AiDraft
import app.finance.android.R
import app.finance.android.ledger.InputResult
import app.finance.android.ledger.LedgerField
import app.finance.android.ledger.LocalRatePanel
import app.finance.android.ledger.buildEntry

@Composable
internal fun AiRatePanel(preview: AiPreview, draft: AiDraft, busy: Boolean, model: AiViewModel) {
    val fields = preview.fields
    val source = fields.currency.trim().uppercase()
    val base = draft.context.settings.primaryCurrency.code
    if (source == base) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_fx_explanation, source, base))
        LedgerField(
            fields.rate,
            stringResource(R.string.ledger_rate, source, base),
            "ai_rate_${preview.index}",
            128,
            !busy,
            { text -> model.edit(preview.index) { it.withRate(text) } },
            stringResource(R.string.ledger_rate_hint),
            decimal = true
        )
        LocalRatePanel(
            fields, busy, preview.rateCandidate, preview.rateNotice, preview.rateProblem,
            { model.findRate(preview.index) }, { model.rememberRate(preview.index) },
            { model.useRateCandidate(preview.index) }, "ai_fx_${preview.index}"
        )
        val entry =
            buildEntry(fields, draft.context.settings, null, preview.id, draft.context.reference)
        if (entry is InputResult.Success) {
            val amount = entry.value.conversion.baseAmount
            Text(
                stringResource(
                    R.string.ai_fx_converted,
                    amount.formatDecimal(),
                    amount.currency.code
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("ai_fx_${preview.index}_converted")
            )
        }
    }
}
