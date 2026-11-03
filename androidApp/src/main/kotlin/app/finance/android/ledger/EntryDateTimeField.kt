package app.finance.android.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.finance.android.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@Composable
internal fun EntryDateTimeField(
    value: String,
    tag: String,
    enabled: Boolean,
    change: (String) -> Unit
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column {
        LedgerField(
            displayLocalTime(value),
            stringResource(R.string.ledger_local_time),
            tag,
            64,
            enabled,
            change,
            stringResource(R.string.ledger_local_time_hint)
        )
        TextButton(
            onClick = {
                focus.clearFocus()
                keyboard?.hide()
                picking = true
            },
            enabled = enabled,
            modifier = Modifier.testTag(tag + "_picker")
        ) {
            Text(stringResource(R.string.ledger_pick_date_time))
        }
    }
    if (picking) {
        EntryDateTimePicker(value, tag, { picking = false }) {
            change(it)
            picking = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDateTimePicker(
    value: String,
    tag: String,
    dismiss: () -> Unit,
    save: (String) -> Unit
) {
    val initial = runCatching { parseLocalTime(value) }.getOrNull()
    val date = initial?.toLocalDate()
        ?: runCatching { parseLocalDate(value.removeSuffix("T")) }.getOrElse { LocalDate.now() }
    val outsideCalendar = date.year !in 1..9999
    val calendarDate = if (outsideCalendar) LocalDate.now() else date
    // Material calendar dates are UTC dates, not transaction instants in the app zone.
    val calendar = rememberDatePickerState(
        initialSelectedDateMillis = calendarDate.atStartOfDay().toInstant(
            ZoneOffset.UTC
        ).toEpochMilli(),
        yearRange = 1..9999
    )
    val now = LocalDateTime.now()
    val clock = rememberTimePickerState(
        initialHour = initial?.hour ?: now.hour,
        initialMinute = initial?.minute ?: now.minute,
        is24Hour = true
    )
    var timeStep by rememberSaveable { mutableStateOf(false) }
    if (!timeStep) {
        DatePickerDialog(onDismissRequest = dismiss, confirmButton = {
            TextButton(
                onClick = { timeStep = true },
                enabled = calendar.selectedDateMillis != null,
                modifier = Modifier.testTag(tag + "_date_next")
            ) {
                Text(stringResource(R.string.ledger_next))
            }
        }, dismissButton = {
            TextButton(onClick = dismiss, modifier = Modifier.testTag(tag + "_picker_cancel")) {
                Text(stringResource(R.string.ledger_cancel))
            }
        }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (outsideCalendar) Text(stringResource(R.string.ledger_calendar_fallback))
                DatePicker(calendar, showModeToggle = false)
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.ledger_local_time)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(clock) }
            },
            confirmButton = {
                TextButton(onClick = {
                    val selected = java.time.Instant.ofEpochMilli(calendar.selectedDateMillis!!)
                        .atZone(ZoneOffset.UTC).toLocalDate()
                    save(selected.atTime(clock.hour, clock.minute).toString())
                }, modifier = Modifier.testTag(tag + "_time_save")) {
                    Text(stringResource(R.string.ledger_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss, modifier = Modifier.testTag(tag + "_picker_cancel")) {
                    Text(stringResource(R.string.ledger_cancel))
                }
            }
        )
    }
}
