package app.finance.android.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.finance.android.R
import java.util.Locale

@Composable internal fun InferenceDiagnosticPanel(s: AiUiState, onEnabled: (Boolean) -> Unit) {
    val label = stringResource(R.string.ai_diagnostic_label)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Switch(
            checked = s.diagnosticMode,
            onCheckedChange = onEnabled,
            enabled = !s.busy && s.previews.isEmpty(),
            modifier = Modifier.testTag("ai_diagnostic_toggle").semantics {
                contentDescription =
                    label
            }
        )
        Text(label)
    }
    if (s.diagnosticMode) {
        Text(
            stringResource(R.string.ai_diagnostic_notice),
            Modifier.testTag("ai_diagnostic_notice")
        )
        s.generationProgress?.let { p ->
            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            when {
                                s.diagnosticCanceled -> R.string.ai_diagnostic_canceled
                                s.generating -> R.string.ai_diagnostic_running
                                else -> R.string.ai_diagnostic_finished
                            }
                        ),
                        Modifier.testTag("ai_diagnostic_outcome")
                    )
                    Text(
                        stringResource(
                            when (p.phase) {
                                GenerationPhase.PREPARING -> R.string.ai_diagnostic_prepare
                                GenerationPhase.PREFILL -> R.string.ai_diagnostic_prefill
                                GenerationPhase.GENERATING -> R.string.ai_diagnostic_decode
                                GenerationPhase.FINISHED -> R.string.ai_diagnostic_native_done
                            }
                        ),
                        Modifier.testTag("ai_diagnostic_phase")
                    )
                    Text(
                        stringResource(R.string.ai_diagnostic_elapsed, p.elapsedMs / 1000),
                        Modifier.testTag("ai_diagnostic_elapsed")
                    )
                    Text(
                        stringResource(
                            R.string.ai_diagnostic_prompt,
                            p.processedTokens,
                            p.promptTokens
                        ),
                        Modifier.testTag("ai_diagnostic_prompt")
                    )
                    Text(
                        stringResource(R.string.ai_diagnostic_tokens, p.outputTokens),
                        Modifier.testTag("ai_diagnostic_tokens")
                    )
                    Text(
                        p.firstTokenMs?.let { stringResource(R.string.ai_diagnostic_first, it) }
                            ?: stringResource(R.string.ai_diagnostic_no_first),
                        Modifier.testTag("ai_diagnostic_first")
                    )
                    Text(
                        p.tokensPerSecond?.let {
                            stringResource(
                                R.string.ai_diagnostic_rate,
                                String.format(Locale.getDefault(), "%.2f", it)
                            )
                        } ?: stringResource(R.string.ai_diagnostic_no_rate),
                        Modifier.testTag("ai_diagnostic_rate")
                    )
                    Text(
                        stringResource(R.string.ai_diagnostic_memory, p.pssKiB / 1024),
                        Modifier.testTag("ai_diagnostic_memory")
                    )
                }
            }
        }
    }
}
