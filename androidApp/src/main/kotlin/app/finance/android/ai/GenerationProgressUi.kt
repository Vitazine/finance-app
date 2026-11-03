package app.finance.android.ai

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.finance.android.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun GenerationProgressCard(progress: GenerationProgress?, onCancel: () -> Unit) {
    val bring = remember { BringIntoViewRequester() }
    val started = remember { SystemClock.elapsedRealtime() }
    var elapsed by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) {
        bring.bringIntoView()
        while (isActive) {
            elapsed = SystemClock.elapsedRealtime() - started
            delay(1000)
        }
    }
    val p = progress ?: GenerationProgress()
    val phase = stringResource(
        when (p.phase) {
            GenerationPhase.PREPARING -> R.string.ai_generation_prepare
            GenerationPhase.PREFILL -> R.string.ai_generation_prefill
            GenerationPhase.GENERATING -> R.string.ai_generation_answer
            GenerationPhase.FINISHED -> R.string.ai_generation_validate
        }
    )
    Card(Modifier.fillMaxWidth().bringIntoViewRequester(bring)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                phase,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("ai_generation_phase").semantics {
                    liveRegion = LiveRegionMode.Polite
                }
            )
            val bar = Modifier.fillMaxWidth().testTag("ai_loading")
            if (p.phase == GenerationPhase.PREFILL && p.promptTokens > 0) {
                LinearProgressIndicator(
                    progress = { (p.processedTokens.toFloat() / p.promptTokens).coerceIn(0f, 1f) },
                    modifier = bar
                )
                Text(
                    stringResource(R.string.ai_generation_input, p.processedTokens, p.promptTokens),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                LinearProgressIndicator(modifier = bar)
                if (p.outputTokens > 0) {
                    Text(
                        stringResource(R.string.ai_generation_output, p.outputTokens),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                stringResource(R.string.ai_generation_elapsed, maxOf(elapsed, p.elapsedMs) / 1000),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onCancel, modifier = Modifier.testTag("ai_cancel_generation")) {
                Text(stringResource(R.string.ai_cancel))
            }
        }
    }
}
