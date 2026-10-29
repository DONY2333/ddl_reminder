package com.example.ddlreminder.reminder

import com.example.ddlreminder.data.DdlEntity

interface AlarmScheduler {
    /** 返回 null 表示成功；返回文本表示可向用户显示的异常。 */
    suspend fun schedule(ddl: DdlEntity, nowMillis: Long = System.currentTimeMillis()): String?
    fun cancel(ddl: DdlEntity)
    fun scheduleDebugTest(triggerAtMillis: Long): String?
    fun canScheduleExact(): Boolean
}
