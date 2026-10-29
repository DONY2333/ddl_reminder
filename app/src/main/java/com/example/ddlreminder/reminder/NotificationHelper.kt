package com.example.ddlreminder.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.ddlreminder.MainActivity
import com.example.ddlreminder.R
import com.example.ddlreminder.data.DdlEntity

object NotificationHelper {
    // Channel 的震动等行为创建后不可由 App 修改，V2 使用新 ID 让升级用户获得新配置。
    const val CHANNEL_ID = "ddl_reminders_v2"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DDL 提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "在任务截止前发送提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 120, 240)
        }
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission") // canPost() 已同时检查运行时权限和通知总开关。
    fun showDdlReminder(context: Context, ddl: DdlEntity, offset: ReminderOffset) {
        if (!canPost(context)) return
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_DDL_ID, ddl.id)
            putExtra(MainActivity.EXTRA_REMINDER_OFFSET, offset.minutes)
            putExtra(MainActivity.EXTRA_REMINDER_FIRED_AT, System.currentTimeMillis())
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId(ddl.id, offset),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (offset.minutes == 0L) "DDL 已到截止时间" else "DDL 将在 ${offset.displayName.removePrefix("提前 ")}后截止")
            .setContentText(ddl.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ddl.title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(ddl.id, offset), notification)
    }

    @SuppressLint("MissingPermission") // canPost() 已同时检查运行时权限和通知总开关。
    fun showDebugTest(context: Context) {
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DDL Reminder 测试通知")
            .setContentText("AlarmManager 和通知功能工作正常。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(991_001, notification)
    }

    fun canPost(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun notificationId(ddlId: Long, offset: ReminderOffset): Int =
        (31L * ddlId + offset.minutes).hashCode()
}
