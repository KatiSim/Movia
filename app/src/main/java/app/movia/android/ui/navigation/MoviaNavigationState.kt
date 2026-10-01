package app.movia.android.ui.navigation

enum class MoviaTopLevel(val index: Int) {
    HOME(0),
    CATALOG(1),
    LIBRARY(2),
    ;

    companion object {
        fun fromIndex(index: Int): MoviaTopLevel = entries.firstOrNull { it.index == index } ?: HOME
    }
}

enum class MoviaSettingsPage {
    DOWNLOADS,
    HELP,
}

enum class MoviaPersonCredit {
    ACTOR,
    DIRECTOR,
}

sealed interface MoviaRoute {
    data class Details(val mediaId: String, val title: String) : MoviaRoute
    data class Person(
        val name: String,
        val photoUrl: String? = null,
        val credit: MoviaPersonCredit = MoviaPersonCredit.ACTOR,
    ) : MoviaRoute
    data object Profile : MoviaRoute
    data class Settings(val page: MoviaSettingsPage) : MoviaRoute
    data object Player : MoviaRoute
}

data class MoviaNavigationState(
    val selectedTab: MoviaTopLevel = MoviaTopLevel.HOME,
    val backStack: List<MoviaRoute> = emptyList(),
) {
    val currentRoute: MoviaRoute? get() = backStack.lastOrNull()

    fun push(route: MoviaRoute): MoviaNavigationState =
        if (currentRoute == route) this else copy(backStack = backStack + route)

    fun pop(): MoviaNavigationState =
        if (backStack.isEmpty()) copy(selectedTab = MoviaTopLevel.HOME)
        else copy(backStack = backStack.dropLast(1))

    fun selectTab(tab: MoviaTopLevel): MoviaNavigationState = copy(selectedTab = tab)

    fun navigateToTab(tab: MoviaTopLevel): MoviaNavigationState =
        copy(selectedTab = tab, backStack = emptyList())
}
