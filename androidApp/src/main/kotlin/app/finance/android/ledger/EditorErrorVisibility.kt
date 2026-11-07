package app.finance.android.ledger

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first

/** Reveal the validator's first failure after every rejected Save, including identical retries. */
@Composable
internal fun Modifier.revealEditorIssue(state: LedgerUiState, vararg issues: InputIssue): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val currentIssue by rememberUpdatedState(state.editorIssue)
    LaunchedEffect(state.editor?.id, state.editorSaveAttempt) {
        if (state.editorSaveAttempt > 0 && currentIssue?.issue in issues) {
            // Save hides the IME. Reveal once after it closes, not on later keyboard changes.
            snapshotFlow { ime.getBottom(density) }.first { it == 0 }
            withFrameNanos { }
            if (currentIssue?.issue in issues) requester.bringIntoView()
        }
    }
    return bringIntoViewRequester(requester)
}
