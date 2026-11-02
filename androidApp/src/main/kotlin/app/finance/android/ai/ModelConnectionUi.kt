package app.finance.android.ai

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import app.finance.android.R
import kotlinx.coroutines.delay

@Composable internal fun ModelConnectionProgress(status: ModelConnection) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            elapsed = (SystemClock.elapsedRealtime() - start) / 1000
        }
    }
    Text(
        stringResource(
            when (status.stage) {
                ConnectionStage.DEVICE -> R.string.ai_connect_device
                ConnectionStage.FILES -> R.string.ai_connect_files
                ConnectionStage.PROMPT -> R.string.ai_connect_prompt
                ConnectionStage.VERIFY -> R.string.ai_connect_verify
                ConnectionStage.LOAD -> R.string.ai_connect_load
                ConnectionStage.STOPPING -> R.string.ai_connect_stopping
            }
        ),
        Modifier.testTag("ai_connection_stage").semantics { liveRegion = LiveRegionMode.Polite }
    )
    val percent = status.percent
    if (status.stage == ConnectionStage.VERIFY && percent != null) {
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth().testTag("ai_model_loading")
        )
        Text(
            stringResource(R.string.ai_connect_percent, percent),
            Modifier.testTag("ai_connection_percent")
        )
    } else {
        CircularProgressIndicator(Modifier.testTag("ai_model_loading"))
    }
    Text(
        stringResource(R.string.ai_connect_elapsed, elapsed),
        Modifier.testTag("ai_connection_elapsed")
    )
}

internal fun connectionMessage(failure: ConnectionFailure?): Int = when (failure) {
    ConnectionFailure.RUNTIME_MISSING -> R.string.ai_runtime_missing
    ConnectionFailure.MEMORY -> R.string.ai_memory_error
    ConnectionFailure.MODEL_MISSING -> R.string.ai_model_missing
    ConnectionFailure.PROMPT_MISSING -> R.string.ai_prompt_missing
    ConnectionFailure.FILE_ACCESS -> R.string.ai_file_access
    ConnectionFailure.PROMPT_INVALID -> R.string.ai_prompt_invalid
    ConnectionFailure.MODEL_SIZE -> R.string.ai_model_size
    ConnectionFailure.MODEL_HASH -> R.string.ai_model_hash
    ConnectionFailure.TIMEOUT -> R.string.ai_connect_timeout
    ConnectionFailure.LOAD_FAILED, null -> R.string.ai_connect_failed
}
