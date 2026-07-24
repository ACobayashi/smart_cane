package com.nankai.smartcane.navigation

sealed class AppRoute {
    data object Splash : AppRoute()
    data object Login : AppRoute()
    data object ModeSelection : AppRoute()
    data object BlindHome : AppRoute()
    data object BlindNavigation : AppRoute()
    data object BlindPairing : AppRoute()
    data object CompanionHome : AppRoute()
    data object CompanionPairing : AppRoute()
    data object CompanionRisk : AppRoute()
    data object CompanionMap : AppRoute()
    data object CompanionCollaboration : AppRoute()
    data object CompanionMine : AppRoute()
}
