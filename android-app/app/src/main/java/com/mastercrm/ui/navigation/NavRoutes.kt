package com.mastercrm.ui.navigation

sealed class Screen(val route: String, val title: String) {
    object Login : Screen("login", "Авторизація")
    object Calendar : Screen("calendar", "Календар")
    object Clients : Screen("clients", "Клієнти")
    object Finance : Screen("finance", "Фінанси")
    object Marketing : Screen("marketing", "Маркетинг")
    object Settings : Screen("settings", "Налаштування")
}
