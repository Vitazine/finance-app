package app.finance.android.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
        androidx.compose.material3.OutlinedTextField(
            value = displayLocalTime(value),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.ledger_local_time)) },
            modifier = Modifier.fillMaxWidth().testTag(tag)
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
            if (it != value) change(it)
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
    val date = occurrenceDate(value) ?: LocalDate.now()
    val outsideCalendar = date.year !in 1..9999
    val calendarDate = if (outsideCalendar) LocalDate.now() else date
    val calendar = rememberDatePickerState(
        initialSelectedDateMillis = calendarDate.atStartOfDay().toInstant(
            ZoneOffset.UTC
        ).toEpochMilli(),
        yearRange = 1..9999
    )
    DatePickerDialog(onDismissRequest = dismiss, confirmButton = {
        TextButton(
            onClick = {
                val selected = java.time.Instant.ofEpochMilli(calendar.selectedDateMillis!!)
                    .atZone(ZoneOffset.UTC).toLocalDate()
                val next = replaceOccurrenceDate(value, selected)
                save(next)
            },
            enabled = calendar.selectedDateMillis != null,
            modifier = Modifier.testTag(tag + "_date_apply")
        ) {
            Text(stringResource(R.string.ledger_apply))
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
}
