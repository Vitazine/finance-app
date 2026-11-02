package app.finance.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppNavigationTest {
    @Test
    fun backFromSectionReturnsHome() {
        val navigation = AppNavigation(Destination.TRANSACTIONS)

        assertEquals(Destination.HOME, navigation.back()?.destination)
    }

    @Test
    fun backAtHomeAllowsSystemExit() {
        assertNull(AppNavigation().back())
    }

    @Test
    fun reselectingSectionDoesNotCreateBackStack() {
        val navigation = AppNavigation().select(Destination.AI).select(Destination.AI)

        assertEquals(Destination.AI, navigation.destination)
        assertNull(navigation.back()?.back())
    }

    @Test
    fun switchingSectionsAlwaysReturnsHomeOnBack() {
        val navigation = AppNavigation().select(Destination.ANALYTICS).select(Destination.SETTINGS)

        assertEquals(Destination.SETTINGS, navigation.destination)
        assertEquals(Destination.HOME, navigation.back()?.destination)
    }
}
