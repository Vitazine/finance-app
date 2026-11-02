package app.finance.android

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.finance.android.ledger.BudgetScreen
import app.finance.android.ledger.EditorActions
import app.finance.android.ledger.EntryEditor
import app.finance.android.ledger.ErrorText
import app.finance.android.ledger.HistoryScreen
import app.finance.android.ledger.HomeScreen
import app.finance.android.ledger.LedgerDialogs
import app.finance.android.ledger.LedgerViewModel
import app.finance.android.ledger.PageHeader
import app.finance.android.ledger.SettingsScreen
import app.finance.android.ledger.message
import app.finance.presentation.AppNavigation
import app.finance.presentation.Destination
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FinanceApp(
    model: LedgerViewModel,
    aiModel: app.finance.android.ai.AiViewModel? = null,
    backupModel: app.finance.android.backup.BackupViewModel? = null,
    appearance: String = "system",
    onAppearanceChange: (String) -> Unit = {}
) {
    val state by model.state.collectAsStateWithLifecycle()
    val backupState = backupModel?.state?.collectAsStateWithLifecycle()?.value
    val aiState = aiModel?.state?.collectAsStateWithLifecycle()?.value
    var selectedName by rememberSaveable { mutableStateOf(Destination.HOME.name) }
    val selected = Destination.entries.find { it.name == selectedName } ?: Destination.HOME
    val owner = LocalLifecycleOwner.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
    LaunchedEffect(owner, state.settings?.zoneId) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                model.refreshCalendarIfNeeded()
                delay(model.millisUntilCalendarBoundary())
            }
        }
    }
    val editorVisible = state.editor != null && state.fatalError == null
    val navigation = AppNavigation(selected)
    val select: (Destination) -> Unit = { selectedName = navigation.select(it).destination.name }
    BackHandler(enabled = editorVisible || selected != Destination.HOME) {
        if (editorVisible) {
            model.cancelEditor()
        } else {
            navigation.back()?.let {
                selectedName =
                    it.destination.name
            }
        }
    }
    Scaffold(modifier = Modifier.semantics { testTagsAsResourceId = true }, bottomBar = {
        if (editorVisible) {
            Box(Modifier.navigationBarsPadding().imePadding()) { EditorActions(state, model) }
        } else {
            FinanceNavigation(selected, select)
        }
    }) { padding ->
        if (editorVisible) {
            EntryEditor(state, model, padding)
        } else {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    when {
                        selected == Destination.AI && state.fatalError == null -> {
                            if (aiModel == null) {
                                PlaceholderScreen(selected)
                            } else {
                                app.finance.android.ai.AiScreen(aiModel, model::refresh) {
                                    if (state.settings ==
                                        null
                                    ) {
                                        select(Destination.HOME)
                                    } else {
                                        model.createEntry()
                                    }
                                }
                            }
                        }
                        state.loading -> Column(
                            Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            PageHeader(stringResource(selected.label()))
                            CircularProgressIndicator()
                            Text(stringResource(R.string.ledger_loading))
                        }
                        state.fatalError != null -> Column(
                            Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            PageHeader(stringResource(selected.label()))
                            ErrorText(stringResource(state.fatalError!!.message()))
                            Button(
                                onClick = model::refresh,
                                enabled = !state.busy,
                                modifier = Modifier.testTag("fatal_retry")
                            ) {
                                Text(stringResource(R.string.ledger_retry))
                            }
                        }
                        selected == Destination.HOME -> HomeScreen(state, model) {
                            select(Destination.TRANSACTIONS)
                        }
                        selected == Destination.TRANSACTIONS -> HistoryScreen(state, model)
                        selected == Destination.ANALYTICS -> BudgetScreen(state, model)
                        selected == Destination.SETTINGS -> SettingsScreen(state, model) {
                            ThemePicker(appearance, onAppearanceChange)
                            if (backupModel !=
                                null
                            ) {
                                app.finance.android.backup.BackupPanel(
                                    backupModel,
                                    !state.busy && aiState?.busy != true
                                )
                            }
                        }
                    }
                    if (state.busy) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }
    if (state.fatalError == null) LedgerDialogs(state, model)
    if (backupState?.busy == true) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { backupModel?.cancelIo() },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = backupState.cancellable,
                dismissOnClickOutside = false
            )
        ) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.material3.MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.ledger_loading))
                    if (backupState.cancellable) {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                backupModel?.cancelIo()
                            },
                            modifier = Modifier.testTag("backup_cancel_operation")
                        ) { Text(stringResource(R.string.ai_cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(screen: Destination) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageHeader(stringResource(screen.label()))
        Text(
            stringResource(
                if (screen ==
                    Destination.AI
                ) {
                    R.string.ai_empty
                } else {
                    R.string.analytics_empty
                }
            ),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            stringResource(
                if (screen ==
                    Destination.AI
                ) {
                    R.string.ai_detail
                } else {
                    R.string.analytics_detail
                }
            )
        )
        Text(stringResource(R.string.foundation_notice), style = MaterialTheme.typography.bodySmall)
    }
}

@StringRes
internal fun Destination.label(): Int = when (this) {
    Destination.HOME -> R.string.home
    Destination.TRANSACTIONS -> R.string.transactions
    Destination.ANALYTICS -> R.string.analytics
    Destination.AI -> R.string.ai
    Destination.SETTINGS -> R.string.settings
}
