package com.dd3boh.outertune.ui.utils

import androidx.compose.ui.util.fastAny
import androidx.navigation.NavController
import androidx.navigation.NavGraph
import com.dd3boh.outertune.ui.screens.Screens

val NavController.canNavigateUp: Boolean
    get() = backQueue.count { entry -> entry.destination !is NavGraph } > 1

fun NavController.backToMain() {
    while (canNavigateUp && !Screens.MainScreens.fastAny { it.route == currentBackStackEntry?.destination?.route }) {
        navigateUp()
    }
}