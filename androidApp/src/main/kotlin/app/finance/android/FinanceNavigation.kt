package app.finance.android

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
        Column {
            rows.forEachIndexed { index, destinations ->
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                                Icon(destination.icon(), contentDescription = null)
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

private fun Destination.icon(): ImageVector = when (this) {
    Destination.HOME -> Icons.Default.Home
    Destination.TRANSACTIONS -> Icons.AutoMirrored.Filled.List
    Destination.ANALYTICS -> Icons.Default.DateRange
    Destination.AI -> Icons.Default.Star
    Destination.SETTINGS -> Icons.Default.Settings
}
