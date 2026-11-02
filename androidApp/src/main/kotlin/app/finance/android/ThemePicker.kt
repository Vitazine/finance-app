package app.finance.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role

@Composable
internal fun ThemePicker(selected: String, select: (String) -> Unit) {
    Column {
        Text(stringResource(R.string.theme_title))
        for ((key, label) in listOf(
            "system" to R.string.theme_system,
            "light" to R.string.theme_light,
            "dark" to R.string.theme_dark
        )) {
            Row(
                Modifier.selectable(selected = key == selected, role = Role.RadioButton, onClick = {
                    select(key)
                }).testTag("theme_$key")
            ) {
                RadioButton(selected = key == selected, onClick = null)
                Text(stringResource(label))
            }
        }
    }
}
