package app.finance.presentation

enum class Destination {
    HOME,
    TRANSACTIONS,
    ANALYTICS,
    AI,
    SETTINGS
}

data class AppNavigation(val destination: Destination = Destination.HOME) {
    fun select(destination: Destination): AppNavigation = AppNavigation(destination)

    fun back(): AppNavigation? = if (destination == Destination.HOME) null else AppNavigation()
}
