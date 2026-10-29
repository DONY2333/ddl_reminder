package com.example.ddlreminder.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.ddlreminder.diagnostics.StabilityDiagnostics
import com.example.ddlreminder.diagnostics.recoverable
import androidx.navigation.navArgument
import com.example.ddlreminder.DdlReminderApplication
import com.example.ddlreminder.reminder.NotificationHelper
import com.example.ddlreminder.ui.calendar.CalendarScreen
import com.example.ddlreminder.ui.calendar.CalendarViewModel
import com.example.ddlreminder.ui.editor.DdlEditorScreen
import com.example.ddlreminder.ui.editor.DdlEditorViewModel
import com.example.ddlreminder.ui.settings.SettingsScreen
import com.example.ddlreminder.ui.settings.SettingsViewModel
import com.example.ddlreminder.ui.smartadd.SmartAddScreen
import com.example.ddlreminder.ui.smartadd.SmartAddViewModel
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

@Composable
fun DdlApp(openDdlId: Long?) {
    val context = LocalContext.current
    val app = context.applicationContext as DdlReminderApplication
    if (app.initializationFailed) {
        RecoveryPage(onHome = { (context as? android.app.Activity)?.recreate() }, initializationFailed = true)
        return
    }
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry?.destination?.route) {
        currentEntry?.destination?.route?.let { StabilityDiagnostics.record(StabilityDiagnostics.Node.ROUTE, route = it) }
    }
    val snackbar = remember { SnackbarHostState() }
    val calendarViewModel: CalendarViewModel = viewModel(
        factory = CalendarViewModel.Factory(app.repository, app.eventLogger),
    )
    val message by calendarViewModel.message.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionRefresh by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { calendarViewModel.synchronize() }
    LaunchedEffect(openDdlId) {
        if (openDdlId != null) {
            recoverable { app.repository.get(openDdlId) }.getOrNull()?.let { ddl ->
                val date = Instant.ofEpochMilli(ddl.deadlineMillis)
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                calendarViewModel.selectDate(date)
                navController.returnHome()
            }
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            calendarViewModel.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        NavHost(navController = navController, startDestination = "calendar") {
            composable("calendar") {
                // 读取该值，使从系统权限页返回时权限横幅立即重算。
                permissionRefresh
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { calendarViewModel.synchronize() }
                CalendarScreen(
                    viewModel = calendarViewModel,
                    contentPadding = padding,
                    notificationsEnabled = NotificationHelper.canPost(context),
                    exactAlarmsEnabled = app.repository.canScheduleExact(),
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            })
                        }
                    },
                    onRequestExactAlarm = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            })
                        }
                    },
                    onAdd = { date -> navController.openSafe("editor/-1/$date") },
                    onEdit = { ddl -> navController.openSafe("editor/${ddl.id}/${LocalDate.now()}") },
                    onSettings = { navController.openSafe("settings") },
                    onSmartAdd = { navController.openSafe("smart_add") },
                )
            }
            composable(
                route = "editor/{ddlId}/{date}",
                arguments = listOf(
                    navArgument("ddlId") { type = NavType.LongType },
                    navArgument("date") { type = NavType.StringType },
                ),
            ) { entry ->
                val rawId = entry.arguments?.getLong("ddlId") ?: -1
                val initialDate = recoverable {
                    LocalDate.parse(entry.arguments?.getString("date"))
                }.getOrNull()
                if (initialDate == null || (rawId != -1L && rawId <= 0)) {
                    RecoveryPage(onHome = { navController.returnHome() })
                    return@composable
                }
                val editorViewModel: DdlEditorViewModel = viewModel(
                    key = "editor-$rawId-$initialDate",
                    factory = DdlEditorViewModel.Factory(
                        app.repository,
                        app.reminderSettings,
                        app.presetRepository,
                        app.eventLogger,
                        rawId.takeIf { it > 0 },
                        initialDate,
                    ),
                )
                DdlEditorScreen(editorViewModel, padding) { navController.closeSafe("editor/{ddlId}/{date}") }
            }
            composable("settings") {
                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(
                        app.reminderSettings,
                        app.eventLogger,
                        app.csvExportManager,
                        app.presetRepository,
                        app.aiSettings,
                        app.aiApiClient,
                    ),
                )
                SettingsScreen(settingsViewModel, padding) { navController.closeSafe("settings") }
            }
            composable("smart_add") {
                val smartAddViewModel: SmartAddViewModel = viewModel(
                    factory = SmartAddViewModel.Factory(app.repository, app.reminderSettings, app.aiSettings, app.aiApiClient, app.eventLogger),
                )
                SmartAddScreen(smartAddViewModel) { navController.closeSafe("smart_add") }
            }
            composable("recovery") { RecoveryPage(onHome = { navController.returnHome() }) }
        }
        if (currentEntry == null) RecoveryPage(onHome = { navController.returnHome() })
    }
}
