package com.example.ddlreminder.ui

import androidx.navigation.NavHostController
import com.example.ddlreminder.diagnostics.recoverable
import java.time.LocalDate

fun safeDestination(route: String?): String {
    if (route in setOf("calendar", "settings", "smart_add", "recovery")) return route!!
    val parts = route?.split('/') ?: return "calendar"
    val id = parts.getOrNull(1)?.toLongOrNull()
    if (parts.size == 3 && parts[0] == "editor" && id != null && (id == -1L || id > 0) &&
        recoverable { LocalDate.parse(parts[2]) }.isSuccess) return route
    return "calendar"
}

fun NavHostController.returnHome() {
    navigate("calendar") {
        popUpTo(graph.startDestinationId) { inclusive = false }
        launchSingleTop = true
    }
}

fun NavHostController.openSafe(route: String) {
    recoverable { navigate(safeDestination(route)) { launchSingleTop = true } }
        .onFailure { returnHome() }
}

/** 重复返回回调只能关闭原路由一次，不能继续 pop 首页并留下空 NavHost。 */
fun NavHostController.closeSafe(expectedRoute: String) {
    if (currentDestination?.route != expectedRoute) return
    if (!popBackStack() || currentDestination == null) returnHome()
}
