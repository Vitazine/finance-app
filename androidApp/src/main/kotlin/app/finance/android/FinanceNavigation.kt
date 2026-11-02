package app.finance.android

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.finance.presentation.Destination

@Composable
internal fun FinanceNavigation(selected: Destination, onSelect: (Destination) -> Unit) {
    BoxWithConstraints {
        val rowSize = if (maxWidth < 600.dp && LocalDensity.current.fontScale > 1.3f) {
            3
        } else {
            Destination.entries.size
        }
        val rows = Destination.entries.chunked(rowSize)
        val iconSize = with(LocalDensity.current) { 20.dp.toSp() }
        val iconLineHeight = with(LocalDensity.current) { 24.dp.toSp() }

        Column {
            rows.forEachIndexed { index, destinations ->
                NavigationBar(
                    windowInsets = if (index == rows.lastIndex) {
                        NavigationBarDefaults.windowInsets
                    } else {
                        WindowInsets(0, 0, 0, 0)
                    }
                ) {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = selected == destination,
                            onClick = { onSelect(destination) },
                            icon = {
                                Text(
                                    destination.symbol(),
                                    fontSize = iconSize,
                                    lineHeight = iconLineHeight,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.size(24.dp).clearAndSetSemantics { }
                                )
                            },
                            label = { Text(stringResource(destination.label())) },
                            modifier = Modifier.testTag("nav_${destination.name}")
                        )
                    }
                }
            }
        }
    }
}

private fun Destination.symbol(): String = when (this) {
    Destination.HOME -> "⌂"
    Destination.TRANSACTIONS -> "≡"
    Destination.ANALYTICS -> "◔"
    Destination.AI -> "✦"
    Destination.SETTINGS -> "⚙"
}
