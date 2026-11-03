package app.finance.android.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.finance.ai.contract.AiError
import app.finance.android.R
import app.finance.android.ledger.ChoiceMenu
import app.finance.android.ledger.EntryDateTimeField
import app.finance.android.ledger.ErrorText
import app.finance.android.ledger.LedgerField
import app.finance.android.ledger.OccurrenceChoices
import app.finance.android.ledger.PageHeader
import app.finance.android.ledger.TypeChoices
import app.finance.android.ledger.message

@Composable internal fun AiScreen(model: AiViewModel, onSaved: () -> Unit, onManual: () -> Unit) {
    val s by model.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val owner = LocalLifecycleOwner.current
    var active by remember {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var automatic by remember { mutableStateOf(true) }
    DisposableEffect(model) { onDispose { model.disconnect() } }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        active = true
        automatic = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        active = false
        model.disconnect()
    }
    LaunchedEffect(model, active, automatic, s.busy, s.connected, s.connectionError) {
        if (active &&
            automatic &&
            !s.busy &&
            !s.connected &&
            s.connectionError == null
        ) {
            model.connect()
        }
    }
    LaunchedEffect(s.savedVersion) { if (s.savedVersion > 0) onSaved() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageHeader(stringResource(R.string.ai_title))
        Text(stringResource(R.string.ai_confirmation_rule))
        if (model.experimental) Text(stringResource(R.string.ai_experimental))
        when {
            s.connecting -> {
                ModelConnectionProgress(s.connectionStatus)
                TextButton(onClick = {
                    automatic = false
                    model.disconnect()
                }, modifier = Modifier.testTag("ai_connect_cancel")) {
                    Text(stringResource(R.string.ai_cancel))
                }
            }
            s.connected -> Text(
                stringResource(R.string.ai_connected),
                Modifier.testTag("ai_model_ready")
            )
            // A failed load records a connection-stage failure. Generation cleanup only
            // blocks auto-reconnect; editing the prompt may already have cleared s.error.
            s.error != null ||
                (s.connectionError != null && s.connectionStatus.failure == null) -> Text(
                stringResource(R.string.ai_generation_stopped),
                Modifier.testTag("ai_model_stopped")
            )
            s.connectionError != null -> ErrorText(
                stringResource(
                    connectionMessage(
                        s.connectionStatus.failure
                            ?: s.connectionError?.let(::connectionFailure)
                    )
                ),
                "ai_model_error"
            )
            else -> Text(
                stringResource(R.string.ai_connect_idle),
                Modifier.testTag("ai_model_idle")
            )
        }
        s.error?.let {
            ErrorText(
                stringResource(
                    generationMessage(it)
                ),
                "ai_error"
            )
            if (model.experimental) {
                Text(
                    stringResource(R.string.ai_generation_error_code, it.name),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("ai_generation_error_code")
                )
                if (it == AiError.TIMEOUT && !s.diagnosticMode) {
                    Text(
                        stringResource(R.string.ai_timeout_diagnostic_hint),
                        modifier = Modifier.testTag("ai_timeout_hint")
                    )
                }
            }
        }
        if (!s.connected &&
            !s.busy
        ) {
            TextButton(onClick = {
                automatic = true
                model.connect()
            }, modifier = Modifier.testTag("ai_connect_retry")) {
                Text(stringResource(R.string.ai_connect_retry))
            }
        }
        if (s.generating) {
            GenerationProgressCard(s.generationProgress, model::cancel)
        }
        TextButton(
            onClick = onManual,
            enabled = !s.busy,
            modifier = Modifier.testTag("ai_manual")
        ) {
            Text(stringResource(R.string.ai_manual_entry))
        }
        if (model.experimental) InferenceDiagnosticPanel(s, model::diagnostics)
        if (s.previews.isEmpty()) {
            LedgerField(
                s.prompt,
                stringResource(
                    if (model.experimental) R.string.ai_expense_prompt else R.string.ai_prompt_label
                ),
                "ai_prompt",
                4096,
                !s.busy && model.available,
                model::prompt,
                multiline = true
            )
            Button(
                onClick = {
                    focus.clearFocus()
                    keyboard?.hide()
                    model.generate()
                },
                enabled =
                !s.busy && model.available,
                modifier = Modifier.testTag("ai_generate")
            ) {
                Text(stringResource(R.string.ai_generate))
            }
        }
        if (s.clarification) Text(stringResource(R.string.ai_clarification))
        if (s.unsupported) Text(stringResource(R.string.ai_unsupported))
        s.previews.forEach { p ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.ai_preview, p.index + 1),
                        style = MaterialTheme.typography.titleLarge
                    )
                    when {
                        p.confirmed -> Text(
                            stringResource(R.string.ai_confirmed),
                            Modifier.testTag("ai_confirmed_${p.index}")
                        )
                        p.canceled -> Text(stringResource(R.string.ai_discarded))
                        else -> {
                            val f = p.fields
                            TypeChoices(f.type, enabled = !s.busy) { t ->
                                if (t !=
                                    null
                                ) {
                                    model.edit(p.index) {
                                        it.copy(
                                            type = t,
                                            categoryId = if (t ==
                                                it.type
                                            ) {
                                                it.categoryId
                                            } else {
                                                null
                                            }
                                        )
                                    }
                                }
                            }
                            LedgerField(
                                f.amount,
                                stringResource(
                                    R.string.ledger_amount
                                ),
                                "ai_amount_${p.index}",
                                64,
                                !s.busy,
                                { v ->
                                    model.edit(p.index) { it.copy(amount = v) }
                                },
                                decimal = true
                            )
                            LedgerField(
                                f.currency,
                                stringResource(
                                    R.string.ledger_source_currency
                                ),
                                "ai_currency_${p.index}",
                                8,
                                !s.busy,
                                { v ->
                                    model.edit(p.index) { it.withCurrency(v) }
                                }
                            )
                            val categories = s.draft!!.context.categories.filter {
                                it.type == f.type &&
                                    it.archivedAt == null
                            }
                            ChoiceMenu(
                                categories.find {
                                    it.id == f.categoryId
                                }?.name ?: stringResource(R.string.ledger_choose_category),
                                "ai_category_${p.index}",
                                !s.busy,
                                categories.map { c ->
                                    c.name to
                                        { model.edit(p.index) { it.copy(categoryId = c.id) } }
                                }
                            )
                            Text(
                                stringResource(
                                    if (f.localTime.endsWith("T")) {
                                        R.string.ai_time_ambiguous
                                    } else {
                                        R.string.ai_time_rule
                                    }
                                )
                            )
                            EntryDateTimeField(f.localTime, "ai_time_${p.index}", !s.busy) { v ->
                                model.edit(p.index) { it.withLocalTime(v) }
                            }
                            OccurrenceChoices(f, "ai_time_${p.index}", !s.busy) { offset ->
                                model.edit(p.index) { it.copy(offset = offset) }
                            }
                            if (f.currency !=
                                s.draft!!.context.settings.primaryCurrency.code
                            ) {
                                LedgerField(
                                    f.rate,
                                    stringResource(
                                        R.string.ai_manual_rate
                                    ),
                                    "ai_rate_${p.index}",
                                    128,
                                    !s.busy,
                                    { v ->
                                        model.edit(p.index) { it.withRate(v) }
                                    },
                                    decimal = true
                                )
                            }
                            LedgerField(
                                f.note,
                                stringResource(
                                    R.string.ai_note
                                ),
                                "ai_note_${p.index}",
                                2000,
                                !s.busy,
                                { v ->
                                    model.edit(p.index) { it.copy(note = v) }
                                },
                                multiline = true
                            )
                            p.inputError?.let {
                                ErrorText(
                                    stringResource(it.issue.message()),
                                    "ai_input_error_${p.index}"
                                )
                            }
                            p.ledgerError?.let {
                                ErrorText(stringResource(it.message()), "ai_save_error_${p.index}")
                            }
                            Button(
                                onClick = {
                                    focus.clearFocus()
                                    keyboard?.hide()
                                    model.confirm(p.index)
                                },
                                enabled = !s.busy,
                                modifier = Modifier.testTag(
                                    "ai_confirm_${p.index}"
                                )
                            ) {
                                Text(stringResource(R.string.ai_confirm))
                            }
                            TextButton(onClick = {
                                model.discard(p.index)
                            }, enabled = !s.busy) { Text(stringResource(R.string.ai_cancel)) }
                        }
                    }
                }
            }
        }
        if (s.previews.isNotEmpty()) {
            TextButton(
                onClick = model::cancel,
                enabled = !s.busy,
                modifier = Modifier.testTag("ai_discard")
            ) {
                Text(stringResource(R.string.ai_close_draft))
            }
        }
    }
}
